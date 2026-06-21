cmake_minimum_required(VERSION 3.16)
project(irpanoview_host CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
find_package(ZLIB REQUIRED)
pkg_check_modules(LIBUSB REQUIRED libusb-1.0)
pkg_check_modules(GSTREAMER REQUIRED gstreamer-1.0 gstreamer-app-1.0)
pkg_check_modules(GSTREAMER_RTSP_SERVER gstreamer-rtsp-server-1.0)

set(GSTREAMER_ALL_LIBRARIES ${GSTREAMER_LIBRARIES})
set(GSTREAMER_ALL_INCLUDE_DIRS ${GSTREAMER_INCLUDE_DIRS})
set(GSTREAMER_ALL_CFLAGS_OTHER ${GSTREAMER_CFLAGS_OTHER})

if(GSTREAMER_RTSP_SERVER_FOUND)
    list(APPEND GSTREAMER_ALL_LIBRARIES ${GSTREAMER_RTSP_SERVER_LIBRARIES})
    list(APPEND GSTREAMER_ALL_INCLUDE_DIRS ${GSTREAMER_RTSP_SERVER_INCLUDE_DIRS})
    list(APPEND GSTREAMER_ALL_CFLAGS_OTHER ${GSTREAMER_RTSP_SERVER_CFLAGS_OTHER})
else()
    find_library(GST_RTSP_SERVER_LIB NAMES gstrtspserver-1.0 gstrtspserver-1.0.so.0
        PATHS /usr/lib/aarch64-linux-gnu /usr/lib /lib)
    if(NOT GST_RTSP_SERVER_LIB AND EXISTS "/usr/lib/aarch64-linux-gnu/libgstrtspserver-1.0.so.0")
        set(GST_RTSP_SERVER_LIB "/usr/lib/aarch64-linux-gnu/libgstrtspserver-1.0.so.0")
    endif()
    if(NOT GST_RTSP_SERVER_LIB)
        message(FATAL_ERROR "gstreamer-rtsp-server not found (install libgstrtspserver-1.0-dev or extract headers locally)")
    endif()
    list(APPEND GSTREAMER_ALL_LIBRARIES ${GST_RTSP_SERVER_LIB})
    if(DEFINED ENV{GST_RTSP_SERVER_INCLUDE_DIR})
        list(APPEND GSTREAMER_ALL_INCLUDE_DIRS $ENV{GST_RTSP_SERVER_INCLUDE_DIR})
    elseif(EXISTS "$ENV{HOME}/gst-local/usr/include/gstreamer-1.0")
        list(APPEND GSTREAMER_ALL_INCLUDE_DIRS "$ENV{HOME}/gst-local/usr/include/gstreamer-1.0")
    endif()
endif()

add_library(irpv_proto STATIC
    proto/thermal_frame.cpp
    proto/thermal_chunk.cpp
)
target_include_directories(irpv_proto PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/proto)
target_link_libraries(irpv_proto PUBLIC ZLIB::ZLIB)

add_library(irpv_host STATIC
    host/hik_constants.h
    host/hik_wire_layout.cpp
    host/hik_usb_device.cpp
    host/hik_uvc_protocol.cpp
    host/hik_bulk_reassembler.cpp
    host/hik_camera.cpp
    host/hik_stitch.cpp
    host/stitch_offsets.cpp
    host/overlap_equalizer.cpp
    host/pano_compositor.cpp
    host/ptgui_calib.cpp
    host/warp_stitch.cpp
    host/udp_streamer.cpp
    host/raw_stream_server.cpp
    host/meta_stream.cpp
    host/thermal_renderer.cpp
    host/rtsp_publisher.cpp
    host/host_state.cpp
)
target_include_directories(irpv_host PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/host ${CMAKE_CURRENT_SOURCE_DIR}/proto)
target_link_libraries(irpv_host PUBLIC
    irpv_proto
    ${LIBUSB_LIBRARIES}
    ${GSTREAMER_ALL_LIBRARIES}
    pthread
)
target_compile_options(irpv_host PUBLIC ${LIBUSB_CFLAGS_OTHER} ${GSTREAMER_ALL_CFLAGS_OTHER})
target_include_directories(irpv_host PUBLIC ${LIBUSB_INCLUDE_DIRS} ${GSTREAMER_ALL_INCLUDE_DIRS})

add_executable(irpanoview-host host/main.cpp)
target_link_libraries(irpanoview-host PRIVATE irpv_host irpv_proto ${LIBUSB_LIBRARIES} ${GSTREAMER_ALL_LIBRARIES} pthread)

add_executable(irpanoview-capture host/capture_probe.cpp)
target_link_libraries(irpanoview-capture PRIVATE irpv_host irpv_proto ${LIBUSB_LIBRARIES} pthread)

add_executable(irpanoview-align host/align_cli.cpp)
target_link_libraries(irpanoview-align PRIVATE irpv_host irpv_proto ${LIBUSB_LIBRARIES} ${GSTREAMER_ALL_LIBRARIES} pthread)

add_executable(irpanoview-eq-samples host/eq_sample_cli.cpp)
target_link_libraries(irpanoview-eq-samples PRIVATE irpv_host irpv_proto ${LIBUSB_LIBRARIES} pthread)

add_executable(overlap_equalizer_test host/overlap_equalizer_test.cpp)
target_link_libraries(overlap_equalizer_test PRIVATE irpv_host irpv_proto pthread)

add_executable(warp_stitch_test host/warp_stitch_test.cpp)
target_link_libraries(warp_stitch_test PRIVATE irpv_host irpv_proto pthread)

install(TARGETS irpanoview-host irpanoview-capture irpanoview-align irpanoview-eq-samples overlap_equalizer_test warp_stitch_test RUNTIME DESTINATION bin)
