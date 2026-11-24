package com.lululu.zcoderemote.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接的校验与归并（方案第 2、10 条，约定见 docs/ARCHITECTURE.md 第 10 节）。
 * 覆盖那张表里点名的每一种情况，加上实现时定下的边界。
 */
class RemoteLinkTest {

    private val base = "https://zcode.z.ai/remote/v4"

    // ---------------------------------------------------------------- 校验：可用的电脑链接

    @Test
    fun fullLinkIsAComputer() {
        val link = "$base?app_version=4.0.0&sid=s1&mid=m1&name=pc"
        assertEquals(
            LinkCheck.Computer(sid = "s1", name = "pc", hasAppVersion = true),
            RemoteLink.check(link),
        )
    }

    @Test
    fun parameterOrderDoesNotMatter() {
        assertEquals(
            RemoteLink.check("$base?app_version=4.0.0&sid=s1&mid=m1&name=pc"),
            RemoteLink.check("$base?name=pc&sid=s1&app_version=4.0.0&mid=m1"),
        )
    }

    @Test
    fun percentEncodedChineseNameIsDecoded() {
        val checked = RemoteLink.check("$base?sid=s1&name=%E7%AC%94%E8%AE%B0%E6%9C%AC%E7%94%B5%E8%84%91")
        assertTrue(checked is LinkCheck.Computer)
        assertEquals("笔记本电脑", (checked as LinkCheck.Computer).name)
        assertEquals("未命名电脑", RemoteLink.displayName(null))
        assertEquals("笔记本电脑", RemoteLink.displayName("笔记本电脑"))
    }

    @Test
    fun plusInQueryDecodesToSpace_likeThePageDoes() {
        // 页面用 URLSearchParams 解析，+ 就是空格。
        val checked = RemoteLink.check("$base?sid=s1&name=office+pc")
        assertEquals("office pc", (checked as LinkCheck.Computer).name)
    }

    @Test
    fun upperCaseHostAndSchemeAreAccepted() {
        val checked = RemoteLink.check("HTTPS://ZCODE.Z.AI/remote/v4?sid=s1")
        assertTrue(checked is LinkCheck.Computer)
        assertEquals("s1", (checked as LinkCheck.Computer).sid)
    }

    @Test
    fun explicitDefaultPortIsAccepted() {
        assertTrue(RemoteLink.check("https://zcode.z.ai:443/remote/v4?sid=s1") is LinkCheck.Computer)
    }

    @Test
    fun unknownParametersAreKeptButDoNotChangeTheVerdict() {
        val checked = RemoteLink.check("$base?sid=s1&extra=1&x=%2F")
        assertTrue(checked is LinkCheck.Computer)
    }

    @Test
    fun duplicateSidTakesTheLastValue_likeThePageDoes() {
        val checked = RemoteLink.check("$base?sid=a&sid=b")
        assertEquals("b", (checked as LinkCheck.Computer).sid)
    }

    @Test
    fun missingAppVersionIsStillAComputer_butMarked() {
        val checked = RemoteLink.check("$base?sid=s1&name=pc")
        assertEquals(LinkCheck.Computer("s1", "pc", hasAppVersion = false), checked)
    }

    @Test
    fun fragmentIsNotALegacyParam() {
        // # 后面的部分不是查询参数，不触发旧版判断；原样文本不受影响。
        assertTrue(RemoteLink.check("$base?sid=s1#top") is LinkCheck.Computer)
    }

    // ---------------------------------------------------------------- 旧版链接（带 hash 或 t）

    @Test
    fun hashParamMeansLegacy() {
        assertEquals(LinkCheck.Legacy, RemoteLink.check("$base?sid=s1&hash=abc&t=123"))
    }

    @Test
    fun tParamMeansLegacy_evenWithoutSid() {
        assertEquals(LinkCheck.Legacy, RemoteLink.check("$base?t=1700000000"))
        assertEquals(LinkCheck.Legacy, RemoteLink.check("$base?hash=x"))
    }

    @Test
    fun legacyParamNamesAreCaseSensitive_likeThePage() {
        // 页面用 URLSearchParams，键区分大小写：T=1 不是 t。
        assertTrue(RemoteLink.check("$base?sid=s1&T=1") is LinkCheck.Computer)
        assertTrue(RemoteLink.check("$base?sid=s1&HASH=x") is LinkCheck.Computer)
    }

    @Test
    fun hashInFragmentDoesNotMeanLegacy() {
        assertTrue(RemoteLink.check("$base?sid=s1#hash") is LinkCheck.Computer)
    }

