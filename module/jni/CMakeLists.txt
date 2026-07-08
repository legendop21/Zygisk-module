cmake_minimum_required(VERSION 3.22.1)
project(hivirtus_zygisk_mode)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(${CMAKE_PROJECT_NAME} SHARED
    main.cpp
    config.cpp
    logger.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(${CMAKE_PROJECT_NAME}
    ${log-lib}
    ${android-lib}
)
