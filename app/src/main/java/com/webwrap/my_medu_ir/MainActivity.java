package com.webwrap.my_medu_ir;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final String CHANNEL = "web_notifications";
    private static final Pattern META = Pattern.compile("^\\s*//\\s*@(\\S+)\\s+(.+?)\\s*$");

    private WebView web;
    private FrameLayout root;
    private ImageView splash;
    private boolean splashGone = false;
    private long startedAt;

    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private ValueCallback<Uri[]> fileCb;

    private final SparseArray<Runnable> pending = new SparseArray<>();
    private int nextCode = 100;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String bridgeJs = "";
    private String gmJs = "";
    private final List<Script> scripts = new ArrayList<>();

    static class Script {
        int idx;
        String name;
        String code;
        boolean atStart = false;
        final List<Pattern> inc = new ArrayList<>();
        final List<Pattern> exc = new ArrayList<>();
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startedAt = SystemClock.uptimeMillis();

        String startUrl = readAsset("url.txt").trim();
        bridgeJs = readAsset("bridge.js");
        gmJs = readAsset("gm.js");
        loadScripts();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF121212);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        int sid = getResources().getIdentifier("splash", "drawable", getPackageName());
        if (sid != 0) {
            splash = new ImageView(this);
            splash.setImageResource(sid);
            splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
            ui.postDelayed(() -> hideSplash(), 8000);
        }
        setContentView(root);

        setupWebView();
        web.loadUrl(startUrl);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustom();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    private void hideSplash() {
        if (splash == null || splashGone) return;
        splashGone = true;
        splash.animate().alpha(0f).setDuration(300).withEndAction(() -> root.removeView(splash)).start();
    }

    // ------------------------------------------------------------ webview

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Look like regular Chrome so sites (login pages etc.) don't treat us as a WebView
        String userAgent = s.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", "");
        s.setUserAgentString(userAgent);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setDownloadListener((dlUrl, agent, disposition, mime, length) -> {
            if (dlUrl.startsWith("blob:") || dlUrl.startsWith("data:")) {
                Toast.makeText(MainActivity.this, "This download type isn't supported", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(dlUrl));
                if (mime != null) rq.setMimeType(mime);
                String ck = CookieManager.getInstance().getCookie(dlUrl);
                if (ck != null) rq.addRequestHeader("Cookie", ck);
                if (agent != null) rq.addRequestHeader("User-Agent", agent);
                rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String name = URLUtil.guessFileName(dlUrl, disposition, mime);
                rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(rq);
                Toast.makeText(MainActivity.this, "Downloading " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dlUrl))); } catch (Exception ignored) { }
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                inject(url, true);
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                inject(url, true);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                inject(url, false);
                CookieManager.getInstance().flush();
                if (splash != null && !splashGone) {
                    long wait = Math.max(0, 1000 - (SystemClock.uptimeMillis() - startedAt));
                    ui.postDelayed(() -> hideSplash(), wait);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(r.getUrl());
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                String failing = r.getUrl().toString();
                String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                        + "<body style='font-family:sans-serif;background:#121212;color:#eee;text-align:center;padding:48px 24px'>"
                        + "<h2>Can't load page</h2><p style='opacity:.7'>"
                        + TextUtils.htmlEncode(String.valueOf(e.getDescription())) + "</p>"
                        + "<button style='padding:12px 28px;font-size:16px;border:0;border-radius:24px' "
                        + "onclick=\"location.href='" + failing.replace("'", "%27") + "'\">Retry</button></body></html>";
                v.loadDataWithBaseURL(failing, html, "text/html", "UTF-8", failing);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback cb) {
                ensure(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                        () -> cb.invoke(origin,
                                has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION),
                                false));
            }

            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    List<String> need = new ArrayList<>();
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) need.add(Manifest.permission.CAMERA);
                        else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) need.add(Manifest.permission.RECORD_AUDIO);
                    }
                    ensure(need.toArray(new String[0]), () -> {
                        List<String> ok = new ArrayList<>();
                        for (String r : req.getResources()) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                                if (has(Manifest.permission.CAMERA)) ok.add(r);
                            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                                if (has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
                            } else {
                                ok.add(r);
                            }
                        }
                        if (ok.isEmpty()) req.deny();
                        else req.grant(ok.toArray(new String[0]));
                    });
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult r) {
                final EditText et = new EditText(MainActivity.this);
                et.setText(def);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setView(et)
                        .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm(et.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = cb;
                web.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() {
                hideCustom();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
    }

    private void hideCustom() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
    }

    private boolean handleUrl(Uri u) {
        String scheme = u.getScheme();
        if (scheme == null) return false;
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                || scheme.equals("blob") || scheme.equals("data")) return false;
        if (scheme.equals("file")) return true;
        try {
            Intent i;
            if (scheme.equals("intent")) {
                i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
            } else {
                i = new Intent(Intent.ACTION_VIEW, u);
            }
            startActivity(i);
        } catch (Exception ignored) { }
        return true;
    }

    // ------------------------------------------------------------ permissions

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensure(String[] perms, Runnable after) {
        List<String> missing = new ArrayList<>();
        for (String p : perms) if (!has(p)) missing.add(p);
        if (missing.isEmpty()) {
            after.run();
            return;
        }
        int code = nextCode++;
        pending.put(code, after);
        requestPermissions(missing.toArray(new String[0]), code);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        Runnable r = pending.get(code);
        pending.remove(code);
        if (r != null) r.run();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    // ------------------------------------------------------------ notifications

    private String[] notifPerms() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        return new String[0];
    }

    private boolean notifGranted() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!nm.areNotificationsEnabled()) return false;
        return Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void postNotification(String title, String body) {
        if (!notifGranted()) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Web notifications", NotificationManager.IMPORTANCE_HIGH));
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH);
        }
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (title == null) title = "";
        if (body == null) body = "";
        nb.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setDefaults(Notification.DEFAULT_ALL);
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), nb.build());
    }

    class Bridge {
        @JavascriptInterface
        public boolean hasNotifPermission() {
            return notifGranted();
        }

        @JavascriptInterface
        public void requestNotifPermission() {
            runOnUiThread(() -> ensure(notifPerms(), () -> { }));
        }

        @JavascriptInterface
        public void showNotification(String title, String body) {
            postNotification(title, body);
        }
    }

    // ------------------------------------------------------------ userscripts

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private void loadScripts() {
        try {
            String[] names = getAssets().list("userscripts");
            if (names == null) return;
            Arrays.sort(names);
            int idx = 0;
            for (String n : names) {
                String code = readAsset("userscripts/" + n);
                Script sc = new Script();
                sc.idx = idx++;
                sc.name = n;
                if (n.toLowerCase().endsWith(".css")) {
                    sc.code = "GM_addStyle(" + JSONObject.quote(code) + ");";
                    sc.atStart = true;
                } else {
                    sc.code = code;
                    parseMeta(sc, code);
                }
                scripts.add(sc);
            }
        } catch (IOException ignored) { }
    }

    private void parseMeta(Script sc, String code) {
        boolean inMeta = false;
        for (String line : code.split("\\r?\\n")) {
            if (line.contains("==UserScript==")) { inMeta = true; continue; }
            if (line.contains("==/UserScript==")) break;
            if (!inMeta) continue;
            Matcher m = META.matcher(line);
            if (!m.find()) continue;
            String k = m.group(1);
            String v = m.group(2);
            if (k.equals("match") || k.equals("include")) addPattern(sc.inc, v);
            else if (k.equals("exclude") || k.equals("exclude-match")) addPattern(sc.exc, v);
            else if (k.equals("run-at") && v.startsWith("document-start")) sc.atStart = true;
        }
    }

    private static void addPattern(List<Pattern> list, String v) {
        try { list.add(Pattern.compile(glob(v))); } catch (Exception ignored) { }
    }

    private static String glob(String v) {
        if (v.equals("<all_urls>") || v.equals("*")) return ".*";
        if (v.length() > 2 && v.startsWith("/") && v.endsWith("/")) return v.substring(1, v.length() - 1);
        StringBuilder sb = new StringBuilder();
        if (v.startsWith("*://")) { sb.append("https?://"); v = v.substring(4); }
        if (v.startsWith("*.")) { sb.append("([^/]*\\.)?"); v = v.substring(2); }
        for (char c : v.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (".?+()[]{}^$|\\".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return sb.toString();
    }

    private boolean matches(Script sc, String url) {
        for (Pattern p : sc.exc) if (p.matcher(url).matches()) return false;
        if (sc.inc.isEmpty()) return true;
        for (Pattern p : sc.inc) if (p.matcher(url).matches()) return true;
        return false;
    }

    private String wrap(Script sc) {
        return "(function(){if(window['__us_" + sc.idx + "'])return;window['__us_" + sc.idx + "']=1;\n"
                + gmJs + "\ntry{\n" + sc.code + "\n}catch(e){console.error('[userscript "
                + sc.name.replace("'", "").replace("\\", "") + "]',e);}})();";
    }

    private void inject(String url, boolean atStart) {
        if (url == null || !url.startsWith("http")) return;
        web.evaluateJavascript(bridgeJs, null);
        for (Script sc : scripts) {
            if (sc.atStart != atStart) continue;
            if (!matches(sc, url)) continue;
            web.evaluateJavascript(wrap(sc), null);
        }
    }
}
