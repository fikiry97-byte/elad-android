package com.elad.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    // Web client ID (Firebase console > Authentication > Sign-in method > Google > Web SDK configuration)
    static final String WEB_CLIENT_ID = "898464952387-p5nlra4lk8jcm61e9ot6n7tq6okf9j96.apps.googleusercontent.com";

    WebView web;
    View splashOverlay;
    boolean splashGone = false;
    ValueCallback<Uri[]> chooser;
    final Executor mainExec = new Handler(Looper.getMainLooper())::post;
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    void openOutside(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) {}
    }

    boolean stayInApp(Uri u) {
        String h = u.getHost() == null ? "" : u.getHost();
        String p = u.getPath() == null ? "" : u.getPath();
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

    // Blue status bar with light icons while the splash is showing
    void statusBarForSplash() {
        getWindow().setStatusBarColor(Color.parseColor("#1046E6"));
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(d.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    // White status bar with dark icons for the normal app
    void statusBarForApp() {
        getWindow().setStatusBarColor(Color.WHITE);
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(d.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    // Fade the native splash out once the real page is on screen (or after a safety timeout).
    void hideSplashOverlay() {
        if (splashGone || splashOverlay == null) return;
        splashGone = true;
        statusBarForApp();
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(280)
            .withEndAction(() -> {
                if (splashOverlay.getParent() != null) {
                    ((ViewGroup) splashOverlay.getParent()).removeView(splashOverlay);
                }
            })
            .start();
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
    }

    @Override
    protected void onCreate(Bundle b) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(b);
        statusBarForSplash();

        // Root container: WebView underneath, gradient + logo overlay on top.
        FrameLayout root = new FrameLayout(this);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splashOverlay = buildSplashOverlay();
        root.addView(splashOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " EladApp/1.0");
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "EladApp");
        web.setBackgroundColor(Color.parseColor("#1046E6"));

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (stayInApp(u)) return false;
                openOutside(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if (url != null && HOST.equals(Uri.parse(url).getHost())) {
                    // Small delay so the page's own splash has painted before we fade ours out
                    mainHandler.postDelayed(MainActivity.this::hideSplashOverlay, 150);
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                super.onReceivedError(v, r, e);
                if (r.isForMainFrame()) hideSplashOverlay();
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

        // Safety net: never leave the overlay stuck on screen (very slow or broken connection).
        mainHandler.postDelayed(this::hideSplashOverlay, 8000);

        if (b == null) web.loadUrl(HOME); else web.restoreState(b);
    }

    // Same look as the Worker's #eladSplash: blue gradient and the white wordmark,
    // width = 62% of the screen, at most 260dp, centered.
    View buildSplashOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundResource(R.drawable.splash_gradient);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.elad_logo_white);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);

        float density = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int w = Math.min((int) (screenW * 0.62f), (int) (260 * density));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        overlay.addView(logo, lp);

        return overlay;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooser = null;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
