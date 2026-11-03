package com.copy.account.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonlLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `append read rotate clear - full lifecycle`() {
        val log = JsonlLog(File(tmp.root, "logs/t.jsonl"), maxBytes = 100L, keepLines = 5)

        assertTrue(log.readAll().isEmpty())

        log.append("a"); log.append("b"); log.append("c")
        assertEquals(listOf("c", "b", "a"), log.readAll())

        repeat(20) { log.append("line-${it.toString().padStart(2, '0')}") }
        val all = log.readAll()
        assertTrue("应轮转丢弃旧行：${all.size}", all.size < 20)
        assertTrue("行数应有界：${all.size}", all.size <= 15)
        assertEquals("line-19", all.first())
        assertTrue("文件应有界", File(tmp.root, "logs/t.jsonl").length() < 400L)

        log.clear()
        assertTrue(log.readAll().isEmpty())
    }
}
