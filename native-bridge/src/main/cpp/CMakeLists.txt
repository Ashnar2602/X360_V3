cmake_minimum_required(VERSION 3.22.1)
project(nativebridge)

add_library(
    nativebridge
    SHARED
    native_bridge.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    nativebridge
    ${log-lib}
)
