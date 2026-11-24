package com.lululu.zcoderemote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UA（方案第 6 条）：去掉「; wv」和「Version/x.x」，得到标准的 Chrome 安卓串。 */
class UserAgentTest {

    @Test
    fun webViewMarksAreRemovedAndTheRestIsKept() {
        val webView = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.107 Mobile Safari/537.36"
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.6723.107 Mobile Safari/537.36"

        assertEquals(chrome, UserAgent.toChromeUserAgent(webView))
        assertLooksLikeChromeAndroid(UserAgent.toChromeUserAgent(webView))
    }

    @Test
    fun olderWebViewStringKeepsItsRealDeviceAndSystemVersion() {
        // 没有 OS 版本和型号缩减的老写法：除了两处标记，其余（含真实 Chrome 版本）都要留着。
        val webView = "Mozilla/5.0 (Linux; Android 6.0.1; SM-G920F; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Mobile Safari/537.36"
        val chrome = "Mozilla/5.0 (Linux; Android 6.0.1; SM-G920F) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36"

        assertEquals(chrome, UserAgent.toChromeUserAgent(webView))
        assertLooksLikeChromeAndroid(UserAgent.toChromeUserAgent(webView))
    }

    @Test
    fun tabletStringHasNoMobileTokenAndIsLeftThatWay() {
        val webView = "Mozilla/5.0 (Linux; Android 13; SM-X700; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Safari/537.36"
        val chrome = "Mozilla/5.0 (Linux; Android 13; SM-X700) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        assertEquals(chrome, UserAgent.toChromeUserAgent(webView))
        assertFalse(UserAgent.toChromeUserAgent(webView).contains("Mobile"))
    }

    @Test
    fun aStringWithoutTheMarksIsReturnedUnchanged() {
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.6723.107 Mobile Safari/537.36"
        assertEquals(chrome, UserAgent.toChromeUserAgent(chrome))
        assertEquals("", UserAgent.toChromeUserAgent(""))
    }

    @Test
    fun applyingItTwiceChangesNothing() {
        val webView = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.107 Mobile Safari/537.36"
        val once = UserAgent.toChromeUserAgent(webView)
        assertEquals(once, UserAgent.toChromeUserAgent(once))
    }

    @Test
    fun wordsThatMerelyContainWvOrVersionAreNotTouched() {
        val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.6723.107 wvbuild/1 Version"
        assertEquals(ua, UserAgent.toChromeUserAgent(ua))
    }

    private fun assertLooksLikeChromeAndroid(ua: String) {
        assertFalse(ua, ua.contains(" wv"))
        assertFalse(ua, ua.contains(";wv"))
        assertFalse(ua, Regex("Version/\\d").containsMatchIn(ua))
        assertTrue(ua, Regex("\\(KHTML, like Gecko\\) Chrome/\\d+\\.\\d+(\\.\\d+){0,2}( Mobile)? Safari/[\\d.]+$").containsMatchIn(ua))
        assertFalse(ua, ua.contains("  "))
    }
}
