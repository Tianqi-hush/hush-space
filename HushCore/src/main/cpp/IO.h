//
// Created by Milk on 4/10/21.
//

#ifndef VIRTUAL_APP_IO_H
#define VIRTUAL_APP_IO_H

#include <jni.h>

#include <list>
#include <iostream>
#include "BoxCore.h"

using namespace std;

class IO {
public:
    static void init(JNIEnv *env);

    struct RelocateInfo {
        const char *targetPath;
        const char *relocatePath;
    };

    static void addRule(const char *targetPath, const char *relocatePath);

    static jstring redirectPath(JNIEnv *env, jstring path);

    static jobject redirectPath(JNIEnv *env, jobject path);

    static const char *redirectPath(const char *__path);

    /**
     * 供 native hook 使用的无分配版本。
     * 命中规则时把结果写进 out（含结尾 0）并返回 true；未命中返回 false，调用方
     * 应原样使用传入的 path。做成"写进调用方缓冲区"是为了避开 open/stat 这种
     * 高频路径上的 malloc —— 那条路上每毫秒都可能被调上千次。
     */
    static bool redirectPathTo(const char *__path, char *out, size_t cap);

    static size_t ruleCount();
};


#endif //VIRTUAL_APP_IO_H
