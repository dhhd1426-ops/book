package app.book.shelf;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

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
            return send(reqJson)[0];
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
                String[] r = send(reqJson);
                reply = "{\"text\":" + JSONObject.quote(r[0]) + ",\"url\":" + JSONObject.quote(r[1]) + "}";
            } catch (Throwable e) {   // always answer, or the page would only see a timeout
                reply = "{\"err\":" + JSONObject.quote(why(e)) + "}";
            }
            final String js = "window.__shelfFetched && window.__shelfFetched(" + JSONObject.quote(id) + "," + reply + ")";
            ui.post(() -> main.evaluateJavascript(js, null));
        }, "shelf-request").start();
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
                if (!hasUa) c.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(activity));
                String cookie = CookieManager.getInstance().getCookie(url);
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
                c.disconnect();
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
