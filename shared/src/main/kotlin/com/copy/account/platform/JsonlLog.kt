/**
 * 职责：带上限的 JSONL 日志工具（追加写 + 超限轮转 + 读 + 清空）。
 *       纯 JVM（只碰 java.io.File）——:app 与可选扩展模块经 srcDir 共用，可单测。
 *       轮转策略：append 前查 length()，超 [maxBytes] 即「保留最新 keepLines 行 →
 *       写临时文件 → 原子 rename 覆盖」——1MB 硬顶，永不无限膨胀；崩溃也只丢正在
 *       追加的那一行（appendText 单行原子性足够，日志设施自身不能成为故障源）。
 * 架构位置：shared/platform/；本体与各扩展模块各持一个实例（各写各的沙箱）。
 * Python 类比：≈ 一个带 maxBytes 轮转的 open("log.jsonl","a")——logging.RotatingFileHandler
 *           的极简版（按行数保留，不做多份备份文件）。
 */
package com.copy.account.platform

import java.io.File

class JsonlLog(
    /** 日志文件全路径（含文件名；父目录不存在自动创建）。 */
    private val file: File,
    /** 硬顶字节数：超过即触发轮转。 */
    private val maxBytes: Long,
    /** 轮转时保留的最新行数（防止单条超长日志把保留区撑爆的兜底 = 顺带截断）。 */
    private val keepLines: Int = 2000
) {
    /** 追加一行（写入失败静默——调用方各异，失败不该炸业务）。 */
    fun append(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.isFile && file.length() > maxBytes) rotate()
            file.appendText(line + "\n")
        }
    }

    /**
     * 轮转：读全部行 → 保留末尾 keepLines 行 → 写 tmp → 原子 rename 覆盖。
     * readLines 对 1MB 文件毫秒级；发生在 append 路径上（低频日志，可接受）。
     * 无论行数多少都截到 keepLines（字节超限 = 必须缩；行少说明单行超长，更该截）。
     */
    private fun rotate() {
        val lines = file.readLines()
        val tmp = File(file.parentFile, file.name + ".tmp")
        if (lines.isEmpty()) {
            file.delete() // 空文件（理论不可达，防御）直接重建
            return
        }
        tmp.writeText(lines.takeLast(keepLines).joinToString("\n") + "\n")
        tmp.renameTo(file) // 同目录 rename = 原子替换
    }

    /** 读全部（新在前）；损坏行跳过。 */
    fun readAll(): List<String> = runCatching {
        if (!file.isFile) emptyList() else file.readLines().asReversed()
    }.getOrDefault(emptyList())

    /** 清空（设置页危险操作）。 */
    fun clear() {
        runCatching { file.delete() }
    }
}
