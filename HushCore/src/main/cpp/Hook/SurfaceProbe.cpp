//
// SurfaceProbe.cpp
//
// 旋转后黑屏的观测层。
//
// 为什么必须从 EGL 这一层看：
//
// Java 侧的 Activity.onConfigurationChanged 只能证明「有没有收到配置变化」，
// 证明不了「画面到底画到哪儿去了」。Unity 黑屏有三种完全不同的成因，只有
// EGL/ANativeWindow 的调用序列能区分开：
//
//   1. ANativeWindow_setBuffersGeometry 在旋转后仍然报旧尺寸 → 窗口没真正 resize；
//   2. setBuffersGeometry 报了新尺寸，但没有新的 eglCreateWindowSurface
//      → Unity 没重建 EGLSurface，继续往旧 surface 上画，屏幕全黑；
//   3. eglSwapBuffers 直接停了 → 渲染线程已经退出/被卡住，跟 surface 无关。
//
// 这三种在 Java 层看起来一模一样，所以这一层是必要的，不是"多打点日志"。
//
// 全部 hook 只在 g_on 为真时（壳白名单）安装，普通 App 一个字节都不碰。
//

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <dlfcn.h>

#include "Log.h"
#include "DiagSink.h"
#include "Dobby/dobby.h"

namespace SurfaceProbe {

namespace {

typedef void *EGLDisplay_;
typedef void *EGLSurface_;
typedef void *EGLConfig_;
typedef void *EGLContext_;
typedef int EGLint_;
typedef void *EGLNativeWindowType_;

typedef EGLSurface_ (*eglCreateWindowSurface_t)(EGLDisplay_, EGLConfig_, EGLNativeWindowType_,
                                                const EGLint_ *);
typedef EGLSurface_ (*eglCreatePbufferSurface_t)(EGLDisplay_, EGLConfig_, const EGLint_ *);
typedef int (*eglMakeCurrent_t)(EGLDisplay_, EGLSurface_, EGLSurface_, EGLContext_);
typedef int (*eglSwapBuffers_t)(EGLDisplay_, EGLSurface_);
typedef int (*eglDestroySurface_t)(EGLDisplay_, EGLSurface_);
typedef int (*setBuffersGeometry_t)(void *, int32_t, int32_t, int32_t);
typedef int (*setBuffersFormat_t)(void *, int32_t);

std::atomic<bool> g_on{false};
std::atomic<bool> g_busy{false};

eglCreateWindowSurface_t o_createWin = nullptr;
eglMakeCurrent_t o_makeCurrent = nullptr;
eglSwapBuffers_t o_swap = nullptr;
eglDestroySurface_t o_destroy = nullptr;
setBuffersGeometry_t o_setGeom = nullptr;

// swap 是每帧一次，不能全打。前 12 次留着看启动，后面只在「距上次超过 2 秒」
// 时打一条 —— 黑屏的判定标准就是「还有没有 swap」，只看这个时间间隔就够。
std::atomic<int> g_swapCount{0};
std::atomic<long long> g_lastSwapMs{0};

long long nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

int32_t readInt(const EGLint_ *attr, int32_t key, int32_t def) {
    if (!attr) return def;
    for (int i = 0; attr[i] != 0x3038 /* EGL_NONE */ && i < 64; i += 2) {
        if (attr[i] == key) return attr[i + 1];
    }
    return def;
}

EGLSurface_ hk_createWin(EGLDisplay_ dpy, EGLConfig_ cfg, EGLNativeWindowType_ win,
                         const EGLint_ *attr) {
    bool wasBusy = g_busy.exchange(true);
    EGLSurface_ r = o_createWin ? o_createWin(dpy, cfg, win, attr) : nullptr;
    if (!wasBusy) {
        DiagSink::write("SURF createWin win=%p attr(w=%d h=%d) -> surf=%p", win,
                        readInt(attr, 0x3056 /*EGL_WIDTH*/, -1),
                        readInt(attr, 0x3057 /*EGL_HEIGHT*/, -1), r);
        g_busy = false;
    }
    return r;
}

int hk_makeCurrent(EGLDisplay_ dpy, EGLSurface_ draw, EGLSurface_ read, EGLContext_ ctx) {
    bool wasBusy = g_busy.exchange(true);
    int r = o_makeCurrent ? o_makeCurrent(dpy, draw, read, ctx) : 0;
    if (!wasBusy) {
        DiagSink::write("SURF makeCurrent draw=%p read=%p ctx=%p ok=%d", draw, read, ctx, r);
        g_busy = false;
    }
    return r;
}

int hk_swap(EGLDisplay_ dpy, EGLSurface_ surf) {
    int n = g_swapCount.fetch_add(1) + 1;
    long long t = nowMs();
    long long last = g_lastSwapMs.load();
    bool logIt = (n <= 12) || (t - last >= 2000);
    if (logIt) g_lastSwapMs.store(t);
    int r = o_swap ? o_swap(dpy, surf) : 0;
    if (logIt) {
        DiagSink::write("SURF swap #%d surf=%p ok=%d gap=%lldms", n, surf, r, last ? (t - last) : 0);
    }
    return r;
}

int hk_destroy(EGLDisplay_ dpy, EGLSurface_ surf) {
    int r = o_destroy ? o_destroy(dpy, surf) : 0;
    DiagSink::write("SURF destroy surf=%p ok=%d", surf, r);
    return r;
}

int hk_setGeom(void *win, int32_t w, int32_t h, int32_t fmt) {
    int r = o_setGeom ? o_setGeom(win, w, h, fmt) : 0;
    DiagSink::write("SURF setGeom win=%p %dx%d fmt=%d ok=%d", win, w, h, fmt, r);
    return r;
}

void *sym(const char *lib, const char *name) {
    void *h = dlopen(lib, RTLD_NOW);
    if (!h) return nullptr;
    return dlsym(h, name);
}

bool hookOne(void *target, void *fake, void **orig, const char *label) {
    if (!target) {
        DiagSink::write("SURF skip %s (symbol not found)", label);
        return false;
    }
    if (!fake) {
        DiagSink::write("SURF skip %s (null detour)", label);
        return false;
    }
    void *old = nullptr;
    int rc = DobbyHook(target, fake, &old);
    if (rc != 0 || old == nullptr) {
        DiagSink::write("SURF hook %s failed rc=%d old=%p", label, rc, old);
        return false;
    }
    *orig = old;
    return true;
}

}  // namespace

bool install() {
    if (g_on.exchange(true)) return true;

    int ok = 0;
    ok += hookOne(sym("libEGL.so", "eglCreateWindowSurface"),
                  reinterpret_cast<void *>(&hk_createWin),
                  reinterpret_cast<void **>(&o_createWin), "eglCreateWindowSurface");
    ok += hookOne(sym("libEGL.so", "eglMakeCurrent"),
                  reinterpret_cast<void *>(&hk_makeCurrent),
                  reinterpret_cast<void **>(&o_makeCurrent), "eglMakeCurrent");
    ok += hookOne(sym("libEGL.so", "eglSwapBuffers"),
                  reinterpret_cast<void *>(&hk_swap),
                  reinterpret_cast<void **>(&o_swap), "eglSwapBuffers");
    ok += hookOne(sym("libEGL.so", "eglDestroySurface"),
                  reinterpret_cast<void *>(&hk_destroy),
                  reinterpret_cast<void **>(&o_destroy), "eglDestroySurface");
    ok += hookOne(sym("libandroid.so", "ANativeWindow_setBuffersGeometry"),
                  reinterpret_cast<void *>(&hk_setGeom),
                  reinterpret_cast<void **>(&o_setGeom), "ANativeWindow_setBuffersGeometry");

    DiagSink::write("SURF probe installed ok=%d/5", ok);
    return ok > 0;
}

}  // namespace SurfaceProbe
