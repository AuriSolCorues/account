# 账号本子：自用版开发计划

## 1. 目标与边界

开发一款仅供个人使用的 Android 账号、密码和双因素认证（2FA）管理应用。重点是本地加密、便捷解锁、受控剪贴板，以及用户主动触发的完整 `.acc` 加密备份。

不做会员、广告、应用账号登录、自建服务器、数据收集、行为分析、静默双向同步或网页自动填充。

## 2. 第一版功能范围

### 账号管理

- 分组与筛选：左栏固定顺序为“默认、动态密码、自定义分组”；所有分组名称均可自定义。
- “默认”和“动态密码”使用稳定的系统标识，名称可改但位置和行为不可改变：默认显示没有任何自定义分组的账号，动态密码显示所有已配置 TOTP 的账号。
- 新建账号时沿用当前选中的普通分组；选中默认时保持未分组，选中动态密码时不建立普通分组关联，只有配置 TOTP 后才进入动态密码分组。
- 用户可新增、改名、删除和拖动排序自定义分组；删除账号最后一个自定义分组后自动回到默认分组。
- 长按分组进入批量转移模式：列表锁定该组账号供勾选（点卡片选/取消），点侧栏其他分组为目标并弹确认；动态密码组只可作源不可作目标；移入「默认（未分组）」会清空账号全部自定义分组归属，移入自定义组保留其他组归属；长按账号可选择作为模板新建或复制该账号的全部可复制字段。
- 账号列表、详情、新增、编辑、删除和以既有条目为模板新建。
- 每条账号可有任意数量的自定义字段，例如账号、密码、邮箱、网址、恢复码、备注；编辑页只提供“新增字段”，在单条字段编辑窗口内切换隐藏状态。
- 搜索跨所有账号查找名称、分组和字段内容；后续支持中文、拼音和拼音首字母搜索。
- 复制字段内容；密码和验证码默认在设定时间后自动清除剪贴板。
- 点击账号条目打开底部速览面板，点击面板外空白处或向下滑关闭；不因快速复制而进入详情页。
- 用户名和动态验证码的整行文本及复制按钮均可直接复制。密码默认掩码，点击掩码文本显示明文，点击明文文本复制；密码复制按钮始终直接复制明文。关闭速览后密码恢复掩码。

### 2FA（TOTP）

- 每条账号可保存一个基于时间的一次性密码（TOTP）；如需第二个 2FA，新增独立账号条目保存。
- 支持 6 或 8 位验证码、30 秒等标准周期，以及 SHA-1、SHA-256、SHA-512 算法参数。
- 支持手动输入 Google Authenticator 常见 `otpauth://`/Base32 TOTP，以及 Steam Guard `shared_secret`/Base64；也可在编辑页「扫码」用相机直接扫 otpauth 二维码回填。标准 TOTP 默认 SHA256，支持 SHA1/SHA256/SHA512、5/6/8 位快捷或自定义 1-10 位和 1-300 秒周期，Steam 使用专用 5 字符算法。
- 支持 HOTP（RFC 4226 事件型，`otpauth://hotp/...`）：可配置算法与位数（同上），无周期；账号存 `totpCounter` 计数器（TOTP/Steam 恒为 0），**复制验证码即 +1 并持久化**（vault/`.acc` 均有该字段，旧文件反序列化默认为 0，向后兼容）。编辑页服务提供下拉可切 TOTP / Steam / HOTP；Hotp 显示「当前计数」输入。
- 详情页显示当前验证码、剩余时间和下一周期状态，并可一键复制验证码。
- 恢复码作为普通敏感字段保存；不尝试替用户自动登录或提交验证码。

### 解锁与界面安全

- 首次启动时设置主密码；主密码支持中文、英文、数字和符号。
- 使用主密码或设备已注册的指纹/人脸解锁。
- 已解锁后可在设置中修改主密码；输入新密码和确认密码即可，不重复要求旧密码。
- 二级页面绑定 Android 返回键，按页面层级返回设置/首页；首页返回保持系统默认行为。
- 进入后台或无操作超过默认 5 分钟时自动锁定；时长可配置。
- 默认设置安全窗口标记，禁止截图、录屏和最近任务预览显示敏感内容；允许截图开关打开后立即解除该限制。
- 不设置输错次数限制或故意延迟，保持个人使用时的解锁效率。

