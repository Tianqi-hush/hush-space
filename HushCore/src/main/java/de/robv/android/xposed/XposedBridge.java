package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.hush.core.xposed.XposedEngine;

/**
 * Xposed 兼容层入口。
 *
 * <p>真正的分派逻辑在 {@link XposedEngine}：它把模块注册的回调挂到
 * HushHook 上（native 侧为每个目标方法生成同签名桩），每次调用先跑
 * before、必要时跳过原方法、再跑 after。
 */
public final class XposedBridge {

    public static final int LOGLEVEL_NONE = 0;
    public static final int LOGLEVEL_ERROR = 1;
    public static final int LOGLEVEL_WARNING = 2;
    public static final int LOGLEVEL_INFO = 3;
    public static final int LOGLEVEL_DEBUG = 4;

    /** 对外宣称的 API 版本。模块常用它判断兼容性，93 是 LSPosed 的常用值。 */
    public static final int XPOSED_VERSION = 93;

    private XposedBridge() {
    }

    public static int getXposedVersion() {
        return XPOSED_VERSION;
    }

    public static void log(String text) {
        XposedEngine.log(text);
    }

    public static void log(Throwable t) {
        XposedEngine.log(android.util.Log.getStackTraceString(t));
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return XposedEngine.hook(hookMethod, callback);
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        XposedEngine.unhook(hookMethod, callback);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                           XC_MethodHook callback) {
        if (hookClass == null || methodName == null) {
            return Collections.emptySet();
        }
        Set<XC_MethodHook.Unhook> unhooks = new LinkedHashSet<>();
        for (Method method : hookClass.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (Modifier.isAbstract(method.getModifiers())) {
                continue;
            }
            XC_MethodHook.Unhook unhook = XposedEngine.hook(method, callback);
            if (unhook != null) {
                unhooks.add(unhook);
            }
        }
        return unhooks;
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(String className, ClassLoader classLoader,
                                                           String methodName, XC_MethodHook callback) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            return hookAllMethods(clazz, methodName, callback);
        } catch (Throwable t) {
            log(t);
            return Collections.emptySet();
        }
    }

    /**
     * 构造器 hook。HushHook 的 Hook 接受 {@link java.lang.reflect.Constructor}
     * （JNI FromReflectedMethod 对 Method/Constructor 都成立），所以这里把每个
     * 构造器都当普通成员交给引擎即可。
     */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        if (hookClass == null || callback == null) {
            return Collections.emptySet();
        }
        Set<XC_MethodHook.Unhook> unhooks = new LinkedHashSet<>();
        for (Constructor<?> constructor : hookClass.getDeclaredConstructors()) {
            try {
                XC_MethodHook.Unhook unhook = XposedEngine.hook(constructor, callback);
                if (unhook != null) {
                    unhooks.add(unhook);
                }
            } catch (Throwable t) {
                XposedEngine.log("hookAllConstructors 失败 " + hookClass.getName() + " " + t);
            }
        }
        return unhooks;
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        return XposedEngine.invokeOriginal(method, thisObject, args);
    }

    public static boolean isMethodHooked(Member method) {
        return XposedEngine.isHooked(method);
    }

    public static void deoptimizeMethod(Member method) {
        // HushHook 走 ArtMethod 入口替换，不需要主动去优化；留空以兼容模块调用。
    }

    /** 兼容部分模块对构造器的直接引用。 */
    public static XC_MethodHook.Unhook hookConstructor(Constructor<?> constructor, XC_MethodHook callback) {
        return XposedEngine.hook(constructor, callback);
    }
}
