package com.builder.vedicastrology69;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * NativeBridge: the one file that handles what a plain WebView cannot do on its own.
 * Downloads (http, blob: and data: links), notifications, sharing, vibration, keeping the
 * screen awake, links that belong to other apps, file uploads, camera, microphone and
 * location prompts, and the JavaScript object "Native" that web pages can call.
 */
public class NativeBridge implements DownloadListener {

    public static final int REQ_STORAGE = 2101;
    public static final int REQ_NOTIFY = 2102;
    public static final int REQ_PAGE = 2103;
    public static final int REQ_GEO = 2104;
    public static final int REQ_FILE = 2105;

    private static final String CHANNEL_ID = "app_main";
    private static final int NOTIFICATION_BASE = 4000;

    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int notificationCount = 0;
    private Runnable pendingStorage;
    private PermissionRequest pendingPage;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private ValueCallback<Uri[]> fileCallback;

    public NativeBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        createChannel();
    }

    /** Connects the bridge to the WebView. Call once after the WebView is created. */
    public void attach() {
        webView.addJavascriptInterface(this, "Native");
        webView.setDownloadListener(this);
    }

    // ───────────── Downloads ─────────────

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (!AppConfig.FEATURE_DOWNLOADS) {
            toast("Downloads are turned off in this app.");
            return;
        }
        final String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (needsStorage(() -> downloadWithManager(url, userAgent, mimeType, fileName))) return;
            downloadWithManager(url, userAgent, mimeType, fileName);
        } else {
            // blob: and data: links cannot go to the download manager, so the page reads them and sends the bytes back
            readInPageAndSave(url, mimeType, fileName);
        }
    }

    private void downloadWithManager(String url, String userAgent, String mimeType, String fileName) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null) request.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            request.setTitle(fileName);
            request.setDescription("Downloading");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("no download manager");
            manager.enqueue(request);
            toast("Downloading " + fileName);
        } catch (Exception e) {
            toast("This download could not start.");
        }
    }

    private void readInPageAndSave(String url, String mimeType, String fileName) {
        String safeUrl = jsString(url);
        String safeName = jsString(fileName);
        String safeMime = jsString(mimeType == null ? "application/octet-stream" : mimeType);
        String script = "(function(){fetch('" + safeUrl + "').then(function(r){return r.blob();}).then(function(b){"
                + "var reader=new FileReader();"
                + "reader.onload=function(){var s=reader.result;"
                + "Native.saveBase64('" + safeName + "','" + safeMime + "',s.substring(s.indexOf(',')+1));};"
                + "reader.readAsDataURL(b);"
                + "}).catch(function(){Native.toast('This download could not be read.');});})();";
        main.post(() -> webView.evaluateJavascript(script, null));
    }

    private static String jsString(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }

    /** Called by the page with the bytes of a blob or data download (base64). */
    @JavascriptInterface
    public void saveBase64(final String fileName, final String mimeType, final String base64) {
        if (needsStorage(() -> saveBase64(fileName, mimeType, base64))) return;
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            String name = safeFileName(fileName);
            Uri saved = writeToDownloads(name, mimeType, data);
            if (saved == null) {
                toast("Could not save " + name);
                return;
            }
            toast("Saved to Downloads: " + name);
            if (AppConfig.FEATURE_NOTIFICATIONS) showNotification("Download complete", name);
        } catch (Exception e) {
            toast("Could not save the file.");
        }
    }

    private Uri writeToDownloads(String name, String mimeType, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = activity.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType == null ? "application/octet-stream" : mimeType);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) return null;
                out.write(data);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return Uri.fromFile(file);
    }

    private static String safeFileName(String name) {
        String clean = (name == null ? "" : name).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return clean.isEmpty() ? "download" : clean;
    }

    /** Android 9 and older need storage permission before saving to Downloads. Returns true if we are asking first. */
    private boolean needsStorage(Runnable action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false;
        if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;
        pendingStorage = action;
        main.post(() -> activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
        return true;
    }

    // ───────────── Notifications ─────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Messages and results from the app");
        manager.createNotificationChannel(channel);
    }

    /** Shows a notification from the page: Native.notifyUser(title, body). */
    @JavascriptInterface
    public void notifyUser(final String title, final String body) {
        if (!AppConfig.FEATURE_NOTIFICATIONS) return;
        showNotification(title, body);
    }

    private void showNotification(final String title, final String body) {
        main.post(() -> {
            if (Build.VERSION.SDK_INT >= 33
                    && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission();
                return;
            }
            Intent open = new Intent(activity, activity.getClass());
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int requestCode = NOTIFICATION_BASE + notificationCount;
            PendingIntent pending = PendingIntent.getActivity(activity, requestCode, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = new Notification.Builder(activity, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pending)
                    .setAutoCancel(true);
            NotificationManager manager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(requestCode, builder.build());
            notificationCount = (notificationCount + 1) % 50;
        });
    }

    /** Asks for notification permission (Android 13 and newer): Native.requestNotifications(). */
    @JavascriptInterface
    public void requestNotifications() {
        main.post(this::requestNotificationPermission);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    // ───────────── Everyday phone features ─────────────

    /** Short vibration: Native.vibrate(milliseconds). */
    @JavascriptInterface
    public void vibrate(long milliseconds) {
        if (!AppConfig.FEATURE_VIBRATION) return;
        try {
            Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null) return;
            long ms = Math.max(10L, Math.min(milliseconds, 1000L));
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException e) {
            // the device has no vibrator or the setting is off
        }
    }

    /** Opens the system share sheet: Native.shareText(text, title). */
    @JavascriptInterface
    public void shareText(final String text, final String title) {
        if (!AppConfig.FEATURE_SHARE) return;
        main.post(() -> {
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                String chooserTitle = (title == null || title.isEmpty()) ? "Share" : title;
                activity.startActivity(Intent.createChooser(send, chooserTitle));
            } catch (ActivityNotFoundException e) {
                toast("Nothing can share right now.");
            }
        });
    }

    /** Keeps the screen on while a page needs it: Native.keepAwake(true). */
    @JavascriptInterface
    public void keepAwake(final boolean on) {
        main.post(() -> {
            if (on) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    /** Small message at the bottom: Native.toast(message). */
    @JavascriptInterface
    public void toast(final String message) {
        main.post(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    /** Reloads the start page: Native.reload(). Used by the offline screen. */
    @JavascriptInterface
    public void reload() {
        main.post(() -> webView.loadUrl(AppConfig.START_URL));
    }

    /** True when the phone has a working internet connection: Native.isOnline(). */
    @JavascriptInterface
    public boolean isOnline() {
        return hasInternet(activity);
    }

    public static boolean hasInternet(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** Which features are on, as JSON: Native.appInfo(). */
    @JavascriptInterface
    public String appInfo() {
        return "{\"platform\":\"android\",\"name\":\"" + AppConfig.APP_NAME.replace("\"", "'")
                + "\",\"downloads\":" + AppConfig.FEATURE_DOWNLOADS
                + ",\"notifications\":" + AppConfig.FEATURE_NOTIFICATIONS
                + ",\"share\":" + AppConfig.FEATURE_SHARE
                + ",\"vibration\":" + AppConfig.FEATURE_VIBRATION + "}";
    }

    // ───────────── Links and navigation ─────────────

    /** Returns true when the link was handled outside the WebView. */
    public boolean handleNavigation(String url) {
        if (url == null) return false;
        if (url.startsWith("file:") || url.startsWith("about:") || url.startsWith("blob:") || url.startsWith("data:")) return false;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (!AppConfig.OPEN_EXTERNAL_LINKS || isSameSite(url)) return false;
            openExternal(url);
            return true;
        }
        if (url.startsWith("intent:")) {
            try {
                activity.startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME));
            } catch (Exception e) {
                toast("This link needs an app that is not installed.");
            }
            return true;
        }
        // tel:, mailto:, sms:, maps and other app links
        openExternal(url);
        return true;
    }

    private boolean isSameSite(String url) {
        if (AppConfig.START_URL.startsWith("file:")) return false;
        String home = Uri.parse(AppConfig.START_URL).getHost();
        return home != null && home.equals(Uri.parse(url).getHost());
    }

    public boolean openExternal(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        } catch (ActivityNotFoundException e) {
            toast("No app can open this link.");
            return false;
        }
    }

    // ───────────── File uploads and web permissions ─────────────

    /** Opens the phone's file picker for an upload field on the page. */
    public boolean onFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (!AppConfig.FEATURE_FILE_UPLOAD) {
            callback.onReceiveValue(null);
            return true;
        }
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        try {
            activity.startActivityForResult(params.createIntent(), REQ_FILE);
        } catch (Exception e) {
            fileCallback = null;
            toast("No app can pick files here.");
            callback.onReceiveValue(null);
        }
        return true;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    /** Camera and microphone requests from the page. */
    public void onPagePermission(PermissionRequest request) {
        if (!AppConfig.FEATURE_MEDIA_PERMISSIONS) {
            request.deny();
            return;
        }
        List<String> wanted = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) wanted.add(Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wanted.add(Manifest.permission.RECORD_AUDIO);
        }
        if (wanted.isEmpty()) {
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String permission : wanted) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        pendingPage = request;
        main.post(() -> activity.requestPermissions(missing.toArray(new String[0]), REQ_PAGE));
    }

    /** Location requests from the page. */
    public void onGeolocation(String origin, GeolocationPermissions.Callback callback) {
        if (!AppConfig.FEATURE_MEDIA_PERMISSIONS) {
            callback.invoke(origin, false, false);
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            callback.invoke(origin, true, false);
            return;
        }
        pendingGeo = callback;
        pendingGeoOrigin = origin;
        main.post(() -> activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_GEO));
    }

    /** Called by MainActivity when the user answers a permission prompt. */
    public void handlePermissionResult(int requestCode, int[] grantResults) {
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
        }
        if (requestCode == REQ_STORAGE) {
            Runnable next = pendingStorage;
            pendingStorage = null;
            if (allGranted && next != null) main.post(next);
            else toast("Storage permission is needed to save downloads.");
        } else if (requestCode == REQ_NOTIFY) {
            toast(allGranted ? "Notifications are on" : "Notifications are off");
        } else if (requestCode == REQ_PAGE) {
            PermissionRequest request = pendingPage;
            pendingPage = null;
            if (request != null) {
                if (allGranted) request.grant(request.getResources());
                else request.deny();
            }
        } else if (requestCode == REQ_GEO) {
            if (pendingGeo != null) pendingGeo.invoke(pendingGeoOrigin, allGranted, false);
            pendingGeo = null;
            pendingGeoOrigin = null;
        }
    }

    // ───────────── Offline screen ─────────────

    /** A branded offline page. The button calls Native.reload(). */
    public static String offlineHtml(String title, String message, String accent) {
        return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#0B0A18;color:#F8FAFC;font-family:system-ui,sans-serif}"
                + "body{display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(60vw 60vw at 10% 0%,rgba(124,58,237,.35),transparent 70%),#0B0A18}"
                + ".card{text-align:center;padding:28px;max-width:340px}"
                + ".orb{width:88px;height:88px;margin:0 auto 22px;border-radius:28px;background:linear-gradient(135deg," + accent + ",#EC4899);box-shadow:0 14px 40px rgba(124,58,237,.45)}"
                + "h1{font-size:22px;margin:0 0 10px}p{color:#94A3B8;line-height:1.5;margin:0 0 24px}"
                + "button{border:0;border-radius:16px;padding:14px 30px;font-size:16px;font-weight:700;color:#fff;background:linear-gradient(90deg," + accent + ",#EC4899)}"
                + "</style></head><body><div class='card'><div class='orb'></div><h1>" + title + "</h1><p>" + message + "</p>"
                + "<button onclick='Native.reload()'>Try again</button></div></body></html>";
    }
}
