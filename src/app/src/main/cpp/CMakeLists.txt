cmake_minimum_required(VERSION 3.22.1)
project(hik_native_usb)

set(CMAKE_CXX_STANDARD 17)

set(LIBUSB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb")

add_library(usb1.0 SHARED
    ${LIBUSB_DIR}/core.c
    ${LIBUSB_DIR}/descriptor.c
    ${LIBUSB_DIR}/hotplug.c
    ${LIBUSB_DIR}/io.c
    ${LIBUSB_DIR}/sync.c
    ${LIBUSB_DIR}/strerror.c
    ${LIBUSB_DIR}/linux_usbfs.c
    ${LIBUSB_DIR}/events_posix.c
    ${LIBUSB_DIR}/threads_posix.c
    ${LIBUSB_DIR}/linux_netlink.c
)

target_include_directories(usb1.0 PRIVATE
    "${LIBUSB_DIR}/android"
    "${LIBUSB_DIR}"
    "${LIBUSB_DIR}/include")

target_compile_options(usb1.0 PRIVATE -fvisibility=hidden)
target_link_libraries(usb1.0 log)

add_library(hik_native_usb SHARED
    hik_libusb_globals.cpp
    hik_libusb_session.cpp
    hik_native_usb_jni.cpp
)

target_include_directories(hik_native_usb PRIVATE
    "${LIBUSB_DIR}/include")

target_link_libraries(hik_native_usb usb1.0 log android)
