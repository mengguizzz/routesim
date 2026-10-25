cmake_minimum_required(VERSION 3.22.1)



project("routesim")



# 开启 C++17 标准并默认隐藏内部符号表（仅暴露 JNI_OnLoad）

set(CMAKE_CXX_STANDARD 17)

set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_CXX_VISIBILITY_PRESET hidden)



# 编译生成 libroutesim.so 动态库（对应 Kotlin 中的 System.loadLibrary("routesim")）

add_library(

        routesim

        SHARED

        kinematics_engine.cpp

)


find_library(

        log-lib

        log

)



target_link_libraries(

        routesim

        ${log-lib}

)

