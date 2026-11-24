package com.lululu.zcoderemote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 入口选择：打开哪个地址，主文档 404 时怎么办（方案第 2、3、4 条）。 */
class EntrySelectorTest {

    private val version = "4.0.0"
    private val fallback = "https://zcode.z.ai/remote/v4?app_version=4.0.0"

    // ---------------------------------------------------------------- 第一次打开哪个地址

    @Test
    fun noLink_opensFallbackEntry() {
        val target = EntrySelector.selectInitialEntry(link = null, fallbackVersion = version)
        assertEquals(EntryTarget.Fallback(fallback), target)
    }

    @Test
    fun blankLink_countsAsNoLink() {
        assertEquals(
            EntryTarget.Fallback(fallback),
            EntrySelector.selectInitialEntry(link = "", fallbackVersion = version),
        )
        assertEquals(
            EntryTarget.Fallback(fallback),
            EntrySelector.selectInitialEntry(link = "   ", fallbackVersion = version),
        )
    }

    @Test
    fun linkThatIsNotHttp_opensFallbackEntry() {
        assertEquals(
            EntryTarget.Fallback(fallback),
            EntrySelector.selectInitialEntry(link = "weixin://dl/business/?t=abc", fallbackVersion = version),
        )
    }

    @Test
    fun link_opensTheLinkAsIs() {
        val link = "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1&mid=m1&name=pc"
        assertEquals(EntryTarget.Link(link), EntrySelector.selectInitialEntry(link, version))
    }

    @Test
    fun linkIsLoadedVerbatim_notRebuiltCompletedOrRewritten() {
        // 参数顺序、没有 name、app_version 的值，都原样保留。
        val odd = "https://zcode.z.ai/remote/v4?name=%E7%94%B5%E8%84%91&sid=s1&app_version=9.9.9&extra=1"
        assertEquals(EntryTarget.Link(odd), EntrySelector.selectInitialEntry(odd, version))

        val http = "http://example.test/a?b=c#d"
        assertEquals(EntryTarget.Link(http), EntrySelector.selectInitialEntry(http, version))
    }

    // ---------------------------------------------------------------- 有链接，主文档 404

    @Test
    fun linkDocumentNotFound_switchesToFallback() {
        val link = "https://zcode.z.ai/remote/v4?app_version=3.16.0&sid=s1"
        assertEquals(
            EntryAction.SWITCH_TO_FALLBACK,
            EntrySelector.decideOnHttpError(EntryTarget.Link(link), link, isForMainFrame = true, statusCode = 404, fallbackVersion = version),
        )
    }

    @Test
    fun linkDocumentLoaded_isNotAnError() {
        // 正常情况不会有 404 回调；这里钉的是：除 404 之外的状态码不触发兜底。
        val link = "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1"
        for (status in listOf(200, 301, 400, 401, 403, 500, 502, 503)) {
            assertEquals(
                "status $status",
                EntryAction.NONE,
                EntrySelector.decideOnHttpError(EntryTarget.Link(link), link, true, status, version),
            )
        }
    }

    // ---------------------------------------------------------------- 子资源 404 不触发兜底

    @Test
    fun subresourceNotFound_doesNotTriggerFallback() {
        // 入口文档 200 了，页面里的图片、脚本、接口返回 404 是页面自己的事。
        val link = "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1"
        assertEquals(
            EntryAction.NONE,
            EntrySelector.decideOnHttpError(EntryTarget.Link(link), "https://zcode.z.ai/missing.png", isForMainFrame = false, statusCode = 404, fallbackVersion = version),
        )
        // 哪怕子资源请求的地址和入口一模一样，只要不是主框架，也不动。
        assertEquals(
            EntryAction.NONE,
            EntrySelector.decideOnHttpError(EntryTarget.Link(link), link, isForMainFrame = false, statusCode = 404, fallbackVersion = version),
        )
    }

    @Test
    fun subresourceNotFoundOnFallback_doesNotShowErrorPage() {
        assertEquals(
            EntryAction.NONE,
            EntrySelector.decideOnHttpError(EntryTarget.Fallback(fallback), "https://zcode.z.ai/x.js", isForMainFrame = false, statusCode = 404, fallbackVersion = version),
        )
    }

    // ---------------------------------------------------------------- 兜底也 404

    @Test
    fun fallbackDocumentNotFound_showsErrorPageAndDoesNotSwitchAgain() {
        val action = EntrySelector.decideOnHttpError(EntryTarget.Fallback(fallback), fallback, true, 404, version)
        assertEquals(EntryAction.SHOW_ERROR_PAGE, action)
        assertNotEquals(EntryAction.SWITCH_TO_FALLBACK, action)
    }

    // ---------------------------------------------------------------- 不是入口文档的 404

    @Test
    fun anotherDocumentNotFound_isLeftToThePage() {
        // 登录授权页、页面自己跳到的别的地址返回 404：不能把用户拽去兜底入口，那会打断登录。
        val link = "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1"
        val other = "https://zcode.z.ai/remote/v4?code=c&state=s"
        assertEquals(EntryAction.NONE, EntrySelector.decideOnHttpError(EntryTarget.Link(link), other, true, 404, version))

        val elsewhere = "https://auth.example.test/login?client=x"
        assertEquals(EntryAction.NONE, EntrySelector.decideOnHttpError(EntryTarget.Link(link), elsewhere, true, 404, version))

        // 回跳地址只比入口多了 code、state 两个参数，也不是入口文档。
        val callback = "$link&code=c&state=s"
        assertEquals(EntryAction.NONE, EntrySelector.decideOnHttpError(EntryTarget.Link(link), callback, true, 404, version))
    }

