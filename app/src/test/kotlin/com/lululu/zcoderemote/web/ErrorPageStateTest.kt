package com.lululu.zcoderemote.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断网页的状态。钉住的是一条和链接保密有关的规矩：主框架加载失败之后，WebView 要一直藏着，
 * 直到之后某一次主框架真正加载成功。重试、换入口都不能把它放出来。
 */
class ErrorPageStateTest {

    // ---------------------------------------------------------------- 刚开始

    @Test
    fun nothingIsCoveredAtFirst() {
        val state = ErrorPageState()
        assertFalse(state.covering)
        assertFalse(state.retrying)
    }

    // ---------------------------------------------------------------- 加载失败

    @Test
    fun loadFailed_coversTheWebView() {
        val state = ErrorPageState()
        state.onLoadFailed()
        assertTrue(state.covering)
        assertFalse(state.retrying)
    }

    // ---------------------------------------------------------------- 重试

    @Test
    fun retry_keepsTheWebViewHiddenUntilTheLoadSucceeds() {
        // 模拟器上实测过的漏洞就是这一条：按了「重试」WebView 立刻被放出来，屏幕上是它自带的那张写着整条链接的错误页。
        val state = ErrorPageState()
        state.onLoadFailed()
        state.onRetry()
        assertTrue("重试进行中，WebView 不能露出来", state.covering)
        assertTrue(state.retrying)
        state.onLoadSucceeded()
        assertFalse(state.covering)
        assertFalse(state.retrying)
    }

    @Test
    fun retryThatFailsAgain_staysCoveredAndLetsTheUserRetryAgain() {
        val state = ErrorPageState()
        state.onLoadFailed()
        state.onRetry()
        state.onLoadFailed()
        assertTrue(state.covering)
        assertFalse("这一发已经结束，按钮要能用", state.retrying)
        // 再按一次重试照样算数。
        state.onRetry()
        assertTrue(state.retrying)
    }

    @Test
    fun retryIsOnlyARetryWhileTheErrorPageIsShowing() {
        // 断网页没显示时（比如以后菜单里的「重新加载」）按重试，不该冒出转圈。
        val state = ErrorPageState()
        state.onRetry()
        assertFalse(state.covering)
        assertFalse(state.retrying)
    }

    // ---------------------------------------------------------------- 加载成功

    @Test
    fun loadSucceeded_isTheOnlyWayToUncover() {
        val state = ErrorPageState()
        state.onLoadFailed()
        state.onRetry()
        state.onRetry()
        assertTrue(state.covering)
        state.onLoadSucceeded()
        assertFalse(state.covering)
    }

    @Test
    fun successAfterSuccessChangesNothing() {
        val state = ErrorPageState()
        state.onLoadSucceeded()
        assertFalse(state.covering)
        assertFalse(state.retrying)
    }

    // ---------------------------------------------------------------- 渲染进程挂掉

    @Test
    fun rendererCrash_replacesTheWebView_soNothingNeedsCovering() {
        // 换进来的是新的 WebView，里面没有旧的那张错误页。
        val state = ErrorPageState()
        state.onLoadFailed()
        state.onRetry()
        state.onWebViewReplaced()
        assertFalse(state.covering)
        assertFalse(state.retrying)
    }

    @Test
    fun failureAfterTheWebViewWasReplaced_coversAgain() {
        val state = ErrorPageState()
        state.onLoadFailed()
        state.onWebViewReplaced()
        state.onLoadFailed()
        assertTrue(state.covering)
    }
}
