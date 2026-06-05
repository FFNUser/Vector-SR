package org.matrix.vector.daemon.env

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.MATCH_ALL_FLAGS
import org.matrix.vector.daemon.system.getInstalledPackagesFromAllUsers
import org.matrix.vector.daemon.system.packageManager

private const val DEX2OAT_SERVICE_TAG = "VectorDex2Oat"

object Dex2OatService {

  fun isNoInlineNeeded(apkPath: String?): Boolean {
    Log.d(DEX2OAT_SERVICE_TAG, "checking noinline for $apkPath")

    if (apkPath.isNullOrBlank()) {
      Log.d(DEX2OAT_SERVICE_TAG, "noinline needed: empty apkPath")
      return true
    }

    val normalizedApkPath = normalizePath(apkPath)
    val rootPath = File("/proc/1/root$normalizedApkPath")
    if (!rootPath.exists()) {
      Log.w(DEX2OAT_SERVICE_TAG, "noinline needed: apk file does not exist: $rootPath")
      return true
    }

    val pkg = apkPathToPackage(normalizedApkPath)
    if (pkg == null) {
      Log.d(DEX2OAT_SERVICE_TAG, "noinline needed: no package found for $normalizedApkPath")
      return true
    }

    val selected = PreferenceStore.getInvalidateInlineHookApps()
    if (selected.contains(pkg)) {
      Log.d(DEX2OAT_SERVICE_TAG, "noinline needed: selected package $pkg")
      return true
    }

    val selectedReferer = queryReferers(pkg).firstOrNull { selected.contains(it) }
    if (selectedReferer != null) {
      Log.d(
          DEX2OAT_SERVICE_TAG,
          "noinline needed: pkg $pkg is referenced by selected $selectedReferer")
      return true
    }

    Log.d(DEX2OAT_SERVICE_TAG, "noinline not needed: $normalizedApkPath pkg=$pkg")
    return false
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
        "record dex2oat pkg=$pkg apkPath=$normalizedApkPath odexPath=$normalizedOdexPath realPath=$realPath mtime=$odexMtime")
  }

  fun onInvalidateInlineHookAppsChanged(added: Set<String>, removed: Set<String>) {
    added.forEach { invalidateCompiledArtifacts(it) }
    removed.forEach { invalidateCompiledArtifacts(it) }
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
    runCatching {
          val process = Runtime.getRuntime().exec(arrayOf("cmd", "package", "compile", "--reset", pkg))
          val exitCode = process.waitFor()
          if (exitCode != 0) {
            Log.w(DEX2OAT_SERVICE_TAG, "cmd package compile --reset $pkg exited with $exitCode")
          }
        }
        .onFailure { Log.w(DEX2OAT_SERVICE_TAG, "failed to reset compiled artifacts for $pkg", it) }
  }

  private fun normalizePath(path: String): String = path.removePrefix("/proc/1/root")
}
