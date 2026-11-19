cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llm)

# llama.cpp is unusably slow unoptimised, so build it as Release even inside debug APKs.
set(CMAKE_BUILD_TYPE Release)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
# The Snapdragon 8 Gen 2 has dotprod and i8mm, which llama.cpp's fastest 4-bit kernels use.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_DIR} llama.cpp)

add_library(jarvis_llm SHARED jarvis_llm.cpp)
target_link_libraries(jarvis_llm llama log)
