package com.mineradio.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Runs the bundled Node.js engine (nodejs-mobile) inside the app.
 *
 * All failures are reported through a Listener instead of throwing, so the UI
 * can always tell the user what went wrong.
 */
public class NodeService {

    public interface Listener {
        void onStage(String stage);
        void onError(String stage, Throwable cause);
        void onReady();
    }

    private static final String TAG = "MineradioNode";
    private static final String PROJECT_ASSET_DIR = "nodejs-project";

    private volatile boolean librariesLoaded = false;
    private volatile String libraryError = null;

    /** Loads libnative-lib.so (which pulls in libnode.so). Never throws. */
    public synchronized String loadLibraries() {
        if (librariesLoaded) return null;
        if (libraryError != null) return libraryError;

        String abi = android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?";
        File libDir = null;
        try {
            libDir = contextRef == null ? null : new File(contextRef.getApplicationInfo().nativeLibraryDir);
        } catch (Throwable ignored) {
        }
        try {
            System.loadLibrary("node");
        } catch (Throwable t) {
            libraryError = "libnode.so 加载失败 (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")"
                    + " | abi=" + abi
                    + (libDir != null ? " | dir=" + libDir + " exists=" + libDir.exists() : "");
            Log.e(TAG, libraryError, t);
            return libraryError;
        }
        try {
            System.loadLibrary("native-lib");
        } catch (Throwable t) {
            libraryError = "libnative-lib.so 加载失败 (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")"
                    + " | abi=" + abi;
            Log.e(TAG, libraryError, t);
            return libraryError;
        }
        librariesLoaded = true;
        return null;
    }

    private Context contextRef;

    public native Integer startNodeWithArguments(String[] arguments);

    /** Verifies the libraries are usable and starting Node actually works. */
    public void start(final Context context, final Listener listener) {
        contextRef = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String stage = "加载引擎库";
                try {
                    if (listener != null) listener.onStage("加载内置引擎库");
                    String err = loadLibraries();
                    if (err != null) {
                        if (listener != null) listener.onError("加载引擎库", new IllegalStateException(err));
                        return;
                    }

                    stage = "释放引擎文件";
                    if (listener != null) listener.onStage("释放引擎文件（首次启动较慢）");
                    File nodeDir = new File(contextRef.getFilesDir(), PROJECT_ASSET_DIR);
                    int[] counter = new int[] { 0 };
                    long t0 = System.currentTimeMillis();
                    if (!new File(nodeDir, "main.js").exists()) {
                        deleteRecursively(nodeDir);
                        copyAssetFolder(contextRef.getAssets(), PROJECT_ASSET_DIR, nodeDir, counter);
                    }
                    long ms = System.currentTimeMillis() - t0;
                    Log.i(TAG, "engine files ready: " + counter[0] + " files in " + ms + "ms");

                    stage = "启动引擎";
                    if (listener != null) listener.onStage("启动引擎（" + counter[0] + " 个文件）");
                    File entry = new File(nodeDir, "main.js");
                    if (!entry.exists()) {
                        if (listener != null) listener.onError(stage, new IllegalStateException("找不到引擎入口文件: " + entry));
                        return;
                    }
                    if (listener != null) listener.onStage("引擎已启动，等待就绪");
                    startNodeWithArguments(new String[] { "node", entry.getAbsolutePath() });
                } catch (Throwable t) {
                    Log.e(TAG, "engine start failed at stage: " + stage, t);
                    if (listener != null) listener.onError(stage, t);
                }
            }
        }, "mineradio-node").start();
    }

    private void copyAssetFolder(AssetManager assets, String assetPath, File dest, int[] counter) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assets, assetPath, dest);
            counter[0]++;
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("Cannot create directory " + dest.getAbsolutePath());
        }
        for (String child : children) {
            copyAssetFolder(assets, assetPath + "/" + child, new File(dest, child), counter);
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
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete()) Log.w(TAG, "Could not delete " + file.getAbsolutePath());
    }

    public boolean isRunning() { return librariesLoaded; }
    public void stop() { }
}
