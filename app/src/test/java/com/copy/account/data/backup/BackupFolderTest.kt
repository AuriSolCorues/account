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

    // ===== 位置前缀模型（结构固定 <位置>/Account/account）=====

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
        // 刻意拒绝前导斜杠：backupAccountDirectory 依赖 folder 是相对路径，
        // 静默降级成「外部存储根下的同名子目录」会让落盘位置变得莫名其妙。
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
        // 每次都认领上一次生成的名字：同一秒内必逐级追加 -1/-2 序号，跨秒则时间戳本身不同。
        // 两种情况都要求「互不相同」，因此这个断言与时钟无关。
        repeat(3) { names += uniqueBackupName { candidate -> candidate in names } }

        assertEquals(3, names.toSet().size)
        assertTrue("命名规则应为 account-<时间戳>.acc，实际：$names", names.all { it.startsWith("account-") && it.endsWith(".acc") })
    }

    /** 明文导出用独立命名器：必须带 plain 前缀（便于用户一眼认出该删）、以 .json 结尾，且同样去重。 */
    @Test
    fun uniquePlainName_avoidsCollisionsAndMarksPlaintext() {
        val names = mutableListOf<String>()
        repeat(3) { names += uniquePlainExportName { candidate -> candidate in names } }

        assertEquals(3, names.toSet().size)
        assertTrue("明文命名规则应为 account-plain-<时间戳>.json，实际：$names", names.all { it.startsWith("account-plain-") && it.endsWith(".json") })
    }
}
