#include <cstddef>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include "logging.h"

namespace {

using Constructor = void (*)(void*);

constexpr const char* kCompilerOptionsC1 = "_ZN3art15CompilerOptionsC1Ev";
constexpr const char* kCompilerOptionsC2 = "_ZN3art15CompilerOptionsC2Ev";

Constructor resolve_symbol(const char* symbol) {
    dlerror();
    void* original = dlsym(RTLD_NEXT, symbol);
    if (original == nullptr) {
        const char* error = dlerror();
        LOGW("CompilerOptions: failed to resolve %s: %s", symbol, error != nullptr ? error : "unknown");
        return nullptr;
    }
    return reinterpret_cast<Constructor>(original);
}

Constructor resolve_original(const char* symbol, const char** resolved_symbol, bool* used_fallback) {
    *resolved_symbol = symbol;
    *used_fallback = false;
    if (auto original = resolve_symbol(symbol)) return original;

    const char* fallback = std::strcmp(symbol, kCompilerOptionsC1) == 0 ? kCompilerOptionsC2 : kCompilerOptionsC1;
    if (auto original = resolve_symbol(fallback)) {
        LOGW("CompilerOptions: using fallback constructor %s for %s", fallback, symbol);
        *resolved_symbol = fallback;
        *used_fallback = true;
        return original;
    }

    LOGE("CompilerOptions: no original constructor available for %s", symbol);
    return nullptr;
}

void patch_options(void* self) {
    if (self == nullptr) return;

    constexpr size_t kScanQwords = 64;
    auto* qwords = reinterpret_cast<int64_t*>(self);
    char sdk_version[PROP_VALUE_MAX] = {};
    __system_property_get("ro.build.version.sdk", sdk_version);

    LOGI("CompilerOptions: patch scan sdk=%s bitness=%zu",
         sdk_version[0] != '\0' ? sdk_version : "unknown",
         sizeof(void*) * 8);
    size_t patch_index = kScanQwords;
    for (size_t i = 0; i < kScanQwords; ++i) {
        if (qwords[i] == -1) {
#ifndef NDEBUG
            LOGD("CompilerOptions: candidate sentinel qword[%zu]", i);
#endif
            if (patch_index == kScanQwords) {
                patch_index = i;
            }
        }
    }

    if (patch_index != kScanQwords) {
        qwords[patch_index] = 0;
        LOGI("CompilerOptions: patched qword[%zu]", patch_index);
        return;
    }

    LOGW("CompilerOptions: no sentinel found");
}

void call_original_and_patch(void* self, const char* symbol) {
    static Constructor original_c1 = nullptr;
    static Constructor original_c2 = nullptr;
    Constructor* slot = std::strcmp(symbol, kCompilerOptionsC1) == 0 ? &original_c1 : &original_c2;
    static const char* resolved_symbol_c1 = nullptr;
    static const char* resolved_symbol_c2 = nullptr;
    static bool used_fallback_c1 = false;
    static bool used_fallback_c2 = false;
    const char** resolved_symbol =
        std::strcmp(symbol, kCompilerOptionsC1) == 0 ? &resolved_symbol_c1 : &resolved_symbol_c2;
    bool* used_fallback =
        std::strcmp(symbol, kCompilerOptionsC1) == 0 ? &used_fallback_c1 : &used_fallback_c2;

    if (*slot == nullptr) {
        *slot = resolve_original(symbol, resolved_symbol, used_fallback);
    }
    if (*slot == nullptr) {
        _exit(127);
    }

    LOGD("CompilerOptions: constructor requested=%s resolved=%s fallback=%s",
         symbol,
         *resolved_symbol != nullptr ? *resolved_symbol : "unknown",
         *used_fallback ? "true" : "false");
    (*slot)(self);
    patch_options(self);
}

}  // namespace

extern "C" __attribute__((visibility("default"))) void _ZN3art15CompilerOptionsC1Ev(void* self) {
    call_original_and_patch(self, kCompilerOptionsC1);
}

extern "C" __attribute__((visibility("default"))) void _ZN3art15CompilerOptionsC2Ev(void* self) {
    call_original_and_patch(self, kCompilerOptionsC2);
}
