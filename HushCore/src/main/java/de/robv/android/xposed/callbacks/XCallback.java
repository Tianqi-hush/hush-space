package de.robv.android.xposed.callbacks;

/**
 * Xposed 兼容层：所有回调的基类。
 *
 * <p>真正被模块用到的只有 {@code priority} 和 {@link Param}，其余（比较器、
 * 回调数组）保留形状即可 —— 模块在编译期可能引用这些成员，少一个字段
 * 就是 NoSuchFieldError。
 */
public abstract class XCallback implements Comparable<XCallback> {

    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = Integer.MIN_VALUE;
    public static final int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    public final int priority;

    public XCallback() {
        this.priority = PRIORITY_DEFAULT;
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(XCallback other) {
        if (this == other) return 0;
        if (other == null) return -1;
        // 优先级高的排前面
        return other.priority - this.priority;
    }

    /** 回调参数基类。真 Xposed 在这里放 callbacks 数组，容器里用不到，保留字段形状。 */
    public abstract static class Param {
        public final Object[] callbacks;

        protected Param(Object[] callbacks) {
            this.callbacks = callbacks;
        }
    }
}
