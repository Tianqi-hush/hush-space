package de.robv.android.xposed;

/**
 * Xposed 兼容层：整方法替换。
 *
 * <p>模块最常用的写法是 {@code XC_MethodReplacement.returnConstant(0)}，
 * 这里保留官方语义：before 阶段直接把方法顶掉，after 不再执行。
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    public XC_MethodReplacement() {
        super(PRIORITY_REPLACE);
    }

    public XC_MethodReplacement(int priority) {
        super(priority);
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            param.setResult(replaceHookedMethod(param));
        } catch (Throwable t) {
            param.setThrowable(t);
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    /** 什么都不做：原方法被跳过，返回默认值。 */
    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement(PRIORITY_HIGHEST * 2) {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) {
            return null;
        }
    };

    public static XC_MethodReplacement returnConstant(final Object result) {
        return returnConstant(PRIORITY_DEFAULT, result);
    }

    public static XC_MethodReplacement returnConstant(final int priority, final Object result) {
        return new XC_MethodReplacement(priority) {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return result;
            }
        };
    }
}
