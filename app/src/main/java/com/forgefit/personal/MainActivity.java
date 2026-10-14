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
import android.webkit.ValueCallback;
import android.provider.MediaStore;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private WebView web;
    private static final int PICK_FOOD_PHOTO = 7403;
    private ValueCallback<Uri[]> foodPhotoCallback;
    private Uri foodCameraUri;
    private static final int EXPORT_BACKUP = 7401;
    private static final int IMPORT_BACKUP = 7402;
    private String pendingBackup;
    private long updateDownloadId = -1;
    private BroadcastReceiver downloadReceiver;

    public class FoodScanBridge {
        @JavascriptInterface public void analyze(String endpoint, String image) {
            new Thread(() -> {
                String result;
                HttpURLConnection connection = null;
                try {
                    Uri uri = Uri.parse(endpoint);
                    if (!"https".equals(uri.getScheme()) || !"script.google.com".equals(uri.getHost())
                        || !uri.getPath().matches("/macros/s/[^/]+/exec")) {
                        throw new Exception("Scanner URL must be a Google Apps Script web app /exec URL.");
                    }
                    if (image == null || image.length() > 2800000 || (!image.startsWith("data:image/") && (image.trim().length() < 2 || image.length() > 500))) {
                        throw new Exception("Please enter a food description or choose a valid photo.");
                    }
                    connection = (HttpURLConnection) new URL(endpoint).openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(45000);
                    connection.setDoOutput(true);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    byte[] bytes = new JSONObject().put(image.startsWith("data:image/") ? "image" : "description", image).toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream out = connection.getOutputStream()) {out.write(bytes);}
                    int status = connection.getResponseCode();
                    InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
                    if (stream == null) throw new Exception("No response from scanner (HTTP " + status + ")");
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192]; int n;
                    while ((n = stream.read(chunk)) != -1) {
                        buffer.write(chunk, 0, n);
                        if (buffer.size() > 30000) throw new Exception("Unexpected scanner response.");
                    }
                    String body = buffer.toString("UTF-8");
                    if (status >= 400) throw new Exception("Scanner HTTP " + status + ". Check deployment access.");
                    result = new JSONObject(body).toString();
                } catch (Exception ex) {
                    try {result = new JSONObject().put("error", "Scanner connection: " + ex.getMessage()).toString();}
                    catch (Exception ignored) {result = "{}";}
                } finally {if (connection != null) connection.disconnect();}
                final String response = result;
                runOnUiThread(() -> web.evaluateJavascript("forgeFoodScanComplete(" + JSONObject.quote(response) + ")", null));
            }).start();
        }
    }

    public class UpdateBridge {
        @JavascriptInterface public void openRelease() {
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/chrisseavey/ForgeFit/releases/latest"))));
        }
        @JavascriptInterface public void downloadUpdate(String tag) {
            runOnUiThread(() -> startUpdateDownload(tag));
        }
    }


    public class BackupBridge {
        @JavascriptInterface public void exportBackup(String json) {
            runOnUiThread(() -> {
                pendingBackup = json;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "forgefit-backup.json");
                startActivityForResult(intent, EXPORT_BACKUP);
            });
        }
        @JavascriptInterface public void importBackup() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, IMPORT_BACKUP);
            });
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FOOD_PHOTO) {
            if (foodPhotoCallback != null) {
                Uri selected = data != null ? data.getData() : null;
                if (resultCode == RESULT_OK && selected == null) selected = foodCameraUri;
                foodPhotoCallback.onReceiveValue(resultCode == RESULT_OK && selected != null ? new Uri[]{selected} : null);
                foodPhotoCallback = null;
                foodCameraUri = null;
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (requestCode == EXPORT_BACKUP && pendingBackup != null) {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                    if (stream == null) throw new Exception("Cannot open destination");
                    stream.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                }
                pendingBackup = null;
                Toast.makeText(this, "ForgeFit backup saved", Toast.LENGTH_LONG).show();
            } else if (requestCode == IMPORT_BACKUP) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                    if (in == null) throw new Exception("Cannot open backup");
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        if (out.size() > 10000000) throw new Exception("Backup file too large");
                    }
                }
                String json = out.toString("UTF-8");
                web.evaluateJavascript("receiveForgeBackup(" + JSONObject.quote(json) + ")", null);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Backup error: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (foodPhotoCallback != null) foodPhotoCallback.onReceiveValue(null);
                foodPhotoCallback = callback;
                try {
                    Intent gallery = new Intent(Intent.ACTION_GET_CONTENT);
                    gallery.addCategory(Intent.CATEGORY_OPENABLE);
                    gallery.setType("image/*");
                    Intent chooser = Intent.createChooser(gallery, "Choose food photo");
                    File photo = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "forgefit-food-photo.jpg");
                    foodCameraUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", photo);
                    Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    camera.putExtra(MediaStore.EXTRA_OUTPUT, foodCameraUri);
                    camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                    startActivityForResult(chooser, PICK_FOOD_PHOTO);
                } catch (Exception ex) {
                    foodPhotoCallback.onReceiveValue(null);
                    foodPhotoCallback = null;
                    Toast.makeText(MainActivity.this, "Could not open photo chooser", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        web.addJavascriptInterface(new UpdateBridge(), "ForgeFitUpdates");
        web.addJavascriptInterface(new FoodScanBridge(), "ForgeFitFoodScan");
        web.addJavascriptInterface(new BackupBridge(), "ForgeFitBackup");
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
