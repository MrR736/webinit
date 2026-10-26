#pragma once

#include "config.h"

#ifdef __ANDROID__
#include <android/log.h>

#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,    LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   LOG_TAG, __VA_ARGS__)

#ifdef DEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,   LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) do { } while (0)
#endif
#else
#include <stdio.h>

#define LOG_N fprintf(stderr, " (%s:%d)\n",__FILE__,__LINE__)

#define LOGV(...) fprintf(stderr, "[VERBOSE] " __VA_ARGS__), LOG_N
#define LOGI(...) fprintf(stderr, "[INFO] "    __VA_ARGS__), LOG_N
#define LOGW(...) fprintf(stderr, "[WARN] "    __VA_ARGS__), LOG_N
#define LOGE(...) fprintf(stderr, "[ERROR] "   __VA_ARGS__), LOG_N

#ifdef DEBUG
#define LOGD(...) fprintf(stderr, "[DEBUG] "   __VA_ARGS__), LOG_N
#else
#define LOGD(...) do { } while (0)
#endif
#endif
