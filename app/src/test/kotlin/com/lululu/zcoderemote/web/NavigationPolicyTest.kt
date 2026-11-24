package com.lululu.zcoderemote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导航策略（方案第 5 条）：http/https 一律留在 WebView，非 http(s) 的 scheme 拦下。 */
class NavigationPolicyTest {

    @Test
    fun httpAndHttpsStayInTheWebView() {
        assertEquals(NavigationAction.LOAD_IN_WEBVIEW, NavigationPolicy.actionFor("http"))
        assertEquals(NavigationAction.LOAD_IN_WEBVIEW, NavigationPolicy.actionFor("https"))
        assertEquals(NavigationAction.LOAD_IN_WEBVIEW, NavigationPolicy.actionFor("HTTP"))
        assertEquals(NavigationAction.LOAD_IN_WEBVIEW, NavigationPolicy.actionFor("HTTPS"))
    }

    @Test
    fun authorisationAndCallbackPagesAreNotSpecialCased() {
        // 没有域名白名单：授权页在哪、回跳带不带 code 和 state，只要是 http(s) 都留在 WebView。
        val urls = listOf(
            "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1",
            "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1&code=c&state=s",
            "https://chat.z.ai/oauth/authorize?client_id=x&redirect_uri=y",
            "https://bigmodel.cn/login?redirect=z",
            "https://open.bigmodel.cn/oauth?x=1",
            "http://example.test/plain",
        )
        for (url in urls) {
            assertEquals(url, NavigationAction.LOAD_IN_WEBVIEW, NavigationPolicy.actionFor(NavigationPolicy.schemeOf(url)))
        }
    }

    @Test
    fun otherSchemesAreBlockedWithTheLoginHint() {
        val urls = listOf(
            "weixin://dl/business/?ticket=t",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "market://details?id=com.example",
            "alipays://platformapi/startapp?saId=10000007",
            "mailto:a@b.test",
            "tel:10086",
            "javascript:alert(1)",
            "chrome://crash",
            "file:///sdcard/x.html",
            "content://contacts/people/1",
            "ftp://example.test/x",
        )
        for (url in urls) {
            assertEquals(url, NavigationAction.BLOCK_WITH_LOGIN_HINT, NavigationPolicy.actionFor(NavigationPolicy.schemeOf(url)))
        }
    }

    @Test
    fun unknownSchemeIsBlocked() {
        // 看不出 scheme 的地址不知道会打开什么：按最坏的算，拦下。
        assertEquals(NavigationAction.BLOCK_WITH_LOGIN_HINT, NavigationPolicy.actionFor(null))
        assertEquals(NavigationAction.BLOCK_WITH_LOGIN_HINT, NavigationPolicy.actionFor(""))
        assertNull(NavigationPolicy.schemeOf(null))
        assertNull(NavigationPolicy.schemeOf("not a url"))
    }

    @Test
    fun webInternalSchemesLoadInsideTheWebViewWithoutAHint() {
        // 它们出不了 WebView，也拉不起别的 App。拦下会让页面里的预览、空白 iframe 失效。
        for (url in listOf("about:blank", "about:srcdoc", "blob:https://zcode.z.ai/uuid", "data:text/html,x")) {
            assertEquals(url, NavigationAction.LOAD_INTERNAL, NavigationPolicy.actionFor(NavigationPolicy.schemeOf(url)))
        }
        // 内部地址不是 http(s)：不会被当成外链交给系统浏览器。
        assertFalse(NavigationPolicy.isHttp("blob"))
        assertFalse(NavigationPolicy.isHttp("about"))
    }

    @Test
    fun schemeIsReadFromTheStartOfTheUrlOnly() {
        assertEquals("https", NavigationPolicy.schemeOf("https://example.test/weixin://x?y=weixin://z"))
        assertEquals("weixin", NavigationPolicy.schemeOf("weixin://x?next=https://example.test"))
    }

    @Test
    fun isHttpUrlOnlyTrustsAnHttpOrHttpsPrefix() {
        assertTrue(NavigationPolicy.isHttpUrl("https://zcode.z.ai/remote/v4"))
        assertTrue(NavigationPolicy.isHttpUrl("http://10.0.2.2:8443/x?y=1"))
        assertTrue(NavigationPolicy.isHttpUrl("HTTPS://ZCODE.Z.AI/remote/v4"))

        assertFalse(NavigationPolicy.isHttpUrl(null))
        assertFalse(NavigationPolicy.isHttpUrl(""))
        assertFalse(NavigationPolicy.isHttpUrl("weixin://dl/business/?url=https://x.test"))
        assertFalse(NavigationPolicy.isHttpUrl("javascript://comment%0Ahttps://x.test"))
        assertFalse(NavigationPolicy.isHttpUrl("javascript:location='https://x.test'"))
        assertFalse(NavigationPolicy.isHttpUrl(" https://zcode.z.ai/remote/v4"))
        assertFalse(NavigationPolicy.isHttpUrl("zcode.z.ai/remote/v4"))
        assertFalse(NavigationPolicy.isHttpUrl("data:text/html,https://x.test"))
    }
}
