package app.book.shelf;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * What Legado book-source rules need and a page script cannot do on its own,
 * exposed to index.html as window.ShelfNative:
 *  - ajax:   a blocking HTTP request for java.ajax() (with cookies, User-Agent, Referer)
 *  - crypto: AES / MD5 for sources that decrypt chapter text
 *  - render: load a page in a hidden WebView and return its HTML once its scripts ran
 *            (chapters marked {"webView":true})
 *  - verification: when a site answers with a Cloudflare challenge, the page is first loaded in a hidden WebView
 *            (as Mihon does) until the cf_clearance cookie appears, then the request is sent again; if that fails
 *            the page offers verify(), a visible browser the user solves the check in (as Legado and Futon do).
 *            browserAwait() is the same browser for rules that call java.startBrowserAwait().
 * Errors come back as "\u0000ERR:" + message.
 */
final class ShelfNative {
    private static final String ERR = "\u0000ERR:";
    private final Activity activity;
    private final WebView main;
    private final Handler ui = new Handler(Looper.getMainLooper());

    ShelfNative(Activity activity, WebView main) {
        this.activity = activity;
        this.main = main;
    }

    // ---------- ajax ----------

    /** Blocking request, for java.ajax() inside rule scripts. */
    @JavascriptInterface
    public String ajax(String reqJson) {
        try {
            return sendSolving(reqJson)[0];
        } catch (Exception e) {
            return ERR + why(e);
        }
    }

    /**
     * The same request off the UI thread, for every book-source page (search, detail, toc, chapter).
     * Answers window.__shelfFetched(id, {text, url}) or (id, {err}).
     */
    @JavascriptInterface
    public void request(final String id, final String reqJson) {
        new Thread(() -> {
            String reply;
            try {
                String[] r = sendSolving(reqJson);
                reply = "{\"text\":" + JSONObject.quote(r[0]) + ",\"url\":" + JSONObject.quote(r[1]) + "}";
            } catch (Challenge e) {
                reply = "{\"err\":\"需要网页验证\",\"verify\":" + JSONObject.quote(e.url) + "}";
            } catch (Throwable e) {   // always answer, or the page would only see a timeout
                reply = "{\"err\":" + JSONObject.quote(why(e)) + "}";
            }
            final String js = "window.__shelfFetched && window.__shelfFetched(" + JSONObject.quote(id) + "," + reply + ")";
            ui.post(() -> main.evaluateJavascript(js, null));
        }, "shelf-request").start();
    }

    // ---------- site verification (Cloudflare and the like) ----------

    /** the site answered with a browser check instead of the page */
    private static final class Challenge extends java.io.IOException {
        final String url;
        Challenge(String url) { super("需要网页验证"); this.url = url; }
    }

    private final ConcurrentHashMap<String, Object> hostLocks = new ConcurrentHashMap<>();

    /** send; on a challenge solve it once per site in a hidden WebView, then send again */
    private String[] sendSolving(String reqJson) throws Exception {
        try {
            return send(reqJson);
        } catch (Challenge first) {
            String host = new URL(first.url).getHost();
            Object lock = hostLocks.get(host);
            if (lock == null) { hostLocks.putIfAbsent(host, new Object()); lock = hostLocks.get(host); }
            synchronized (lock) {
                try {
                    return send(reqJson);          // another request may have solved it while this one waited
                } catch (Challenge again) {
                    if (!solveHidden(again.url)) throw again;
                    return send(reqJson);
                }
            }
        }
    }

    private static String clearance(String url) {
        String all = CookieManager.getInstance().getCookie(url);
        if (all == null) return null;
        for (String part : all.split(";")) {
            String c = part.trim();
            if (c.startsWith("cf_clearance=")) return c.substring(13);
        }
        return null;
    }

    private static boolean isChallenge(int code, HttpURLConnection c, byte[] body) {
        if (code != 403 && code != 503) return false;
        String mitigated = c.getHeaderField("cf-mitigated");
        if (mitigated != null && mitigated.toLowerCase().contains("challenge")) return true;
        String server = c.getHeaderField("Server");
        if (server == null || !server.toLowerCase().contains("cloudflare")) return false;
        String head = new String(body, 0, Math.min(body.length, 20000), StandardCharsets.ISO_8859_1);
        return head.contains("Just a moment") || head.contains("_cf_chl_opt") || head.contains("challenge-platform");
    }

    /** load the page in a WebView nobody sees and wait for a new cf_clearance cookie (non-interactive checks pass on their own) */
    private boolean solveHidden(final String url) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        final WebView[] holder = {null};
        final String before = clearance(url);
        ui.post(() -> {
            final WebView w = new WebView(activity);
            holder[0] = w;
            WebSettings st = w.getSettings();
            st.setJavaScriptEnabled(true);
            st.setDomStorageEnabled(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
            w.setWebViewClient(new WebViewClient());
            w.loadUrl(url, new HashMap<String, String>());
            ui.postDelayed(new Runnable() {
                int n = 0;
                @Override
                public void run() {
                    String now = clearance(url);
                    if (now != null && !now.equals(before)) { ok[0] = true; latch.countDown(); return; }
                    if (++n >= 20) { latch.countDown(); return; }
                    ui.postDelayed(this, 1000);
                }
            }, 1500);
        });
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        ui.post(() -> { if (holder[0] != null) { holder[0].stopLoading(); holder[0].destroy(); } });
        CookieManager.getInstance().flush();
        return ok[0];
    }

