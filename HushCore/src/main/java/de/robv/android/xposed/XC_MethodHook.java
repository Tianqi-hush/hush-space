package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Xposed 兼容层：方法 hook 回调。
 *
 * <p>只实现方法级 hook（before/after），不做资源替换（资源 hook 需要改
 * AssetManager，容器这一版没做）。字段名与官方 ABI 保持一致，模块按原版
 * 编译出来的字节码可以正常工作。
 */
public abstract class XC_MethodHook {

    /** 官方常量：越早执行优先级越高。 */
    public static final int PRIORITY_HIGHEST = 10000;
    public static final int PRIORITY_HIGH = 5000;
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOW = -5000;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_REPLACE = 20000;

    private final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public final int getPriority() {
        return priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /**
     * 供容器内的分派器调用。
     *
     * <p>分派器在 {@code com.hush.core.xposed} 包里，跨包调不到 protected，
     * 这里开两个公有入口转一层；模块侧看不到、也不会用到。
     */
    public final void handleBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public final void handleAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public class Unhook implements IXUnhook<XC_MethodHook> {

        private final Member hookMethod;

        public Unhook(Member hookMethod) {
            this.hookMethod = hookMethod;
        }

        public Member getHookedMethod() {
            return hookMethod;
        }

        @Override
        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        @Override
        public void unhook() {
            XposedBridge.unhookMethod(hookMethod, XC_MethodHook.this);
        }
    }

    /** 回调参数容器，字段名与官方一致（模块会直接读写 public 字段）。 */
    public static final class MethodHookParam {

        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean resultSet;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.resultSet = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.resultSet = false;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }

        /**
         * 结果是否被回调显式设置过。
         *
         * <p>没有这个标记就分不清「模块把结果设成 null」和「模块没动过」，
         * 后者必须继续执行原方法。
         */
        public boolean isResultSet() {
            return resultSet;
        }
    }
}
