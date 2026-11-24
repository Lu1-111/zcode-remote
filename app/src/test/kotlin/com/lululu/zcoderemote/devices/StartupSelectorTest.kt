package com.lululu.zcoderemote.devices

import org.junit.Assert.assertEquals
import org.junit.Test

/** 启动时默认打开哪台电脑的逻辑（方案第 13 条）：只有一台直接进，否则进列表。 */
class StartupSelectorTest {

    private fun computer(sid: String, lastUsedAt: Long = 0L, addedAt: Long = 0L) =
        Computer(sid = sid, link = "https://zcode.z.ai/remote/v4?sid=$sid", name = "pc-$sid", addedAt = addedAt, lastUsedAt = lastUsedAt)

    @Test
    fun noComputers_goesToTheList() {
        assertEquals(StartupDestination.ComputerList, selectStartupDestination(emptyList()))
    }

    @Test
    fun exactlyOneComputer_opensItDirectly() {
        val only = computer("s1", lastUsedAt = 42L, addedAt = 7L)
        val destination = selectStartupDestination(listOf(only))
        assertEquals(StartupDestination.OpenTheOnlyComputer(only), destination)
    }

    @Test
    fun moreThanOneComputer_goesToTheList_evenIfOneWasUsedRecently() {
        val destination = selectStartupDestination(
            listOf(
                computer("s1", lastUsedAt = 999L),
                computer("s2", lastUsedAt = 0L),
            ),
        )
        assertEquals(StartupDestination.ComputerList, destination)
    }

    @Test
    fun theDecisionOnlyDependsOnHowManyThereAre() {
        // 链接、名字、时间都不影响：三台里哪怕两台从没用过，也是进列表。
        val three = listOf(computer("s1"), computer("s2"), computer("s3"))
        assertEquals(StartupDestination.ComputerList, selectStartupDestination(three))
    }
}
