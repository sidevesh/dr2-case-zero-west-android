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
// libadrenotools can silently fall back to the stock driver; --diag (CHECK THIS
// DEVICE) names the driver the device really reports, which is the proof.
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

// One Turnip build: <dir>/<file>, loaded through adrenotools. On failure `why` says so.
void* OpenTurnipAt(const char* dirEnv, const char* fileEnv, std::string& why)
{
    // As LibertyRecompAndroid (GTA IV) does it on the same phones: the app copies the
    // chosen Turnip build into PRIVATE storage (files/drivers/<build>/, never shared
    // storage — dlopen refuses that) and names it here; the adrenotools hooks stay in
    // nativeLibraryDir, where the manifest's legacy packaging extracts them.
    // adrenotools joins dir + file name, so the trailing slashes matter.
    const char* hooks = std::getenv("DR2_NATIVE_LIB_DIR");
    const char* dir = std::getenv(dirEnv);
    const char* file = std::getenv(fileEnv);
    if (!hooks || !*hooks || !dir || !*dir || !file || !*file)
    {
        why = std::string("DR2_NATIVE_LIB_DIR / ") + dirEnv + " / " + fileEnv + " not set";
        return nullptr;
    }
    std::string h = hooks, d = dir;
    if (h.back() != '/') h += '/';
    if (d.back() != '/') d += '/';
    dlerror();
    void* lib = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
                                           d.c_str(), h.c_str(), d.c_str(), file, nullptr, nullptr);
    if (!lib)
    {
        const char* e = dlerror();
        why = "adrenotools could not open " + d + file + " (" + (e ? e : "no dlerror") + ")";
    }
    return lib;
}

// Writes what was loaded, and why anything before it was not, where the launcher reads
// it (DR2_VK_RESULT_FILE): the driver line on its main screen.
void WriteResult(const std::string& text)
{
    const char* path = std::getenv("DR2_VK_RESULT_FILE");
    if (!path || !*path)
        return;
    if (FILE* f = std::fopen(path, "w"))
    {
        std::fputs(text.c_str(), f);
        std::fclose(f);
    }
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
        std::string report;
        // turnip: the chosen build first, then (DR2_VK_DRIVER2_*) the other bundled
        // build, then the system driver — so one build failing to load on some phone
        // or firmware still leaves a driver that can run the game.
        if (want && std::strcmp(want, "turnip") == 0)
        {
            struct { const char *dir, *file, *label; } tries[] = {
                { "DR2_VK_DRIVER_DIR", "DR2_VK_DRIVER_FILE", "DR2_VK_DRIVER_LABEL" },
                { "DR2_VK_DRIVER2_DIR", "DR2_VK_DRIVER2_FILE", "DR2_VK_DRIVER2_LABEL" },
            };
            for (const auto& t : tries)
            {
                const char* label = std::getenv(t.label);
                if (&t != &tries[0] && !std::getenv(t.dir))
                    break;
                std::string why;
                lib = OpenTurnipAt(t.dir, t.file, why);
                const std::string name = label && *label ? label : "bundled Turnip";
                if (lib)
                {
                    g_driverName = "turnip: " + name;
                    break;
                }
                std::fprintf(stderr, "[android] Turnip (%s) failed: %s\n", name.c_str(), why.c_str());
                report += "failed: " + name + ": " + why + "\n";
            }
            if (!lib)
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
        WriteResult("loaded: " + g_driverName + "\n" + report);
        ok = true;
    });
    return ok;
}
