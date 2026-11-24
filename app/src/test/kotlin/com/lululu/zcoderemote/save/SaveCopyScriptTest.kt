package com.lululu.zcoderemote.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入页面的文档起始脚本（方案第 16 条）。这里钉住它的几条硬约束：
 * 常量和原生两边一致；只走唯一一条消息通道；不改页面 DOM、不碰存储、不开任何通用的执行口子。
 */
class SaveCopyScriptTest {

    private val script = SaveCopyScript.build()

    @Test
    fun theChunkSizeMatchesTheNativeSide() {
        // 页面切块和原生记账必须切得一样，这个数是插进去的。
        assertTrue(script.contains("var CHUNK = ${SaveProtocol.CHUNK_BYTES};"))
        assertEquals(256 * 1024, SaveProtocol.CHUNK_BYTES)
    }

    @Test
    fun theSizeLimitMatchesThePageAndNativeAgreement() {
        assertTrue(script.contains("var MAX = ${SaveProtocol.MAX_FILE_BYTES};"))
    }

    @Test
    fun theBlobRememberingCapIsPresent() {
        assertTrue(script.contains("var CAP = ${SaveCopyScript.MAX_REMEMBERED_BLOBS};"))
    }

    @Test
    fun theOnlyChannelIsTheInjectedObject() {
        // 通道名只在取 handler 那一处出现；不许出现 addJavascriptInterface 之类的通用桥。
        assertEquals(1, Regex("messageHandlers\\.${SaveCopyScript.JS_OBJECT_NAME}").findAll(script).count())
        assertFalse(script.contains("addJavascriptInterface"))
    }

    @Test
    fun theScriptWrapsObjectUrlAndInterceptsDownloadClicks() {
        assertTrue(script.contains("URL.createObjectURL"))
        assertTrue(script.contains("URL.revokeObjectURL"))
        assertTrue(script.contains("hasAttribute('download')"))
        assertTrue(script.contains("blob:"))
        assertTrue(script.contains("preventDefault"))
    }

    @Test
    fun theScriptNeverTouchesTheDomOrStorage() {
        // 只挂事件监听、只读属性：没有创建节点、没有写入内容、没有碰存储。
        assertFalse(
            Regex("innerHTML|outerHTML|insertAdjacentHTML|appendChild|removeChild|createElement|document\\.write")
                .containsMatchIn(script),
        )
        assertFalse(Regex("localStorage|sessionStorage|indexedDB|document\\.cookie").containsMatchIn(script))
    }

    @Test
    fun theScriptOpensNoExecutionLoophole() {
        assertFalse(script.contains("eval("))
        assertFalse(script.contains("new Function"))
        assertFalse(script.contains("setTimeout("))
        assertFalse(script.contains("setInterval("))
    }

    @Test
    fun theScriptSendsOnlyControlJsonAndRawArrayBuffers() {
        // 控制消息：JSON 字符串；数据块：ArrayBuffer 原样发。
        assertTrue(script.contains("JSON.stringify"))
        assertTrue(script.contains("arrayBuffer()"))
        assertTrue(script.contains("handler.postMessage(buf)"))
    }

    @Test
    fun theOnlyGlobalNameIsTheSentinel() {
        // 防重复注入的哨兵是唯一新加的全局名；别的都关在 IIFE 里。
        assertEquals(2, Regex("window\\.__zcodeSaveInstalled").findAll(script).count())
        assertFalse(Regex("window\\.[a-zA-Z_$][a-zA-Z0-9_$]*\\s*=").containsMatchIn(script.replace("__zcodeSaveInstalled", "")))
    }

    @Test
    fun theScriptIsWrappedSoFailuresNeverEscape() {
        // 顶部和底部都有 try/catch：脚本出任何错都不打扰页面。
        assertTrue(script.contains("'use strict';"))
        assertTrue(script.contains("} catch (x) {}"))
    }

    @Test
    fun withoutArrayBufferSupportTheScriptOnlyReportsUnsupported() {
        // WebView 收不了 ArrayBuffer 消息时：点击拦得到，但只报一声 unsupported 就撒手，不 preventDefault。
        val degraded = SaveCopyScript.build(arrayBufferSupported = false)
        assertTrue(degraded.contains("var CAN_CHUNK = false;"))
        assertTrue(degraded.contains("ctrl('unsupported');"))
        assertTrue(script.contains("var CAN_CHUNK = true;"))
        // 撒手的位置在 preventDefault 之前。
        assertTrue(
            degraded.indexOf("ctrl('unsupported');") < degraded.indexOf("e.preventDefault();"),
        )
    }
}
