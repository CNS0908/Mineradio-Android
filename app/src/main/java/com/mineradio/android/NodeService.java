package com.mineradio.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Runs the bundled Node.js backend (nodejs-mobile) inside the app.
 * The JS project lives in assets/nodejs-project and is unpacked to the app
 * files directory on first launch.
 */
public class NodeService {

    private static final String TAG = "MineradioNode";
    private static final String PROJECT_ASSET_DIR = "nodejs-project";
    private static final String PREFS_NAME = "mineradio_node";
    private static final int SERVER_PORT = 3000;

    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("node");
    }

    private volatile boolean running = false;
    private volatile int port = SERVER_PORT;

    public interface Callback {
        void onReady();
    }

    /** Implemented by the nodejs-mobile JNI bridge in libnative-lib.so. */
    public native Integer startNodeWithArguments(String[] arguments);

    public void start(final Context context, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        if (running) {
            if (callback != null) callback.onReady();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File nodeDir = new File(appContext.getFilesDir(), PROJECT_ASSET_DIR);
                    if (needsRefresh(appContext, nodeDir)) {
                        Log.i(TAG, "Unpacking Node.js project into " + nodeDir.getAbsolutePath());
                        deleteRecursively(nodeDir);
                        copyAssetFolder(appContext.getAssets(), PROJECT_ASSET_DIR, nodeDir);
                        rememberVersion(appContext);
                    }
                    File entry = new File(nodeDir, "main.js");
                    running = true;
                    Log.i(TAG, "Starting Node.js backend: " + entry.getAbsolutePath());
                    startNodeWithArguments(new String[] { "node", entry.getAbsolutePath() });
                } catch (Throwable t) {
                    running = false;
                    Log.e(TAG, "Unable to start the Node.js backend", t);
                } finally {
                    if (callback != null) callback.onReady();
                }
            }
        }, "mineradio-node").start();
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
    }

    private boolean needsRefresh(Context context, File nodeDir) {
        if (!nodeDir.exists() || !new File(nodeDir, "main.js").exists()) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt("versionCode", -1) != currentVersionCode(context);
    }

    private void rememberVersion(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("versionCode", currentVersionCode(context))
                .apply();
    }

    @SuppressWarnings("deprecation")
    private int currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private void copyAssetFolder(AssetManager assets, String assetPath, File dest) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assets, assetPath, dest);
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("Cannot create directory " + dest.getAbsolutePath());
        }
        for (String child : children) {
            copyAssetFolder(assets, assetPath + "/" + child, new File(dest, child));
        }
    }

    private void copyAssetFile(AssetManager assets, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory " + parent.getAbsolutePath());
        }
        InputStream in = assets.open(assetPath);
        OutputStream out = new FileOutputStream(dest);
        try {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Could not delete " + file.getAbsolutePath());
        }
    }
}
