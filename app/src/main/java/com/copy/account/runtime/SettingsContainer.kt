/**
 * 职责：设置域状态容器——持有 DataStore 真值 baseSettings 与 appsettings.json 外挂覆盖层
 *       appSettingsOverride，对外暴露生效值 settings（= applyOverride 合并结果）；
 *       冷启动加载（loadInitial）、设置页回调写回（update → persist）、
 *       手动「重新加载配置文件」（reload）全部收敛于此。AccountApp 只负责创建与接线，
 *       不再直接触碰 DataStore 键与覆盖分派。backupTreeUri/backupFolder 不归本容器
 *       （那是 BackupLocationState 的域，刻意不进 AppSettings）。
 * 架构位置：runtime/ 组装层——上承 AccountApp（rememberSettingsContainer 工厂），
 *           下接 data/config/Preferences.kt 的键常量与 AppSettingsStore.kt 的
 *           loadAppSettingsOverride / loadSafAppSettingsOverride / applyOverride。
 * Python 类比：≈ 一个带 __init__(context, scope) 的 SettingsService 类——实例属性持状态、
 *           方法做读写；compose 的 mutableStateOf ≈ 给属性挂订阅通知，读它的 Composable 自动重渲染。
 */
package com.copy.account.runtime

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import com.copy.account.BuildConfig
import com.copy.account.data.config.ACCENT_THEME_SETTING
import com.copy.account.data.config.ALLOW_SCREENSHOTS_SETTING
import com.copy.account.data.config.AUTO_LOCK_SETTING
import com.copy.account.data.config.AppSettingsOverride
import com.copy.account.data.config.BIOMETRIC_SETTING
import com.copy.account.data.config.CLIPBOARD_CLEAR_SETTING
import com.copy.account.data.config.CUSTOM_THEME_JSON_SETTING
import com.copy.account.data.config.CUSTOM_THEMES_SETTING
import com.copy.account.data.config.LANGUAGE_TAG_SETTING
import com.copy.account.data.config.THEME_MODE_SETTING
import com.copy.account.data.config.applyOverride
import com.copy.account.data.config.decodeSavedThemes
import com.copy.account.data.config.encodeSavedThemes
import com.copy.account.data.config.loadAppSettingsOverride
import com.copy.account.data.config.loadSafAppSettingsOverride
import com.copy.account.data.config.settingsDataStore
import com.copy.account.data.model.AppSettings
import com.copy.account.data.model.MAX_AUTO_LOCK_MINUTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置域状态容器。无 ViewModel 哲学下的「状态上提」：状态与改状态的逻辑同住一类，
 * 页面仍是无状态 props + 回调。初始快照参数（initial*）对应 AccountApp 原先从
 * MainActivity 下发的同名参数——冷启动 DataStore 缺键时的回退值，构造时定格，不复读当前值。
 */
