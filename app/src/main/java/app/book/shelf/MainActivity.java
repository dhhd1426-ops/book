package app.book.shelf;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** A thin shell around the shelf prototype in assets/index.html. */
public class MainActivity extends Activity {

    private static final int PICK_FILE = 42;
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private Parsers parsers;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // phones stay upright; tablets (600dp and wider) rotate freely
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        paintSystemBars();

        web = new WebView(this);
        web.setBackgroundColor(isNight() ? Color.parseColor("#111216") : Color.parseColor("#f2f1ec"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // remembers theme and animation-speed choices
        s.setAllowFileAccess(true);
        // the page is loaded from file://; these let its search reach online OPDS catalogues
        // (Project Gutenberg, Standard Ebooks, a home Calibre server) that do not send CORS headers
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(100);                    // keep the designed type scale
        s.setMediaPlaybackRequiresUserGesture(true);

        // Futon's manga sources (see Parsers.kt); their page images are served through shouldInterceptRequest
        parsers = new Parsers(this, web);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = parsers.intercept(request);
                return r != null ? r : super.shouldInterceptRequest(view, request);
            }
        });
        web.addJavascriptInterface(parsers, "ShelfParsers");
        // java.ajax, AES / MD5 and hidden-WebView pages for book-source rules (see ShelfNative)
        web.addJavascriptInterface(new ShelfNative(this, web), "ShelfNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pending != null) pending.onReceiveValue(null);
                pending = callback;
                try {
                    Intent i = params.createIntent();
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, PICK_FILE);
                } catch (Exception e) {
                    pending = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK_FILE && pending != null) {
            pending.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            pending = null;
            return;
        }
        super.onActivityResult(request, result, data);
    }

    /** Back closes an open book, then returns to the shelf, then leaves the app. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back ? window.__back() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        paintSystemBars();
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void paintSystemBars() {
        Window w = getWindow();
        boolean night = isNight();
        int bar = night ? Color.parseColor("#111216") : Color.parseColor("#f2f1ec");
        w.setStatusBarColor(bar);
        w.setNavigationBarColor(bar);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= 23) {
            flags = night ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            flags = night ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(flags);
    }
}
