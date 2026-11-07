package com.hush.utils;

import android.app.Activity;
import android.content.Intent;

/**
 * runtimekit 跳板页的 session 对齐。
 *
 * <p>HMS 的跳板页把「这条 session 是谁」记在两个地方：</p>
 * <ul>
 *   <li>Intent 的 <b>action</b>（形如 {@code <hex>:com.huawei.hwid.container2}）</li>
 *   <li>Intent 的 extra <b>{@code stub_session_id}</b></li>
 * </ul>
 *
 * <p>真机上这两者始终一致，因为跳板页一进来就会把 action 抄进 extra
 * （{@code ContainerActivity} 里那段 {@code putExtra("stub_session_id", getAction())}），
 * 而且 kams 常驻、session 用完即弃，不会有残留。</p>
 *
 * <p>容器里会错位，原因是真实页面（比如 {@code OneKeyLoginOrRegisterActivity}）
 * 启动下一个跳板时用的是 {@code new Intent(getIntent())}——它把**上一条**
 * session 的 extra 一起复制走了，只把 action 换成了新 session。跳板页拿到手就是
 * 「action 是新的、extra 是旧的」。随后 {@code ContainerActivity} 里的
 * {@code setSessionIdAsAction()} 会用 extra 覆盖 action，于是新 session 被旧的顶掉，
 * 去 kams 查回来的是**上一个** kit（已经被前一个实例占住），注入失败，
 * 页面在 onCreate 之后几十毫秒自己 finish。</p>
 *
 * <p>2.6.27 日志里两次跳板页的对照就是硬证据：</p>
 * <pre>
 *   第1次（成功） action=262b8f36…  stub_session_id=null
 *   第2次（失败） action=af682a02…  stub_session_id=262b8f36…   ← 旧值
 * </pre>
 *
 * <p>所以这里在 intent 出手（execStartActivity）和 intent 落地（onCreate 之前）
 * 各对齐一次：只要 action 是 session 形态，就把 extra 强制写成 action。
 * 改的是 HMS 自己也会写的那一个字段，不新增语义，风险只在于「别让它继续用旧值」。</p>
 */
public final class StubSession {

    /** session id 的后缀，HMS 用它区分「这条 session 属于哪个容器进程」。 */
    private static final String SUFFIX = ":com.huawei.hwid.container";

    private StubSession() {
    }

    /** action 是不是一条 runtimekit session。 */
    public static boolean isSession(String action) {
        return action != null && action.contains(SUFFIX);
    }

    /**
     * 把 intent 的 action 同步进 {@code stub_session_id}。
     *
     * @return true 表示确实改写了（调用方可据此打日志）
     */
    public static boolean fix(Intent it) {
        if (it == null) return false;
        String action;
        try {
            action = it.getAction();
        } catch (Throwable t) {
            return false;
        }
        if (!isSession(action)) return false;

        String old = null;
        try {
            old = it.getStringExtra("stub_session_id");
        } catch (Throwable ignored) {
            // extras 里带的是 guest 的 Parcelable，unparcel 失败时按「没有」处理，
            // 下面照样写；写进去的是 String，不会再触发一次反序列化。
        }
        if (action.equals(old)) return false;

        try {
            it.putExtra("stub_session_id", action);
            Diag.log("act", "stub session 对齐 " + brief(old) + " -> " + brief(action));
            return true;
        } catch (Throwable t) {
            Diag.log("act", "stub session 对齐失败 " + t);
            return false;
        }
    }

    /** Activity 落地时的兜底：HMS 若在 attachBaseContext 就读过 extra，这里补一次。 */
    public static void fixActivity(Activity a) {
        if (a == null) return;
        try {
            fix(a.getIntent());
        } catch (Throwable ignored) {
        }
    }

    /** 日志里只露前 12 位 hex + 后缀，够区分两条 session，又不至于把整份日志撑爆。 */
    public static String brief(String s) {
        if (s == null) return "null";
        int i = s.indexOf(':');
        String head = i > 0 ? s.substring(0, i) : s;
        if (head.length() > 12) head = head.substring(0, 12);
        return head + (i > 0 ? s.substring(i) : "");
    }
}
