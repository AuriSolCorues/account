/**
 * 职责：Phase 2 数据层单测——uid 生成唯一性、createdMillis/modifiedMillis 三层解析、
 *       排序稳定性（创建序倒序，新的在前）。
 * 测试策略：纯 JVM（Models.kt 全部为纯函数），不依赖 Android/Robolectric。
 * （2026-10-17：自定义拖动排序档与 sortKey 已全链拆除，相关两档排序/中值重算用例随之移除。）
 */
package com.copy.account.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSortTest {

    // created 直接编码进 id（account-<ms>），Models 的 createdMillis 从 id 解析——与生产路径一致。
    private fun account(id: String, uid: String = "", created: Long = 0L) = Account(
        id = if (created > 0L) "account-$created" else id,
        name = "n-$id",
        username = "u",
        password = "p",
        uid = uid
    )

    // ==================== uid 生成 ====================

    @Test
    fun newUid_format_and_uniqueness() {
        val now = 1_760_000_000_000L
        val a = newUid(now)
        val b = newUid(now) // 同一毫秒连续生成
        assertTrue(a.matches(Regex("a-$now-[2-9A-HJ-NP-Z]{6}")))
        assertTrue(a != b) // 随机后缀防同毫秒撞号
        // 批量生成唯一性（同毫秒 1000 个，6 位后缀碰撞期望 <0.1）：容忍至多 1 个碰撞
        val set = (1..1000).map { newUid(now) }.toSet()
        assertTrue("1000 个 uid 至多 1 个碰撞，实际 ${1000 - set.size}", set.size >= 999)
    }

    @Test
    fun withUidFilled_fills_blank_only() {
        val old = account("account-123", uid = "")
        val filled = old.withUidFilled()
        assertTrue(filled.uid.isNotBlank())
        // 已有 uid 不动
        val kept = filled.withUidFilled()
        assertEquals(filled.uid, kept.uid)
    }

    // ==================== createdMillis / modifiedMillis ====================

    @Test
    fun createdMillis_embedded_from_id() {
        // id 内嵌毫秒 = 创建时间锚（account-<ms>）
        val now = 1_760_000_000_000L
        val a = account("x", created = now)
        assertEquals(now, a.createdMillis)
    }

    @Test
    fun createdMillis_zero_for_demo_ids() {
        // 无内嵌毫秒（demo-login 等）→ 0，排序垫底
        val a = account("demo-login")
        assertEquals(0L, a.createdMillis)
    }

    @Test
    fun modifiedMillis_prefers_uid_over_id() {
        val a = account("x", uid = newUid(2_000L), created = 1_000L)
        assertEquals(2_000L, a.modifiedMillis)
        // uid 空（旧数据未再保存过）退化 = 创建时间
        val b = account("y", uid = "", created = 1_000L)
        assertEquals(1_000L, b.modifiedMillis)
    }

    // ==================== 排序（创建序倒序） ====================

    @Test
    fun sortAccounts_newest_first_demo_last() {
        val list = listOf(
            account("old", created = 1_000L),
            account("new", created = 3_000L),
            account("mid", created = 2_000L),
            account("demo", created = 0L) // createdMillis=0 垫底
        )
        // 断言用 name（工厂把 id 编码成 account-<ms>，name 保留原始标记）
        val sorted = sortAccounts(list).map { it.name }
        assertEquals(listOf("n-new", "n-mid", "n-old", "n-demo"), sorted)
    }

    @Test
    fun sortAccounts_stable_for_same_timestamp() {
        // 同毫秒账号保持输入相对顺序（sortedWith 稳定排序）
        val list = listOf(account("a", created = 5_000L), account("b", created = 5_000L))
        assertEquals(listOf("n-a", "n-b"), sortAccounts(list).map { it.name })
    }
}