### 剪贴板

- 密码和 2FA 验证码复制后默认 30 秒自动清除，支持关闭、预设值或自定义 1-86400 秒。
- 清除前确认剪贴板仍是本应用复制的同一内容，避免删除用户后来复制的文字。
- 自动清除只处理本应用写入的敏感内容，不读取或记录其他应用的剪贴板内容。

### 暂不实现的功能

- 当前版本不实现安全日志、二维码扫描和 WebDAV。
- 这些功能不出现在当前页面入口、`.acc` 数据结构或验收范围中。

### 主题

- 设置中提供始终深色、始终浅色、跟随系统；默认值由 `gradle.properties` 的 `account.defaultThemeMode` 配置（`dark`/`light`/`system`，当前为 `light`），经 `BuildConfig.DEFAULT_THEME_MODE` 注入 `MainActivity`/`AccountApp`/`AppSettings` 三处默认值，切换后立即生效。已安装用户的 DataStore 选择优先。
- 提供绿色/蓝色两套内置配色，以及可导入的 JSONC 自定义主题（`customThemeJson`/`customThemes`，解析见 `ui/theme/Theme.kt`）。
- 语言默认使用 `zh-CN`，保留 `languageTag` 供后续增加语言资源。
- 界面只使用文字、颜色、Compose 组件和 Material 矢量图标，不引入图片资源。
- 掩码符号默认 `•`，可通过 `appsettings.json` 外挂的 `maskChar` 覆盖（多字符取首字符）；编辑页密码框带「显示/隐藏」明文切换。

### `.acc` 导入与导出

- 仅支持一种完整加密备份：扩展名为 `.acc`，文件内容为 JSON。
- `.acc` 根节点严格只有两个同级字段：`passwordVault` 和 `appSettings`。不兼容旧版 `ACCOUNTBOX_BACKUP_13` 密文，也不再导出旧版明文格式。
- `passwordVault` 是 Base64 加密数据，内部打包格式版本、KDF 参数（PBKDF2-HMAC-SHA256、300,000 次迭代）、随机盐、随机 IV、认证标签及加密后的自定义分组、账号、自定义字段、隐藏字段标记和单个 TOTP；默认分组和动态密码分组均由账号状态派生，不单独保存。
- `appSettings` 是可读、可手工编辑的软件设置，保存主题模式、配色、语言标识、掩码符号、自定义主题、自动锁定时长、剪贴板清除时长和允许截图开关；不做完整性校验。
- 导出不显示密码输入框，直接复用当前主密码派生的密钥；导出前提示用户记住主密码。
- 导入从 `backups/account` 文件列表选择 `.acc` 文件，再输入该备份设置的密码（4-20 个 Unicode 字符，可含中文、英文、数字和符号），通过 AES-GCM 认证后才允许恢复；不校验本机当前主密码。
- 导入成功前只在内存中解析，用户确认后整体替换当前密码库与软件设置；失败不修改现有数据。
- 存储授权按系统版本双轨：API ≥ 30（Android 11+）申请「所有文件访问」（MANAGE_EXTERNAL_STORAGE），备份固定直写 `内部存储/backups/account`，不弹目录选择器（部分 OEM 的 SAF 目录选择器被锁定不可用）；API < 30 保留 SAF `OpenDocumentTree` 目录授权，在授权目录下自动创建并固定使用 `backups/account`。
- 剪贴板由系统 `ClipboardManager` 写入；清除时间支持关闭、预设秒数和 1-86400 秒自定义输入。Android 应用写入前台剪贴板不需要额外运行时权限。
- 设置提供“允许截图”开关，默认关闭；切换后立即控制 `FLAG_SECURE`。
- 备份包含密码库与软件设置；不包含设备 Keystore 密钥、生物识别状态或任何日志。