    // ---------------------------------------------------------------- 拒绝的情况

    @Test
    fun notHttpsIsRejected() {
        assertEquals(LinkCheck.Invalid, RemoteLink.check("http://zcode.z.ai/remote/v4?sid=s1"))
        assertEquals(LinkCheck.Invalid, RemoteLink.check("weixin://dl/business/?t=1"))
        assertEquals(LinkCheck.Invalid, RemoteLink.check("zcode.z.ai/remote/v4?sid=s1"))
        assertEquals(LinkCheck.Invalid, RemoteLink.check(null))
        assertEquals(LinkCheck.Invalid, RemoteLink.check(""))
        assertEquals(LinkCheck.Invalid, RemoteLink.check("   "))
    }

    @Test
    fun wrongHostIsRejected() {
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://zcode.z.ai.evil.test/remote/v4?sid=s1"))
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://example.test/remote/v4?sid=s1"))
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://zcode.z.ai/remote/v4.evil.test/remote/v4?sid=s1"))
        // 子域不算。
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://sub.zcode.z.ai/remote/v4?sid=s1"))
    }

    @Test
    fun wrongPathIsRejected() {
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://zcode.z.ai/remote/v3?sid=s1"))
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://zcode.z.ai/remote/v42?sid=s1"))
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://zcode.z.ai/?sid=s1"))
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://zcode.z.ai/remote/v4x?sid=s1"))
    }

    @Test
    fun nonDefaultPortIsRejected() {
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://zcode.z.ai:8443/remote/v4?sid=s1"))
    }

    @Test
    fun userInfoIsRejected() {
        assertEquals(LinkCheck.Invalid, RemoteLink.check("https://user:pw@zcode.z.ai/remote/v4?sid=s1"))
    }

    // ---------------------------------------------------------------- 免扫码入口（没有 sid）

    @Test
    fun noSidIsTheEntry_notAComputer() {
        assertEquals(LinkCheck.Entry, RemoteLink.check("https://zcode.z.ai/remote/v4?app_version=4.0.0"))
        assertEquals(LinkCheck.Entry, RemoteLink.check("https://zcode.z.ai/remote/v4"))
        // sid 空串也当没有。
        assertEquals(LinkCheck.Entry, RemoteLink.check("$base?sid="))
        assertEquals(LinkCheck.Entry, RemoteLink.check("$base?sid=&app_version=4.0.0"))
    }

    @Test
    fun noNameIsStillAComputer_withFallbackName() {
        val checked = RemoteLink.check("$base?sid=s1&app_version=4.0.0")
        assertEquals(LinkCheck.Computer("s1", null, true), checked)
        assertEquals("未命名电脑", RemoteLink.displayName((checked as LinkCheck.Computer).name))
    }

    // ---------------------------------------------------------------- 归并

    @Test
    fun sameSidIsMerged_noDuplicateEntry() {
        val first = RemoteLink.merge(
            emptyList(),
            "$base?app_version=4.0.0&sid=s1&name=pc",
            RemoteLink.check("$base?app_version=4.0.0&sid=s1&name=pc") as LinkCheck.Computer,
            nowMs = 100L,
        )
        assertEquals(1, first.size)

        val second = RemoteLink.merge(
            first,
            "$base?app_version=4.1.0&sid=s1&name=pc2&mid=m1",
            RemoteLink.check("$base?app_version=4.1.0&sid=s1&name=pc2&mid=m1") as LinkCheck.Computer,
            nowMs = 200L,
        )
        assertEquals(1, second.size)
        // 新链接的原样文本覆盖旧链接；电脑名跟着更新；添加时间保留；最后使用时间保留。
        assertEquals("$base?app_version=4.1.0&sid=s1&name=pc2&mid=m1", second[0].link)
        assertEquals("pc2", second[0].name)
        assertEquals(100L, second[0].addedAt)
        assertEquals(0L, second[0].lastUsedAt)
    }

    @Test
    fun mergeWithoutNameKeepsTheCurrentName() {
        // 新链接没带 name：不把用户改过的名字冲掉。
        val renamed = listOf(Computer("s1", "$base?sid=s1", "我的小本", 100L, 0L))
        val merged = RemoteLink.merge(
            renamed,
            "$base?sid=s1&app_version=4.1.0",
            RemoteLink.check("$base?sid=s1&app_version=4.1.0") as LinkCheck.Computer,
            nowMs = 200L,
        )
        assertEquals("我的小本", merged.single().name)
        assertEquals("$base?sid=s1&app_version=4.1.0", merged.single().link)
    }

