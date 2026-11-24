package com.lululu.zcoderemote.devices

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** 全应用只有这一个 DataStore 实例（同名文件只允许一个实例）。 */
private val Context.devicesDataStore by preferencesDataStore(name = "computers")

/**
 * 电脑列表的存储（方案第 9 条）：App 私有存储里的 Preferences DataStore。
 *
 * - 链接是密码级数据：这个类不写日志、不打异常细节（DataStore 自己的异常只按「读不到」处理）。
 * - 云备份和设备迁移在清单里已整体排除（`allowBackup=false` 加 `dataExtractionRules`），
 *   这里的数据不会离开本机。
 * - 归并规则（同一台电脑重复添加不产生重复项）在 [RemoteLink.merge] 里，纯逻辑、有单元测试。
 */
class ComputerStore(context: Context) {

    /** 改名时的最大长度，防止一条超长名字把界面撑坏。 */
    private val maxNameLength = 64

    private val appContext = context.applicationContext
    private val listKey = stringPreferencesKey("computers")

    /** 电脑列表，按添加先后排列。读不了（文件损坏等 IO 问题）按空列表处理。 */
    val computers: Flow<List<Computer>> = appContext.devicesDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> ComputerCodec.unpack(prefs[listKey]) }

    /**
     * 添加或更新一台电脑（按第 10 节归并：sid 相同就是同一台）。返回归并后的那一条。
     */
    suspend fun upsert(link: String, checked: LinkCheck.Computer, nowMs: Long): Computer {
        var saved: Computer? = null
        appContext.devicesDataStore.edit { prefs ->
            val merged = RemoteLink.merge(ComputerCodec.unpack(prefs[listKey]), link, checked, nowMs)
            prefs[listKey] = ComputerCodec.pack(merged)
            saved = merged.firstOrNull { it.sid == checked.sid }
        }
        return saved ?: Computer(
            sid = checked.sid,
            link = link,
            name = RemoteLink.displayName(checked.name),
            addedAt = nowMs,
            lastUsedAt = 0L,
        )
    }

    /** 给一台电脑改名。空名字或名字全是空白时什么都不做。 */
    suspend fun rename(sid: String, newName: String) {
        val trimmed = newName.trim().take(maxNameLength)
        if (trimmed.isEmpty()) return
        appContext.devicesDataStore.edit { prefs ->
            val list = ComputerCodec.unpack(prefs[listKey]).map {
                if (it.sid == sid) it.copy(name = trimmed) else it
            }
            prefs[listKey] = ComputerCodec.pack(list)
        }
    }

    /** 从列表里删掉一台电脑。列表里没有它时什么都不做。 */
    suspend fun delete(sid: String) {
        appContext.devicesDataStore.edit { prefs ->
            val list = ComputerCodec.unpack(prefs[listKey]).filterNot { it.sid == sid }
            prefs[listKey] = ComputerCodec.pack(list)
        }
    }

    /** 记一次「最后使用」（打开这台电脑的页面时调用）。 */
    suspend fun markUsed(sid: String, nowMs: Long) {
        appContext.devicesDataStore.edit { prefs ->
            val list = ComputerCodec.unpack(prefs[listKey]).map {
                if (it.sid == sid) it.copy(lastUsedAt = nowMs) else it
            }
            prefs[listKey] = ComputerCodec.pack(list)
        }
    }
}