```json
{
  "passwordVault": "Base64 encrypted payload",
  "appSettings": {
    "themeMode": "light",
    "accentTheme": "green",
    "languageTag": "zh-CN",
    "maskChar": "•",
    "customThemeJson": "",
    "customThemes": [],
    "autoLockSeconds": 300,
    "clipboardClearSeconds": 30,
    "allowScreenshots": false
  }
}
```

## 3. 技术架构、存储与依赖

### 平台与依赖

- 最低支持 Android 9（API 28），以便直接使用系统的 `PBKDF2WithHmacSHA256`、`AES/GCM/NoPadding`、`GCMParameterSpec` 和 `AndroidKeyStore`；不引入任何第三方加密 JAR。
- 使用 Jetpack Compose、Material 3、Lifecycle；页面导航用 `AccountApp` 内的 sealed `AppPage` + `when` 手工路由，不引入 Navigation Compose；依赖用 `remember { SecureVaultStore(context) }` 手工组装，不引入 Hilt 或其他依赖注入框架。
- 使用 AndroidX Biometric 提供指纹/人脸认证，DataStore Preferences 保存非敏感软件设置，kotlinx-serialization-json 序列化密码库和 `.acc` 文件。
- 不引入二维码扫描、WebDAV 或其他网络依赖，控制安装包大小。
- 不使用 Room、SQLite、SQLCipher、Bouncy Castle 或其他数据库/加密库，控制安装包大小。

### 代码目录（已整理）

```text
com.copy.account/
├── MainActivity.kt        # Activity、窗口安全、主题装配
├── AccountApp.kt          # 全局状态、页面路由、SAF 回调
├── page/                  # 只放页面：Unlock、Home、Detail、Edit、Groups、Settings、Backup
├── ui/
│   ├── components/        # 只放可复用控件、弹窗、底部框与 ComponentsPreview
│   └── theme/             # 颜色、字体、主题
├── security/              # 独立安全区：Crypto、VaultStore、AccCodec、Totp、Clipboard
├── data/
│   ├── model/             # Account、Group、AccountField、AppSettings、PersistedVault
│   ├── config/            # Preferences、AppSettingsStore
│   └── backup/            # BackupFiles（SAF 目录与文件操作）
└── navigation/            # AppPage；仅页面路由定义
```

- `page` 只组装页面；`ui/components` 只提供可复用控件，不放页面业务。
- `security` 不依赖 Compose 或页面；全部加密、密码库、备份编解码和敏感剪贴板代码集中在此。
- 本次仅移动文件、修正包名与 import；功能行为不因目录整理而改变。

### 当前实现文件与数据流

