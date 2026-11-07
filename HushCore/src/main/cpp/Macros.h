#ifndef MACROS_H
#define MACROS_H

#include <jni.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <cstdlib>
#include <vector>
#include <sys/mman.h>
#include <android/log.h>
#include "SandHook/oxorany.h"

// --- Logger (Replacing Main/Logger.h) ---
#define LOG_TAG "HushCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// --- Utils & Memory Logic ---
typedef unsigned long DWORD;
static uintptr_t libBase;
static bool libLoaded = false;

inline DWORD findLibrary(const char *library) {
    char filename[0xFF] = {0}, buffer[1024] = {0};
    FILE *fp = NULL;
    DWORD address = 0;

    sprintf(filename, oxorany("/proc/self/maps"));
    fp = fopen(filename, oxorany("rt"));
    if (fp == NULL) {
        return 0;
    }

    while (fgets(buffer, sizeof(buffer), fp)) {
        if (strstr(buffer, library)) {
            address = (DWORD) strtoul(buffer, NULL, 16);
            break;
        }
    }
    fclose(fp);
    return address;
}

inline DWORD getAbsoluteAddress(const char *libraryName, DWORD relativeAddr) {
    libBase = findLibrary(libraryName);
    if (libBase == 0) return 0;
    return (reinterpret_cast<DWORD>(libBase + relativeAddr));
}

inline bool isLibraryLoaded(const char *libraryName) {
    char line[512] = {0};
    FILE *fp = fopen(oxorany("/proc/self/maps"), oxorany("rt"));
    if (fp != NULL) {
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, libraryName)) {
                libLoaded = true;
                fclose(fp);
                return true;
            }
        }
        fclose(fp);
    }
    return false;
}

inline uintptr_t string2Offset(const char *c) {
    int base = 16;
    if (sizeof(uintptr_t) == sizeof(unsigned long)) {
        return strtoul(c, nullptr, base);
    }
    return strtoull(c, nullptr, base);
}

// --- Inline Memory Patching Logic (Replacing KittyMemory) ---
inline std::vector<uint8_t> hexStrToBytes(const char* hex) {
    std::vector<uint8_t> bytes;
    std::string hexStr(hex);
    std::string cleanedHex;
    for (char c : hexStr) {
        if (c != ' ') cleanedHex += c;
    }
    for (size_t i = 0; i < cleanedHex.length(); i += 2) {
        std::string byteString = cleanedHex.substr(i, 2);
        uint8_t byte = (uint8_t) strtol(byteString.c_str(), nullptr, 16);
        bytes.push_back(byte);
    }
    return bytes;
}

inline bool patchOffset(const char* libName, uintptr_t offset, const char* hexStr, bool isRelative = true) {
    uintptr_t targetLibBase = findLibrary(libName);
    if (targetLibBase == 0) {
        LOGE("Failed to find library: %s", libName);
        return false;
    }
    
    uintptr_t targetAddr = isRelative ? (targetLibBase + offset) : offset;
    std::vector<uint8_t> patchBytes = hexStrToBytes(hexStr);
    
    if (patchBytes.empty()) return false;

    size_t pageSize = sysconf(_SC_PAGESIZE);
    uintptr_t pageStart = targetAddr & ~(pageSize - 1);

    // Unprotect memory
    if (mprotect(reinterpret_cast<void*>(pageStart), pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        // Fallback for newer Android versions (W^X restrictions)
        mprotect(reinterpret_cast<void*>(pageStart), pageSize, PROT_READ | PROT_WRITE);
    }

    // Write bytes
    memcpy(reinterpret_cast<void*>(targetAddr), patchBytes.data(), patchBytes.size());

    // Restore memory protection
    mprotect(reinterpret_cast<void*>(pageStart), pageSize, PROT_READ | PROT_EXEC);
    
    LOGI("Successfully patched %s at offset %lx", libName, offset);
    return true;
}

// --- Macros ---
#define PATCH_LIB(lib, offset, hex) patchOffset(oxorany(lib), string2Offset(oxorany(offset)), oxorany(hex), true)

namespace ToastLength {
    inline const int LENGTH_LONG = 1;
    inline const int LENGTH_SHORT = 0;
}

#endif // MACROS_H
