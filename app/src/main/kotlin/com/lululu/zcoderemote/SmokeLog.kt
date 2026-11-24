package com.lululu.zcoderemote

import android.util.Log

/**
 * 冒烟日志。约定见 docs/ARCHITECTURE.md「冒烟日志约定」。
 *
 * logcat 谁连上 adb 都看得到，所以这里从方法签名上就不给传整条 URL 的机会：只收主机名和状态码。
 */
object SmokeLog {
    const val TAG = "ZCodeRemote"

    private const val UNKNOWN_HOST = "unknown"
    private const val UNKNOWN_STATUS = "unknown"

    /**
     * 主框架页面加载完成时调用（只对主框架，子框架不要调）。
     *
     * [statusCode] 是主文档真实的 HTTP 状态码；取不到就传 null，打成 `status=unknown`，不要猜一个数字。
     */
    fun pageLoaded(host: String?, statusCode: Int?) {
        Log.i(TAG, pageLoadedLine(host, statusCode))
    }

    internal fun pageLoadedLine(host: String?, statusCode: Int?): String =
        "pageLoaded host=${safeHost(host)} status=${statusCode ?: UNKNOWN_STATUS}"

    // 只认纯主机名。带了路径、查询串、用户信息或端口的，一律打成 unknown。
    private val HOST_REGEX = Regex("[A-Za-z0-9.-]+")

    private fun safeHost(host: String?): String =
        if (host != null && HOST_REGEX.matches(host)) host else UNKNOWN_HOST
}
