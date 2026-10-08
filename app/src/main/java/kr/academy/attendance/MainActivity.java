package kr.academy.attendance;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline-only shell. The bridge is reachable only from bundled, allowlisted assets. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int EXPORT = 100, IMPORT = 101, MAX_BYTES = 24 * 1024 * 1024;
    private final Set<String> assets = new HashSet<>(Arrays.asList("/index.html", "/styles.css", "/core.js", "/app.js"));
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    private WebView webView;
    private AtomicFile dataFile;
    private byte[] pendingExport;
    private boolean pickerOpen;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dataFile = new AtomicFile(new File(getFilesDir(), "attendance-v1.json"));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(245, 246, 248));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(245, 246, 248));
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost()) || uri.getPort() != -1 || !assets.contains(uri.getPath()) || !"GET".equals(request.getMethod())) return blocked();
                String path = uri.getPath();
                String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css" : "application/javascript";
                try { return new WebResourceResponse(mime, "UTF-8", getAssets().open(path.substring(1))); }
                catch (Exception error) { return blocked(); }
            }
        });
        webView.addJavascriptInterface(new Bridge(), "NativeAttendance");
        webView.loadUrl(ORIGIN + "/index.html");
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0]));
    }
    private static String envelope(boolean ok, String data) {
        try { return new JSONObject().put("ok", ok).put("data", data).toString(); }
        catch (Exception ignored) { return "{\"ok\":false}"; }
    }
    private static byte[] readLimited(InputStream input) throws Exception {
        if (input == null) throw new FileNotFoundException();
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int length;
            while ((length = in.read(buffer)) != -1) {
                if (out.size() + length > MAX_BYTES) throw new IllegalArgumentException("File too large");
                out.write(buffer, 0, length);
            }
            return out.toByteArray();
        }
    }
    public final class Bridge {
        @JavascriptInterface public String readState() {
            synchronized (stateLock) {
                try { return envelope(true, new String(readLimited(dataFile.openRead()), StandardCharsets.UTF_8)); }
                catch (FileNotFoundException error) {
                    boolean exists = dataFile.getBaseFile().exists() || new File(dataFile.getBaseFile() + ".bak").exists();
                    return envelope(!exists, "");
                }
                catch (Exception error) { return envelope(false, ""); }
            }
        }
        @JavascriptInterface public String saveState(String json) {
            synchronized (stateLock) {
                FileOutputStream out = null;
                try {
                    if (json == null) return envelope(false, "");
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_BYTES) return envelope(false, "");
                    JSONObject value = new JSONObject(json);
                    if (value.getInt("schema") != 1 || value.getJSONArray("students").length() > 5000 || value.getJSONArray("classes").length() > 200 || value.getJSONArray("records").length() > 100000) return envelope(false, "");
                    value.getJSONObject("settings");
                    out = dataFile.startWrite(); out.write(bytes); dataFile.finishWrite(out);
                    return envelope(true, "");
                } catch (Exception error) { if (out != null) dataFile.failWrite(out); return envelope(false, ""); }
            }
        }
        @JavascriptInterface public void exportFile(String name, String mime, String content) {
            if (content == null || name == null || (!"application/json".equals(mime) && !"text/csv".equals(mime))) return;
            final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) { notifyResult("파일이 너무 커서 저장할 수 없어요. 월별 CSV로 나누어 저장해 주세요."); return; }
            final String safeName = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            runOnUiThread(() -> {
                if (pickerOpen) return;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, safeName);
                pendingExport = bytes; pickerOpen = true;
                try { startActivityForResult(intent, EXPORT); }
                catch (Exception error) { pendingExport = null; pickerOpen = false; notifyResult("파일 저장 앱을 열 수 없어요."); }
            });
        }
        @JavascriptInterface public void importFile() {
            runOnUiThread(() -> {
                if (pickerOpen) return;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                pickerOpen = true;
                try { startActivityForResult(intent, IMPORT); }
                catch (Exception error) { pickerOpen = false; notifyResult("파일 선택 앱을 열 수 없어요."); }
            });
        }
    }
    private void notifyResult(String message) {
        runOnUiThread(() -> { if (!isFinishing() && webView != null) webView.evaluateJavascript("window.AttendanceApp&&window.AttendanceApp.onNativeResult(" + JSONObject.quote(message) + ")", null); });
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request != EXPORT && request != IMPORT) return;
        pickerOpen = false;
        final byte[] bytes = pendingExport; pendingExport = null;
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        final Uri uri = intent.getData();
        if (request == EXPORT) {
            if (bytes == null) { notifyResult("저장할 내용을 다시 선택해 주세요."); return; }
            io.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new FileNotFoundException();
                    out.write(bytes); out.flush();
                } catch (Exception error) { notifyResult("파일을 저장하지 못했어요. 저장 위치와 공간을 확인해 주세요."); return; }
                notifyResult("파일을 저장했어요.");
            });
        } else {
            io.execute(() -> {
                try {
                    String content = new String(readLimited(getContentResolver().openInputStream(uri)), StandardCharsets.UTF_8);
                    runOnUiThread(() -> { if (!isFinishing() && webView != null) webView.evaluateJavascript("window.AttendanceApp&&window.AttendanceApp.onImport(" + JSONObject.quote(content) + ")", null); });
                } catch (Exception error) { notifyResult("백업을 읽지 못했어요. 24MB 이하의 오늘출석 JSON 파일인지 확인해 주세요."); }
            });
        }
    }
    @Override public void onBackPressed() {
        handleBack();
    }
    private void handleBack() {
        webView.evaluateJavascript("window.AttendanceApp?window.AttendanceApp.handleBack():false", value -> { if (!"true".equals(value)) finish(); });
    }
    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.evaluateJavascript("window.AttendanceApp&&window.AttendanceApp.onResume()", null);
    }
    @Override protected void onDestroy() {
        if (webView != null) { webView.removeJavascriptInterface("NativeAttendance"); webView.destroy(); webView = null; }
        io.shutdown(); super.onDestroy();
    }
}
