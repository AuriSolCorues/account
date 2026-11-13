package com.copy.account.sync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun re(name: String, mtimeSec: Long = 0, size: Long = 0) =
    RemoteEntry(name, size = size, mtimeSec = mtimeSec)

class SyncEngineTest {

    @Test
    fun planA_empty_sides_noWork() {
        val plan = planScope(emptySet(), emptyMap(), emptyMap(), ConflictRule.SameNameSkip, SyncMode.Upload)
        assertTrue(!plan.hasWork)
    }

    @Test
    fun bidi_localNew_upload_remoteNewer_download() {
        val plan = planBackupBidirectional(
            local = setOf("new.acc", "old.acc", "fresh.acc"),
            remote = mapOf(
                "new.acc" to re("new.acc", mtimeSec = 200),
                "mid.acc" to re("mid.acc", mtimeSec = 100),
                "old.acc" to re("old.acc", mtimeSec = 50)
            ),
            keepLatest = 2
        )
        assertEquals(listOf("fresh.acc"), plan.upload)
        assertEquals(listOf("mid.acc"), plan.download)
        assertEquals(listOf("old.acc"), plan.trim)
    }

    @Test
    fun bidi_neverTrimsJustUploaded_evenIfUploadFailedLater() {
        val plan = planBackupBidirectional(
            local = setOf("fresh.acc", "ancient.acc"),
            remote = mapOf("ancient.acc" to re("ancient.acc", mtimeSec = 1)),
            keepLatest = 1
        )
        assertEquals(listOf("fresh.acc"), plan.upload)
        assertTrue(plan.download.isEmpty())
        assertTrue(plan.trim.isEmpty())
    }

    @Test
    fun bidi_steadyState_noPullLoop() {
        val remote = mapOf(
            "a.acc" to re("a.acc", mtimeSec = 30),
            "b.acc" to re("b.acc", mtimeSec = 20),
            "c.acc" to re("c.acc", mtimeSec = 10)
        )
        val plan = planBackupBidirectional(local = setOf("a.acc", "b.acc"), remote = remote, keepLatest = 2)
        assertTrue(!plan.hasWork)
    }

    @Test
    fun bidi_localOnlyFiles_uploadNotTrim() {
        val plan = planBackupBidirectional(local = setOf("x.acc"), remote = emptyMap(), keepLatest = 3)
        assertEquals(listOf("x.acc"), plan.upload)
        assertTrue(plan.trim.isEmpty())
    }

    @Test
    fun planA_upload_transfersLocalOnly_andSkipsSameName() {
        val plan = planScope(
            local = setOf("1.acc", "2.acc"),
            remote = mapOf("2.acc" to re("2.acc")),
            localMtimeSec = emptyMap(),
            rule = ConflictRule.SameNameSkip,
            mode = SyncMode.Upload
        )
        assertEquals(listOf("1.acc"), plan.transfer)
        assertEquals(1, plan.skipped)
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun planA_mirrorToRemote_deletesRemoteExtras_onlyInMirror() {
        val plan = planScope(
            local = setOf("1.acc"),
            remote = mapOf("1.acc" to re("1.acc"), "stale.acc" to re("stale.acc")),
            localMtimeSec = emptyMap(),
            rule = ConflictRule.SameNameSkip,
            mode = SyncMode.MirrorToRemote
        )
        assertEquals(listOf("stale.acc"), plan.delete)
        assertTrue("1.acc" in plan.transfer)
    }

    @Test
    fun planB_upload_localNewer_overwrites_remoteOlder_untouched() {
        val plan = planScope(
            local = setOf("a", "b"),
            remote = mapOf("a" to re("a", mtimeSec = 100), "b" to re("b", mtimeSec = 200)),
            localMtimeSec = mapOf("a" to 300L, "b" to 100L),
            rule = ConflictRule.MtimeNewerWins,
            mode = SyncMode.Upload
        )
        assertEquals(listOf("a"), plan.transfer)
    }

    @Test
    fun planB_download_remoteNewer_pulled_localNewer_untouched() {
        val plan = planScope(
            local = setOf("a", "b"),
            remote = mapOf("a" to re("a", mtimeSec = 300), "b" to re("b", mtimeSec = 50)),
            localMtimeSec = mapOf("a" to 100L, "b" to 200L),
            rule = ConflictRule.MtimeNewerWins,
            mode = SyncMode.Download
        )
        assertEquals(listOf("a"), plan.transfer)
    }

    @Test
    fun planB_tieBySecond_skipped() {
        val plan = planScope(
            local = setOf("a"),
            remote = mapOf("a" to re("a", mtimeSec = 100)),
            localMtimeSec = mapOf("a" to 100L),
            rule = ConflictRule.MtimeNewerWins,
            mode = SyncMode.Upload
        )
        assertTrue(plan.transfer.isEmpty())
    }

    @Test
    fun plan_download_pullsRemoteOnly_neverDeletes() {
        val plan = planScope(
            local = setOf("local.acc"),
            remote = mapOf("cloud.acc" to re("cloud.acc")),
            localMtimeSec = emptyMap(),
            rule = ConflictRule.SameNameSkip,
            mode = SyncMode.Download
        )
        assertEquals(listOf("cloud.acc"), plan.transfer)
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun parseBackupNameTime_standardName() {
        val ts = parseBackupNameTime("account-20261113-143025.acc")
        assertEquals(1794580225000L, ts)
    }

    @Test
    fun parseBackupNameTime_indexSuffix() {
        val ts = parseBackupNameTime("account-20261113-143025-1.acc")
        assertEquals(parseBackupNameTime("account-20261113-143025.acc"), ts)
    }

    @Test
    fun parseBackupNameTime_tagSuffix() {
        val ts = parseBackupNameTime("account-20261113-143025-标签.acc")
        assertEquals(parseBackupNameTime("account-20261113-143025.acc"), ts)
    }

    @Test
    fun parseBackupNameTime_noStamp_returnsNull() {
        assertEquals(null, parseBackupNameTime("backup.acc"))
        assertEquals(null, parseBackupNameTime("account-plain-2026-11-13.json"))
    }

    @Test
    fun parseBackupNameTime_invalidDate_returnsNull() {
        assertEquals(null, parseBackupNameTime("account-20261399-999999.acc"))
    }
}
