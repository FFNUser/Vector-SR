#include <cstddef>
#include <cstdint>
#include <dlfcn.h>

#include "logging.h"

namespace {

using Constructor = void (*)(void*);

Constructor resolve_original(const char* symbol) {
    void* original = dlsym(RTLD_NEXT, symbol);
    if (original == nullptr) {
        LOGE("CompilerOptions: failed to resolve %s: %s", symbol, dlerror());
        return nullptr;
    }
    return reinterpret_cast<Constructor>(original);
}

void patch_options(void* self) {
    if (self == nullptr) return;

    constexpr size_t kScanQwords = 64;
    auto* qwords = reinterpret_cast<int64_t*>(self);

    for (size_t i = 0; i < kScanQwords; ++i) {
        if (qwords[i] == -1) {
            qwords[i] = 0;
            LOGI("CompilerOptions: patched qword[%zu]", i);
            return;
        }
    }

    LOGW("CompilerOptions: no sentinel found");
}

void call_original_and_patch(void* self, const char* symbol) {
    static Constructor original_c1 = nullptr;
    static Constructor original_c2 = nullptr;
    Constructor* slot = nullptr;

    if (symbol[sizeof("_ZN3art15CompilerOptionsC") - 1] == '1') {
        slot = &original_c1;
    } else {
        slot = &original_c2;
    }

    if (*slot == nullptr) {
        *slot = resolve_original(symbol);
    }
    if (*slot == nullptr) return;

    LOGD("CompilerOptions: constructor %s", symbol);
    (*slot)(self);
    patch_options(self);
}

}  // namespace

extern "C" __attribute__((visibility("default"))) void _ZN3art15CompilerOptionsC1Ev(void* self) {
    call_original_and_patch(self, "_ZN3art15CompilerOptionsC1Ev");
}

extern "C" __attribute__((visibility("default"))) void _ZN3art15CompilerOptionsC2Ev(void* self) {
    call_original_and_patch(self, "_ZN3art15CompilerOptionsC2Ev");
}