    @Test
    fun noEntryEverLoaded_leaves404Alone() {
        assertEquals(EntryAction.NONE, EntrySelector.decideOnHttpError(null, fallback, true, 404, version))
    }

    @Test
    fun unreadableRequestUrl_isNotTheEntryDocument() {
        assertEquals(EntryAction.NONE, EntrySelector.decideOnHttpError(EntryTarget.Fallback(fallback), null, true, 404, version))
        assertEquals(EntryAction.NONE, EntrySelector.decideOnHttpError(EntryTarget.Fallback(fallback), "not a url", true, 404, version))
    }

    // ---------------------------------------------------------------- WebView 回报的地址和交进去的原样文本写法不同

    @Test
    fun sameDocumentIgnoresCaseDefaultPortAndFragment() {
        val link = "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1#top"
        assertTrue(EntrySelector.sameDocumentUrl(link, link))
        assertTrue(EntrySelector.sameDocumentUrl(link, "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1"))
        assertTrue(EntrySelector.sameDocumentUrl("HTTPS://ZCODE.Z.AI:443/remote/v4?a=1", "https://zcode.z.ai/remote/v4?a=1"))
        assertTrue(EntrySelector.sameDocumentUrl("http://example.test:80/x", "http://example.test/x"))
        assertTrue(EntrySelector.sameDocumentUrl("https://example.test", "https://example.test/"))
        assertTrue(EntrySelector.sameDocumentUrl("https://user:pw@example.test/x", "https://example.test/x"))

        val entry = EntryTarget.Link("HTTPS://ZCODE.Z.AI:443/remote/v4?app_version=9.9.9&sid=s1")
        assertEquals(
            EntryAction.SWITCH_TO_FALLBACK,
            EntrySelector.decideOnHttpError(entry, "https://zcode.z.ai/remote/v4?app_version=9.9.9&sid=s1", true, 404, version),
        )
    }

    @Test
    fun sameDocumentStillTellsDifferentDocumentsApart() {
        assertFalse(EntrySelector.sameDocumentUrl("https://zcode.z.ai/remote/v4?a=1", "https://zcode.z.ai/remote/v4?a=2"))
        assertFalse(EntrySelector.sameDocumentUrl("https://zcode.z.ai/remote/v4?a=1", "https://zcode.z.ai/remote/v4?a=1&b=2"))
        assertFalse(EntrySelector.sameDocumentUrl("https://zcode.z.ai/remote/v4?a=1", "https://zcode.z.ai/remote/v3?a=1"))
        assertFalse(EntrySelector.sameDocumentUrl("https://zcode.z.ai/remote/v4", "http://zcode.z.ai/remote/v4"))
        assertFalse(EntrySelector.sameDocumentUrl("https://zcode.z.ai/remote/v4", "https://zcode.z.ai:8443/remote/v4"))
        assertFalse(EntrySelector.sameDocumentUrl("https://other-nope.z.ai/remote/v4", "https://other.z.ai/remote/v4"))
        assertFalse(EntrySelector.sameDocumentUrl("https://zcode.z.ai/remote/v4?x=1", "https://zcode.z.ai/remote/v4?x=2"))
    }

    @Test
    fun sameDocumentOfUnusableAddressesIsFalse() {
        assertFalse(EntrySelector.sameDocumentUrl(null, "https://a.test/"))
        assertFalse(EntrySelector.sameDocumentUrl("https://a.test/", null))
        assertFalse(EntrySelector.sameDocumentUrl(null, null))
    }

    // ---------------------------------------------------------------- 兜底版本和兜底地址

    @Test
    fun fallbackUrlHasOnlyAppVersion_neverASid() {
        assertEquals("https://zcode.z.ai/remote/v4?app_version=4.0.0", EntrySelector.fallbackUrl("4.0.0"))
        assertEquals("https://zcode.z.ai/remote/v4?app_version=4.1.0", EntrySelector.fallbackUrl("4.1.0"))
    }

    @Test
    fun defaultFallbackVersionIs400() {
        assertEquals("4.0.0", EntrySelector.DEFAULT_FALLBACK_VERSION)
        assertEquals("4.0.0", EntrySelector.normalizeFallbackVersion(null))
        assertEquals("4.0.0", EntrySelector.normalizeFallbackVersion(""))
        assertEquals("https://zcode.z.ai/remote/v4?app_version=4.0.0", EntrySelector.fallbackUrl(null))
    }

    @Test
    fun fallbackVersionIsTrimmedAndKeptWhenItLooksLikeAVersion() {
        assertEquals("4.1.0", EntrySelector.normalizeFallbackVersion("  4.1.0 "))
        assertEquals("4.0.0", EntrySelector.normalizeFallbackVersion("4.0.0"))
        assertEquals("4.1.0-beta.2", EntrySelector.normalizeFallbackVersion("4.1.0-beta.2"))
        assertEquals("v5", EntrySelector.normalizeFallbackVersion("v5"))
    }

    @Test
    fun fallbackVersionCannotSmuggleOtherThingsIntoTheUrl() {
        // 版本要拼进地址，所以不能让它带上别的参数、片段或者空格。
        val bad = listOf("4.0.0&sid=steal", "4.0.0#x", "4.0.0 5.0.0", "../4.0.0", "4.0.0/", "4.0.0?x", "4.0.0\n5.0.0", "四", "4.0.0 x")
        for (input in bad) {
            assertEquals(input, "4.0.0", EntrySelector.normalizeFallbackVersion(input))
            assertEquals(input, "https://zcode.z.ai/remote/v4?app_version=4.0.0", EntrySelector.fallbackUrl(input))
        }
    }
}
