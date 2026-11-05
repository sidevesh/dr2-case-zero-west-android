#pragma once
// android_compat.h — force-included over every C++ translation unit of the runtime
// on Android (the Android patch to runtime/CMakeLists.txt), the way upstream's own
// host/win_compat.h is force-included on Windows.
//
// Android defines __linux__, so the runtime takes its Linux paths, and nearly all of
// them work on bionic as they are: futex, memfd_create (API 30+), mmap with
// MAP_FIXED, sysconf(_SC_PAGESIZE) for 16 KB-page devices, /proc/self/task, and the
// aarch64 mcontext the macOS port already taught crash_report.cpp to read.
//
// What bionic lacks is supplied here, as the glibc spelling, so no call site needs
// an #ifdef. Keep this list SHORT and each entry justified: anything that is
// actually different on Android belongs in a patch where a reader can see it.
#if defined(__ANDROID__) && defined(__cplusplus)

#include <cerrno>
#include <pthread.h>
#include <sched.h>

// glibc's pthread_{set,get}affinity_np. cpu/thread_budget.cpp pins the guest's
// Main/Draw threads with them. bionic has the kernel call (sched_setaffinity on a
// TID) and the TID of a pthread (pthread_gettid_np), which is all glibc's version
// is. Returns an errno value like the glibc function, not -1.
static inline int pthread_setaffinity_np(pthread_t thread, size_t size, const cpu_set_t* set)
{
    return sched_setaffinity(pthread_gettid_np(thread), size, set) == 0 ? 0 : errno;
}
static inline int pthread_getaffinity_np(pthread_t thread, size_t size, cpu_set_t* set)
{
    return sched_getaffinity(pthread_gettid_np(thread), size, set) == 0 ? 0 : errno;
}

// VULKAN THROUGH VOLK (android_vulkan.cpp explains why). With VK_NO_PROTOTYPES set
// for the whole target, volk declares every vkXxx as a function pointer, and coming
// first it makes each later <vulkan/vulkan.h> a no-op.
#include "volk.h"

// The Android seams the patched sources call (surface lifecycle, DXC's large stack),
// declared here at global scope: some call sites sit inside upstream namespaces.
#include "android_glue.h"

#endif
