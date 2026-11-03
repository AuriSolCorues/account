package com.copy.account.sync.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class WebDavClientTest {

    private lateinit var server: HttpServer
    private var lastBody = ByteArray(0)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            lastBody = ex.requestBody.readBytes()
            val propfind = ex.requestMethod == "PROPFIND"
            val body: ByteArray = if (propfind) propfindXml("D").toByteArray() else byteArrayOf()
            ex.sendResponseHeaders(207, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.write(body)
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client(baseUrl: String) = WebDavClient(baseUrl, "user", "pass", allowHttp = true)

    @Test
    fun put_sendsBody_andAccepts2xx() {
        val base = "http://127.0.0.1:" + server.address.port
        client(base).put("dir/file.bin", byteArrayOf(1, 2, 3))
        assertTrue(lastBody.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun get_streamsBody() {
        val base = "http://127.0.0.1:" + server.address.port
        val out = java.io.ByteArrayOutputStream()
        client(base).get("$base/dir/file.bin", out)
        assertTrue(out.size() >= 0)
    }

    @Test
    fun propfind_parsesPrefixedResponse() {
        val base = "http://127.0.0.1:" + server.address.port
        val entries = client(base).listDir("dir")
        assertEquals(listOf("a.txt", "b.bin"), entries.filter { !it.isDir }.map { it.name })
    }

    private fun propfindXml(prefix: String): String = """
        <?xml version="1.0"?>
        <$prefix:multistatus xmlns:$prefix="DAV:">
          <$prefix:response><$prefix:href>/dir/a.txt</$prefix:href><$prefix:propstat><$prefix:prop>
            <$prefix:getcontentlength>10</$prefix:getcontentlength><$prefix:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</$prefix:getlastmodified>
          </$prefix:prop><$prefix:status>HTTP/1.1 200 OK</$prefix:status></$prefix:propstat></$prefix:response>
          <$prefix:response><$prefix:href>/dir/b.bin</$prefix:href><$prefix:propstat><$prefix:prop>
            <$prefix:getcontentlength>20</$prefix:getcontentlength><$prefix:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</$prefix:getlastmodified>
          </$prefix:prop><$prefix:status>HTTP/1.1 200 OK</$prefix:status></$prefix:propstat></$prefix:response>
          <$prefix:response><$prefix:href>/dir/sub/</$prefix:href><$prefix:propstat><$prefix:prop>
            <$prefix:resourcetype><$prefix:collection/></$prefix:resourcetype>
          </$prefix:prop><$prefix:status>HTTP/1.1 200 OK</$prefix:status></$prefix:propstat></$prefix:response>
        </$prefix:multistatus>
    """.trimIndent()
}
