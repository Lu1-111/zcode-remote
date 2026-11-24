package com.lululu.zcoderemote.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 返回键决策（docs/ARCHITECTURE.md 第 7 节的表）：CHAT、HOME、ABSENT、UNKNOWN 各自在能与不能后退时的结果。
 */
class BackDecisionTest {

    // ---------------------------------------------------------------- 聊天页

    @Test
    fun chatAndCanGoBack_goesBackInWeb() {
        assertEquals(BackTarget.WEB_BACK, decideBack(MobilePage.CHAT, canGoBack = true))
    }

    @Test
    fun chatButCannotGoBack_returnsToComputerList() {
        assertEquals(BackTarget.COMPUTER_LIST, decideBack(MobilePage.CHAT, canGoBack = false))
    }

    // ---------------------------------------------------------------- 首页、没有这个属性的页面

    @Test
    fun home_returnsToComputerListWhetherOrNotItCanGoBack() {
        assertEquals(BackTarget.COMPUTER_LIST, decideBack(MobilePage.HOME, canGoBack = true))
        assertEquals(BackTarget.COMPUTER_LIST, decideBack(MobilePage.HOME, canGoBack = false))
    }

    @Test
    fun absent_returnsToComputerListWhetherOrNotItCanGoBack() {
        // 登录页、授权页：历史记录里有授权页和回跳页，不能后退走回去。
        assertEquals(BackTarget.COMPUTER_LIST, decideBack(MobilePage.ABSENT, canGoBack = true))
        assertEquals(BackTarget.COMPUTER_LIST, decideBack(MobilePage.ABSENT, canGoBack = false))
    }

    // ---------------------------------------------------------------- 脚本没有返回结果

    @Test
    fun unknownAndCanGoBack_goesBackInWeb() {
        assertEquals(BackTarget.WEB_BACK, decideBack(MobilePage.UNKNOWN, canGoBack = true))
    }

    @Test
    fun unknownAndCannotGoBack_goesToDesktop() {
        assertEquals(BackTarget.DESKTOP, decideBack(MobilePage.UNKNOWN, canGoBack = false))
    }

    @Test
    fun everyPageHasADecision() {
        // 表没有漏项：四种页面位置、能不能后退，一共八种组合都有结果。
        var combinations = 0
        for (page in MobilePage.entries) {
            for (canGoBack in listOf(true, false)) {
                decideBack(page, canGoBack)
                combinations++
            }
        }
        assertEquals(8, combinations)
    }
}

/** 把 evaluateJavascript 的结果翻译成页面位置。 */
class BackProbeTest {

    @Test
    fun readsTheAttributeValue() {
        assertEquals(MobilePage.CHAT, BackProbe.pageFromScriptResult("\"chat\""))
        assertEquals(MobilePage.HOME, BackProbe.pageFromScriptResult("\"home\""))
    }

    @Test
    fun emptyStringMeansTheAttributeIsAbsent() {
        assertEquals(MobilePage.ABSENT, BackProbe.pageFromScriptResult("\"\""))
    }

    @Test
    fun noResultMeansUnknown() {
        // 回调拿到 null（WebView 不能用了），或者 JSON 的 null（脚本返回 undefined）。
        assertEquals(MobilePage.UNKNOWN, BackProbe.pageFromScriptResult(null))
        assertEquals(MobilePage.UNKNOWN, BackProbe.pageFromScriptResult("null"))
    }

    @Test
    fun resultThatIsNotAJsonStringMeansUnknown() {
        // 脚本出错时拿到的可能是别的东西：不知道页面在哪，按 UNKNOWN 处理。
        assertEquals(MobilePage.UNKNOWN, BackProbe.pageFromScriptResult(""))
        assertEquals(MobilePage.UNKNOWN, BackProbe.pageFromScriptResult("chat"))
        assertEquals(MobilePage.UNKNOWN, BackProbe.pageFromScriptResult("[\"chat\"]"))
        assertEquals(MobilePage.UNKNOWN, BackProbe.pageFromScriptResult("\"ch\nat\""))
    }

    @Test
    fun unrecognisedValueIsTreatedAsAbsent() {
        // 页面改版，属性的值变了：不是聊天页，不后退，按首页处理。
        assertEquals(MobilePage.ABSENT, BackProbe.pageFromScriptResult("\"settings\""))
        assertEquals(MobilePage.ABSENT, BackProbe.pageFromScriptResult("\"CHAT\""))
    }

    @Test
    fun probeOnlyReadsAndTouchesNothing() {
        // 只读：没有赋值、不改 DOM、不写入任何东西。
        val script = BackProbe.SCRIPT
        assertEquals(true, script.startsWith("document.querySelector('[data-mobile-page]')"))
        assertEquals(false, script.contains("="))
        assertEquals(false, Regex("innerHTML|appendChild|removeChild|setAttribute|localStorage|cookie").containsMatchIn(script))
    }
}
