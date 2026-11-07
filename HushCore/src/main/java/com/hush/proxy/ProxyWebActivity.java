package com.hush.proxy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import com.hush.HushCore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * ProxyWebActivity — OAuth WebView, Card Dialog over Game
 *
 * APPEARANCE (image 2 style):
 *  White rounded-corner card, 92%×85%, centered, game visible behind it.
 *  Theme ProxyWebDialogTheme in styles.xml provides windowIsTranslucent=true.
 *  windowIsTranslucent MUST come from XML theme — setting it in Java is too late.
 *
 * HOW IT WORKS:
 *  1. AppInstrumentation.interceptBrowserIntent intercepts the OAuth https:// URL
 *  2. Calls redirectToProxyWebActivity → modifies intent for ProxyWebActivity
 *  3. Calls HushCore.getContext().startActivity(intent) — starts ProxyWebActivity
 *     in the HOST process with FLAG_MULTIPLE_TASK (own task)
 *  4. Returns null from execStartActivity — CustomTabMainActivity stays alive
 *     (its startActivity call "succeeded" from its perspective, no exception)
 *  5. User logs in via WebView in the card
 *  6. fbconnect:// intercepted → storePendingRedirect() → finish()
 *  7. ProxyWebActivity's task destroyed → CustomTabMainActivity RESUMES
 *  8. callActivityOnResume(CustomTabMainActivity) fires BEFORE onResume():
 *     → consumePendingRedirect() reads bridge file
 *     → callOnNewIntent(ACTION_VIEW + fbconnect_uri) → SDK sets mRedirectUrl
 *  9. CustomTabMainActivity.onResume():
 *     → mRedirectUrl != null → setResult(RESULT_OK) + finish()
 *     → FacebookActivity.onActivityResult(RESULT_OK) → game login ✓
 *
 * WHY FLAG_MULTIPLE_TASK:
 *  ProxyWebActivity gets its own separate task. When it finishes, its task
 *  is destroyed and CustomTabMainActivity returns to front. Without this flag,
 *  ProxyWebActivity would share the host task (with LauncherActivity) and
 *  LauncherActivity would flash on screen when ProxyWebActivity finishes.
 *
 * clearFacebookLogin(context): clears all WebView cookies and storage.
 *   Call from anywhere to log out of Facebook in the virtual container.
 */
public class ProxyWebActivity extends Activity {

    public static final String TAG = "ProxyWebActivity";

    // Intent extras set by ActivityManagerCommonProxy.redirectToProxyWebActivity
    public static final String EXTRA_URL        = "url";
    public static final String EXTRA_USER_ID    = "userId";
    public static final String EXTRA_CALLER_PKG = "callerPkg";

    // Cross-process bridge file
    public static final String BRIDGE_FILE   = "proxy_web_redirect.tmp";
    private static final long  BRIDGE_TTL_MS = 30_000L;

    private static final String[] FACEBOOK_DOMAINS = {
        "https://www.facebook.com", "https://m.facebook.com",
        "https://facebook.com",     "https://graph.facebook.com",
        "https://staticxx.facebook.com", "https://connect.facebook.net",
        "https://fbcdn.net",        "https://web.facebook.com"
    };

    // ─────────────────────────────────────────────────────────────────────────
    //  Bridge file — host process writes, virtual process reads
    // ─────────────────────────────────────────────────────────────────────────

    public static void storePendingRedirect(String url) {
        try {
            File f = new File(HushCore.getContext().getFilesDir(), BRIDGE_FILE);
            FileWriter fw = new FileWriter(f, false);
            fw.write(System.currentTimeMillis() + "\n" + url);
            fw.close();
            Log.d(TAG, "storePendingRedirect: " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "storePendingRedirect failed", e);
        }
    }

    public static String consumePendingRedirect() {
        try {
            File f = new File(HushCore.getContext().getFilesDir(), BRIDGE_FILE);
            if (!f.exists()) return null;
            BufferedReader br = new BufferedReader(new FileReader(f));
            String ts = br.readLine(), url = br.readLine();
            br.close();
            f.delete();
            if (ts == null || url == null || url.isEmpty()) return null;
            long age = System.currentTimeMillis() - Long.parseLong(ts.trim());
            if (age < 0 || age > BRIDGE_TTL_MS) {
                Log.w(TAG, "expired " + age + "ms");
                return null;
            }
            Log.d(TAG, "consumePendingRedirect: age=" + age + "ms");
            return url.trim();
        } catch (Exception e) {
            Log.w(TAG, "consumePendingRedirect: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  clearFacebookLogin — call from anywhere to log out
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    public static void clearFacebookLogin(Context context) {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (String d : FACEBOOK_DOMAINS) {
                    cm.setCookie(d, "c_user=; expires=Thu, 01 Jan 1970 00:00:00 GMT");
                    cm.setCookie(d, "xs=; expires=Thu, 01 Jan 1970 00:00:00 GMT");
                    cm.setCookie(d, "fr=; expires=Thu, 01 Jan 1970 00:00:00 GMT");
                    cm.setCookie(d, "datr=; expires=Thu, 01 Jan 1970 00:00:00 GMT");
                }
                cm.removeAllCookies(null);
                cm.flush();
            } else {
                CookieSyncManager.createInstance(context);
                cm.removeAllCookie();
                cm.removeSessionCookie();
                CookieSyncManager.getInstance().sync();
            }
            try { WebStorage.getInstance().deleteAllData(); } catch (Exception ignored) {}
            try {
                WebViewDatabase db = WebViewDatabase.getInstance(context);
                db.clearHttpAuthUsernamePassword();
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) db.clearUsernamePassword();
            } catch (Exception ignored) {}
            try { new File(HushCore.getContext().getFilesDir(), BRIDGE_FILE).delete(); }
            catch (Exception ignored) {}
            Log.i(TAG, "clearFacebookLogin: done");
        } catch (Exception e) {
            Log.e(TAG, "clearFacebookLogin error", e);
        }
    }

