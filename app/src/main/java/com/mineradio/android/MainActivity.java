package com.mineradio.android;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MineradioMain";
    private static final int SERVER_PORT = 3000;
    private static final long STARTUP_TIMEOUT_MS = 180000L;

    private TextView statusView;
    private LinearLayout rootLayout;
    private WebView webView;
    private NodeService nodeService;
    private volatile String lastStage = "启动";
    private volatile String report = "";

    public class AndroidBridge {
        @JavascriptInterface
        public String getPlatform() { return "android"; }

        @JavascriptInterface
        public boolean isAndroid() { return true; }

        @JavascriptInterface
        public int getServerPort() { return SERVER_PORT; }

        @JavascriptInterface
        public void showToast(final String text) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, final Throwable t) {
                Log.e(TAG, "uncaught", t);
                runOnUiThread(new Runnable() {
                    @Override public void run() { showError("未捕获异常（" + thread.getName() + "）", t); }
                });
            }
        });

        try {
            buildStatusUi();
        } catch (Throwable t) {
            showError("初始化界面", t);
            return;
        }

        try {
            nodeService = new NodeService();
            nodeService.start(this, new NodeService.Listener() {
                @Override public void onStage(final String stage) {
                    lastStage = stage;
                    Log.i(TAG, "stage: " + stage);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { if (statusView != null) statusView.setText("正在启动引擎…\n" + stage); }
                    });
                }
                @Override public void onError(final String stage, final Throwable cause) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError(stage, cause); }
                    });
                }
                @Override public void onReady() { }
            });
            waitForBackendThenShowUi();
        } catch (Throwable t) {
            showError("启动引擎", t);
        }
    }

    private void buildStatusUi() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#08090B"));
        rootLayout.setPadding(dp(28), dp(48), dp(28), dp(28));

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#E8E8E8"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setText("正在启动引擎…\n准备中");
        rootLayout.addView(statusView);
        setContentView(rootLayout);
    }

    private void showError(String stage, Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String stack = sw.toString();
            if (stack.length() > 1200) stack = stack.substring(0, 1200) + "…";

            report = "【Mineradio 启动失败】\n"
                    + "阶段: " + stage + "\n"
                    + "异常: " + t.getClass().getName() + "\n"
                    + "信息: " + t.getMessage() + "\n\n"
                    + "—— 堆栈 ——\n" + stack + "\n\n"
                    + diagnose();

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(Color.parseColor("#08090B"));
            box.setPadding(dp(20), dp(60), dp(20), dp(20));

            TextView tv = new TextView(this);
            tv.setTextColor(Color.parseColor("#FF9A9A"));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextIsSelectable(true);
            tv.setText(report);

            ScrollView sv = new ScrollView(this);
            sv.addView(tv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            Button copy = new Button(this);
            copy.setText("复制诊断信息");
            copy.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Mineradio", report));
                    Toast.makeText(MainActivity.this, "已复制，可以粘贴发送", Toast.LENGTH_LONG).show();
                }
            });
            box.addView(copy);

            Button retry = new Button(this);
            retry.setText("重新启动引擎");
            retry.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { recreate(); }
            });
            box.addView(retry);

            setContentView(box);
        } catch (Throwable ignored) {
            TextView fallback = new TextView(this);
            fallback.setText("启动失败: " + t);
            fallback.setTextColor(Color.WHITE);
            setContentView(fallback);
        }
    }

    private String diagnose() {
        StringBuilder sb = new StringBuilder("—— 诊断信息 ——\n");
        try {
            sb.append("手机: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
            StringBuilder abis = new StringBuilder();
            for (String a : Build.SUPPORTED_ABIS) abis.append(a).append(" ");
            sb.append("ABI: ").append(abis).append("\n");
        } catch (Throwable ignored) { }
        try {
            long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
            sb.append("系统页大小: ").append(pageSize).append(" 字节");
            if (pageSize >= 16384) sb.append("  ← 16KB 页设备，内置引擎库不兼容");
            sb.append("\n");
        } catch (Throwable t) { sb.append("系统页大小: 读取失败\n"); }
        try {
            String libDirPath = getApplicationInfo().nativeLibraryDir;
            File libDir = new File(libDirPath);
            File nodeSo = new File(libDir, "libnode.so");
            sb.append("库目录: ").append(libDirPath).append("\n");
            sb.append("libnode.so: ").append(nodeSo.exists() ? ("存在 " + (nodeSo.length() / 1048576) + "MB") : "缺失").append("\n");
            File nl = new File(libDir, "libnative-lib.so");
            sb.append("libnative-lib.so: ").append(nl.exists() ? "存在" : "缺失").append("\n");
        } catch (Throwable t) { sb.append("库目录: 读取失败\n"); }
        try {
            File engine = new File(getFilesDir(), "nodejs-project");
            File mainJs = new File(engine, "main.js");
            sb.append("引擎目录: ").append(engine).append("\n");
            sb.append("main.js: ").append(mainJs.exists() ? "存在" : "缺失").append("\n");
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    private void waitForBackendThenShowUi() {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ready = false;
                long start = System.currentTimeMillis();
                long deadline = start + STARTUP_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (isBackendUp(SERVER_PORT)) { ready = true; break; }
                    final long elapsed = (System.currentTimeMillis() - start) / 1000;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (statusView != null && webView == null) {
                                statusView.setText("正在启动引擎…\n" + lastStage + "\n已等待 " + elapsed + " 秒");
                            }
                        }
                    });
                    try { Thread.sleep(700L); } catch (InterruptedException ignored) { return; }
                }
                if (ready) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showWebView("http://127.0.0.1:" + SERVER_PORT + "/"); }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            showError("等待引擎端口超时", new IllegalStateException("180 秒内 127.0.0.1:" + SERVER_PORT + " 没有就绪，最后阶段: " + lastStage));
                        }
                    });
                }
            }
        }, "mineradio-startup").start();
    }

    private boolean isBackendUp(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showWebView(String url) {
        if (webView != null) return;
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setBackgroundColor(Color.parseColor("#08090B"));
        setContentView(webView);
        webView.loadUrl(url);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (nodeService != null) nodeService.stop();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
