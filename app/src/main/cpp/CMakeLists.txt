cmake_minimum_required(VERSION 3.22)
project(habits_local_ai LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set_property(GLOBAL APPEND PROPERTY JOB_POOLS habits_compile=2)
set(CMAKE_JOB_POOL_COMPILE habits_compile)

# Keep host checkout/toolchain paths out of __FILE__ literals and native debug metadata.
# Directory options are inherited by the pinned vendored targets below.
add_compile_options(
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=native"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build/native"
)
if(CMAKE_ANDROID_NDK)
    add_compile_options("-ffile-prefix-map=${CMAKE_ANDROID_NDK}=android-ndk")
endif()

add_library(habits_llama SHARED native_llama.cpp)
target_compile_options(habits_llama PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(habits_llama PRIVATE "-Wl,-z,max-page-size=16384")

# Preserve the basic app on 32-bit phones without loading a large model there.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_definitions(habits_llama PRIVATE HABITS_LLAMA_UNSUPPORTED=1)
    return()
endif()

foreach(option LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES
        LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_BUILD_MTMD LLAMA_OPENSSL LLAMA_SUBPROCESS
        GGML_NATIVE GGML_BACKEND_DL GGML_CPU_ALL_VARIANTS GGML_CPU_KLEIDIAI GGML_CPU_REPACK
        GGML_OPENMP GGML_BLAS GGML_ACCELERATE GGML_LLAMAFILE GGML_CUDA GGML_VULKAN
        GGML_METAL GGML_OPENCL GGML_RPC GGML_SYCL GGML_CANN GGML_MUSA GGML_HIP
        GGML_AVX GGML_AVX2 GGML_AVX_VNNI GGML_AVX512 GGML_AVX512_VBMI GGML_AVX512_VNNI
        GGML_AVX512_BF16 GGML_BMI2 GGML_FMA GGML_F16C GGML_AMX_TILE GGML_AMX_INT8 GGML_AMX_BF16)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_NUMBER 10948 CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMIT "5f436dddb440a288ee5611d7d1eca564a6aca9f4" CACHE STRING "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
endif()
add_subdirectory(third_party/llama.cpp)
target_link_libraries(habits_llama PRIVATE llama)
