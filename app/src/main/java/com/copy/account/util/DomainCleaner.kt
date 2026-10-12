/**
 * 职责：域名/URL 清洗与根域名提取——把任意用户输入的网址串规整成可比较的域名，
 *       并支持多段后缀（com.cn / co.uk 等）下的 eTLD+1 提取与 SSO 重定向参数回退解析。
 * 架构位置：util/ 纯 JVM 工具，无 Android 依赖；自动填充匹配（AppAutofillService）与
 *           将来的网址归一化展示共用此单一来源。
 * Python 类比：≈ 一个只有 @staticmethod 的工具类；全部函数纯字符串进出，无状态无副作用。
 */
package com.copy.account.util

import java.net.URLDecoder

/** 多段公共后缀（二级 TLD）：eTLD+1 计算时按整体对待，否则 a.b.com.cn 会被切错。 */
private val MULTI_PART_TLDS = setOf(
    "com.cn", "net.cn", "org.cn", "gov.cn",
    "com.hk", "com.tw",
    "co.jp", "co.uk"
)

/** SSO 登录跳转常见参数名：值多为另一个待匹配 URL，值得作为匹配回退。 */
private val FALLBACK_PARAM_KEYS = setOf("service", "redirect", "redirect_uri", "target", "goto", "url")

/**
 * 清洗任意原始输入为可比对域名：trim → 小写 → 去协议头 → 去 www. → 截断路径 →
 * 去端口 → 防御性再去 query/fragment → 去尾部点。空/非域名输入返回空串。
 */
internal fun cleanDomain(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.trim()
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore("/")
        .substringBefore(":")
        .substringBefore("?")
        .substringBefore("#")
        .trimEnd('.')
}

/** 根域名（eTLD+1）：先清洗，再按多段后缀集合决定取最后两段还是三段。 */
internal fun extractRootDomain(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val core = cleanDomain(raw)
    if (core.isBlank()) return ""
    val parts = core.split('.')
    if (parts.size <= 2) return core
    val tail = parts.takeLast(2).joinToString(".")
    return if (tail in MULTI_PART_TLDS) {
        parts.takeLast(3).joinToString(".")
    } else {
        tail
    }
}

/** 公司标识：根域名的第一个标签（example.com → example），用于同公司多域归并。 */
internal fun extractCompanyId(rootDomain: String?): String {
    if (rootDomain.isNullOrBlank()) return ""
    return rootDomain.trim().substringBefore(".")
}

/**
 * 回退根域名集合：从 SSO 登录 URL 的 query 里找跳转参数（service/redirect/…），
 * URL 解码后逐个提取根域名去重返回；非网页 URL 或无参数时返回空列表。
 */
internal fun extractFallbackRootDomains(rawUrl: String?): List<String> {
    if (rawUrl.isNullOrBlank() || !isWebUrl(rawUrl)) return emptyList()
    // 注意第二个参数：substringBefore("#") 缺分隔符时返回原串；PM 原版误传 "" 会把无
    // fragment 的 query 整段丢掉——移植时已修正。
    val query = rawUrl.substringAfter("?", "").substringBefore("#")
    if (query.isBlank()) return emptyList()

    val result = mutableListOf<String>()
    for (pair in query.split("&")) {
        if (pair.isBlank()) continue
        val key = pair.substringBefore("=", "").trim().lowercase()
        if (key !in FALLBACK_PARAM_KEYS) continue
        val value = pair.substringAfter("=", "").trim()
        if (value.isEmpty()) continue
        val decoded = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            continue
        }
        val root = extractRootDomain(decoded)
        if (root.isNotEmpty() && root !in result) {
            result.add(root)
        }
    }
    return result
}

/** 是否形如网页 URL（http/https 前缀，忽略大小写）；自动填充决定走域名匹配的前提。 */
internal fun isWebUrl(raw: String?): Boolean =
    !raw.isNullOrBlank() &&
            (raw.trim().startsWith("http://", ignoreCase = true) ||
                    raw.trim().startsWith("https://", ignoreCase = true))