    @Test
    fun mergeWithEmptyNameKeepsTheCurrentName() {
        val existing = listOf(Computer("s1", "$base?sid=s1&name=pc", "pc", 100L, 5L))
        val merged = RemoteLink.merge(
            existing,
            "$base?sid=s1&name=",
            RemoteLink.check("$base?sid=s1&name=") as LinkCheck.Computer,
            nowMs = 200L,
        )
        assertEquals("pc", merged.single().name)
    }

    @Test
    fun differentSidsAreSeparateComputers_evenWithSameMid() {
        val first = RemoteLink.merge(
            emptyList(),
            "$base?sid=s1&mid=m1&name=pc",
            RemoteLink.check("$base?sid=s1&mid=m1&name=pc") as LinkCheck.Computer,
            100L,
        )
        val both = RemoteLink.merge(
            first,
            "$base?sid=s2&mid=m1&name=pc",
            RemoteLink.check("$base?sid=s2&mid=m1&name=pc") as LinkCheck.Computer,
            200L,
        )
        assertEquals(2, both.size)
        assertEquals(setOf("s1", "s2"), both.map { it.sid }.toSet())
        // 各自的添加时间各是各的，后来的一条加在末尾。
        assertEquals(100L, both[0].addedAt)
        assertEquals(200L, both[1].addedAt)
    }

    @Test
    fun mergeKeepsLastUsedTime() {
        val used = listOf(Computer("s1", "$base?sid=s1&name=pc", "pc", 100L, 999L))
        val merged = RemoteLink.merge(
            used,
            "$base?sid=s1&name=pc&app_version=4.1.0",
            RemoteLink.check("$base?sid=s1&name=pc&app_version=4.1.0") as LinkCheck.Computer,
            200L,
        )
        assertEquals(999L, merged.single().lastUsedAt)
    }

    @Test
    fun percentEncodedSidMergesWithPlainSid() {
        // 同一个 sid 的两种写法是同一台电脑。
        val first = RemoteLink.merge(
            emptyList(),
            "$base?sid=ab",
            RemoteLink.check("$base?sid=ab") as LinkCheck.Computer,
            100L,
        )
        val second = RemoteLink.merge(
            first,
            "$base?sid=%61%62",
            RemoteLink.check("$base?sid=%61%62") as LinkCheck.Computer,
            200L,
        )
        assertEquals(1, second.size)
    }

    // ---------------------------------------------------------------- 从分享文本里找链接

    @Test
    fun findInTextReturnsTheWholeTextWhenItIsTheLink() {
        val link = "$base?app_version=4.0.0&sid=s1"
        assertEquals(link, RemoteLink.findInText(link))
        assertEquals(link, RemoteLink.findInText("  $link\n"))
    }

    @Test
    fun findInTextPicksTheLinkOutOfASentence() {
        val link = "$base?app_version=4.0.0&sid=s1&mid=m1&name=%E7%94%B5%E8%84%91"
        val shared = "用手机打开这个链接遥控电脑：$link ，记得登录同一账号。"
        assertEquals(link, RemoteLink.findInText(shared))
    }

    @Test
    fun findInTextTakesTheFirstOfTwo() {
        val a = "$base?sid=a"
        val b = "$base?sid=b"
        assertEquals(a, RemoteLink.findInText("先试 $a 不行再试 $b"))
    }

    @Test
    fun findInTextStopsAtQuotesAndBrackets() {
        assertEquals(
            "$base?sid=s1",
            RemoteLink.findInText("<a href=\"$base?sid=s1\">打开</a>"),
        )
        assertEquals(
            "$base?sid=s1",
            RemoteLink.findInText("「$base?sid=s1」"),
        )
    }

    @Test
    fun findInTextReturnsNullWhenThereIsNoLink() {
        assertNull(RemoteLink.findInText("今天天气不错"))
        assertNull(RemoteLink.findInText("https://example.test/other"))
        assertNull(RemoteLink.findInText(null))
        assertNull(RemoteLink.findInText(""))
        // 别的主机的链接不认。
        assertNull(RemoteLink.findInText("看这个 https://zcode.z.ai/other/path"))
    }

    @Test
    fun whatFindInTextReturnsStillPassesCheck() {
        val link = "$base?app_version=4.0.0&sid=s1&name=pc"
        val found = RemoteLink.findInText("链接：$link 。")
        assertEquals(link, found)
        assertTrue(RemoteLink.check(found) is LinkCheck.Computer)
        assertNotEquals(LinkCheck.Invalid, RemoteLink.check(found))
    }
}