- `app/src/main/java/com/copy/account/MainActivity.kt`：`FragmentActivity`、edge-to-edge 布局、`FLAG_SECURE` 截图保护、主题状态装配；首次启动主题取 `BuildConfig.DEFAULT_THEME_MODE`（来自 `gradle.properties`）。
- `app/src/main/java/com/copy/account/AccountApp.kt`：应用状态、解锁会话、sealed `AppPage` 页面路由、账号管理、剪贴板复制和 SAF 回调；复制敏感字段后由 `ClipboardManager` 写入并定时校验清除。
- `app/src/main/java/com/copy/account/security/VaultStore.kt`：`filesDir/vault.bin` 加密读写、主密码 KEK/DEK、AndroidKeyStore 生物识别包装、改密与解锁。
- `app/src/main/java/com/copy/account/data/config/Preferences.kt`：DataStore 保存主题/配色/自定义主题/自动锁定/剪贴板清除/截图开关/备份目录 URI。
- `app/src/main/java/com/copy/account/security/AccCodec.kt`：`.acc` 的 JSON 外层、PBKDF2/AES-GCM 导出与导入校验；只返回内存结果，确认后才由 `AccountApp` 保存。
- `app/src/main/java/com/copy/account/security/Totp.kt`：TOTP 与 Steam Guard 验证码计算（RFC 6238）。
- `app/src/main/java/com/copy/account/ui/theme/Theme.kt`：深色/浅色/系统模式、绿色/蓝色配色和 JSONC 自定义主题解析。
- `app/src/main/java/com/copy/account/data/config/AppSettingsStore.kt`：`appsettings.json` 外挂覆盖层。与 `vault.bin` 同级，文件缺失或解析失败返回 null（不生效），`applyOverride` 逐字段合并（只覆盖非 null 键）；App 只读不写，启动不主动读，仅设置页手动「重新加载配置文件」加载。
- `app/src/main/java/com/copy/account/ui/components/Rows.kt`：设置行/开关行（SettingsHeader、SettingsRow、SettingsSwitchRow、通用 SwitchRow）、`DangerButton`（红字危险按钮）、`SurfaceCard`（扁平卡，surface 色 0dp 阴影）。`FieldTextBox.kt` 统一文本/密码输入、`TextActionButton` 提供文字按钮图标 slot；`FieldEditorSheet.kt` 负责单条字段编辑。
- `app/src/main/java/com/copy/account/ui/components/Dialogs.kt`：`DeleteConfirmDialog`（红「删除」+「取消」）与 `TextInputDialog`（单输入框，账号编辑页新增分组共用，内部持文本）。
- `app/src/main/java/com/copy/account/ui/components/Panels.kt`、`Sheet.kt`：账号速览/操作底部面板、随机密码生成器、底部面板通用行（AppBottomSheet、ActionSheetRow）。
- `app/src/main/java/com/copy/account/ui/components/ComponentsPreview.kt`：组件集中预览，Design/Split 视图查看全部 UI 组件。
- 掩码与显隐：`AppSettings.maskChar`（默认 `•`）→ `AccountApp` 派生生效 `maskChar`（多字符取首字符）→ Home/Detail/Edit → `SensitiveValueRow`/`AccountFieldItem`/`PasswordField`；掩码固定 8 位不泄真实长度。
- 组件复用：Home/Edit/Groups 删除确认框、GroupManage 的新增/直接编辑分组底部框、AccountEdit 新增分组对话框、Edit 两步验证开关行与密码框、Home 账号卡、Backup 备份卡、AccountActionSheet 删除按钮，均改用上述通用组件。
- `app/src/main/java/com/copy/account/page/*`：unlock、home、edit、detail、groups、settings、backup 七个页面，均带 `@Preview` 静态预览（跟随 `gradle.properties` 默认主题）。
- `plan/REFERENCE_ACCOUNT_APP.md`：手机端 `com.wei.account` 的真机交互研究记录；只用于初步验证，不作为最终视觉模板，不复制图标、源码或旧加密。
- 真机截图中的像素仅用于比例测量；首页分栏、底部面板和编辑内容均按窗口约束自适应，不固定某一台手机的分辨率。
- 数据流：主密码 → KEK/本地 DEK → 内存密码库；首次进入备份页：API ≥ 30 授予「所有文件访问」→ 固定目录 `内部存储/backups/account`，API < 30 走 `OpenDocumentTree` 授权 → 自动创建 `backups/account`；导出时读取当前 KEK → 生成 `.acc` → 在固定目录创建文件；备份管理页列出目录中的 `.acc` → 输入该文件密码并验证解密 → 用户确认 → 原子保存。

### 本地文件存储

- 密码库不使用数据库。`filesDir/vault.bin` 保存 AES-256-GCM 加密后的完整 `PersistedVault` JSON；默认分组和动态密码分组按账号状态实时派生。
- DataStore Preferences 保存主题、配色、自定义主题、自动锁定和剪贴板清除时长等非敏感设置；导出时组装为 `.acc` 的明文 `appSettings`。
- 每次保存密码库或本机敏感设置时，先完整序列化并加密，再用 `android.util.AtomicFile` 原子替换旧文件；中断写入不破坏现有密码库。
- `filesDir/appsettings.json` 为可选「外挂」配置（与 `vault.bin` 同级，支持 JSONC 注释）：只写想覆盖的键，启动不主动读，仅设置页手动「重新加载配置文件」时加载并覆盖 DataStore 生效值；文件缺失/解析失败/删除均恢复正常，App 只读不写。
- 解锁后将 `PersistedVault` 解密到内存中完成几百条账号的搜索、筛选、排序和去重；锁定后关闭会话并清除 DEK 与敏感内存引用。

