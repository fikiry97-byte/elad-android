package com.elad.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
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
import android.widget.FrameLayout;
import android.widget.ImageView;

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

    // Size of the icon on the loading cover. Matches the system splash icon canvas.
    // If you ever see a small "jump" between the system splash and the cover, tweak this number.
    static final int ICON_DP = 288;
    // After the page finishes loading, wait this long for it to paint before removing the cover
    // (the page can remove it sooner by calling EladApp.splashDone()).
    static final long PAGE_SETTLE_MS = 700;
    // If nothing is shown after this long, give up and show offline.html (which keeps retrying by itself).
    static final long SLOW_LOAD_MS = 30000;

    WebView web;
    FrameLayout cover;                // white screen with the ELAD icon, sits on top of the WebView
    ValueCallback<Uri[]> chooser;
    volatile boolean ready = false;   // native splash stays only until the cover is in place

    // How many times we retry loading HOME after a network error before
    // falling back to the bundled offline.html. This gives the Service Worker
    // a real chance to serve its cache.
    int offlineRetries = 0;

    // URL of the last failed main-frame load. The browser fires onPageFinished for its own
    // error page with this URL; we must not treat that as "the page is ready".
    String errorUrl = null;
    // True while the bundled offline.html is what's on screen.
    boolean onOfflinePage = false;

    final Executor mainExec = new Handler(Looper.getMainLooper())::post;
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    final Runnable hideCoverTask = this::hideCover;
    final Runnable slowLoadTask = () -> {
        if (cover != null && cover.getVisibility() == View.VISIBLE) web.loadUrl(OFFLINE_PAGE);
    };

    void showCover() {
        mainHandler.removeCallbacks(hideCoverTask);
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(slowLoadTask);
        mainHandler.postDelayed(slowLoadTask, SLOW_LOAD_MS);
    }

    void hideCover() {
        mainHandler.removeCallbacks(hideCoverTask);
        mainHandler.removeCallbacks(slowLoadTask);
        if (cover.getVisibility() != View.VISIBLE) return;
        cover.animate().alpha(0f).setDuration(180)
            .withEndAction(() -> cover.setVisibility(View.GONE)).start();
    }

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

        // The page calls this when its real UI is on screen: the loading cover goes away right then.
        // If the page never calls it, the cover is removed shortly after the page finishes loading.
        @JavascriptInterface
        public void splashDone() {
            runOnUiThread(() -> { if (onOurPage()) hideCover(); });
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        // Must run before super.onCreate. White background + ELAD icon, shown instantly by the system.
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setKeepOnScreenCondition(() -> !ready);
        splash.setOnExitAnimationListener(provider -> provider.remove());

        super.onCreate(b);
        applySystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        root.addView(web, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Loading cover: same white + same icon as the system splash, so there is never a bare white page.
        cover = new FrameLayout(this);
        cover.setBackgroundColor(Color.WHITE);
        cover.setClickable(true); // swallow taps while it is showing
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.elad_splash_icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int px = (int) (ICON_DP * getResources().getDisplayMetrics().density);
        px = Math.min(px, getResources().getDisplayMetrics().widthPixels);
        cover.addView(icon, new FrameLayout.LayoutParams(px, px, Gravity.CENTER));
        root.addView(cover, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

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
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                mainHandler.removeCallbacks(hideCoverTask);
                if (url != null && url.equals(errorUrl)) errorUrl = null;
                boolean isFile = url != null && url.startsWith("file:");
                // Leaving offline.html to try the real site again: cover the screen while it loads.
                if (!isFile && onOfflinePage) {
                    onOfflinePage = false;
                    showCover();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                CookieManager.getInstance().flush();
                String u = url == null ? "" : url;

                // This is the browser's own error page for a failed load, not real content.
                // Ignore it (and do NOT reset the retry counter).
                if (errorUrl != null && errorUrl.equals(u)) { errorUrl = null; return; }

                if (u.startsWith("file:")) {
                    // offline.html is on screen: show it.
                    onOfflinePage = true;
                    mainHandler.removeCallbacks(hideCoverTask);
                    mainHandler.postDelayed(hideCoverTask, 150);
                    return;
                }

                // A real page finished loading. Reset the retry count and let it paint
                // before removing the cover (or the page can call EladApp.splashDone()).
                offlineRetries = 0;
                mainHandler.removeCallbacks(hideCoverTask);
                mainHandler.postDelayed(hideCoverTask, PAGE_SETTLE_MS);
            }

            // No network (or a very weak one): keep the icon cover up, retry HOME so the Service Worker
            // can serve its cache, and only then fall back to the bundled offline.html.
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                super.onReceivedError(v, r, e);
                if (!r.isForMainFrame()) return;
                String failed = r.getUrl().toString();
                mainHandler.removeCallbacks(hideCoverTask);

                if (failed.startsWith("file:")) { hideCover(); return; }

                errorUrl = failed;
                showCover(); // hide the browser's error page behind the icon cover

                if (offlineRetries < 2) {
                    offlineRetries++;
                    mainHandler.postDelayed(() -> v.loadUrl(HOME), 1000);
                    return;
                }
                offlineRetries = 0;
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

        // Cover is already in place (same look as the system splash), so the system splash can leave now.
        // The cover itself stays until real content is displayed.
        showCover();
        ready = true;

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
