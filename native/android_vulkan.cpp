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

// Did adrenotools REALLY give us Turnip? Its hook cannot redirect a driver that is
// already loaded in the process (libadrenotools hook_impl.cpp: "If there is already an
// instance of a Vulkan driver loaded hooks won't be applied" — which is what Android's
// own UI renderer does when it draws with Vulkan), and in that case it quietly returns
// the STOCK driver. Ask the driver itself, through a throwaway instance.
bool IsTurnip(PFN_vkGetInstanceProcAddr gipa, std::string& name, uint32_t& devices)
{
    devices = 0;
    auto create = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
    if (!create)
        return false;
    VkApplicationInfo app{ VK_STRUCTURE_TYPE_APPLICATION_INFO };
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo ci{ VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    ci.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    if (create(&ci, nullptr, &inst) != VK_SUCCESS)
        return false;
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(gipa(inst, "vkDestroyInstance"));
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(inst, "vkEnumeratePhysicalDevices"));
    auto props2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(gipa(inst, "vkGetPhysicalDeviceProperties2"));
    bool turnip = false;
    uint32_t n = 1;
    VkPhysicalDevice pd = VK_NULL_HANDLE;
    if (enumerate && props2 && (enumerate(inst, &n, &pd) == VK_SUCCESS || n) && pd)
    {
        devices = n;
        VkPhysicalDeviceDriverProperties drv{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
        VkPhysicalDeviceProperties2 p{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2 };
        p.pNext = &drv;
        props2(pd, &p);
        name = std::string(p.properties.deviceName) + " / " + drv.driverName;
        turnip = drv.driverID == VK_DRIVER_ID_MESA_TURNIP ||
                 std::strstr(p.properties.deviceName, "Turnip") != nullptr;
    }
    if (destroy)
        destroy(inst, nullptr);
    return turnip;
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
        // An imported driver package (the launcher's CustomDrivers) loads the same way
        // but need not be Turnip: it may be a GPU maker's own driver.
        const char* kind = std::getenv("DR2_VK_DRIVER_KIND");
        const bool custom = kind && std::strcmp(kind, "custom") == 0;
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
                    g_driverName = (custom && &t == &tries[0] ? "" : "turnip: ") + name;
                    break;
                }
                std::fprintf(stderr, "[android] Turnip (%s) failed: %s\n", name.c_str(), why.c_str());
                report += "failed: " + name + ": " + why + "\n";
            }
            if (!lib)
                std::fprintf(stderr, "[android] falling back to the system Vulkan driver\n");
        }
        if (lib)
        {
            auto tg = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
            std::string seen;
            uint32_t devices = 0;
            const bool turnip = tg && IsTurnip(tg, seen, devices);
            if (tg && custom)
                std::fprintf(stderr, "[android] imported driver reports: %s (%u device%s)\n",
                             seen.c_str(), devices, devices == 1 ? "" : "s");
            if (tg && devices == 0)
            {
                // The driver loaded but drives no GPU here (an Adreno generation this
                // Turnip build does not support, or a package made for another GPU).
                // The phone's own driver is the only chance.
                std::fprintf(stderr, "[android] the loaded driver found no GPU it supports — "
                                     "using the system driver\n");
                report += "failed: " + g_driverName + " does not support this GPU (no device), so the system driver is used\n";
                lib = nullptr;
            }
            else if (tg && !turnip && !(custom && g_driverName.rfind("turnip: ", 0) != 0))
            {
                std::fprintf(stderr, "[android] Turnip was requested but the driver reports '%s': "
                                     "the stock driver was already loaded in this process, so "
                                     "adrenotools could not redirect it\n", seen.c_str());
                report += "failed: the stock driver was already loaded in this process (seen: " + seen +
                          "), so Turnip could not replace it\n";
                g_driverName = "system (Turnip could not replace an already-loaded stock driver)";
            }
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
        if (const char* why = std::getenv("DR2_VK_DECISION"))
            std::fprintf(stderr, "[android] the app's driver choice: %s\n", why);
        WriteResult("loaded: " + g_driverName + "\n" + report);
        ok = true;
    });
    return ok;
}
