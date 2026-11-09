cmake_minimum_required(VERSION 3.22.1)

project(vyrox_native LANGUAGES CXX)

find_library(VULKAN_LIB vulkan REQUIRED)

add_library(vyrox_native SHARED vyrox_native.cpp)
target_compile_features(vyrox_native PRIVATE cxx_std_17)
target_link_libraries(vyrox_native PRIVATE ${VULKAN_LIB})
