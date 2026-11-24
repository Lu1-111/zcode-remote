package com.lululu.zcoderemote.web

/**
 * 返回键的决策（方案第 15 条 ③，约定见 docs/ARCHITECTURE.md 第 7 节）。
 *
 * 纯逻辑，不引用任何 `android.*`，在电脑上就能跑单元测试。
 */

/** 页面当前在哪一页。 */
enum class MobilePage {
    /** 聊天页：`data-mobile-page="chat"`。 */
    CHAT,

    /** 页面自己的首页：`data-mobile-page="home"`。 */
    HOME,

    /** 页面里没有这个属性（登录页、授权页、设备列表、错误页等），或者属性值不是 chat、home。 */
    ABSENT,

    /** 读属性的那段脚本没有返回结果，页面在哪一页不知道。 */
    UNKNOWN,
}

/** 按返回键之后去哪。 */
enum class BackTarget {
    /** 网页后退：`webView.goBack()`。 */
    WEB_BACK,

    /** 回原生的电脑列表。M2 还没有电脑列表，由界面层决定（现在是回桌面）。 */
    COMPUTER_LIST,

    /** 回桌面：`moveTaskToBack(true)`。 */
    DESKTOP,
}

/**
 * 第 7 节那张表。
 *
 * 「没有这个属性」不后退：登录流程会在历史记录里留下授权页和回跳页，从登录页后退会走回那些已经过时的页面。
 * 「脚本没有返回结果」才退回普通浏览器的做法。
 */
fun decideBack(page: MobilePage, canGoBack: Boolean): BackTarget = when (page) {
    MobilePage.CHAT -> if (canGoBack) BackTarget.WEB_BACK else BackTarget.COMPUTER_LIST
    MobilePage.HOME, MobilePage.ABSENT -> BackTarget.COMPUTER_LIST
    MobilePage.UNKNOWN -> if (canGoBack) BackTarget.WEB_BACK else BackTarget.DESKTOP
}

/** 读页面当前在哪一页的脚本，以及把脚本结果翻译成 [MobilePage]。 */
object BackProbe {
    /**
     * 只读，不改页面。页面没有 `data-mobile-page` 这个属性时得到空串。
     * 4.0.0 的页面同一时刻只渲染 `<section data-mobile-page="home">` 或 `"chat"` 中的一个。
     */
    const val SCRIPT: String =
        "document.querySelector('[data-mobile-page]')?.getAttribute('data-mobile-page') || ''"

    /** evaluateJavascript 把字符串结果按 JSON 编码后交回来：`"chat"`、`"home"`、`""`。 */
    private val RESULT = Regex("^\"([A-Za-z0-9_-]*)\"$")

    /**
     * 把 [SCRIPT] 的执行结果翻译成页面位置。
     *
     * 结果不是字符串（脚本出错、返回 `null`、WebView 已经不能用），算 [MobilePage.UNKNOWN]：
     * 这时不知道页面在哪，按「能后退就后退，否则回桌面」处理。
     */
    fun pageFromScriptResult(result: String?): MobilePage {
        if (result == null || result == "null") return MobilePage.UNKNOWN
        val value = RESULT.matchEntire(result)?.groupValues?.get(1) ?: return MobilePage.UNKNOWN
        return when (value) {
            "chat" -> MobilePage.CHAT
            "home" -> MobilePage.HOME
            "" -> MobilePage.ABSENT
            // 有这个属性，但值不认识：页面改版了。不后退，按首页处理。
            else -> MobilePage.ABSENT
        }
    }
}
