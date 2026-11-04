cmake_minimum_required(VERSION 3.22.1)
project(vap_vk)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(VAP_BUILD_HOST_TESTS "Build resource ownership tests without Android/Vulkan" OFF)
if(VAP_BUILD_HOST_TESTS)
    enable_testing()
    add_executable(retired_import_pool_test ../../test/cpp/retired_import_pool_test.cpp)
    target_include_directories(retired_import_pool_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    add_test(NAME retired_import_pool COMMAND retired_import_pool_test)
    find_package(Threads REQUIRED)
    add_executable(queue_guard_test ../../test/cpp/queue_guard_test.cpp)
    target_include_directories(queue_guard_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(queue_guard_test Threads::Threads)
    add_test(NAME queue_guard COMMAND queue_guard_test)
    set_tests_properties(queue_guard PROPERTIES TIMEOUT 5)
    return()
endif()

add_library(vap_vk SHARED
    vap_vk_jni.cpp
    vap_vk_engine.cpp
    vap_vk_shared_device.cpp
    vap_ahb_probe.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(mediandk-lib mediandk)

target_include_directories(vap_vk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/shaders
)

target_compile_options(vap_vk PRIVATE
    -Wall
    -Wextra
    -fno-exceptions
    -fno-rtti
)

target_compile_definitions(vap_vk PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
)

target_link_libraries(vap_vk
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${mediandk-lib}
)
