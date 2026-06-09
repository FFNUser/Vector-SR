package org.matrix.vector.daemon.env

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.vector.daemon.VectorDaemon
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.MATCH_ALL_FLAGS
import org.matrix.vector.daemon.system.getInstalledPackagesFromAllUsers
import org.matrix.vector.daemon.system.packageManager

private const val DEX2OAT_SERVICE_TAG = "VectorDex2Oat"

object Dex2OatService {

  fun isNoInlineNeeded(apkPath: String?): Boolean {
    Log.d(DEX2OAT_SERVICE_TAG, "noinline query raw apkPath=$apkPath")

    if (apkPath.isNullOrBlank()) {
      Log.w(
          DEX2OAT_SERVICE_TAG,
          "noinline decision raw apkPath=$apkPath normalized apkPath=null mapped package=null selectedDirect=false selectedReferer=false final=false reason=empty_apk_path")
      return false
    }

    val normalizedApkPath = normalizePath(apkPath)
    Log.d(DEX2OAT_SERVICE_TAG, "noinline query normalized apkPath=$normalizedApkPath")
    val rootPath = File("/proc/1/root$normalizedApkPath")
    if (!rootPath.exists()) {
      Log.w(
          DEX2OAT_SERVICE_TAG,
          "noinline decision raw apkPath=$apkPath normalized apkPath=$normalizedApkPath mapped package=null selectedDirect=false selectedReferer=false final=false reason=missing_apk rootPath=$rootPath")
      return false
    }

    val pkg = apkPathToPackage(normalizedApkPath)
    if (pkg == null) {
      Log.w(
          DEX2OAT_SERVICE_TAG,
          "noinline decision raw apkPath=$apkPath normalized apkPath=$normalizedApkPath mapped package=null selectedDirect=false selectedReferer=false final=false reason=no_package_mapping")
      return false
    }
    Log.d(DEX2OAT_SERVICE_TAG, "noinline query mapped package=$pkg")

    val selected = PreferenceStore.getInvalidateInlineHookApps()
    val selectedDirectly = selected.contains(pkg)
    val selectedReferer = queryReferers(pkg).firstOrNull { selected.contains(it) }
    val selectedViaReferer = selectedReferer != null
    val decision = selectedDirectly || selectedViaReferer
    Log.i(
        DEX2OAT_SERVICE_TAG,
        "noinline decision raw apkPath=$apkPath normalized apkPath=$normalizedApkPath mapped package=$pkg selectedDirect=$selectedDirectly selectedReferer=$selectedViaReferer selectedRefererPackage=$selectedReferer final=$decision")
    return decision
  }

  fun recordDex2oat(apkPath: String?, odexPath: String?, realPath: String?) {
    if (apkPath.isNullOrBlank() || odexPath.isNullOrBlank()) return

    val normalizedApkPath = normalizePath(apkPath)
    val normalizedOdexPath = normalizePath(odexPath)
    val pkg = apkPathToPackage(normalizedApkPath) ?: return
    val odexMtime =
        File("/proc/1/root$normalizedOdexPath").lastModified().takeIf { it > 0 }
            ?: File(normalizedOdexPath).lastModified()

    val values =
        ContentValues().apply {
          put("pkg", pkg)
          put("apk_path", normalizedApkPath)
          put("odex_path", normalizedOdexPath)
          put("odex_mtime", odexMtime)
        }
    ConfigCache.dbHelper.writableDatabase.insertWithOnConflict(
        "managed_odex", null, values, SQLiteDatabase.CONFLICT_REPLACE)

    Log.d(
        DEX2OAT_SERVICE_TAG,
        "managed_odex recorded pkg=$pkg apkPath=$normalizedApkPath odexPath=$normalizedOdexPath realPath=$realPath mtime=$odexMtime")
  }

  fun onInvalidateInlineHookAppsChanged(added: Set<String>, removed: Set<String>) {
    if (added.isEmpty() && removed.isEmpty()) return
    VectorDaemon.scope.launch(Dispatchers.IO) {
      added.forEach { invalidateCompiledArtifacts(it) }
      removed.forEach { resetCompiledArtifacts(it) }
    }
  }

  private fun apkPathToPackage(apkPath: String): String? {
    val normalized = normalizePath(apkPath)
    val packages =
        packageManager?.getInstalledPackagesFromAllUsers(MATCH_ALL_FLAGS, false) ?: emptyList()
    return packages.firstOrNull { pkg ->
      val appInfo = pkg.applicationInfo ?: return@firstOrNull false
      appInfo.sourceDir == normalized || appInfo.splitSourceDirs?.contains(normalized) == true
    }?.packageName
  }

  private fun queryReferers(pkg: String): Set<String> {
    // TODO: pkg_dependency is reserved for split/shared-library dependency propagation.
    // Do not rely on this path until population logic is implemented and verified.
    val result = mutableSetOf<String>()
    ConfigCache.dbHelper.readableDatabase
        .query(
            "pkg_dependency",
            arrayOf("referer"),
            "referee = ?",
            arrayOf(pkg),
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            result.add(cursor.getString(0))
          }
        }
    return result
  }

  private fun invalidateCompiledArtifacts(pkg: String) {
    Log.i(DEX2OAT_SERVICE_TAG, "invalidate compiled artifacts for $pkg")
    Log.i(DEX2OAT_SERVICE_TAG, "compile reset start for noinline app $pkg")
    val resetExit = runCommand("/system/bin/cmd", "package", "compile", "--reset", pkg)
    Log.i(DEX2OAT_SERVICE_TAG, "compile speed -f start for noinline app $pkg")
    val speedExit = runCommand("/system/bin/cmd", "package", "compile", "-m", "speed", "-f", pkg)
    if (resetExit == 0 && speedExit == 0) {
      Log.i(
          DEX2OAT_SERVICE_TAG,
          "noinline recompile finished for $pkg; target app must be restarted for new oat artifacts to take effect")
      // TODO: expose this restart requirement in Manager UI instead of force-stopping automatically.
    }
  }

  private fun resetCompiledArtifacts(pkg: String) {
    Log.i(DEX2OAT_SERVICE_TAG, "reset compiled artifacts for removed noinline app $pkg")
    runCommand("/system/bin/cmd", "package", "compile", "--reset", pkg)
  }

  private fun runCommand(vararg command: String): Int? {
    val commandText = command.joinToString(" ")
    return runCatching {
          val process = Runtime.getRuntime().exec(command)
          val exitCode = process.waitFor()
          if (exitCode == 0) {
            Log.i(DEX2OAT_SERVICE_TAG, "$commandText exited with 0")
          } else {
            Log.w(DEX2OAT_SERVICE_TAG, "$commandText exited with $exitCode")
          }
          exitCode
        }
        .onFailure { Log.w(DEX2OAT_SERVICE_TAG, "failed to run $commandText", it) }
        .getOrNull()
  }

  private fun normalizePath(path: String): String = path.removePrefix("/proc/1/root")
}
