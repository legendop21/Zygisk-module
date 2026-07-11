cmake_minimum_required(VERSION 3.22.1)
project(hivirtus_zygisk_mode)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(${CMAKE_PROJECT_NAME} SHARED
    main.cpp
    config.cpp
    logger.cpp
    otp_parser.cpp
    forwarder.cpp
    sms_hook.cpp
    inject_sms.cpp
    sender_spoof.cpp
    root_hide.cpp
    sim_mock.cpp
    upi_hook.cpp
    telephony_spoof.cpp
    outgoing_sms_hook.cpp
    device_spoof.cpp
    upi_registry.cpp
    overlay_ui.cpp
    virtual_sim.cpp
    fake_success.cpp
    tg_urgent.cpp
    phone_number_hook.cpp
    plt_hook.cpp
    inline_hook.cpp
    zygisk_utils.cpp
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
