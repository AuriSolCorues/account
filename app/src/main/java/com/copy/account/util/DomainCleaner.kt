package com.copy.account.util

import java.net.URLDecoder

private val MULTI_PART_TLDS = setOf(
    "com.cn", "net.cn", "org.cn", "gov.cn",
    "com.hk", "com.tw",
    "co.jp", "co.uk"
)

private val FALLBACK_PARAM_KEYS = setOf("service", "redirect", "redirect_uri", "target", "goto", "url")

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

internal fun extractCompanyId(rootDomain: String?): String {
    if (rootDomain.isNullOrBlank()) return ""
    return rootDomain.trim().substringBefore(".")
}

internal fun extractFallbackRootDomains(rawUrl: String?): List<String> {
    if (rawUrl.isNullOrBlank() || !isWebUrl(rawUrl)) return emptyList()
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

internal fun isWebUrl(raw: String?): Boolean =
    !raw.isNullOrBlank() &&
            (raw.trim().startsWith("http://", ignoreCase = true) ||
                    raw.trim().startsWith("https://", ignoreCase = true))
