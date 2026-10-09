package kr.academy.attendance;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
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
import org.json.JSONArray;
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

/** Local-only app shell; optional contacts and carrier SMS use Android permissions. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int EXPORT = 100, IMPORT = 101, CONTACT_PERMISSION = 110, SMS_PERMISSION = 111, MAX_BYTES = 24 * 1024 * 1024;
    private final Set<String> assets = new HashSet<>(Arrays.asList("/index.html", "/styles.css", "/core.js", "/app.js", "/contacts-sms.js"));
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    private WebView webView;
    private AtomicFile dataFile;
    private byte[] pendingExport;
    private boolean pickerOpen;
    private String pendingContactRequest="";

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
                try {
                    byte[] bytes = readLimited(dataFile.openRead());
                    // Existing but empty files are corrupt, not first-run state.
                    if (bytes.length == 0) return envelope(false, "");
                    return envelope(true, new String(bytes, StandardCharsets.UTF_8));
                }
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
                    if (value.getInt("schema") != 2 || value.getJSONArray("students").length() > 5000 || value.getJSONArray("classes").length() > 201 || value.getJSONArray("records").length() > 100000) return envelope(false, "");
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
        @JavascriptInterface public String deviceInfo(){
            // A missing SIM must never erase independently verified permission grants.
            JSONObject info=new JSONObject();
            try{info.put("contactsGranted",checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED);}catch(Exception ignored){}
            try{info.put("smsGranted",checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED);}catch(Exception ignored){}
            try{info.put("smsCapable",SmsOutbox.capable(MainActivity.this));}catch(Exception ignored){}
            try{info.put("simReady",SmsOutbox.simReady());}catch(Exception ignored){}
            return info.toString();
        }
        @JavascriptInterface public void loadContacts(String requestId){
            if(requestId==null||requestId.length()>80)return;
            runOnUiThread(()->{
                pendingContactRequest=requestId;
                if(checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)queryContacts(requestId);
                else requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACT_PERMISSION);
            });
        }
        @JavascriptInterface public void requestSmsPermission(){runOnUiThread(()->{
            if(checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED)permissionResult();
            else requestPermissions(new String[]{Manifest.permission.SEND_SMS},SMS_PERMISSION);
        });}
        @JavascriptInterface public void openAppSettings(){runOnUiThread(()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));}
        @JavascriptInterface public void sendAttendanceSms(String raw){
            if(raw==null||raw.length()>2*1024*1024)return;
            io.execute(()->{try{
                JSONArray changes=new JSONArray(raw);if(changes.length()>5000)throw new Exception("한 번에 처리할 학생이 너무 많아요.");
                JSONObject snapshot=readSnapshot();JSONObject result=SmsOutbox.send(getApplicationContext(),snapshot,changes);result.put("ok",true);addon("onSmsQueue",result);
            }catch(Exception e){addonError("onSmsQueue",e.getMessage()==null?"문자 요청을 처리하지 못했어요.":e.getMessage());}});
        }
        @JavascriptInterface public String smsLogs(){try{return SmsOutbox.logs(MainActivity.this).toString();}catch(Exception e){return "{\"ok\":false,\"error\":\"문자 기록을 읽지 못했어요. 중복 방지를 위해 자동 발송을 중단합니다.\"}";}}
        @JavascriptInterface public void retrySms(String id){
            if(id==null||id.length()>80)return;
            io.execute(()->{try{SmsOutbox.retry(getApplicationContext(),readSnapshot(),id);addon("onSmsQueue",new JSONObject().put("ok",true).put("message","재전송을 요청했어요. 문자 발송 기록에서 결과를 확인해 주세요."));}catch(Exception e){addonError("onSmsQueue",e.getMessage());}});
        }
    }
    private JSONObject readSnapshot() throws Exception {synchronized(stateLock){return new JSONObject(new String(readLimited(dataFile.openRead()),StandardCharsets.UTF_8));}}
    private void addon(String method,JSONObject value){runOnUiThread(()->{if(!isFinishing()&&webView!=null)webView.evaluateJavascript("window.ContactSms&&window.ContactSms."+method+"("+value.toString()+")",null);});}
    private void addonError(String method,String error){try{addon(method,new JSONObject().put("ok",false).put("error",error==null?"요청을 처리하지 못했어요.":error));}catch(Exception ignored){}}
    private void permissionResult(){try{addon("onPermission",new JSONObject(new Bridge().deviceInfo()));}catch(Exception ignored){}}
    private void queryContacts(String requestId){
        io.execute(()->{
            JSONObject result=new JSONObject();
            try{
                result.put("requestId",requestId);JSONArray contacts=new JSONArray();boolean truncated=false;
                String[] projection={ContactsContract.CommonDataKinds.Phone._ID,ContactsContract.CommonDataKinds.Phone.CONTACT_ID,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER};
                try(Cursor cursor=getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,projection,null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC")){
                    if(cursor==null)throw new Exception("연락처를 읽을 수 없어요.");
                    Set<String> seen=new HashSet<>();
                    while(cursor.moveToNext()){
                        if(contacts.length()>=20000){truncated=true;break;}
                        String phone=SmsRules.phone(cursor.getString(3)),name=cursor.getString(2);if(phone.isEmpty()||name==null||name.trim().isEmpty())continue;
                        String key=cursor.getString(1)+"|"+phone;if(!seen.add(key))continue;
                        contacts.put(new JSONObject().put("id",cursor.getString(1)+"_"+cursor.getString(0)).put("name",name.length()>80?name.substring(0,80):name).put("phone",phone));
                    }
                }
                result.put("ok",true).put("contacts",contacts).put("truncated",truncated);
            }catch(Exception e){try{result.put("ok",false).put("error","연락처 권한을 허용했는지 확인해 주세요.");}catch(Exception ignored){}}
            addon("onContacts",result);
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==CONTACT_PERMISSION){
            if(checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)queryContacts(pendingContactRequest);
            else {try{addon("onContacts",new JSONObject().put("requestId",pendingContactRequest).put("ok",false).put("error","연락처 권한이 거부됐어요. 연락처를 직접 입력하거나 앱 설정에서 권한을 허용해 주세요."));}catch(Exception ignored){}}
        }else if(request==SMS_PERMISSION)permissionResult();
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
