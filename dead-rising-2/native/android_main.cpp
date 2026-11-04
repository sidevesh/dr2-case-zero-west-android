// android_main.cpp — the Android entry points of a Dead Rising 2 runtime (libmain.so).
//
// Shared by Case Zero and Case West; compiled into the runtime target by the Android
// patch to runtime/CMakeLists.txt, which also compiles upstream main.cpp with
// -Dmain=Dr2AndroidMain so the unmodified desktop main() becomes a function here.
//
// What lives here, and why each is Android-only:
//   1. THE LOG BRIDGE. An Android app's stdout/stderr go nowhere. The runtime logs
//      everything to stderr (and tees it to <root>/cz_runtime.log itself, by dup'ing
//      fd 2). Installing a pipe on fd 1/2 at library load — before anything else can
//      dup fd 2 — sends every line to logcat as well.
//   2. SDL_main. SDL's Java activity loads libmain.so and calls SDL_main on its own
//      thread. We call upstream's main() and then end the PROCESS: the runtime keeps
//      process-wide state (guest memory, the recompiled image's globals, threads it
//      never joins) that cannot be torn down and started again, and the game
//      activity runs in its own process (":game") precisely so this is safe.
//   3. THE TOOL ENTRY. The launcher activity runs upstream's own CLI modes
//      (--extract-package, --gen-overlays, --shader-cache-status, --diag) in-process
//      through NativeBridge.runTool, capturing their output to a file it can show.
//      Those modes all return before main() touches guest memory or SDL.
//   4. THE SURFACE LIFECYCLE flags (android_glue.h).
#include "android_glue.h"

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifndef DR2_LOG_TAG
#define DR2_LOG_TAG "DR2"
#endif

// Upstream main(), renamed at compile time (see the CMake patch).
int Dr2AndroidMain(int argc, char** argv);

namespace
{
// ---- 1. the log bridge ----------------------------------------------------------
constexpr char kToolEnd[] = "\x01" "DR2_TOOL_END";

int g_logRead = -1;
std::mutex g_toolMu;
std::condition_variable g_toolCv;
FILE* g_toolLog = nullptr;   // non-null while runTool is capturing
bool g_toolEndSeen = false;

void EmitLine(std::string& line)
{
    if (line == kToolEnd)
    {
        std::lock_guard<std::mutex> lk(g_toolMu);
        g_toolEndSeen = true;
        g_toolCv.notify_all();
        line.clear();
        return;
    }
    __android_log_write(ANDROID_LOG_INFO, DR2_LOG_TAG, line.c_str());
    {
        std::lock_guard<std::mutex> lk(g_toolMu);
        if (g_toolLog)
        {
            std::fputs(line.c_str(), g_toolLog);
            std::fputc('\n', g_toolLog);
        }
    }
    line.clear();
}

void* LogPump(void*)
{
    pthread_setname_np(pthread_self(), "dr2-logcat");
    std::string line;
    char buf[4096];
    for (;;)
    {
        const ssize_t n = read(g_logRead, buf, sizeof buf);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            break;
        for (ssize_t i = 0; i < n; ++i)
        {
            if (buf[i] == '\n')
                EmitLine(line);
            else if (buf[i] != '\r')
                line.push_back(buf[i]);
            // logcat truncates at ~4 KB a message; split long lines rather than lose them.
            if (line.size() >= 1000)
                EmitLine(line);
        }
    }
    return nullptr;
}

void StartLogBridge()
{
    static std::once_flag once;
    std::call_once(once, [] {
        int fds[2];
        if (pipe(fds) != 0)
            return;
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        dup2(fds[1], 1);
        dup2(fds[1], 2);
        close(fds[1]);
        g_logRead = fds[0];
        pthread_t t;
        if (pthread_create(&t, nullptr, LogPump, nullptr) == 0)
            pthread_detach(t);
    });
}

// ---- 4. the surface lifecycle ---------------------------------------------------
std::atomic<bool> g_surfaceLost{ false };
std::atomic<bool> g_surfaceReleased{ true };
} // namespace

extern "C" bool Dr2Android_SurfaceLost()
{
    return g_surfaceLost.load(std::memory_order_acquire);
}

extern "C" void Dr2Android_SurfaceReleased()
{
    g_surfaceReleased.store(true, std::memory_order_release);
}

// ---- DXC's large-stack thread (android_glue.h) ------------------------------------------
namespace
{
std::mutex g_bigMu;
std::condition_variable g_bigCv;
const std::function<void()>* g_bigJob = nullptr;   // the one job in flight
bool g_bigDone = false;
pthread_t g_bigThread{};
std::once_flag g_bigOnce;

void* BigStackLoop(void*)
{
    pthread_setname_np(pthread_self(), "dr2-dxc");
    for (;;)
    {
        std::unique_lock<std::mutex> lk(g_bigMu);
        g_bigCv.wait(lk, [] { return g_bigJob != nullptr && !g_bigDone; });
        const std::function<void()>* job = g_bigJob;
        lk.unlock();
        (*job)();
        lk.lock();
        g_bigDone = true;
        g_bigCv.notify_all();
    }
    return nullptr;
}
} // namespace

