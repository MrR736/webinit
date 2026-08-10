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
#include <cstdio>

#define LOGV(...) std::fprintf(stderr, "[VERBOSE] " __VA_ARGS__), std::fprintf(stderr, "\n")
#define LOGI(...) std::fprintf(stderr, "[INFO] "    __VA_ARGS__), std::fprintf(stderr, "\n")
#define LOGW(...) std::fprintf(stderr, "[WARN] "    __VA_ARGS__), std::fprintf(stderr, "\n")
#define LOGE(...) std::fprintf(stderr, "[ERROR] "   __VA_ARGS__), std::fprintf(stderr, "\n")

#ifdef DEBUG
#define LOGD(...) std::fprintf(stderr, "[DEBUG] "   __VA_ARGS__), std::fprintf(stderr, "\n")
#else
#define LOGD(...) do { } while (0)
#endif
#endif
