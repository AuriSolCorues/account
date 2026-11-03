package com.copy.account.sync.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.sink
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal class WebDavException(val status: Int, message: String) : Exception(message)

internal data class RemoteEntry(val name: String, val size: Long, val mtimeSec: Long, val isDir: Boolean = false)

internal class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    allowHttp: Boolean = false
) {
    private val base = baseUrl.trimEnd('/')
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun listDir(path: String): List<RemoteEntry> {
        val body = request("PROPFIND", path, propfindBody(), depth = "1")
        val selfSegment = path.trim().trim('/').substringAfterLast('/')
        return parsePropfind(body, selfSegment)
    }

    fun put(path: String, body: ByteArray) {
        request("PUT", path, body.toRequestBody("application/octet-stream".toMediaType()))
    }

    fun get(path: String, out: OutputStream) {
        val request = Request.Builder()
            .url(urlFor(path))
            .header("Authorization", basicAuth())
            .get()
            .build()
        val response = execute(request)
        response.use { resp ->
            if (resp.code !in 200..299) throw WebDavException(resp.code, "GET 失败（HTTP ${resp.code}）")
            val source = resp.body?.source() ?: throw WebDavException(resp.code, "GET 响应体为空")
            out.use { output -> source.readAll(output.sink()) }
        }
    }

    fun mkcol(path: String) {
        request("MKCOL", path, ByteArray(0).toRequestBody(null), tolerate = intArrayOf(405, 301))
    }

    fun delete(path: String) {
        request("DELETE", path, ByteArray(0).toRequestBody(null), tolerate = intArrayOf(404))
    }

    fun testConnection() {
        try {
            request("PROPFIND", "", propfindBody(), depth = "0", tolerate = intArrayOf(404))
        } catch (e: WebDavException) {
            if (e.status != 404) throw e
        }
    }

    private fun propfindBody(): RequestBody =
        ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
                "<D:displayname/><D:getcontentlength/><D:getlastmodified/><D:resourcetype/>" +
                "</D:prop></D:propfind>")
            .toRequestBody("application/xml".toMediaType())

    private fun request(
        method: String,
        path: String,
        body: RequestBody? = null,
        depth: String? = null,
        tolerate: IntArray = intArrayOf()
    ): ByteArray {
        val builder = Request.Builder()
            .url(urlFor(path))
            .header("Authorization", basicAuth())
            .method(method, body)
        if (depth != null) builder.header("Depth", depth)
        val response = execute(builder.build())
        return response.use { resp ->
            val status = resp.code
            if (status in 200..299 || status in tolerate) {
                resp.body?.bytes() ?: ByteArray(0)
            } else {
                throw WebDavException(status, "$method 失败（HTTP $status）")
            }
        }
    }

    private fun execute(request: Request): okhttp3.Response = try {
        http.newCall(request).execute()
    } catch (e: IOException) {
        throw WebDavException(0, e.message ?: "网络错误")
    }

    private fun basicAuth(): String =
        "Basic " + Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8)
        )

    private fun urlFor(path: String): String {
        val encoded = path.trim().trimStart('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        return "$base/$encoded"
    }

    internal fun parsePropfind(xml: ByteArray, selfPath: String = ""): List<RemoteEntry> {
        val text = String(xml, Charsets.UTF_8)
        val entries = mutableListOf<RemoteEntry>()
        val responseRegex = Regex("<(?:[Dd]:)?response\\b.*?</(?:[Dd]:)?response>", RegexOption.DOT_MATCHES_ALL)
        for (block in responseRegex.findAll(text)) {
            val seg = block.value
            val href = extractTag(seg, "href") ?: continue
            val size = extractTag(seg, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
            val mtime = extractTag(seg, "getlastmodified") ?: ""
            val isCollection = seg.contains("<D:collection") || seg.contains("<d:collection")
            entryFromHref(href, size, mtime, selfPath, isCollection)?.let { entries.add(it) }
        }
        return entries
    }

    private fun extractTag(segment: String, local: String): String? =
        Regex("<(?:[Dd]:)?$local\\b[^>]*>(.*?)</(?:[Dd]:)?$local\\s*>", RegexOption.DOT_MATCHES_ALL)
            .find(segment)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun entryFromHref(
        href: String,
        size: Long,
        mtime: String,
        selfSegment: String = "",
        isCollection: Boolean = false
    ): RemoteEntry? {
        val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        val isDir = isCollection || decoded.endsWith("/")
        val name = decoded.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return null
        if (selfSegment.isNotEmpty() && name == selfSegment) return null
        return RemoteEntry(name, size, parseHttpDate(mtime), isDir)
    }

    internal fun parseHttpDate(raw: String): Long {
        if (raw.isBlank()) return 0
        val rfc1123 = runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(raw.trim())?.time?.div(1000)
        }.getOrNull()
        if (rfc1123 != null) return rfc1123
        runCatching { return java.time.Instant.parse(raw.trim()).epochSecond }
        return runCatching { java.time.OffsetDateTime.parse(raw.trim()).toInstant().epochSecond }
            .getOrDefault(0L)
    }
}
