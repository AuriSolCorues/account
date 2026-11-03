package com.copy.account.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSortTest {

    private fun account(id: String, uid: String = "", created: Long = 0L) = Account(
        id = if (created > 0L) "account-$created" else id,
        name = "n-$id",
        username = "u",
        password = "p",
        uid = uid
    )

    @Test
    fun newUid_format_and_uniqueness() {
        val now = 1_760_000_000_000L
        val a = newUid(now)
        val b = newUid(now)
        assertTrue(a.matches(Regex("a-$now-[2-9A-HJ-NP-Z]{6}")))
        assertTrue(a != b)
        val set = (1..1000).map { newUid(now) }.toSet()
        assertTrue("1000 个 uid 至多 1 个碰撞，实际 ${1000 - set.size}", set.size >= 999)
    }

    @Test
    fun withUidFilled_fills_blank_only() {
        val old = account("account-123", uid = "")
        val filled = old.withUidFilled()
        assertTrue(filled.uid.isNotBlank())
        val kept = filled.withUidFilled()
        assertEquals(filled.uid, kept.uid)
    }

    @Test
    fun createdMillis_embedded_from_id() {
        val now = 1_760_000_000_000L
        val a = account("x", created = now)
        assertEquals(now, a.createdMillis)
    }

    @Test
    fun createdMillis_zero_for_demo_ids() {
        val a = account("demo-login")
        assertEquals(0L, a.createdMillis)
    }

    @Test
    fun modifiedMillis_prefers_uid_over_id() {
        val a = account("x", uid = newUid(2_000L), created = 1_000L)
        assertEquals(2_000L, a.modifiedMillis)
        val b = account("y", uid = "", created = 1_000L)
        assertEquals(1_000L, b.modifiedMillis)
    }

    @Test
    fun sortAccounts_newest_first_demo_last() {
        val list = listOf(
            account("old", created = 1_000L),
            account("new", created = 3_000L),
            account("mid", created = 2_000L),
            account("demo", created = 0L)
        )
        val sorted = sortAccounts(list).map { it.name }
        assertEquals(listOf("n-new", "n-mid", "n-old", "n-demo"), sorted)
    }

    @Test
    fun sortAccounts_stable_for_same_timestamp() {
        val list = listOf(account("a", created = 5_000L), account("b", created = 5_000L))
        assertEquals(listOf("n-a", "n-b"), sortAccounts(list).map { it.name })
    }
}
