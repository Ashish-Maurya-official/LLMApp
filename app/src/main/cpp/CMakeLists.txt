cmake_minimum_required(VERSION 3.22.1)

project(llm_jni)

# Include headers
include_directories(/Users/apple/Documents/ashish/llama.cpp)
include_directories(/Users/apple/Documents/ashish/llama.cpp/include)
include_directories(/Users/apple/Documents/ashish/llama.cpp/ggml/include)

# Your JNI library
add_library(
        llm_jni
        SHARED
        llm_jni.cpp
)

# Android log
find_library(log-lib log)

# 👇 IMPORT llama
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libllama.so)

# 👇 IMPORT ggml
add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libggml.so)

# 👇 IMPORT ggml-cpu
add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libggml-cpu.so)

# Link everything
target_link_libraries(
        llm_jni
        ${log-lib}
        llama
        ggml
        ggml-cpu
)