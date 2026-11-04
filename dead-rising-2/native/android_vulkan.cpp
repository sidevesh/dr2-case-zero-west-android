// android_vulkan.cpp — which Vulkan driver the runtime talks to, chosen at startup.
//
// On Android the runtime does not link Vulkan's entry points: android_compat.h
// force-includes volk with VK_NO_PROTOTYPES, so every `vkXxx(...)` call in the
// renderer is a call through a function pointer that this file loads. That is what
// lets one APK carry two drivers:
//
//   system   the device's own Vulkan driver, through /system/lib64/libvulkan.so;
//   turnip   Mesa's open-source Adreno driver, bundled in the APK
//            (libvulkan_freedreno.so) and loaded by libadrenotools into a private
//            copy of the system loader — no root, no system change.
//
// WHY TURNIP: Qualcomm's own Adreno 7xx driver reports shaderInt64 ABSENT, and the
// renderer requires it (every translated shader loads constants through 64-bit
// buffer device addresses). Turnip exposes it. The app picks per device (launcher:
// GPU DRIVER, "auto" = Turnip on Qualcomm SoCs) and passes DR2_VK_DRIVER.
//
// Instance-level and device-level pointers are reloaded after every vkCreateInstance
// by wrapping that one pointer — no call site in the renderer changes.
#include "android_glue.h"

#include "volk.h"
#include <adrenotools/driver.h>

#include <dlfcn.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

namespace
{
PFN_vkCreateInstance g_realCreateInstance = nullptr;
std::string g_driverName = "none";

VKAPI_ATTR VkResult VKAPI_CALL CreateInstanceThenLoad(const VkInstanceCreateInfo* ci,
                                                      const VkAllocationCallbacks* alloc,
                                                      VkInstance* out)
{
    const VkResult r = g_realCreateInstance(ci, alloc, out);
    if (r == VK_SUCCESS && out && *out)
        volkLoadInstance(*out);   // instance + device entry points (via the loader)
    return r;
}

void* OpenTurnip()
{
    // nativeLibraryDir, where the APK's libraries are extracted (the manifest asks for
    // legacy packaging, which adrenotools requires). Its hooks and the driver live
    // there; adrenotools joins dir + name, so the trailing slash matters.
    const char* d = std::getenv("DR2_NATIVE_LIB_DIR");
    if (!d || !*d)
    {
        std::fprintf(stderr, "[android] Turnip: DR2_NATIVE_LIB_DIR is not set\n");
        return nullptr;
    }
    std::string dir = d;
    if (dir.back() != '/')
        dir += '/';
    void* h = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
                                         nullptr, dir.c_str(), dir.c_str(),
                                         "libvulkan_freedreno.so", nullptr, nullptr);
    if (!h)
        std::fprintf(stderr, "[android] Turnip: adrenotools could not open it (%s)\n",
                     dlerror());
    return h;
}
} // namespace

extern "C" const char* Dr2Android_VulkanDriver()
{
    return g_driverName.c_str();
}

extern "C" bool Dr2Android_InitVulkan()
{
    static std::once_flag once;
    static bool ok = false;
    std::call_once(once, [] {
        const char* want = std::getenv("DR2_VK_DRIVER");
        void* lib = nullptr;
        if (want && std::strcmp(want, "turnip") == 0)
        {
            lib = OpenTurnip();
            if (lib)
                g_driverName = "turnip (Mesa, bundled)";
            else
                std::fprintf(stderr, "[android] falling back to the system Vulkan driver\n");
        }
        if (!lib)
        {
            lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
            if (lib)
                g_driverName = "system";
        }
        if (!lib)
        {
            std::fprintf(stderr, "[android] no Vulkan library at all: %s\n", dlerror());
            return;
        }
        auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
        if (!gipa)
        {
            std::fprintf(stderr, "[android] the Vulkan library has no vkGetInstanceProcAddr\n");
            return;
        }
        volkInitializeCustom(gipa);
        g_realCreateInstance = vkCreateInstance;
        vkCreateInstance = CreateInstanceThenLoad;
        std::fprintf(stderr, "[android] Vulkan driver: %s (requested: %s)\n", g_driverName.c_str(),
                     want && *want ? want : "system");
        ok = true;
    });
    return ok;
}