    // Legacy token map
    private static final Map<String, String> TOKEN_MAP = new HashMap<>();
    public static final String FACEBOOK = "facebook";
    public static final String GOOGLE   = "google";
    public static final String WECHAT   = "wechat";
    public static final String QQ       = "qq";
    public static void   setToken(String k, String v) { if (k!=null&&v!=null) TOKEN_MAP.put(k,v); }
    public static String getToken(String k)            { return TOKEN_MAP.get(k); }

    // ─────────────────────────────────────────────────────────────────────────
    //  Activity — card dialog over the game
    // ─────────────────────────────────────────────────────────────────────────

    private WebView     mWebView;
    private ProgressBar mProgressBar;
    private boolean     mHandled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        // Size the window as a centered card (92%×85%)
        // windowIsTranslucent=true comes from ProxyWebDialogTheme in styles.xml
        setupCardWindow();

        String url = getIntent().getStringExtra(EXTRA_URL);
        Log.d(TAG, "onCreate | url=" + url);

        if (url == null || url.isEmpty()) { finish(); return; }

        // Improve layout for Facebook OAuth in WebView
        if (url.contains("facebook.com") && (url.contains("oauth") || url.contains("authorize"))) {
            if (!url.contains("display=")) {
                url += (url.contains("?") ? "&" : "?") + "display=touch";
            }
        }