## 4. 加密与数据设计

### 本地数据

1. 首次设置主密码时生成随机 256 位数据加密密钥（DEK）。
2. 主密码以 UTF-8 编码并统一作 NFC Unicode 规范化，兼容中文输入。
3. 每个密码库生成独有随机盐，使用 PBKDF2-HMAC-SHA256 派生密钥加密密钥（KEK）；迭代次数固定为 300,000（常量 `DEFAULT_PASSWORD_ITERATIONS`），以可接受的设备解锁时间选定，并记录在本地与 `.acc` 元数据中。
4. 使用 AES-256-GCM 以 KEK 加密 DEK；账号、自定义分组、单个 2FA 秘钥和隐藏字段标记均使用 DEK 加密。导出的 `passwordVault` 使用同一主密码派生的 KEK 加密。
5. 每次 AES-GCM 加密使用新的随机 IV 并校验认证标签；发现篡改即拒绝读取。
6. TOTP 仅在已解锁的内存会话中按 RFC 6238 计算；锁定时清除内存中的 DEK、密钥和明文数据。

### 生物识别解锁

- 使用 Android Keystore 中不可导出的 AES 密钥加密同一个 DEK。
- 该 Keystore 密钥要求每次使用时完成指纹或人脸认证。
- 生物识别密钥仅当前设备可用；换设备、重装应用或生物识别凭据失效时，使用主密码解锁后重新启用。

### 文件授权与备份

- API ≥ 30 通过「所有文件访问」权限直接读写固定的 `内部存储/backups/account`；API < 30 只通过 Android Storage Access Framework 读写，授权范围由用户在系统选择器中明确选择的目录决定，目录 URI 保存在 DataStore，应用只访问其中的 `backups/account`。
- 禁用 Android 自动备份，避免系统备份产生不受本应用控制的数据副本。

## 5. 页面规划

页面结构与交互细节见 [FRONTEND_DESIGN.md](FRONTEND_DESIGN.md)。

1. 解锁页：主密码输入、指纹/人脸解锁。
2. 首页：左侧纵向分组（长按进入批量转移勾选模式）、右侧紧凑账号卡片、搜索、新增和设置入口。
3. 分组管理页：默认/动态密码名称修改，以及自定义分组的新增、改名、删除与拖动排序。
4. 账号速览面板：快速复制字段与 2FA；点击空白处或下滑关闭。
5. 账号详情页：字段和 2FA 验证码，不展示额外操作记录。
6. 账号编辑页：两列字段、单条字段编辑窗口、排序/删除/随机密码/新增字段工具条及单个 2FA（详见 §8）。
7. 设置页：安全、修改主密码、剪贴板、主题和导入导出。
8. 备份文件管理页：授权并创建 `backups/account`、导出 `.acc`、列出/刷新/删除备份，以及输入备份文件密码后恢复。

## 6. 实施顺序

> 以下 `[x]` 仅表示现有旧版功能；§3 的目录重排和 §8 的字段体系重构独立待完成。

1. [x] 建立 Compose 应用框架、导航、主题和前端设计中的基础组件。
2. [x] 实现安全窗口、自动锁定、主密码与生物识别解锁。
3. [x] 接入本地加密数据层，建立账号、字段、分组和 TOTP 的数据模型。
4. [x] 完成默认/自定义分组与字段的账号管理、跨账号搜索、按当前分组新建、模板新建和随机密码。
5. [x] 实现 TOTP 计算、验证码展示与受控复制。
6. [x] 实现剪贴板定时清除。
7. [x] 完成 `.acc` 加密导入导出与 SAF 文件授权。
8. [ ] 在真机复验主题切换、密码校验和备份恢复流程，并记录日期与结果。
9. [x] 抽取通用组件（开关/卡片/删除编辑对话框）、编辑页密码显隐切换、可配置掩码符号，并接入 `appsettings.json` 外挂手动加载。
10. [x] 按 §3 整理页面、控件与安全代码目录；按 §8 落地字段体系。已通过 `:app:assembleDebug --offline`；真机验证仍见第 8 项。