internal class SettingsContainer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val initialCustomThemeJson: String,
    private val initialThemeMode: String = BuildConfig.DEFAULT_THEME_MODE,
    private val initialAccentTheme: String = "green",
    private val initialAllowScreenshots: Boolean = false,
    private val onThemeApplied: (AppSettings) -> Unit
) {
    /** DataStore 真值（App 正常设置）。appsettings.json 外挂只读覆盖，不写回。 */
    var baseSettings by mutableStateOf(AppSettings(customThemeJson = initialCustomThemeJson))
        private set
    /** 外挂覆盖层：启动为 null（不主动读文件），仅手动「重新加载配置文件」时置入。 */
    var appSettingsOverride by mutableStateOf<AppSettingsOverride?>(null)
        private set
    /** 生效设置 = DataStore 真值 + 外挂覆盖（文件缺失/解析失败时与真值一致）。 */
    val settings: AppSettings
        get() = applyOverride(baseSettings, appSettingsOverride)
    /** 生效掩码符号；多字符配置取首字符，空串回退默认圆点。 */
    val maskChar: Char
        get() = settings.maskChar.firstOrNull() ?: '•'

    /**
     * 冷启动从 DataStore 读一次设置真值（.data.first() 取首值即退出，不持续订阅，
     * 之后的变更靠 update 增量写回）。初始快照定格在构造参数里：缺键回退用 initial 值
     * 而非 baseSettings 当前值——语义与 AccountApp 原参数回退严格一致。
     * backupTreeUri/backupFolder 的读取不在此（属 BackupLocationState 域）。
     */
    suspend fun loadInitial() {
        val values = context.settingsDataStore.data.first()
        val loadedSettings = AppSettings(
            biometricEnabled = values[BIOMETRIC_SETTING] ?: false,
            autoLockMinutes = (values[AUTO_LOCK_SETTING] ?: 5).coerceIn(0, MAX_AUTO_LOCK_MINUTES),
            themeMode = values[THEME_MODE_SETTING] ?: initialThemeMode,
            accentTheme = values[ACCENT_THEME_SETTING] ?: initialAccentTheme,
            languageTag = values[LANGUAGE_TAG_SETTING] ?: "zh-CN",
            customThemeJson = values[CUSTOM_THEME_JSON_SETTING] ?: initialCustomThemeJson,
            customThemes = decodeSavedThemes(values[CUSTOM_THEMES_SETTING].orEmpty()),
            clipboardClearSeconds = (values[CLIPBOARD_CLEAR_SETTING] ?: 30).coerceIn(0, 86_400),
            allowScreenshots = values[ALLOW_SCREENSHOTS_SETTING] ?: initialAllowScreenshots
        )
        baseSettings = loadedSettings
        // 主题/强调色/自定义主题/截图开关四项回调原为 AccountApp 里四次单独下发，这里合并为
        // 一次 onThemeApplied(生效真值)，由接线方自行分派。
        onThemeApplied(baseSettings)
    }

    /**
     * 设置页回调统一入口：改内存真值 + 异步落盘（等价原先
     * 「baseSettings = baseSettings.copy(...); persistSettings(baseSettings)」两步）。
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        baseSettings = transform(baseSettings)
        persist(baseSettings)
    }

    /** 逐键写回 DataStore；存的值可能被 UI 传歪，写前对范围字段再 coerce 一次。 */
    private fun persist(value: AppSettings) {
        scope.launch {
            context.settingsDataStore.edit {
                it[BIOMETRIC_SETTING] = value.biometricEnabled
                it[AUTO_LOCK_SETTING] = value.autoLockMinutes
                it[THEME_MODE_SETTING] = value.themeMode
                it[ACCENT_THEME_SETTING] = value.accentTheme
                it[LANGUAGE_TAG_SETTING] = value.languageTag
                it[CUSTOM_THEME_JSON_SETTING] = value.customThemeJson
                it[CUSTOM_THEMES_SETTING] = encodeSavedThemes(value.customThemes)
                it[CLIPBOARD_CLEAR_SETTING] = value.clipboardClearSeconds.coerceIn(0, 86_400)
                it[ALLOW_SCREENSHOTS_SETTING] = value.allowScreenshots
            }
        }
    }

    /**
     * 手动「重新加载配置文件」：重读备份目录里的 appsettings.json 并应用到生效值。
     * 文件缺失/无权限/解析失败 → 覆盖层置 null，恢复 DataStore 真值（不区分原因）。
     * 走哪轨取决于 directBackup（由调用方判定后传入），分派留在这层、不下沉到 AppSettingsStore。
     * SAF 轨 treeUri 为空（未授权）时无覆盖，返回 null 覆盖层。
     */
    fun reload(directBackup: Boolean, backupTreeUri: String?, backupFolder: String) {
        val override = if (directBackup) {
            loadAppSettingsOverride(File(Environment.getExternalStorageDirectory(), backupFolder))
        } else {
            val tree = backupTreeUri?.let(Uri::parse)
            if (tree == null) null else loadSafAppSettingsOverride(context, tree, backupFolder)
        }
        appSettingsOverride = override
        val effective = applyOverride(baseSettings, override)
        onThemeApplied(effective)
    }
}

/**
 * 组装工厂：AccountApp 侧唯一入口。remember 保证容器全生命周期单例，
 * LaunchedEffect(Unit) 首帧启动一次冷加载。
 *
 * onThemeApplied 经 rememberUpdatedState 转发：容器在 remember 里只构造一次，直接捕获
 * 首帧的 lambda 会永远停在旧引用（闭包陈旧，AccountApp 里 IdleLock 同款问题）；
 * 换成 State 引用后每次调用现读现用。注意不能写成 rememberUpdatedState { ... }——
 * 尾随 lambda 会被当成「要持有的值」本身（T = () -> Unit），而非每次求值的转发。
 * 参数默认值与 AccountApp 的同名参数默认对齐。
 */
@Composable
internal fun rememberSettingsContainer(
    initialCustomThemeJson: String = "",
    initialThemeMode: String = BuildConfig.DEFAULT_THEME_MODE,
    initialAccentTheme: String = "green",
    initialAllowScreenshots: Boolean = false,
    onThemeApplied: (AppSettings) -> Unit = {}
): SettingsContainer {
    val currentOnThemeApplied by rememberUpdatedState(onThemeApplied)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember {
        SettingsContainer(
            context = context,
            scope = scope,
            initialCustomThemeJson = initialCustomThemeJson,
            initialThemeMode = initialThemeMode,
            initialAccentTheme = initialAccentTheme,
            initialAllowScreenshots = initialAllowScreenshots,
            onThemeApplied = { currentOnThemeApplied(it) }
        )
    }
    LaunchedEffect(Unit) { container.loadInitial() }
    return container
}
