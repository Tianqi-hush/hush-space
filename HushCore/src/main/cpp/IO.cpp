#include "IO.h"
#include "Log.h"
#include <cstring>
#include <cstdlib>

jmethodID getAbsolutePathMethodId;
list<IO::RelocateInfo> relocate_rule;

char *replace(const char *str, const char *src, const char *dst) {
    const char *pos = str;
    int count = 0;
    while ((pos = strstr(pos, src))) {
        count++;
        pos += strlen(src);
    }

    size_t result_len = strlen(str) + (strlen(dst) - strlen(src)) * count + 1;
    char *result = (char *) malloc(result_len);
    if (result == nullptr)
        return (char *) str;
    // 原来写的是 memset(result, 0, strlen(result))：对一块未初始化的 malloc
    // 内存调 strlen，读到哪算哪，运气不好直接跨页 SIGSEGV。
    memset(result, 0, result_len);

    const char *left = str;
    const char *right = nullptr;

    while ((right = strstr(left, src))) {
        strncat(result, left, right - left);
        strcat(result, dst);
        right += strlen(src);
        left = right;
    }
    strcat(result, left);
    return result;
}

const char *IO::redirectPath(const char *__path) {
    list<IO::RelocateInfo>::iterator iterator;
    for (iterator = relocate_rule.begin(); iterator != relocate_rule.end(); ++iterator) {
        IO::RelocateInfo info = *iterator;
        if (strstr(__path, info.targetPath) && !strstr(__path, "/SdCard/")) {
            size_t l = strlen(__path);
            if (l >= 7 && strcmp(__path + l - 7, "/SdCard") == 0) continue;
            char *ret = replace(__path, info.targetPath, info.relocatePath);
             ALOGD("redirectPath %s  => %s", __path, ret);
            return ret;
        }
    }
    return __path;
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {
    return BoxCore::redirectPathString(env, path);
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {
    return BoxCore::redirectPathFile(env, path);
}

void IO::addRule(const char *targetPath, const char *relocatePath) {
    IO::RelocateInfo info{};
    info.targetPath = targetPath;
    info.relocatePath = relocatePath;
    relocate_rule.push_back(info);
}

void IO::init(JNIEnv *env) {
    jclass tmpFile = env->FindClass("java/io/File");
    getAbsolutePathMethodId = env->GetMethodID(tmpFile, "getAbsolutePath", "()Ljava/lang/String;");
}

size_t IO::ruleCount() {
    return relocate_rule.size();
}

bool IO::redirectPathTo(const char *__path, char *out, size_t cap) {
    if (__path == nullptr || out == nullptr || cap == 0)
        return false;
    if (__path[0] != '/')
        return false;
    // 已在沙盒 SdCard 内则放行。结尾恰好是 "/SdCard" 的沙盒根同样要放行，
    // 否则根目录会被二次替换成 .../SdCard/SdCard。
    if (strstr(__path, "/SdCard/") != nullptr)
        return false;
    size_t plen0 = strlen(__path);
    if (plen0 >= 7 && strcmp(__path + plen0 - 7, "/SdCard") == 0)
        return false;

    // 幂等保护：路径已经在容器沙盒里了，再改一次会套娃
    // （沙盒路径 /data/user/0/<host>/files/data/user/0/<guest> 恰好包含
    //   /data/user/0/<guest> 这一段，用 strstr 匹配会二次命中）。
    for (list<IO::RelocateInfo>::iterator it = relocate_rule.begin();
         it != relocate_rule.end(); ++it) {
        const char *reloc = it->relocatePath;
        if (reloc == nullptr || reloc[0] == '\0')
            continue;
        size_t rlen = strlen(reloc);
        if (strncmp(__path, reloc, rlen) == 0)
            return false;
    }

    // 取「最长命中」的规则：/data/data/pkg/lib 必须优先于 /data/data/pkg，
    // 否则 lib 目录会被并进 data 目录的改写结果里。
    const char *bestTarget = nullptr;
    const char *bestReloc = nullptr;
    size_t bestLen = 0;
    for (list<IO::RelocateInfo>::iterator it = relocate_rule.begin();
         it != relocate_rule.end(); ++it) {
        const char *target = it->targetPath;
        const char *reloc = it->relocatePath;
        if (target == nullptr || reloc == nullptr || target[0] == '\0')
            continue;
        if (strstr(__path, target) == nullptr)
            continue;
        size_t tlen = strlen(target);
        if (tlen > bestLen) {
            bestLen = tlen;
            bestTarget = target;
            bestReloc = reloc;
        }
    }
    if (bestTarget == nullptr)
        return false;

    const char *target = bestTarget;
    const char *reloc = bestReloc;
    const size_t tlen = bestLen;
    const size_t rlen = strlen(reloc);
    const size_t plen = strlen(__path);

    // 统计出现次数，先算出最终长度，一次写对，避免边写边扩容
    size_t hits = 0;
    for (const char *p = __path; (p = strstr(p, target)) != nullptr; p += tlen)
        hits++;
    if (hits == 0)
        return false;

    long long total = (long long) plen + ((long long) rlen - (long long) tlen) * (long long) hits;
    if (total < 0 || (size_t) total + 1 > cap)
        return false;

    char *w = out;
    const char *r = __path;
    const char *h;
    while ((h = strstr(r, target)) != nullptr) {
        size_t seg = (size_t) (h - r);
        memcpy(w, r, seg);
        w += seg;
        memcpy(w, reloc, rlen);
        w += rlen;
        r = h + tlen;
    }
    size_t rest = strlen(r);
    memcpy(w, r, rest);
    w += rest;
    *w = '\0';
    return true;
}
