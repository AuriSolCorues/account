package com.copy.account.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFolderTest {
    @Test
    fun blankInput_fallsBackToDefault() {
        assertEquals(DEFAULT_BACKUP_FOLDER, normalizeBackupFolder(""))
        assertEquals(DEFAULT_BACKUP_FOLDER, normalizeBackupFolder("   "))
        assertEquals(DEFAULT_BACKUP_FOLDER, normalizeBackupFolder("."))
    }

    @Test
    fun location_blankAndZero_meanStorageRoot() {
        assertEquals("", normalizeBackupLocation(""))
        assertEquals("", normalizeBackupLocation("   "))
        assertEquals("", normalizeBackupLocation("0"))
        assertEquals("", normalizeBackupLocation(" 0 "))
    }

    @Test
    fun location_trimsSlashesAndDropsDotSegments() {
        assertEquals("Download", normalizeBackupLocation(" Download/ "))
        assertEquals("a/b", normalizeBackupLocation("a//b"))
        assertEquals("a/b", normalizeBackupLocation("./a/./b/./"))
    }

    @Test
    fun location_rejectsTraversalAndAbsolute() {
        assertNull(normalizeBackupLocation("../x"))
        assertNull(normalizeBackupLocation("a/.."))
        assertNull(normalizeBackupLocation("/storage/emulated/0/Download"))
        assertNull(normalizeBackupLocation("/"))
    }

    @Test
    fun backupFolder_derivesFixedStructure() {
        assertEquals("Account/account", backupFolderPath(""))
        assertEquals("Account/account", backupFolderPath("0"))
        assertEquals("Download/Account/account", backupFolderPath("Download"))
        assertEquals("a/b/Account/account", backupFolderPath("a/b"))
    }

    @Test
    fun display_prefixBlankShowsZero() {
        assertEquals("0/Account/account", backupLocationDisplay(""))
        assertEquals("Download/Account/account", backupLocationDisplay("Download"))
    }

    @Test
    fun trimsSurroundingSlashesAndWhitespace() {
        assertEquals("backups/account", normalizeBackupFolder("  backups/account/  "))
        assertEquals("Download/账本备份", normalizeBackupFolder("Download/账本备份/ "))
    }

    @Test
    fun collapsesRepeatedSlashesAndDropsDotSegments() {
        assertEquals("a/b", normalizeBackupFolder("a//b"))
        assertEquals("a/b", normalizeBackupFolder("a///b"))
        assertEquals("a/b", normalizeBackupFolder("./a/./b"))
    }

    @Test
    fun rejectsParentTraversal() {
        assertNull(normalizeBackupFolder("../x"))
        assertNull(normalizeBackupFolder("a/../../etc"))
        assertNull(normalizeBackupFolder("a/.."))
    }

    @Test
    fun rejectsAbsolutePath() {
        assertNull(normalizeBackupFolder("/storage/emulated/0/Download"))
        assertNull(normalizeBackupFolder("/backups/account"))
        assertNull(normalizeBackupFolder("/"))
    }

    @Test
    fun keepsNestedRelativePath() {
        assertEquals("a/b/c/d", normalizeBackupFolder("a/b/c/d"))
    }

    @Test
    fun uniqueName_avoidsCollisions() {
        val names = mutableListOf<String>()
        repeat(3) { names += uniqueBackupName("") { candidate -> candidate in names } }

        assertEquals(3, names.toSet().size)
        assertTrue("命名规则应为 <yyyyMMdd-HHmmss>.acc，实际：$names", names.all { it.endsWith(".acc") && Regex("^\\d{8}-\\d{6}").containsMatchIn(it) })
    }

    @Test
    fun uniquePlainName_avoidsCollisionsAndMarksPlaintext() {
        val names = mutableListOf<String>()
        repeat(3) { names += uniquePlainExportName { candidate -> candidate in names } }

        assertEquals(3, names.toSet().size)
        assertTrue("明文命名规则应为 account-plain-<时间戳>.json，实际：$names", names.all { it.startsWith("account-plain-") && it.endsWith(".json") })
    }

    @Test
    fun nameTag_blankClears_keepsEmptyString() {
        assertEquals("", normalizeBackupNameTag(""))
        assertEquals("", normalizeBackupNameTag("   "))
    }

    @Test
    fun nameTag_trimsFoldsAndDropsReservedChars() {
        assertEquals("pixel8", normalizeBackupNameTag(" pixel8 "))
        assertEquals("my-phone", normalizeBackupNameTag("my phone"))
        assertEquals("小米-手机", normalizeBackupNameTag("小米 手机"))
        assertEquals("a-b", normalizeBackupNameTag("a -- b"))
        assertEquals("p8", normalizeBackupNameTag("p:8?"))
        assertEquals(24, normalizeBackupNameTag("x".repeat(40))!!.length)
    }

    @Test
    fun nameTag_allReservedChars_rejected() {
        assertNull(normalizeBackupNameTag("///"))
        assertNull(normalizeBackupNameTag("???"))
        assertNull(normalizeBackupNameTag(":*?\"<>|"))
    }

    @Test
    fun uniqueName_withTag_appendsTagAndDedups() {
        val names = mutableListOf<String>()
        repeat(3) { names += uniqueBackupName("pixel8") { candidate -> candidate in names } }

        assertEquals(3, names.toSet().size)
        assertTrue(
            "带标签命名应为 <yyyyMMdd-HHmmss>-pixel8[-n].acc，实际：$names",
            names.all { Regex("^\\d{8}-\\d{6}-pixel8(-\\d+)?\\.acc$").matches(it) }
        )
    }
}