        buildCardLayout();
        setupCookies();
        mWebView.loadUrl(url);
    }

    /**
     * Sizes the window as a centered floating card.
     * The transparent/dim behavior comes from ProxyWebDialogTheme.
     * FLAG_DIM_BEHIND dims the game behind the card (60%).
     */
    private void setupCardWindow() {
        Window w = getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.6f);
        w.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);

        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width   = (int)(dm.widthPixels  * 0.92f);
        lp.height  = (int)(dm.heightPixels * 0.85f);
        lp.gravity = Gravity.CENTER;
        w.setAttributes(lp);
    }

    /**
     * Builds the white rounded-corner card with progress bar + WebView inside.
     * Matches the visual style of image 2.
     */
    private void buildCardLayout() {
        // White card with 12dp rounded corners + shadow
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setBackground(bg);
            card.setElevation(dp(8));
            card.setClipToOutline(true);
        } else {
            card.setBackgroundDrawable(bg);
        }

        // Blue progress bar at top (3dp)
        mProgressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(100);
        mProgressBar.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        pbLp.gravity = Gravity.TOP;
        card.addView(mProgressBar, pbLp);

        // WebView fills the card
        mWebView = buildWebView();
        FrameLayout.LayoutParams wvLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        wvLp.topMargin = dp(3);
        card.addView(mWebView, wvLp);

        setContentView(card);
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            try {
                mWebView.stopLoading();
                mWebView.setWebViewClient(null);
                mWebView.setWebChromeClient(null);
                if (mWebView.getParent() instanceof ViewGroup)
                    ((ViewGroup)mWebView.getParent()).removeView(mWebView);
                mWebView.destroy();
            } catch (Exception ignored) {}
            mWebView = null;
        }
        flushCookies();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  OAuth URL handling
    // ─────────────────────────────────────────────────────────────────────────

    private boolean handleUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // Normal web URL — load it in WebView
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("about:") || lower.startsWith("javascript:")) {
            return false;
        }
        // OAuth redirect (fbconnect://, etc.) — intercept
        if (mHandled) return true;
        mHandled = true;
        Log.i(TAG, "OAuth deep-link intercepted → " + url);
        try {
            Uri uri = Uri.parse(url);
            Intent redirectIntent = new Intent(Intent.ACTION_VIEW, uri);
            redirectIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            
            if (uri.getHost() != null && uri.getHost().startsWith("cct.")) {
                redirectIntent.setPackage(uri.getHost().substring(4));
            }
            
            // Inject directly into the Virtual OS natively. BActivityManager will resolve 
            // the deep link correctly and fire onNewIntent on the game's CustomTabMainActivity.
            HushCore.get().startActivity(redirectIntent, HushCore.getHostUserId());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start redirect intent natively", e);
        }
        finish();
        return true; // stop WebView from loading fbconnect:// (would cause error)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WebView
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a Chrome-like user agent string.
     * Facebook blocks OAuth from embedded WebViews. We must impersonate a real
     * Chrome browser so Facebook shows the streamlined OAuth consent screen
     * ("Continue as...") instead of the full mobile web login form.
     *
     * The default WebView UA contains "wv" which Facebook uses to detect
     * embedded WebViews and downgrade the login experience.
     */
    private String buildChromeUserAgent() {
        try {
            String defaultUA = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                    ? WebSettings.getDefaultUserAgent(this) : "";
            if (defaultUA != null && !defaultUA.isEmpty()) {
                // Remove "wv" marker and "Version/X.X" that identify embedded WebView
                String ua = defaultUA
                        .replaceAll("\\s*wv\\b", "")
                        .replaceAll("\\s*Version/\\d+\\.\\d+\\s*", " ");
                // Ensure Chrome/xxx is present — Facebook checks this
                if (!ua.contains("Chrome/")) {
                    ua = ua.replace("Safari/", "Chrome/120.0.6099.230 Mobile Safari/");
                }
                return ua.replaceAll("\\s{2,}", " ").trim();
            }
        } catch (Exception ignored) {}
        // Fallback: realistic Chrome Mobile UA
        return "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE
                + "; " + Build.MODEL + ") AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/120.0.6099.230 Mobile Safari/537.36";
    }

    @SuppressLint({"SetJavaScriptEnabled", "ObsoleteSdkInt"})
    private WebView buildWebView() {
        WebView wv = new WebView(this);
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // KEY FIX: Use Chrome-like UA instead of default WebView UA.
        // Facebook blocks OAuth in embedded WebViews (detected by "wv" in UA).
        // With a Chrome UA, Facebook shows "Continue as..." consent dialog.
        s.setUserAgentString(buildChromeUserAgent());

        wv.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Uri uri = req.getUrl();
                    return handleUrl(uri != null ? uri.toString() : null);
                }
                return false;
            }
            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                super.onPageStarted(v, url, f);
                // Also check the page URL itself — some OAuth redirects land here
                if (handleUrl(url)) return;
                if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                // Check if the final URL is an OAuth redirect
                if (handleUrl(url)) return;
                if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
                flushCookies();

                // Inject JS to catch JavaScript-based redirects (window.location = "fbconnect://...")
                // Facebook sometimes uses JS redirects that bypass shouldOverrideUrlLoading
                v.evaluateJavascript(
                    "(function() {" +
                    "  var origAssign = Object.getOwnPropertyDescriptor(Location.prototype, 'href');" +
                    "  if (origAssign && origAssign.set) {" +
                    "    var origSet = origAssign.set;" +
                    "    Object.defineProperty(Location.prototype, 'href', {" +
                    "      set: function(url) {" +
                    "        if (url && (url.indexOf('fbconnect://') === 0 || url.indexOf('fb') === 0) && url.indexOf('http') !== 0) {" +
                    "          window.location.replace(url);" +
                    "          return;" +
                    "        }" +
                    "        origSet.call(this, url);" +
                    "      }," +
                    "      get: origAssign.get" +
                    "    });" +
                    "  }" +
                    "})();",
                    null
                );
            }
            @SuppressWarnings("deprecation")
            @Override
            public void onLoadResource(WebView v, String url) {
                super.onLoadResource(v, url);
                // Catch OAuth redirects that load as sub-resources
                if (url != null && !url.startsWith("http") && !url.startsWith("about:")
                        && !url.startsWith("javascript:") && !url.startsWith("data:")) {
                    handleUrl(url);
                }
            }
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h,
                    android.net.http.SslError e) {
                h.proceed();
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (mProgressBar != null) {
                    mProgressBar.setProgress(p);
                    mProgressBar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                }
            }
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                    boolean isUserGesture, android.os.Message resultMsg) {
                try {
                    String url = view.getHitTestResult().getExtra();
                    if (url != null && !url.isEmpty()) view.loadUrl(url);
                } catch (Exception ignored) {}
                return false;
            }
        });
        return wv;
    }

    @SuppressWarnings("deprecation")
    private void setupCookies() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            CookieSyncManager.createInstance(this);
            CookieSyncManager.getInstance().startSync();
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mWebView != null)
            cm.setAcceptThirdPartyCookies(mWebView, true);
    }

    @SuppressWarnings("deprecation")
    private void flushCookies() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                CookieManager.getInstance().flush();
            else
                CookieSyncManager.getInstance().sync();
        } catch (Exception ignored) {}
    }
}
