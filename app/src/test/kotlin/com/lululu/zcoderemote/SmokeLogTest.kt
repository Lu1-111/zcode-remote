package com.lululu.zcoderemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冒烟日志的格式（docs/ARCHITECTURE.md 第 8 节）。
 * M2 起纯逻辑的测试在 web/ 包旁边。
 */
class SmokeLogTest {

    @Test
    fun tagIsFixed() {
        assertEquals("ZCodeRemote", SmokeLog.TAG)
    }

    @Test
    fun pageLoadedLineContainsHostAndStatusOnly() {
        assertEquals(
            "pageLoaded host=zcode.z.ai status=200",
            SmokeLog.pageLoadedLine("zcode.z.ai", 200),
        )
    }

    @Test
    fun pageLoadedLineNeverPrintsAnythingThatIsNotAHostname() {
        val expected = "pageLoaded host=unknown status=200"

        assertEquals(expected, SmokeLog.pageLoadedLine("zcode.z.ai/remote/v4", 200))
        assertEquals(expected, SmokeLog.pageLoadedLine("zcode.z.ai?sid=secret", 200))
        assertEquals(expected, SmokeLog.pageLoadedLine("zcode.z.ai#token", 200))
        assertEquals(expected, SmokeLog.pageLoadedLine("user:secret@zcode.z.ai", 200))
        assertEquals(expected, SmokeLog.pageLoadedLine("zcode.z.ai:443", 200))
        assertEquals(expected, SmokeLog.pageLoadedLine("", 200))
        assertEquals(expected, SmokeLog.pageLoadedLine(null, 200))
        assertEquals(expected, SmokeLog.pageLoadedLine("zcode.z.ai\npageLoaded host=evil", 200))
        assertEquals(expected, SmokeLog.pageLoadedLine("[::1]", 200))
    }

    @Test
    fun pageLoadedLinePrintsRealErrorCodesAndIpHosts() {
        // 状态码是真的 404 就打 404；主机是 IP 字面量也是纯主机名。
        assertEquals("pageLoaded host=zcode.z.ai status=404", SmokeLog.pageLoadedLine("zcode.z.ai", 404))
        assertEquals("pageLoaded host=10.0.2.2 status=200", SmokeLog.pageLoadedLine("10.0.2.2", 200))
    }

    @Test
    fun unknownStatusIsPrintedAsUnknown_notGuessed() {
        // 取不到主文档的状态码（页面没有 navigation 记录、WebView 已经不能用）就打 unknown，不猜一个数字。
        assertEquals("pageLoaded host=zcode.z.ai status=unknown", SmokeLog.pageLoadedLine("zcode.z.ai", null))
        assertEquals(
            "pageLoaded host=unknown status=unknown",
            SmokeLog.pageLoadedLine("zcode.z.ai/remote/v4?sid=secret", null),
        )
    }

    @Test
    fun theLineHasNothingElse() {
        val line = SmokeLog.pageLoadedLine("zcode.z.ai", 200)
        assertEquals(2, Regex("(host|status)=").findAll(line).toList().size)
        assertFalse(line.contains("?"))
        assertFalse(line.contains("/"))
        assertTrue(line.startsWith("pageLoaded host="))
    }
}
