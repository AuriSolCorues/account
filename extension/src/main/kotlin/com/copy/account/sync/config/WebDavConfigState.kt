package com.copy.account.sync.config

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.copy.account.sync.data.BridgeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SYNC_WEBDAV_URL = stringPreferencesKey("sync_webdav_url")
private val SYNC_WEBDAV_USER = stringPreferencesKey("sync_webdav_user")
private val SYNC_WEBDAV_PASSWORD_ENC = stringPreferencesKey("sync_webdav_password_enc")
private val SYNC_WEBDAV_LAST_SYNC = longPreferencesKey("sync_webdav_last_sync")
private val SYNC_WEBDAV_LAST_RESULT = stringPreferencesKey("sync_webdav_last_result")
private val SYNC_KEEP_LATEST = intPreferencesKey("sync_keep_latest")

internal const val DEFAULT_KEEP_LATEST = 5
internal const val MAX_KEEP_LATEST = 50

private val Context.pluginDataStore by preferencesDataStore(name = "sync_plugin_config")

internal class WebDavConfigState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val vault: com.copy.account.sync.config.StringVault = com.copy.account.sync.config.KeystoreStringVault()
) {
    var url by mutableStateOf("")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    private var storedPasswordCipher: String? = null

    var lastSyncMillis by mutableStateOf(0L)
        private set
    var lastResult by mutableStateOf("")
        private set

    var keepLatest by mutableIntStateOf(DEFAULT_KEEP_LATEST)
        private set

    var message by mutableStateOf("")
        private set
    var messageIsError by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set

    val hasStoredPassword: Boolean get() = !storedPasswordCipher.isNullOrBlank()

    val configured: Boolean
        get() = url.isNotBlank() && user.isNotBlank() && (password.isNotBlank() || hasStoredPassword)

    suspend fun loadInitial() {
        val values = context.pluginDataStore.data.first()
        url = values[SYNC_WEBDAV_URL].orEmpty()
        user = values[SYNC_WEBDAV_USER].orEmpty()
        lastSyncMillis = values[SYNC_WEBDAV_LAST_SYNC] ?: 0L
        lastResult = values[SYNC_WEBDAV_LAST_RESULT].orEmpty()
        storedPasswordCipher = values[SYNC_WEBDAV_PASSWORD_ENC]
        keepLatest = (values[SYNC_KEEP_LATEST] ?: DEFAULT_KEEP_LATEST).coerceIn(1, MAX_KEEP_LATEST)
        runCatching {
            val ctx = context
            BridgeClient(ctx).fetchWebdavConfig()?.let { (fetchedUrl, fetchedUser, fetchedEnc) ->
                context.pluginDataStore.edit {
                    it[SYNC_WEBDAV_URL] = fetchedUrl
                    it[SYNC_WEBDAV_USER] = fetchedUser
                    it[SYNC_WEBDAV_PASSWORD_ENC] = fetchedEnc
                }
                url = fetchedUrl
                user = fetchedUser
                storedPasswordCipher = fetchedEnc
            }
        }
    }

    fun updateKeepLatest(n: Int) {
        keepLatest = n.coerceIn(1, MAX_KEEP_LATEST)
        scope.launch {
            context.pluginDataStore.edit { it[SYNC_KEEP_LATEST] = keepLatest }
        }
    }

    fun save(onDone: (Result<Unit>) -> Unit = {}) {
        val newCipher = if (password.isBlank()) null else runCatching { vault.encrypt(password) }.getOrElse {
            onDone(Result.failure(it)); return
        }
        scope.launch {
            try {
                context.pluginDataStore.edit {
                    it[SYNC_WEBDAV_URL] = url.trim()
                    it[SYNC_WEBDAV_USER] = user.trim()
                    if (newCipher != null) {
                        it[SYNC_WEBDAV_PASSWORD_ENC] = newCipher
                        storedPasswordCipher = newCipher
                    }
                }
                password = ""
                storedPasswordCipher?.let { cipher ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        BridgeClient(context).pushWebdavConfig(url.trim(), user.trim(), cipher)
                    }
                }
                onDone(Result.success(Unit))
            } catch (e: Exception) {
                onDone(Result.failure(e))
            }
        }
    }

    fun onTestResult(success: Boolean, detail: String) {
        testing = false
        message = if (success) "已连通" else detail
        messageIsError = !success
    }

    fun resolvePassword(): String {
        if (password.isNotBlank()) return password
        val cipher = storedPasswordCipher ?: return ""
        return runCatching { vault.decrypt(cipher) }.getOrDefault("")
    }

    fun recordSyncResult(summary: String) {
        lastSyncMillis = System.currentTimeMillis()
        lastResult = summary
        scope.launch {
            context.pluginDataStore.edit {
                it[SYNC_WEBDAV_LAST_SYNC] = lastSyncMillis
                it[SYNC_WEBDAV_LAST_RESULT] = summary
            }
        }
    }

    fun clearMessage() {
        message = ""
    }
}

@Composable
internal fun rememberWebDavConfigState(): WebDavConfigState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { WebDavConfigState(context, scope) }
    LaunchedEffect(Unit) { state.loadInitial() }
    return state
}
