# android.cmake — the Android half of a Dead Rising 2 runtime's build.
#
# Included by the ANDROID block that this project's patch adds to each game's
# runtime/CMakeLists.txt, with:
#   DR2_RUNTIME_TARGET  cz_runtime / cw_runtime
#   DR2_LOG_TAG         the logcat tag
#   DR2_ANDROID_DIR     this directory
# Keeping it here, rather than in the patches, means both games share one copy.

set(DR2_VOLK_DIR "" CACHE PATH "volk source (scripts/fetch-sources.sh)")
set(DR2_ADRENOTOOLS_DIR "" CACHE PATH "libadrenotools prefix (scripts/build-turnip.sh)")
foreach(_need "${DR2_VOLK_DIR}/volk.c" "${DR2_ADRENOTOOLS_DIR}/lib/libadrenotools.a")
    if(NOT EXISTS "${_need}")
        message(FATAL_ERROR "Android build: missing ${_need} (set DR2_VOLK_DIR / DR2_ADRENOTOOLS_DIR; "
                            "see scripts/build-native-android.sh)")
    endif()
endforeach()

set(T ${DR2_RUNTIME_TARGET})

target_sources(${T} PRIVATE
    "${DR2_ANDROID_DIR}/android_main.cpp"     # SDL_main, JNI, the logcat bridge
    "${DR2_ANDROID_DIR}/android_vulkan.cpp"   # the driver choice: system or Turnip
    "${DR2_VOLK_DIR}/volk.c")
# volk's function-pointer globals are named exactly like Vulkan's entry points; hidden,
# they bind inside libmain.so and can never collide with a loader's exports.
set_source_files_properties("${DR2_VOLK_DIR}/volk.c" PROPERTIES COMPILE_OPTIONS "-fvisibility=hidden")

target_include_directories(${T} PRIVATE
    "${DR2_ANDROID_DIR}" "${DR2_VOLK_DIR}" "${DR2_ADRENOTOOLS_DIR}/include")
target_compile_definitions(${T} PRIVATE
    VK_NO_PROTOTYPES VK_USE_PLATFORM_ANDROID_KHR "DR2_LOG_TAG=\"${DR2_LOG_TAG}\"")
# The glibc spellings bionic lacks, and volk ahead of every <vulkan/vulkan.h>.
target_compile_options(${T} PRIVATE
    "$<$<COMPILE_LANGUAGE:CXX>:SHELL:-include ${DR2_ANDROID_DIR}/android_compat.h>")
# Upstream main() becomes a plain function that android_main.cpp's SDL_main calls.
set_property(SOURCE main.cpp APPEND PROPERTY COMPILE_DEFINITIONS main=Dr2AndroidMain)

target_link_libraries(${T} PRIVATE
    "${DR2_ADRENOTOOLS_DIR}/lib/libadrenotools.a"
    "${DR2_ADRENOTOOLS_DIR}/lib/liblinkernsbypass.a"
    android log)
set_target_properties(${T} PROPERTIES OUTPUT_NAME main)
message(STATUS "Android: libmain.so — glue ${DR2_ANDROID_DIR}, volk ${DR2_VOLK_DIR}, "
               "adrenotools ${DR2_ADRENOTOOLS_DIR}")
