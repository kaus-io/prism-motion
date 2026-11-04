cmake_minimum_required(VERSION 3.22.1)
project(kap_vk)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(KTX_SHADER_HOST darwin-x86_64)
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(KTX_SHADER_HOST windows-x86_64)
else()
    set(KTX_SHADER_HOST linux-x86_64)
endif()
find_program(KTX_GLSLC glslc HINTS "${CMAKE_ANDROID_NDK}/shader-tools/${KTX_SHADER_HOST}" REQUIRED)
set(KTX_COLOR_INC "${CMAKE_CURRENT_BINARY_DIR}/ktx_color_frag.inc")
add_custom_command(OUTPUT "${KTX_COLOR_INC}"
    COMMAND "${KTX_GLSLC}" --target-env=vulkan1.1 -mfmt=c
            "${CMAKE_CURRENT_SOURCE_DIR}/shaders/ktx_color.frag" -o "${KTX_COLOR_INC}"
    DEPENDS shaders/ktx_color.frag
    VERBATIM)

file(GLOB ZSTD_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/zstd/common/*.c
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/zstd/decompress/*.c
)

# Codec code is performance-critical even in debug builds. Keep engine/JNI code
# debuggable; do not spend hundreds of milliseconds in unoptimized decompression.
set_source_files_properties(${ZSTD_SOURCES} PROPERTIES COMPILE_OPTIONS "-O3")

add_library(kap_vk SHARED
    kap_vk_jni.cpp
    ktx_zstd_file.cpp
    ktx_frame_read.cpp
    ktx_vk_engine.cpp
    ktx_vk_shared_device.cpp
    "${KTX_COLOR_INC}"
    ${ZSTD_SOURCES}
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_include_directories(kap_vk PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/shaders
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/zstd
)

target_compile_definitions(kap_vk PRIVATE
    ZSTD_LEGACY_SUPPORT=0
    ZSTD_MULTITHREAD=0
)

target_compile_options(kap_vk PRIVATE
    -Wall
    -Wextra
    -fno-exceptions
    -fno-rtti
)

target_compile_definitions(kap_vk PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
)

target_link_libraries(kap_vk
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)
