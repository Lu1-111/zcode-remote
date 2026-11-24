package com.lululu.zcoderemote.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 电脑列表在 DataStore 里的打包/解包：往返一致；字段里的特殊字符不坏格式；坏记录跳过。 */
class ComputerCodecTest {

    @Test
    fun roundTrip() {
        val computers = listOf(
            Computer("s1", "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1&name=pc", "pc", 100L, 0L),
            Computer("s2", "https://zcode.z.ai/remote/v4?sid=s2", "笔记本电脑", 200L, 300L),
        )
        assertEquals(computers, ComputerCodec.unpack(ComputerCodec.pack(computers)))
    }

    @Test
    fun emptyListPacksAndUnpacks() {
        assertEquals(emptyList<Computer>(), ComputerCodec.unpack(ComputerCodec.pack(emptyList())))
        assertEquals(emptyList<Computer>(), ComputerCodec.unpack(null))
        assertEquals(emptyList<Computer>(), ComputerCodec.unpack(""))
    }

    @Test
    fun percentSignsInFieldsSurvive() {
        // 链接里的 % 不能被误解成转义。
        val computer = Computer(
            sid = "s%1",
            link = "https://zcode.z.ai/remote/v4?sid=%73%31&name=%E7%94%B5%E8%84%91",
            name = "100% 电量",
            addedAt = 1L,
            lastUsedAt = 2L,
        )
        assertEquals(listOf(computer), ComputerCodec.unpack(ComputerCodec.pack(listOf(computer))))
    }

    @Test
    fun separatorsAndNewlinesInFieldsSurvive() {
        val computer = Computer(
            sid = "s1",
            link = "https://zcode.z.ai/remote/v4?sid=s1",
            name = "带\u001E分隔\u001F符和\n换行",
            addedAt = 5L,
            lastUsedAt = 6L,
        )
        assertEquals(listOf(computer), ComputerCodec.unpack(ComputerCodec.pack(listOf(computer))))
    }

    @Test
    fun brokenRecordsAreSkippedNotFatal() {
        val good = Computer("s1", "https://zcode.z.ai/remote/v4?sid=s1", "pc", 1L, 2L)
        val packedGood = ComputerCodec.pack(listOf(good))
        val broken = packedGood + "\u001E" + "only-three\u001Ffields\u001Fhere"
        assertEquals(listOf(good), ComputerCodec.unpack(broken))

        val notANumber = packedGood + "\u001E" + "s2\u001Flink\u001Fname\u001Fabc\u001Fdef"
        assertEquals(listOf(good), ComputerCodec.unpack(notANumber))

        val blankSid = packedGood + "\u001E" + "\u001Flink\u001Fname\u001F1\u001F2"
        assertEquals(listOf(good), ComputerCodec.unpack(blankSid))
    }

    @Test
    fun packIsDeterministicAndOrderPreserving() {
        val computers = listOf(
            Computer("a", "l1", "n1", 1L, 0L),
            Computer("b", "l2", "n2", 2L, 0L),
        )
        assertEquals(ComputerCodec.pack(computers), ComputerCodec.pack(computers))
        val unpacked = ComputerCodec.unpack(ComputerCodec.pack(computers))
        assertEquals(listOf("a", "b"), unpacked.map { it.sid })
        assertTrue(unpacked.zip(computers).all { (a, b) -> a.addedAt == b.addedAt })
    }
}
