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
