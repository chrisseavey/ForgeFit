package com.forgefit.personal;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import java.io.File;

public class MainActivity extends Activity {
    private WebView web;
    private long updateDownloadId = -1;
    private BroadcastReceiver downloadReceiver;

    public class UpdateBridge {
        @JavascriptInterface public void openRelease() {
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/chrisseavey/ForgeFit/releases/latest"))));
        }
        @JavascriptInterface public void downloadUpdate(String tag) {
            runOnUiThread(() -> startUpdateDownload(tag));
        }
    }

    private void startUpdateDownload(String tag) {
        if (tag == null || !tag.matches("v[0-9]+\\.[0-9]+(\\.[0-9]+)?")) {
            Toast.makeText(this, "Invalid update version", Toast.LENGTH_LONG).show();
            return;
        }
        if (updateDownloadId != -1) {
            Toast.makeText(this, "Update already downloading", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "forgefit-update.apk");
            if (file.exists() && !file.delete()) throw new Exception("Cannot replace old update");
            String url = "https://github.com/chrisseavey/ForgeFit/releases/download/" + tag + "/app-release.apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("ForgeFit " + tag);
            request.setDescription("Downloading app update");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationUri(Uri.fromFile(file));
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            updateDownloadId = dm.enqueue(request);
            Toast.makeText(this, "Downloading ForgeFit " + tag, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            updateDownloadId = -1;
            Toast.makeText(this, "Could not download update. Use Open Releases instead.", Toast.LENGTH_LONG).show();
        }
    }

    private void installDownloadedUpdate() {
        try {
            File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "forgefit-update.apk");
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open installer. Use Open Releases instead.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(11,13,15));
        getWindow().setNavigationBarColor(Color.rgb(11,13,15));
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != updateDownloadId) return;
                updateDownloadId = -1;
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst() &&
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                        installDownloadedUpdate();
                    } else {
                        Toast.makeText(MainActivity.this, "Download failed. Try again.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        };
        registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(11,13,15));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!"appassets.androidplatform.net".equals(request.getUrl().getHost())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new UpdateBridge(), "ForgeFitUpdates");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }
    @Override protected void onDestroy() {
        if (downloadReceiver != null) unregisterReceiver(downloadReceiver);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
