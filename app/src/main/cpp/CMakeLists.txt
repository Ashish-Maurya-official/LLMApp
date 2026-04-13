cmake_minimum_required(VERSION 3.22.1)

project(llmapp)

# Include paths
set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama)
include_directories(${LLAMA_DIR}/include)
include_directories(${LLAMA_DIR}/src)
include_directories(${LLAMA_DIR}/ggml/include)
include_directories(${LLAMA_DIR}/ggml/src)
include_directories(${LLAMA_DIR}/ggml/src/ggml-cpu)

# Android dependencies
find_library(log-lib log)

# VERSION DEFINITIONS
add_definitions(-DGGML_VERSION="1.0.0")
add_definitions(-DGGML_COMMIT="manual")

# GGML sources - EXCLUDING backend-specific folders like hexagon, cuda, etc.
file(GLOB GGML_CORE_SOURCES 
    ${LLAMA_DIR}/ggml/src/*.c 
    ${LLAMA_DIR}/ggml/src/*.cpp
)
file(GLOB_RECURSE GGML_CPU_SOURCES 
    ${LLAMA_DIR}/ggml/src/ggml-cpu/*.c
    ${LLAMA_DIR}/ggml/src/ggml-cpu/*.cpp
)
list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX "spacemit|amx|kleidiai")
# Note: On Android ARM, we might need specific arch files
file(GLOB GGML_ARM_SOURCES
    ${LLAMA_DIR}/ggml/src/arch/arm/*.c
    ${LLAMA_DIR}/ggml/src/arch/arm/*.cpp
)

# Llama sources (including model-specific files)
file(GLOB LLAMA_CORE_SOURCES 
    ${LLAMA_DIR}/src/*.cpp 
    ${LLAMA_DIR}/src/*.c
)
file(GLOB LLAMA_MODELS_SOURCES
    ${LLAMA_DIR}/src/models/*.cpp
)

# Define llama library
add_library(llama STATIC
    ${GGML_CORE_SOURCES}
    ${GGML_CPU_SOURCES}
    ${GGML_ARM_SOURCES}
    ${LLAMA_CORE_SOURCES}
    ${LLAMA_MODELS_SOURCES}
)

# Compile options
target_compile_features(llama PRIVATE cxx_std_17)
target_compile_definitions(llama PRIVATE GGML_USE_CPU)

# JNI library
add_library(llm_jni SHARED
    llm_jni.cpp
)

# Link
target_link_libraries(
    llm_jni
    llama
    ${log-lib}
    dl
)