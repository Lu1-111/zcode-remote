package com.lululu.zcoderemote.devices

/**
 * 冷启动时先去哪一屏（方案第 13 条：启动时若只有一台电脑，直接进入它的页面；否则进入列表）。
 *
 * 纯逻辑，不引用任何 `android.*`，在电脑上就能跑单元测试。
 */
sealed interface StartupDestination {

    /** 去电脑列表：一台都没有时是引导页，不止一台时是列表。 */
    data object ComputerList : StartupDestination

    /** 只有一台电脑：直接进它的页面。 */
    data class OpenTheOnlyComputer(val computer: Computer) : StartupDestination
}

/**
 * 只按「保存了几台电脑」决定：一台直接进它的页面；零台（还没有电脑，先看引导页）
 * 和多台（要让用户挑）都进列表。链接、名字、使用时间都不影响这个决定。
 */
fun selectStartupDestination(computers: List<Computer>): StartupDestination =
    if (computers.size == 1) StartupDestination.OpenTheOnlyComputer(computers.single())
    else StartupDestination.ComputerList
