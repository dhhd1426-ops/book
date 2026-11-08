package app.book.shelf

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The manga / comics sources of Futon (the kotatsu-parsers library, ~1400 sites), run the way Futon runs them,
 * and exposed to index.html as window.ShelfParsers:
 *
 *   sources()                 → JSON list of every source: id, title, locale, type, broken
 *   call(id, method, args)    → async; answers window.__ktDone(id, {ok: …} | {err: "…"})
 *       search  {source, q}   → books found (key, title, author, cover, intro, tags, state)
 *       details {key}         → the book again, with its chapters (key, name, volume)
 *       pages   {key}         → image addresses of one chapter
 *
 * Images (pages and covers) are requested by the page as https://shelf-img.local/?s=SOURCE&u=ADDRESS and served
 * from [intercept] with that source's own headers, cookies and interceptor (some sites descramble pages there).
 */
class Parsers(private val activity: Activity, private val main: WebView) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ui = Handler(Looper.getMainLooper())
    private val userAgent: String by lazy { WebSettings.getDefaultUserAgent(activity) }

    // ---------- network: one client, cookies shared with the WebViews, each source's headers + interceptor ----------

    private val cookies = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = CookieManager.getInstance()
            for (c in cookies) cm.setCookie(url.toString(), c.toString())
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
        }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain -> withSource(chain) })
        .build()

    /** what Futon's CommonHeadersInterceptor does: the source's headers, a Referer, then the source's interceptor */
    private fun withSource(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = request.tag(MangaSource::class.java) as? MangaParserSource
        val parser = source?.let { parser(it) }
        val headers = request.headers.newBuilder()
        parser?.getRequestHeaders()?.let { extra ->
            for ((name, value) in extra) if (headers[name] == null) headers[name] = value
        }
        if (headers["User-Agent"] == null) headers["User-Agent"] = userAgent
        if (headers["Referer"] == null && parser != null) {
            runCatching { headers["Referer"] = "https://${parser.domain}/" }
        }
        val next = request.newBuilder().headers(headers.build()).build()
        if (parser == null) return chain.proceed(next)
        val proxied = object : Interceptor.Chain by chain {
            override fun request(): Request = next
        }
        return try {
            parser.intercept(proxied)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    // ---------- what the library asks of its host ----------

    private val defaults = object : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }

    private val context = object : MangaLoaderContext() {
        override val httpClient: OkHttpClient get() = http
        override val cookieJar: CookieJar get() = cookies

        @Deprecated("Provide a base url")
        override suspend fun evaluateJs(script: String): String? = runJs("", script, 10_000L)

        override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = runJs(baseUrl, script, timeout)

        override fun getConfig(source: MangaSource): MangaSourceConfig = defaults
        override fun getDefaultUserAgent(): String = userAgent
        override fun encodeBase64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
        override fun decodeBase64(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)
        override fun getPreferredLocales(): List<Locale> = listOf(Locale.getDefault(), Locale.ENGLISH)

        override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
            val bytes = response.peekBody(Long.MAX_VALUE).bytes()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return response
            val src = decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val out = redraw(AndroidBitmap(src)) as AndroidBitmap
            val buf = ByteArrayOutputStream()
            out.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, buf)
            return response.newBuilder().body(buf.toByteArray().toResponseBody("image/jpeg".toMediaType())).build()
        }

        override fun createBitmap(width: Int, height: Int): Bitmap =
            AndroidBitmap(android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888))
    }

    private class AndroidBitmap(val bitmap: android.graphics.Bitmap) : Bitmap {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
            val from = (sourceBitmap as AndroidBitmap).bitmap
            Canvas(bitmap).drawBitmap(
                from,
                android.graphics.Rect(src.left, src.top, src.right, src.bottom),
                android.graphics.Rect(dst.left, dst.top, dst.right, dst.bottom),
                null,
            )
        }
    }

    /** a one-shot hidden WebView: load the page, then poll the script until it returns something (as Futon does) */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runJs(baseUrl: String, script: String, timeout: Long): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeout.coerceAtLeast(3_000L)) {
            suspendCancellableCoroutine<String?> { cont ->
                val w = WebView(activity)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.userAgentString = userAgent
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                var done = false
                fun close() {
                    runCatching { w.stopLoading() }
                    runCatching { w.destroy() }
                }
                fun finish(value: String?) {
                    if (done) return
                    done = true
                    close()
                    if (cont.isActive) cont.resume(value)
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (done) return
                        w.evaluateJavascript(script) { r ->
                            val v = r?.takeUnless { it == "null" }
                            if (!v.isNullOrBlank()) finish(v) else if (!done) ui.postDelayed(this, 1_000L)
                        }
                    }
                }
                cont.invokeOnCancellation { ui.post { if (!done) { done = true; close() } } }
                if (baseUrl.isEmpty()) {
                    w.evaluateJavascript(script) { r -> finish(r?.takeUnless { it == "null" }) }
                } else {
                    w.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (!done && url != "about:blank") ui.post(poll)
                        }
                    }
                    w.loadUrl(baseUrl)
                    ui.postDelayed(poll, 1_500L)
                }
            }
        }
    }

    // ---------- sources, search, details, pages ----------

    private val parsers = ConcurrentHashMap<MangaParserSource, MangaParser>()
    private fun parser(source: MangaParserSource): MangaParser = parsers.getOrPut(source) { context.newParserInstance(source) }

    private val books = ConcurrentHashMap<String, Manga>()
    private val chapters = ConcurrentHashMap<String, MangaChapter>()

    @JavascriptInterface
    fun sources(): String {
        val out = JSONArray()
        for (s in MangaParserSource.entries) {
            out.put(
                JSONObject()
                    .put("id", s.name)
                    .put("title", s.title)
                    .put("locale", s.locale)
                    .put("type", s.contentType.name)
                    .put("broken", s.isBroken),
            )
        }
        return out.toString()
    }

    @JavascriptInterface
    fun call(id: String, method: String, args: String) {
        scope.launch {
            val reply = try {
                JSONObject().put("ok", run(method, JSONObject(args)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                JSONObject().put("err", why(e))
            }
            val js = "window.__ktDone && window.__ktDone(" + JSONObject.quote(id) + "," + reply + ")"
            ui.post { main.evaluateJavascript(js, null) }
        }
    }

    private suspend fun run(method: String, a: JSONObject): Any = when (method) {
        "search" -> search(MangaParserSource.valueOf(a.getString("source")), a.getString("q"))
        "details" -> details(a.getString("key"))
        "pages" -> pages(a.getString("key"))
        else -> throw IllegalArgumentException("未知操作 $method")
    }

    private suspend fun search(source: MangaParserSource, q: String): JSONArray {
        val p = parser(source)
        if (!p.filterCapabilities.isSearchSupported) throw UnsupportedOperationException("这个源不支持按书名搜索")
        val orders = p.availableSortOrders
        val order = if (SortOrder.RELEVANCE in orders) SortOrder.RELEVANCE else orders.firstOrNull() ?: SortOrder.POPULARITY
        val found = p.getList(0, order, MangaListFilter(query = q))
        val out = JSONArray()
        for (m in found.take(30)) {
            val key = source.name + ":" + m.id
            books[key] = m
            out.put(bookJson(key, m))
        }
        return out
    }

    private fun bookJson(key: String, m: Manga): JSONObject = JSONObject()
        .put("key", key)
        .put("title", m.title)
        .put("author", m.authors.joinToString(", "))
        .put("cover", m.largeCoverUrl ?: m.coverUrl ?: "")
        .put("intro", m.description ?: "")
        .put("tags", JSONArray(m.tags.map { it.title }))
        .put("state", m.state?.name ?: "")
        .put("url", m.publicUrl)

    private suspend fun details(key: String): JSONObject {
        val found = books[key] ?: throw IllegalStateException("请重新搜索这本书")
        val source = found.source as MangaParserSource
        val m = parser(source).getDetails(found)
        books[key] = m
        val all = m.chapters.orEmpty()
        // several translations of one book come as "branches": keep the one with most chapters
        val branch = all.groupingBy { it.branch }.eachCount().maxByOrNull { it.value }?.key
        val list = JSONArray()
        for (c in all) {
            if (c.branch != branch) continue
            val ck = key + ":" + c.id
            chapters[ck] = c
            list.put(
                JSONObject()
                    .put("key", ck)
                    .put("name", c.title?.takeIf { it.isNotBlank() } ?: chapterName(c))
                    .put("volume", c.volume),
            )
        }
        return bookJson(key, m).put("chapters", list).put("type", source.contentType.name)
    }

    private fun chapterName(c: MangaChapter): String {
        val n = if (c.number % 1f == 0f) c.number.toInt().toString() else c.number.toString()
        return if (c.volume > 0) "Vol.${c.volume} Ch.$n" else "第 $n 话"
    }

    private suspend fun pages(ck: String): JSONArray {
        val c = chapters[ck] ?: throw IllegalStateException("请重新打开这本书")
        val p = parser(c.source as MangaParserSource)
        val gate = Semaphore(4)
        val urls = coroutineScope {
            p.getPages(c).map { page ->
                async { gate.withPermit { runCatching { p.getPageUrl(page) }.getOrDefault(page.url) } }
            }.awaitAll()
        }
        return JSONArray(urls)
    }

    private fun why(e: Throwable): String = when (e) {
        is UnknownHostException -> "找不到服务器"
        is SocketTimeoutException -> "超时"
        is javax.net.ssl.SSLException -> "安全连接失败"
        else -> (e.message ?: e.javaClass.simpleName).take(160)
    }

    // ---------- images with the source's headers ----------

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val u = request.url
        if (u.host != "shelf-img.local") return null
        val target = u.getQueryParameter("u") ?: return null
        val source = u.getQueryParameter("s")?.let { s -> MangaParserSource.entries.firstOrNull { it.name == s } }
        return try {
            val rb = Request.Builder().url(target).get()
            if (source != null) rb.tag(MangaSource::class.java, source)
            http.newCall(rb.build()).execute().use { r ->
                val bytes = r.body.bytes()
                val mime = r.body.contentType()?.let { it.type + "/" + it.subtype } ?: "image/jpeg"
                val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400")
                if (r.isSuccessful) {
                    WebResourceResponse(mime, null, 200, "OK", headers, ByteArrayInputStream(bytes))
                } else {
                    WebResourceResponse("text/plain", "utf-8", r.code.coerceIn(400, 599), "Error", headers, ByteArrayInputStream(ByteArray(0)))
                }
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
        }
    }
}
