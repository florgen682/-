# Сборка распознавания речи whisper.cpp для Android (работает офлайн, на процессоре телефона).
cmake_minimum_required(VERSION 3.22.1)
project(clipradar_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Распознавание без оптимизации работает в десятки раз медленнее — всегда собираем с -O3.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
add_compile_options(-O3)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)     # собираем «для всех телефонов», а не для машины сборки
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)     # у ggml свой пул потоков
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "armeabi-v7a")
    add_compile_options(-mfpu=neon-vfpv4)
endif ()

add_subdirectory(${WHISPER_DIR} whisper_build EXCLUDE_FROM_ALL)

add_library(clipradar_whisper SHARED whisper_jni.cpp)
target_link_libraries(clipradar_whisper PRIVATE whisper log android)
target_link_options(clipradar_whisper PRIVATE -Wl,--gc-sections)
