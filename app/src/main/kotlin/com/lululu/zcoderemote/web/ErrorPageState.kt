package com.lululu.zcoderemote.web

/**
 * 断网页的状态（方案第 15 条 ⑤）：断网页盖不盖着，用户按下的「重试」结束了没有。
 *
 * 纯逻辑，不引用任何 `android.*`，在电脑上就能跑单元测试。
 *
 * 这里钉住的是一条和链接保密有关的规矩：**主框架加载失败之后，WebView 要一直藏着，直到之后某一次主框架真正加载成功。**
 * WebView 加载失败时自己会画一张错误页，把整条链接连同查询串写在上面。App 的断网页只是盖在它上面，
 * 所以不能在用户按「重试」或换一条链接时就把 WebView 放回来，那时新页面还没出来，露出来的正是那张错误页
 * （模拟器上实测过：按了「重试」之后，屏幕上是「The webpage at https://…?…&sid=…」）。
 * 所以放回来这件事只有 [onLoadSucceeded] 做，重试、换入口都不做。
 */
class ErrorPageState {

    /** 断网页盖在上面，WebView 必须藏着。 */
    var covering: Boolean = false
        private set

    /** 用户按了「重试」，这一发还没结束：按钮先禁用，转个圈。 */
    var retrying: Boolean = false
        private set

    /** 主框架加载失败：盖住。重试这一发结束了（没成功）。 */
    fun onLoadFailed() {
        covering = true
        retrying = false
    }

    /** 主框架真正加载成功：这是唯一把 WebView 放回来的地方。 */
    fun onLoadSucceeded() {
        covering = false
        retrying = false
    }

    /** 用户按了「重试」。只在断网页盖着的时候才算一次重试。 */
    fun onRetry() {
        if (covering) retrying = true
    }

    /** 渲染进程挂了，换的是一个新的 WebView，里面没有旧的那张错误页。 */
    fun onWebViewReplaced() {
        covering = false
        retrying = false
    }
}
