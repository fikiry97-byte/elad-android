package com.elad.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.splashscreen.SplashScreen;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executor;

public class MainActivity extends Activity {
    static final String HOME = "https://elad-mini-appp.fikiry97.workers.dev/";
    static final String HOST = "elad-mini-appp.fikiry97.workers.dev";
    static final String OFFLINE_PAGE = "file:///android_asset/offline.html";

    // Web client ID (Firebase console > Authentication > Sign-in method > Google > Web SDK configuration)
    static final String WEB_CLIENT_ID = "898464952387-p5nlra4lk8jcm61e9ot6n7tq6okf9j96.apps.googleusercontent.com";

    WebView web;
    ValueCallback<Uri[]> chooser;
    volatile boolean ready = false;   // native splash stays until the first page is visible
    final Executor mainExec = new Handler(Looper.getMainLooper())::post;
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    void openOutside(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) {}
    }

    boolean stayInApp(Uri u) {
        String h = u.getHost() == null ? "" : u.getHost();
        String p = u.getPath() == null ? "" : u.getPath();
        if ("file".equals(u.getScheme()) && p.startsWith("/android_asset/")) return true;
        return HOST.equals(h)
            || h.endsWith("firebaseapp.com")
            || h.endsWith("web.app")
            || h.endsWith("gstatic.com")
            || p.startsWith("/recaptcha");
    }

    // Only talk to the page if it is our own site
    boolean onOurPage() {
        String u = web.getUrl();
        return u != null && HOST.equals(Uri.parse(u).getHost());
    }

    void sendToPage(String js) {
        web.post(() -> { if (onOurPage()) web.evaluateJavascript(js, null); });
    }

    void applySystemBars() {
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        View d = getWindow().getDecorView();
        int flags = d.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(flags);
    }

    // Native Google sign-in: Google blocks its sign-in page inside a WebView,
    // so we get the Google ID token here and hand it to the web page.
    void nativeGoogleSignIn() {
        CredentialManager cm = CredentialManager.create(this);
        GetCredentialRequest req = new GetCredentialRequest.Builder()
            .addCredentialOption(new GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build())
            .build();
        cm.getCredentialAsync(this, req, null, mainExec,
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override
                public void onResult(GetCredentialResponse r) {
                    Credential c = r.getCredential();
                    try {
                        if (c instanceof CustomCredential
                                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                            String token = GoogleIdTokenCredential.createFrom(c.getData()).getIdToken();
                            sendToPage("window.onNativeGoogleToken && window.onNativeGoogleToken('" + token + "')");
                            return;
                        }
                    } catch (Exception e) { /* fall through to error */ }
                    sendToPage("window.onNativeGoogleError && window.onNativeGoogleError('UnexpectedCredential')");
                }

                @Override
                public void onError(GetCredentialException e) {
                    sendToPage("window.onNativeGoogleError && window.onNativeGoogleError('"
                        + e.getClass().getSimpleName() + "')");
                }
            });
    }

    public class Bridge {
        @JavascriptInterface
        public void googleSignIn() {
            runOnUiThread(() -> { if (onOurPage()) nativeGoogleSignIn(); });
        }

        // Kept so the page can still call it. The app has no second splash.
        @JavascriptInterface
        public void splashDone() { }
    }

    @Override
    protected void onCreate(Bundle b) {
        // Must run before super.onCreate. White background + ELAD icon, shown instantly by the system.
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setKeepOnScreenCondition(() -> !ready);
        splash.setOnExitAnimationListener(provider -> provider.remove());

        super.onCreate(b);
        applySystemBars();

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " EladApp/1.0");
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        // Do NOT set any LOAD_CACHE_* cache mode (the service worker needs the default)
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "EladApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (stayInApp(u)) return false;
                openOutside(u.toString());
                return true;
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                super.onPageCommitVisible(v, url);
                ready = true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                ready = true;
                CookieManager.getInstance().flush();
            }

            // No network and the service worker has nothing cached yet:
            // show the bundled sign-in screen instead of Chrome's error page.
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                super.onReceivedError(v, r, e);
                if (!r.isForMainFrame()) return;
                String failed = r.getUrl().toString();
                if (failed.startsWith("file:")) { ready = true; return; }
                v.post(() -> v.loadUrl(OFFLINE_PAGE));
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try { startActivityForResult(p.createIntent(), 1); }
                catch (Exception e) { chooser = null; return false; }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView x, WebResourceRequest r) {
                        openOutside(r.getUrl().toString());
                        return true;
                    }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) r.addRequestHeader("Cookie", ck);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            } catch (Exception e) { openOutside(url); }
        });

        // Safety net: never hold the splash longer than 2.5 s on a very slow connection.
        mainHandler.postDelayed(() -> ready = true, 2500);

        if (b == null) web.loadUrl(HOME); else web.restoreState(b);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooser = null;
        }
        super.onActivityResult(req, res, data);
    }

    // WebView writes cookies lazily. Flush so the 30-day session survives the app being killed.
    @Override protected void onPause() { CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onStop()  { CookieManager.getInstance().flush(); super.onStop(); }

    @Override
    protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
