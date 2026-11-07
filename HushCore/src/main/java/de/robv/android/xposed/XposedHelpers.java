package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Xposed 兼容层：反射工具集。
 *
 * <p>模块 90% 的代码都在调这里的静态方法，方法签名必须和官方一致。
 * 只保留真机上常用的那批；资源相关（{@code getResId}、{@code assetPath}）
 * 因为容器没有资源 hook，不提供。
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    /**
     * 反射失败上报。
     *
     * <p>模块几乎把所有 findClass / findMethodExact / findField 都包在
     * try/catch 里，失败就静默 return —— 表现是「面板正常、点功能没反应」。
     * 这里把失败原样写进容器日志（每个签名只报一次，不刷屏），
     * 下一次点功能就能直接看到它在找哪个类/方法/字段。
     */
    private static final java.util.Set<String> sReported =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static void report(String what) {
        if (sReported.add(what)) {
            com.hush.utils.Diag.log("xph", what);
        }
    }

    private static String loaderOf(ClassLoader loader) {
        return loader == null ? "null" : loader.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    /** 与官方一致的包装异常，模块可能 catch 它。 */
    public static class InvocationTargetError extends Error {
        public InvocationTargetError(Throwable cause) {
            super(cause);
        }
    }

    // ── 类 ──────────────────────────────────────────────────────────────

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        if (className == null) {
            throw new ClassNotFoundError("class name is null");
        }
        Class<?> clazz = primitive(className);
        if (clazz != null) {
            return clazz;
        }
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            report("findClass 失败 " + className + " loader=" + loaderOf(classLoader));
            throw new ClassNotFoundError(e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (ClassNotFoundError e) {
            return null;
        }
    }

    public static class ClassNotFoundError extends Error {
        public ClassNotFoundError(Throwable cause) {
            super(cause);
        }

        public ClassNotFoundError(String detailMessage) {
            super(detailMessage);
        }
    }

    // ── 方法 ────────────────────────────────────────────────────────────

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        return findMethodExact(clazz, methodName, resolveTypes(clazz.getClassLoader(), parameterTypes));
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterClasses) {
        Method method = findMethodExactOrNull(clazz, methodName, parameterClasses);
        if (method == null) {
            report("findMethodExact 失败 " + (clazz == null ? "null" : clazz.getName())
                    + "#" + methodName + Arrays.toString(parameterClasses));
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName
                    + Arrays.toString(parameterClasses));
        }
        method.setAccessible(true);
        return method;
    }

    public static Method findMethodExact(String className, ClassLoader classLoader, String methodName,
                                         Object... parameterTypes) {
        return findMethodExact(findClass(className, classLoader), methodName, parameterTypes);
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            return findMethodExact(clazz, methodName, parameterTypes);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Method findMethodExactIfExists(String className, ClassLoader classLoader,
                                                 String methodName, Object... parameterTypes) {
        try {
            return findMethodExact(className, classLoader, methodName, parameterTypes);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Set<Method> findMethodsByExactParameters(Class<?> clazz, Class<?> returnType,
                                                           Class<?>... parameterTypes) {
        Set<Method> result = new LinkedHashSet<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (returnType != null && !returnType.equals(method.getReturnType())) {
                continue;
            }
            if (Arrays.equals(parameterTypes, method.getParameterTypes())) {
                method.setAccessible(true);
                result.add(method);
            }
        }
        return result;
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>[] parameterTypes,
                                             Object[] args) {
        Method exact = findMethodExactOrNull(clazz, methodName, parameterTypes);
        if (exact != null) {
            exact.setAccessible(true);
            return exact;
        }
        Method best = null;
        int bestScore = -1;
        for (Method method : allMethods(clazz)) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != parameterTypes.length) {
                continue;
            }
            int score = 0;
            boolean ok = true;
            for (int i = 0; i < types.length; i++) {
                if (parameterTypes[i] != null && types[i].isAssignableFrom(parameterTypes[i])) {
                    score += 2;
                } else if (args != null && args[i] != null && types[i].isInstance(args[i])) {
                    score += 1;
                } else {
                    ok = false;
                    break;
                }
            }
            if (ok && score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        if (best == null) {
            report("findMethodBestMatch 失败 " + clazz.getName() + "#" + methodName);
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
        best.setAccessible(true);
        return best;
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
        Class<?>[] types = new Class<?>[args == null ? 0 : args.length];
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? null : args[i].getClass();
            }
        }
        return findMethodBestMatch(clazz, methodName, types, args);
    }

    // ── 构造器 ──────────────────────────────────────────────────────────

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        Class<?>[] types = resolveTypes(clazz.getClassLoader(), parameterTypes);
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + Arrays.toString(types));
        }
    }

    public static Constructor<?> findConstructorExact(String className, ClassLoader classLoader,
                                                      Object... parameterTypes) {
        return findConstructorExact(findClass(className, classLoader), parameterTypes);
    }

    // ── 字段 ────────────────────────────────────────────────────────────

    public static Field findField(Class<?> clazz, String fieldName) {
        Field field = findFieldOrNull(clazz, fieldName);
        if (field == null) {
            report("findField 失败 " + (clazz == null ? "null" : clazz.getName()) + "#" + fieldName);
            throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
        }
        field.setAccessible(true);
        return field;
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Field findFirstFieldByExactType(Class<?> clazz, Class<?> type) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == type) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldError("type " + type + " in " + clazz);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return (Boolean) getObjectField(obj, fieldName);
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        setObjectField(obj, fieldName, value);
    }

    public static int getIntField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).intValue();
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        setObjectField(obj, fieldName, value);
    }

    public static long getLongField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).longValue();
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        setObjectField(obj, fieldName, value);
    }

    public static short getShortField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).shortValue();
    }

    public static void setShortField(Object obj, String fieldName, short value) {
        setObjectField(obj, fieldName, value);
    }

    public static byte getByteField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).byteValue();
    }

    public static void setByteField(Object obj, String fieldName, byte value) {
        setObjectField(obj, fieldName, value);
    }

    public static char getCharField(Object obj, String fieldName) {
        return (Character) getObjectField(obj, fieldName);
    }

    public static void setCharField(Object obj, String fieldName, char value) {
        setObjectField(obj, fieldName, value);
    }

    public static float getFloatField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).floatValue();
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
        setObjectField(obj, fieldName, value);
    }

    public static double getDoubleField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).doubleValue();
    }

    public static void setDoubleField(Object obj, String fieldName, double value) {
        setObjectField(obj, fieldName, value);
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            findField(clazz, fieldName).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        return ((Number) getStaticObjectField(clazz, fieldName)).intValue();
    }

    public static void setStaticIntField(Class<?> clazz, String fieldName, int value) {
        setStaticObjectField(clazz, fieldName, value);
    }

    public static boolean getStaticBooleanField(Class<?> clazz, String fieldName) {
        return (Boolean) getStaticObjectField(clazz, fieldName);
    }

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {
        setStaticObjectField(clazz, fieldName, value);
    }

    public static long getStaticLongField(Class<?> clazz, String fieldName) {
        return ((Number) getStaticObjectField(clazz, fieldName)).longValue();
    }

    public static void setStaticLongField(Class<?> clazz, String fieldName, long value) {
        setStaticObjectField(clazz, fieldName, value);
    }

    // ── 调用 ────────────────────────────────────────────────────────────

    public static Object callMethod(Object obj, String methodName, Object... args) {
        Method method = findMethodBestMatch(obj.getClass(), methodName, args);
        try {
            return method.invoke(obj, args);
        } catch (InvocationTargetException e) {
            report("callMethod 异常 " + obj.getClass().getName() + "#" + methodName
                    + " -> " + e.getCause());
            throw new InvocationTargetError(e.getCause());
        } catch (IllegalAccessException e) {
            report("callMethod 不可访问 " + obj.getClass().getName() + "#" + methodName);
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method method = findMethodBestMatch(clazz, methodName, args);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            report("callStaticMethod 异常 " + clazz.getName() + "#" + methodName
                    + " -> " + e.getCause());
            throw new InvocationTargetError(e.getCause());
        } catch (IllegalAccessException e) {
            report("callStaticMethod 不可访问 " + clazz.getName() + "#" + methodName);
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        Class<?>[] types = new Class<?>[args == null ? 0 : args.length];
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? null : args[i].getClass();
            }
        }
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + Arrays.toString(types));
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError(e.getCause());
        } catch (Exception e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    // ── hook 快捷方式 ───────────────────────────────────────────────────

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                         Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = Arrays.copyOf(parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        Method method = findMethodExact(clazz, methodName, types);
        return XposedBridge.hookMethod(method, callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                         String methodName, Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = Arrays.copyOf(parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        Constructor<?> ctor = findConstructorExact(clazz, types);
        XposedBridge.log("构造器 hook 暂不支持: " + ctor);
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(String className, ClassLoader classLoader,
                                                              Object... parameterTypesAndCallback) {
        return findAndHookConstructor(findClass(className, classLoader), parameterTypesAndCallback);
    }

    // ── 附加字段（模块之间传值用） ───────────────────────────────────────

    private static final Map<Object, Map<String, Object>> sInstanceFields = new WeakHashMap<>();
    private static final Map<Class<?>, Map<String, Object>> sStaticFields = new HashMap<>();

    public static Object getAdditionalInstanceField(Object obj, String key) {
        synchronized (sInstanceFields) {
            Map<String, Object> map = sInstanceFields.get(obj);
            return map == null ? null : map.get(key);
        }
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        synchronized (sInstanceFields) {
            Map<String, Object> map = sInstanceFields.get(obj);
            if (map == null) {
                map = new HashMap<>();
                sInstanceFields.put(obj, map);
            }
            return map.put(key, value);
        }
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        synchronized (sInstanceFields) {
            Map<String, Object> map = sInstanceFields.get(obj);
            return map == null ? null : map.remove(key);
        }
    }

    public static Object getAdditionalStaticField(Object obj, String key) {
        return getAdditionalStaticField(obj.getClass(), key);
    }

    public static Object getAdditionalStaticField(Class<?> clazz, String key) {
        synchronized (sStaticFields) {
            Map<String, Object> map = sStaticFields.get(clazz);
            return map == null ? null : map.get(key);
        }
    }

    public static Object setAdditionalStaticField(Object obj, String key, Object value) {
        return setAdditionalStaticField(obj.getClass(), key, value);
    }

    public static Object setAdditionalStaticField(Class<?> clazz, String key, Object value) {
        synchronized (sStaticFields) {
            Map<String, Object> map = sStaticFields.get(clazz);
            if (map == null) {
                map = new HashMap<>();
                sStaticFields.put(clazz, map);
            }
            return map.put(key, value);
        }
    }

    public static Object removeAdditionalStaticField(Object obj, String key) {
        return removeAdditionalStaticField(obj.getClass(), key);
    }

    public static Object removeAdditionalStaticField(Class<?> clazz, String key) {
        synchronized (sStaticFields) {
            Map<String, Object> map = sStaticFields.get(clazz);
            return map == null ? null : map.remove(key);
        }
    }

    // ── 内部工具 ────────────────────────────────────────────────────────

    private static Class<?>[] resolveTypes(ClassLoader classLoader, Object... parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object o = parameterTypes[i];
            if (o instanceof Class) {
                types[i] = (Class<?>) o;
            } else if (o instanceof String) {
                Class<?> prim = primitive((String) o);
                types[i] = prim != null ? prim : findClass((String) o, classLoader);
            } else {
                throw new IllegalArgumentException("参数类型只支持 Class 或 String: " + o);
            }
        }
        return types;
    }

    private static Class<?> primitive(String name) {
        switch (name) {
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "char":
                return char.class;
            case "short":
                return short.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "void":
                return void.class;
            default:
                return null;
        }
    }

    private static Method findMethodExactOrNull(Class<?> clazz, String methodName, Class<?>[] parameterTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
            for (Class<?> itf : c.getInterfaces()) {
                try {
                    return itf.getDeclaredMethod(methodName, parameterTypes);
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return null;
    }

    private static Field findFieldOrNull(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static List<Method> allMethods(Class<?> clazz) {
        List<Method> list = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            list.addAll(Arrays.asList(c.getDeclaredMethods()));
        }
        return list;
    }

    /** 供模块判断方法是不是静态。 */
    public static boolean isStatic(Method method) {
        return Modifier.isStatic(method.getModifiers());
    }
}
