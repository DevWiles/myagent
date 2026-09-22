package dev.wiles.myagent.client.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 只存一样东西：最近一次的配对 URL——重开 app 免得再扫一遍。
 *
 * <p>明文存。token 随后端进程生灭（协议 1.1），存下来的这份多半已经过期，
 * 泄漏了也只是一个连不上的地址；为它引一套加密存储是给自己演戏。
 * 真正的门禁是「后端重启就换 token」这件事本身。
 */
private val Context.dataStore by preferencesDataStore(name = "myagent")

private val KEY_LAST_URL = stringPreferencesKey("last_pairing_url")

class Prefs(private val context: Context) {

    val lastUrl: Flow<String?> = context.dataStore.data.map { it[KEY_LAST_URL] }

    suspend fun saveUrl(url: String) {
        context.dataStore.edit { it[KEY_LAST_URL] = url }
    }
}
