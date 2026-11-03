package com.copy.account.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainCleanerTest {

    @Test
    fun cleanDomain_strips_scheme_www_path_and_port() {
        assertEquals("example.com", cleanDomain("https://www.Example.com:8080/path/page?q=1"))
        assertEquals("example.com", cleanDomain("http://example.com"))
        assertEquals("example.com", cleanDomain("www.example.com"))
        assertEquals("example.com", cleanDomain("  EXAMPLE.com/Path  "))
        assertEquals("example.com", cleanDomain("example.com:443"))
        assertEquals("example.com", cleanDomain("https://example.com#frag"))
        assertEquals("example.com", cleanDomain("example.com?x=1"))
    }

    @Test
    fun cleanDomain_blank_or_empty_returns_empty() {
        assertEquals("", cleanDomain(null))
        assertEquals("", cleanDomain(""))
        assertEquals("", cleanDomain("   "))
    }

    @Test
    fun extractRootDomain_keeps_multi_part_tld_together() {
        assertEquals("example.com.cn", extractRootDomain("a.b.example.com.cn"))
        assertEquals("example.co.uk", extractRootDomain("shop.example.co.uk"))
    }

    @Test
    fun extractRootDomain_normal_domains_take_last_two_labels() {
        assertEquals("google.com", extractRootDomain("mail.google.com"))
        assertEquals("example.com", extractRootDomain("example.com"))
        assertEquals("example.com", extractRootDomain("https://www.example.com:8080/a/b"))
    }

    @Test
    fun extractRootDomain_garbage_returns_empty() {
        assertEquals("", extractRootDomain(null))
        assertEquals("", extractRootDomain(""))
        assertEquals("", extractRootDomain("   "))
    }

    @Test
    fun extractCompanyId_takes_first_label() {
        assertEquals("example", extractCompanyId("example.com"))
        assertEquals("example", extractCompanyId("example.com.cn"))
        assertEquals("", extractCompanyId(null))
        assertEquals("", extractCompanyId(""))
    }

    @Test
    fun extractFallbackRootDomains_finds_encoded_redirect_target() {
        val result = extractFallbackRootDomains("https://sso.example.com/login?redirect=https%3A%2F%2Ftarget.org%2Fhome")
        assertTrue("target.org" in result)
    }

    @Test
    fun extractFallbackRootDomains_dedupes_and_skips_non_url_params() {
        val result = extractFallbackRootDomains(
            "https://sso.example.com/login?redirect=https%3A%2F%2Fa.example.com%2Fx&foo=bar&goto=https%3A%2F%2Fb.example.com%2Fy"
        )
        assertEquals(listOf("example.com"), result)
    }

    @Test
    fun extractFallbackRootDomains_requires_web_url() {
        assertEquals(emptyList<String>(), extractFallbackRootDomains("example.com/login?redirect=https%3A%2F%2Ftarget.org"))
        assertEquals(emptyList<String>(), extractFallbackRootDomains(null))
        assertEquals(emptyList<String>(), extractFallbackRootDomains("https://sso.example.com/login"))
    }

    @Test
    fun isWebUrl_checks_scheme_prefix() {
        assertTrue(isWebUrl("https://example.com"))
        assertTrue(isWebUrl("HTTP://example.com"))
        assertFalse(isWebUrl("example.com"))
        assertFalse(isWebUrl("ftp://example.com"))
        assertFalse(isWebUrl(null))
        assertFalse(isWebUrl(""))
    }
}
