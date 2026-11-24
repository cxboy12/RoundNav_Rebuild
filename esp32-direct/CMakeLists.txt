cmake_minimum_required(VERSION 3.16)

# 圆屏驱动包只支持 ESP32-S3。目标选错时提前给出明确提示，而不是组件版本求解失败。
if(DEFINED ENV{IDF_TARGET} AND NOT "$ENV{IDF_TARGET}" STREQUAL "esp32s3")
    message(FATAL_ERROR
        "当前编译目标是 $ENV{IDF_TARGET}，本项目需要 esp32s3。\n"
        "VS Code：左下角「Set Espressif Device Target」选 esp32s3；"
        "命令行：idf.py set-target esp32s3。切换后删除 build 目录再编译。")
endif()

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(roundnav_direct)
