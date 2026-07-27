cmake_minimum_required(VERSION 3.22.1)
project(voicechanger CXX)
add_library(voicechanger SHARED dummy.cpp)
target_link_libraries(voicechanger log)
