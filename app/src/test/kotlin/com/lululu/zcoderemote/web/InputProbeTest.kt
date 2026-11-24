package com.lululu.zcoderemote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 软键盘弹出后把输入框滚进可见区域的脚本：只滚动，不改页面。 */
class InputProbeTest {

    private val script = InputProbe.REVEAL_FOCUSED_SCRIPT

    @Test
    fun onlyScrollsTheFocusedEditableElement() {
        assertTrue(script.startsWith("(function(){try{"))
        assertTrue(script.contains("document.activeElement"))
        assertTrue(script.contains("e.scrollIntoView({block:'center'"))
    }

    @Test
    fun neverTouchesThePageBeyondScrolling() {
        // 不写 DOM、不写存储、不发请求、不建桥：出不了数据，也留不下痕迹。
        val forbidden = Regex("innerHTML|outerHTML|appendChild|removeChild|insertBefore|setAttribute|removeAttribute" +
            "|localStorage|sessionStorage|document\\.cookie|fetch\\(|XMLHttpRequest|sendBeacon|WebSocket|eval\\(|postMessage|window\\.open")
        assertFalse(forbidden.containsMatchIn(script))
        // 脚本里没有任何地址。
        assertFalse(script.contains("http"))
    }

    @Test
    fun givesUpQuietlyWhenThePageDoesNotCooperate() {
        // 整段包在 try/catch 里：页面不配合就得到空串，不抛异常。
        assertTrue(script.endsWith("}catch(x){return ''}})()"))
    }
}