## 7. 验收标准

- 未解锁时不能读取任何账号或 TOTP 内容。
- 指纹和正确主密码均可解锁；中文主密码可正常创建和解锁。
- TOTP 与主流验证器在相同时间、密钥和参数下生成相同验证码。
- 截图保护关闭时，截屏、录屏和最近任务预览不显示账号内容；打开允许截图后按用户选择执行。
- 已复制的密码或验证码会在设定时间后清除，且不会误清除后续复制的内容。
- 本地 `vault.bin` 和 `.acc` 中的 `passwordVault` 均不能以明文直接读取；`.acc` 根节点只包含 `passwordVault` 和 `appSettings`。
- 导出无需输入密码并提示记住主密码；导入要求输入该文件对应的 4-20 字符备份密码，错误密码或认证失败时拒绝恢复且不改动当前数据。
- API ≥ 30：授予「所有文件访问」后可直接在 `内部存储/backups/account` 完成 `.acc` 保存和读取；API < 30：通过系统目录选择器授权后在 `backups/account` 完成，不申请其他广泛存储权限。
- 错误主密码、Base64 损坏或认证标签校验失败时，拒绝导入密码库；手工修改有效的 `appSettings` 后可正常应用设置。
- 所有分组均可改名；默认和动态密码改名后仍保持固定位置及系统行为。
- 从自定义分组新建账号会自动加入该分组；从默认分组新建账号保持未分组；从动态密码分组新建账号只有在配置 TOTP 后才显示在该分组。
- 删除账号最后一个自定义分组后，账号显示在默认分组；动态密码与自定义分组组合筛选时返回交集结果。


## 8. 编辑页字段体系重构（已实现，待真机复验）

> 已接入 `page/AccountEditScreen.kt` 与 `ui/components/FieldTextBox.kt`、`FieldEditorSheet.kt`；
> 本节仍作为交互约束，真机体验与拖动手感待复验（详见 [FRONTEND_DESIGN.md §4](FRONTEND_DESIGN.md)）。

- 字段行统一“两列”:左键名(可点)+ 右值框;用户名/密码仍为顶层固定字段,不进字段列表。
- 键名编辑:点左侧键名 → 底部滑出“单条字段编辑窗口”(复用 `AppBottomSheet`,顶栏标题 + 右上「保存」);
  新增字段也用同一个窗口(键名留空)。
- 窗口内容:键名输入 + (仅自定义字段)「隐藏文本」开关/滑块 + 内容输入(开则掩码、可显隐)+
  「随机」按钮仅在隐藏文本开时出现。
- 用户名/密码改为保存“可选显示名标签”(`usernameLabel`/`passwordLabel`,空=默认文案),兼容旧 vault JSON。
- 文本/密码已收敛为 `FieldTextBox`(文本为基座、隐藏文本追加 `PasswordVisualTransformation` 显隐尾按钮；用户名不提供清空按钮)，替换原 `PasswordField`/`AccountFieldItem`。
- `TextActionButton` 已统一文字按钮，并预留可选前置/后置图标 slot；保留文字、不做纯图标按钮。图标接入时附内容描述，不改变既有按钮语义或点击区域。
- 工具条(字段列表与两步验证之间)= 排序 / 删除 / 随机密码 / ＋新增字段;排序(☷ 长按拖动)与删除
  (每行删除钮,整行删)为互斥模式;随机密码回填“当前字段”(明文/隐藏均可,用户名除外);行内常驻“删除/随机”
  撤掉,ComponentsPreview 同步去样板。
