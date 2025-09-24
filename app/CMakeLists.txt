cmake_minimum_required(VERSION 3.22)
project(elrs_otg_native CXX)
set(CMAKE_CXX_STANDARD 20)

add_library(elrs_otg SHARED src/main/cpp/native-lib.cpp)
target_link_libraries(elrs_otg android log)
