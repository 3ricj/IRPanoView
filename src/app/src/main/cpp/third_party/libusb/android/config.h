/*
 * Android build config for libusb (from upstream libusb v1.0.27 android/config.h).
 */
#ifndef LIBUSB_ANDROID_CONFIG_H
#define LIBUSB_ANDROID_CONFIG_H

#define DEFAULT_VISIBILITY __attribute__((visibility("default")))

#define ENABLE_LOGGING 1

#define HAVE_ASM_TYPES_H 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_NFDS_T 1
#define HAVE_PIPE2 1
#define HAVE_SYS_TIME_H 1

#define PLATFORM_POSIX 1

#define PRINTF_FORMAT(a, b) __attribute__((__format__(__printf__, a, b)))

#define USE_SYSTEM_LOGGING_FACILITY 1

#define _GNU_SOURCE 1

#endif