void Dr2Android_RunOnLargeStack(const std::function<void()>& fn)
{
    std::call_once(g_bigOnce, [] {
        pthread_attr_t a;
        pthread_attr_init(&a);
        pthread_attr_setstacksize(&a, 32u << 20);
        if (pthread_create(&g_bigThread, &a, BigStackLoop, nullptr) != 0)
            std::fprintf(stderr, "[android] could not start the large-stack DXC thread\n");
        pthread_attr_destroy(&a);
    });
    if (pthread_equal(pthread_self(), g_bigThread))
    {
        fn();   // already on it
        return;
    }
    static std::mutex callers;   // one job at a time
    std::lock_guard<std::mutex> one(callers);
    std::unique_lock<std::mutex> lk(g_bigMu);
    g_bigJob = &fn;
    g_bigDone = false;
    g_bigCv.notify_all();
    g_bigCv.wait(lk, [] { return g_bigDone; });
    g_bigJob = nullptr;
}

// ---- JNI ----------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*)
{
    StartLogBridge();
    return JNI_VERSION_1_6;
}

// ---- 2. SDL_main --------------------------------------------------------------------
extern "C" JNIEXPORT int SDL_main(int argc, char* argv[])
{
    StartLogBridge();
    std::fprintf(stderr, "[android] SDL_main: entering the runtime (%d args)\n", argc);
    Dr2Android_InitVulkan();
    const int rc = Dr2AndroidMain(argc, argv);
    std::fprintf(stderr, "[android] runtime returned %d — ending the game process\n", rc);
    std::fflush(nullptr);
    // Give the log pump a moment to drain the last lines into logcat and the log file.
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    std::_Exit(rc);
}

// ---- 3. the tool entry -------------------------------------------------------------
// int NativeBridge.runTool(String[] args, String logPath)
extern "C" JNIEXPORT jint JNICALL
Java_io_github_sidevesh_dr2_NativeBridge_runTool(JNIEnv* env, jclass, jobjectArray jargs,
                                                 jstring jlog)
{
    std::vector<std::string> args{ "libmain.so" };
    const jsize n = env->GetArrayLength(jargs);
    for (jsize i = 0; i < n; ++i)
    {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        const char* c = env->GetStringUTFChars(s, nullptr);
        args.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    std::vector<char*> argv;
    for (std::string& a : args)
        argv.push_back(a.data());
    argv.push_back(nullptr);

    const char* logPath = jlog ? env->GetStringUTFChars(jlog, nullptr) : nullptr;
    {
        std::lock_guard<std::mutex> lk(g_toolMu);
        g_toolLog = logPath ? std::fopen(logPath, "w") : nullptr;
        g_toolEndSeen = false;
    }
    std::fprintf(stderr, "[android] tool:");
    for (size_t i = 1; i < args.size(); ++i)
        std::fprintf(stderr, " %s", args[i].c_str());
    std::fprintf(stderr, "\n");

    Dr2Android_InitVulkan();   // --diag probes Vulkan, through the same driver choice
    const int rc = Dr2AndroidMain(int(args.size()), argv.data());

    std::fprintf(stderr, "[android] tool exit code %d\n", rc);
    std::fflush(nullptr);
    std::fprintf(stderr, "%s\n", kToolEnd);
    {
        // Wait (bounded) for the pump to reach the sentinel, so the captured log is
        // complete when Java reads it.
        std::unique_lock<std::mutex> lk(g_toolMu);
        g_toolCv.wait_for(lk, std::chrono::seconds(2), [] { return g_toolEndSeen; });
        if (g_toolLog)
            std::fclose(g_toolLog);
        g_toolLog = nullptr;
    }
    if (logPath)
        env->ReleaseStringUTFChars(jlog, logPath);
    return rc;
}

// void NativeBridge.surfaceLost() — from GameSurface.surfaceDestroyed (UI thread).
extern "C" JNIEXPORT void JNICALL
Java_io_github_sidevesh_dr2_NativeBridge_surfaceLost(JNIEnv*, jclass)
{
    g_surfaceReleased.store(false, std::memory_order_release);
    g_surfaceLost.store(true, std::memory_order_release);
    // Up to a second for the renderer to let go of the swapchain. It only looks at
    // the flag when it presents, so a renderer that is not presenting (still booting,
    // or blocked in the guest) is not waited for indefinitely.
    for (int i = 0; i < 100 && !g_surfaceReleased.load(std::memory_order_acquire); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    std::fprintf(stderr, "[android] surface destroyed (%s)\n",
                 g_surfaceReleased.load() ? "renderer released it" : "renderer did not answer in 1 s");
}

// void NativeBridge.surfaceRestored() — from GameSurface.surfaceChanged, after SDL has
// picked up the new ANativeWindow.
extern "C" JNIEXPORT void JNICALL
Java_io_github_sidevesh_dr2_NativeBridge_surfaceRestored(JNIEnv*, jclass)
{
    if (g_surfaceLost.exchange(false, std::memory_order_acq_rel))
        std::fprintf(stderr, "[android] surface available again\n");
}
