cmake_minimum_required(VERSION 3.22.1)
project(dicto_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
find_library(android-lib android)

set(WHISPER_DIR "${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp")

if(NOT EXISTS "${WHISPER_DIR}/include/whisper.h")
    message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_DIR}")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_DIR}" whisper_cpp_build)

add_library(whisper_jni SHARED whisper_jni.cpp)
set_target_properties(whisper_jni PROPERTIES OUTPUT_NAME "dicto_whisper")

target_compile_definitions(whisper_jni PRIVATE DICTO_HAS_WHISPER_CPP=1)

target_link_libraries(whisper_jni
    whisper
    ${log-lib}
    ${android-lib}
)
