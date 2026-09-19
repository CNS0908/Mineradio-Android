package com.mineradio.android;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MineradioMain";
    private static final long STARTUP_TIMEOUT_MS = 120000L;

    private WebView webView;
    private NodeService nodeService;
    private int serverPort = 3000;

    public class AndroidBridge {
        @JavascriptInterface
        public String getPlatform() {
            return "android";
        }

        @JavascriptInterface
        public boolean isAndroid() {
            return true;
        }

        @JavascriptInterface
        public int getServerPort() {
            return serverPort;
        }

        @JavascriptInterface
        public void showToast(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyImmersiveMode();

        nodeService = new NodeService();
        serverPort = nodeService.getPort();
        nodeService.start(this, null);

        waitForBackendThenShowUi();
    }

    private void waitForBackendThenShowUi() {
        final int port = serverPort;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ready = false;
                long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (isBackendUp(port)) {
                        ready = true;
                        break;
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                final boolean backendReady = ready;
                Log.i(TAG, "Backend ready: " + backendReady);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (backendReady) {
                            showWebView("http://127.0.0.1:" + port + "/");
                        } else {
                            showStartupFailure();
                        }
                    }
                });
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
            try {
                socket.close();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showWebView(String url) {
        if (webView != null) return;

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setBackgroundColor(Color.parseColor("#08090B"));
        webView.loadUrl(url);
    }

    private void showStartupFailure() {
        TextView message = new TextView(this);
        message.setText("The music engine did not start in time.\n\nPlease close the app completely and open it again.");
        message.setTextColor(Color.parseColor("#E8E8E8"));
        message.setGravity(Gravity.CENTER);
        message.setPadding(48, 48, 48, 48);
        message.setBackgroundColor(Color.parseColor("#08090B"));
        setContentView(message);
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.evaluateJavascript("window.history.back();", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (nodeService != null) {
            nodeService.stop();
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
