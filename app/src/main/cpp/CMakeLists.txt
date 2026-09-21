cmake_minimum_required(VERSION 3.22.1)
project("cmg_jni")

add_library(
    cmg_jni
    SHARED
    cmg_jni.c
)

find_library(log-lib log)

target_link_libraries(
    cmg_jni
    ${log-lib}
)
