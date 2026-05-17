🎉 **Release: Vector 2.0** 🎉

Welcome to Vector-SR 2.0! This maintenance fork keeps compatibility with upstream Vector while focusing on KernelSU jailbreak-mode soft-restart reliability, including system_server reinjection, stale daemon handling, and dex2oat wrapper robustness.

### 📚 libxposed API 100 & 101
With the recent publication of libxposed API 101, the ecosystem is moving toward a new standard with significant breaking changes. Because API 100 was never officially published, **Vector-SR 2.0 keeps the Vector API 100 era compatibility baseline** while carrying soft-restart fixes for this fork.

### 🏗️ Architecture & API Updates
*   **Vector-SR maintenance branding:** Release metadata, update endpoints, and package artifacts now point to the byemaxx/Vector-SR maintenance fork while preserving framework compatibility.
*   **API 100 Finalization:** Completed all remaining libxposed API 100 features, including comprehensive support for static initializers, constructor hooking, and centralized logging.


### ⚙️ Core Engine & System Enhancements
*   🔓 **Bypassed Bionic `LD_PRELOAD` Restrictions:** Resolved fatal namespace errors on Android 10 by loading the `dex2oat` hook library via a `memfd_create` tmpfs-backed file descriptor, bypassing the linker's namespace checks.
*   🛡️ **Reflection Parity Overhaul:** Completely rebuilt the `invokeSpecialMethod` backend to improve performance, enhance robustness, and mirror standard Java reflection behavior.
*   ⏱️ **Late Injection Standalone Launch:** Added native support for manual late injection (triggered by NeoZygisk), without relying on Magisk's early-init phase—highly useful for AOSP debug builds.
