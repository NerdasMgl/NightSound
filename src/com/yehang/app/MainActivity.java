package com.yehang.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;

/**
 * 单 Activity，整个界面是一个本地网页（assets/ui），通过 Bridge 和系统打交道。
 * 这里只管：WebView 的安全设置、权限、系统栏颜色/亮度、返回键、生命周期事件。
 */
public class MainActivity extends Activity {
    private static final int REQ_PERM = 11;
    private static final String HOME = "file:///android_asset/ui/index.html";
    private static final String ASSET_PREFIX = "file:///android_asset/";

    // 三套主题的底色：夜 / 日 / 床头屏（纯黑，省电）
    private static final int[][] BARS = {
            { 0xFF0B1214, 0xFF111A1D },    // night：状态栏、导航栏
            { 0xFFF3F0E8, 0xFFFBF9F4 },    // day
            { 0xFF000000, 0xFF000000 } };  // bedside

    public Prefs prefs;
    public Db db;
    private Bridge bridge;
    private WebView web;
    private FrameLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String theme = "night";
    private boolean pageReady;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        db = Db.get(this);
        Diag.install(getApplicationContext());
        bridge = new Bridge(this, prefs, db);
        theme = isDark() ? "night" : "day";
        int bg = BARS[isDark() ? 0 : 1][0];
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        setContentView(root);
        applyTheme(theme);
        makeWebView();
    }

    @SuppressWarnings({ "deprecation", "SetJavaScriptEnabled", "JavascriptInterface" })
    private void makeWebView() {
        if (web != null) { root.removeView(web); try { web.destroy(); } catch (Throwable ignore) { } }
        pageReady = false;
        web = new WebView(this);
        web.setBackgroundColor(BARS["day".equals(theme) ? 1 : "bedside".equals(theme) ? 2 : 0][0]);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { return true; } });
        web.setHapticFeedbackEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);                  // 界面全靠它
        s.setAllowFileAccess(false);                   // 不能读手机上的任意文件（assets 不受这个开关影响）
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setBlockNetworkLoads(true);                  // 界面永远不联网
        s.setDomStorageEnabled(false);
        s.setDatabaseEnabled(false);
        s.setGeolocationEnabled(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSaveFormData(false);
        if (Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_OFF);   // 自己有日夜两套配色，别让系统再反色一遍
        WebView.setWebContentsDebuggingEnabled(false);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }   // 不跳转到任何别的页面

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith(ASSET_PREFIX)) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));    // 其他一律拦掉
            }

            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) { pageReady = false; }

            @Override public void onPageFinished(WebView v, String url) { pageReady = true; }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                Diag.log(MainActivity.this, "WebView 渲染进程退出 crashed=" + (Build.VERSION.SDK_INT >= 26 && d.didCrash()));
                handler.post(new Runnable() { public void run() { makeWebView(); } });
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Diag.log(MainActivity.this, "JS " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });
        web.addJavascriptInterface(bridge, "YH");
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(HOME);
    }

    // ================================================================= 生命周期
    @Override protected void onResume() {
        super.onResume();
        bridge.checkOrphans();
        if (web != null) { web.onResume(); web.resumeTimers(); }
        fire("resume", "");
    }

    @Override protected void onPause() {
        super.onPause();
        bridge.onPause();
        MonitorState.bedsideKeepOn = false;
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    @Override protected void onDestroy() {
        bridge.onPause();
        if (web != null) { try { root.removeView(web); web.destroy(); } catch (Throwable ignore) { } web = null; }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        fire("theme", "");
    }

    @Override public void onBackPressed() {
        if (web == null || !pageReady) { super.onBackPressed(); return; }
        web.evaluateJavascript("(function(){try{return !!(window.YH_back&&window.YH_back());}catch(e){return false;}})()", new ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if ("true".equals(v)) return;
                if (MonitorState.running) moveTaskToBack(true);     // 监测中按返回：回桌面，不退出
                else finish();
            }
        });
    }

    // ================================================================= 往网页发事件
    void fire(final String name, final String payload) {
        handler.post(new Runnable() { public void run() {
            if (web == null) return;
            web.evaluateJavascript("window.YH_onEvent&&window.YH_onEvent(" + q(name) + "," + q(payload) + ")", null);
        } });
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    // ================================================================= 外观
    public boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO;
    }

    /** 网页告诉我现在是哪套主题（night / day / bedside），这里同步状态栏、导航栏、屏幕亮度、是否常亮。 */
    public void applyTheme(String t) {
        if (t == null) return;
        theme = t;
        boolean bed = "bedside".equals(t), day = "day".equals(t);
        int[] c = BARS[bed ? 2 : day ? 1 : 0];
        getWindow().setStatusBarColor(c[0]);
        getWindow().setNavigationBarColor(bed ? c[0] : c[1]);
        getWindow().setBackgroundDrawable(new ColorDrawable(c[0]));
        root.setBackgroundColor(c[0]);
        if (web != null) web.setBackgroundColor(c[0]);
        int vis = View.SYSTEM_UI_FLAG_VISIBLE;
        if (day) vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(vis);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = bed ? 0.02f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        if (bed && prefs.bedsideOn()) { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); MonitorState.bedsideKeepOn = true; }
        else { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); MonitorState.bedsideKeepOn = false; }
    }

    void reapplyTheme() { applyTheme(theme); }

    // ================================================================= 权限与系统设置
    public boolean hasMic() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    public boolean hasNotif() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    public boolean ignoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    public void requestPerms() {
        ArrayList<String> need = new ArrayList<String>();
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO);
        if (!hasNotif()) need.add("android.permission.POST_NOTIFICATIONS");
        if (need.isEmpty()) { fire("perm", ""); return; }
        requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_PERM) return;
        if (!hasMic()) Toast.makeText(this, "没有麦克风权限就没法监测。如果系统不再弹窗，请到 系统设置 > 应用 > 夜莺 > 权限 里手动打开。", Toast.LENGTH_LONG).show();
        else if (!hasNotif()) Toast.makeText(this, "没有通知权限：监测仍会进行，但通知栏不会显示“正在监测”。", Toast.LENGTH_LONG).show();
        fire("perm", "");
    }

    public void openAppSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Throwable t) { }
    }

    public void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Throwable t2) { openAppSettings(); }
        }
    }
}