    private interface BrowserDone { void done(String html); }

    /** a visible browser over the app: closes itself once a Cloudflare check has passed, or when the user taps 完成 */
    private void showBrowser(final String url, final String title, final BrowserDone done) {
        final Dialog d = new Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(40, 12, 24, 12);
        TextView label = new TextView(activity);
        label.setText(title + " · 完成验证后会自动返回，也可以点「完成」");
        label.setTextSize(14);
        Button close = new Button(activity);
        close.setText("完成");
        bar.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(close, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final WebView w = new WebView(activity);
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(w, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        final boolean[] finished = {false};
        final boolean[] sawCheck = {false};
        final Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            w.evaluateJavascript("document.documentElement.outerHTML", value -> {
                String html = "";
                try {
                    Object o = new JSONTokener(value == null ? "\"\"" : value).nextValue();
                    if (o instanceof String) html = (String) o;
                } catch (Exception ignored) { }
                CookieManager.getInstance().flush();
                w.stopLoading();
                w.destroy();
                if (d.isShowing()) d.dismiss();
                done.done(html);
            });
        };
        close.setOnClickListener(v -> finish.run());
        d.setOnCancelListener(dialog -> finish.run());      // the back key
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                // Legado's test: the check page defines window._cf_chl_opt; the real page after it does not
                view.evaluateJavascript("!!window._cf_chl_opt", r -> {
                    if ("true".equals(r)) sawCheck[0] = true;
                    else if (sawCheck[0]) ui.postDelayed(finish, 800);
                });
            }
        });
        w.loadUrl(url, new HashMap<String, String>());
        d.show();
    }

    /** the 网页验证 button: answers window.__shelfVerified(id) when the browser closes */
    @JavascriptInterface
    public void verify(final String id, final String url) {
        ui.post(() -> showBrowser(url, "网页验证", html ->
                main.evaluateJavascript("window.__shelfVerified && window.__shelfVerified(" + JSONObject.quote(id) + ")", null)));
    }

    /** java.startBrowserAwait(url, title) in rules: blocks the rule until the browser closes, returns the page's html */
    @JavascriptInterface
    public String browserAwait(final String url, final String title) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {""};
        ui.post(() -> showBrowser(url, title == null || title.isEmpty() ? "网页验证" : title, html -> {
            out[0] = html;
            latch.countDown();
        }));
        try { latch.await(5, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }
        return out[0];
    }

    private static String why(Throwable e) {
        if (e instanceof java.net.UnknownHostException) return "找不到服务器（" + e.getMessage() + "）";
        if (e instanceof java.net.SocketTimeoutException) {
            String m = String.valueOf(e.getMessage()).toLowerCase();
            return m.contains("connect") ? "连接超时：连不上服务器（可能被服务器屏蔽，或网络 / VPN 不通）"
                    : "服务器响应超时：已经连上，但服务器一直没有返回";
        }
        if (e instanceof javax.net.ssl.SSLException) return "安全连接失败：" + e.getMessage();
        if (e instanceof java.net.ConnectException) return "连不上服务器";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** returns {body, final url}; throws with "HTTP nnn" for error statuses */
    private String[] send(String reqJson) throws Exception {
        {
            JSONObject req = new JSONObject(reqJson);
            String url = req.optString("url");
            String method = req.optString("method", "GET");
            String body = req.has("body") && !req.isNull("body") ? req.optString("body") : null;
            String charset = req.optString("charset", "");
            JSONObject headers = req.optJSONObject("headers");
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(12000);
                c.setReadTimeout(25000);
                c.setRequestMethod(method);
                boolean hasUa = false, hasType = false;
                if (headers != null) {
                    Iterator<String> it = headers.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        c.setRequestProperty(k, headers.optString(k));
                        if ("user-agent".equalsIgnoreCase(k)) hasUa = true;
                        if ("content-type".equalsIgnoreCase(k)) hasType = true;
                    }
                }
                String cookie = CookieManager.getInstance().getCookie(url);
                // Cloudflare ties cf_clearance to the browser that earned it: use the WebView's User-Agent with it
                boolean cleared = cookie != null && cookie.contains("cf_clearance=");
                if (!hasUa || cleared) c.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(activity));
                if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
                if (body != null) {
                    c.setDoOutput(true);
                    if (!hasType) {
                        String t = body.trim();
                        c.setRequestProperty("Content-Type", t.startsWith("{") || t.startsWith("[")
                                ? "application/json" : "application/x-www-form-urlencoded");
                    }
                    byte[] out = body.getBytes(charset.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(charset));
                    try (OutputStream os = c.getOutputStream()) { os.write(out); }
                }
                int code = c.getResponseCode();
                keepCookies(url, c.getHeaderFields());
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    if (code != 307 && code != 308) { method = "GET"; body = null; }
                    c.disconnect();
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                byte[] bytes = in == null ? new byte[0] : readAll(in);
                String cs = charset.isEmpty() ? charsetOf(c.getContentType()) : charset;
                boolean challenge = isChallenge(code, c, bytes);
                c.disconnect();
                if (challenge) throw new Challenge("GET".equals(method) ? url : new URL(new URL(url), "/").toString());
                if (code >= 400) throw new java.io.IOException("HTTP " + code);
                if (cs.isEmpty()) cs = metaCharset(bytes);
                Charset dec;
                try { dec = cs.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(cs); } catch (Exception bad) { dec = StandardCharsets.UTF_8; }
                return new String[]{new String(bytes, dec), url};
            }
            throw new java.io.IOException("跳转次数太多");
        }
    }

    /** <meta charset="gbk"> in the first few KB of an HTML page */
    private static String metaCharset(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1).toLowerCase();
        int i = head.indexOf("charset=");
        if (i < 0 || head.indexOf("<meta") < 0) return "";
        String cs = head.substring(i + 8).replace("\"", "").replace("'", "").trim();
        int end = 0;
        while (end < cs.length() && (Character.isLetterOrDigit(cs.charAt(end)) || cs.charAt(end) == '-' || cs.charAt(end) == '_')) end++;
        return cs.substring(0, end);
    }

    private static void keepCookies(String url, Map<String, List<String>> fields) {
        if (fields == null) return;
        for (Map.Entry<String, List<String>> e : fields.entrySet()) {
            if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String v : e.getValue()) CookieManager.getInstance().setCookie(url, v);
        }
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) return "";
        int i = contentType.toLowerCase().indexOf("charset=");
        if (i < 0) return "";
        String cs = contentType.substring(i + 8).trim();
        int semi = cs.indexOf(';');
        if (semi >= 0) cs = cs.substring(0, semi);
        return cs.replace("\"", "").trim();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        try (InputStream s = in) {
            while ((n = s.read(chunk)) > 0) buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    // ---------- crypto ----------

    @JavascriptInterface
    public String crypto(String op, String data, String key, String transformation, String iv) {
        try {
            switch (op) {
                case "md5": {
                    byte[] d = MessageDigest.getInstance("MD5").digest(data.getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (byte b : d) sb.append(String.format("%02x", b));
                    return sb.toString();
                }
                case "aesBase64Decode":
                case "aesDecode": {
                    byte[] in = Base64.decode(data.trim(), Base64.DEFAULT);
                    return new String(aes(Cipher.DECRYPT_MODE, in, key, transformation, iv), StandardCharsets.UTF_8);
                }
                case "aesEncodeBase64": {
                    byte[] out = aes(Cipher.ENCRYPT_MODE, data.getBytes(StandardCharsets.UTF_8), key, transformation, iv);
                    return Base64.encodeToString(out, Base64.NO_WRAP);
                }
                default:
                    return ERR + "不支持 " + op;
            }
        } catch (Exception e) {
            return ERR + "解密失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static byte[] aes(int mode, byte[] in, String key, String transformation, String iv) throws Exception {
        String tr = transformation == null || transformation.isEmpty() ? "AES/ECB/PKCS5Padding" : transformation;
        Cipher cipher = Cipher.getInstance(tr);
        SecretKeySpec k = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        if (tr.toUpperCase().contains("ECB") || iv == null || iv.isEmpty()) cipher.init(mode, k);
        else cipher.init(mode, k, new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        return cipher.doFinal(in);
    }

    // ---------- hidden WebView ----------

    @JavascriptInterface
    public void render(final String id, final String url, final String headersJson, final String js) {
        ui.post(() -> renderOnUi(id, url, headersJson, js));
    }

    private void renderOnUi(final String id, String url, String headersJson, final String js) {
        final WebView w = new WebView(activity);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBlockNetworkImage(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        Map<String, String> headers = new HashMap<>();
        try {
            JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
            Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("user-agent".equalsIgnoreCase(k)) s.setUserAgentString(h.optString(k));
                else headers.put(k, h.optString(k));
            }
        } catch (Exception ignored) { }
        final boolean[] done = {false};
        final Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            String script = js == null || js.trim().isEmpty() ? "document.documentElement.outerHTML" : js;
            w.evaluateJavascript(script, value -> {
                deliver(id, value == null ? "null" : value);
                w.stopLoading();
                w.destroy();
            });
        };
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                ui.postDelayed(finish, 1500);   // give the page's own scripts time to fill in the text
            }
        });
        ui.postDelayed(finish, 15000);        // never wait longer than this
        w.loadUrl(url, headers);
    }

    private void deliver(String id, String jsValue) {
        main.evaluateJavascript("window.__shelfRendered && window.__shelfRendered(" + JSONObject.quote(id) + "," + jsValue + ")", null);
    }
}
