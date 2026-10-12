/**
 * 职责：系统自动填充服务（AutofillService）——响应其他应用的填充请求与保存请求。
 *       填充路径：解析目标页面的表单结构 → 从 AutofillBridge 快照里匹配账号 →
 *       为每个匹配账号构建一个「需生物识别/锁屏凭据认证」的数据集（Dataset）；
 *       保存路径：从表单结构提取用户名/手机/邮箱/密码 → 错误文案网关拦截失败页 →
 *       与快照去重后经 SecureVaultStore 解密-改-加密回写 vault.bin → 刷新桥快照。
 * 架构位置：service/ 系统入口组件（manifest 注册，须 public）。是 AutofillBridge 的唯一
 *           消费方之一；写库复用 security/SecureVaultStore，域匹配复用 util/DomainCleaner。
 * 数据通道（AutofillBridge 契约）：
 *   - AutofillBridge.active == false（开关关 / 未解锁 / 已锁定）→ 填充走自愈分支：若
 *     生物识别包装 DEK 存在（配置过生物识别解锁）则回一个占位认证型数据集（文案
 *     「解锁账号本子/验证后填充匹配的账号」，认证 Intent 带 EXTRA_SELF_HEAL=true +
 *     EXTRA_TARGET_PACKAGE/EXTRA_TARGET_WEB_DOMAIN + 节点 id 列表），由
 *     AutofillAuthActivity 的自愈模式验证后灌桥并一次性回完整 FillResponse；包装 DEK
 *     不存在则维持旧的静默 onSuccess(null)。保存路径遇桥不活跃仍静默跳过（已知限制：
 *     自愈填充成功后桥已激活，保存随之恢复可用）；
 *   - snapshot 是解锁时灌入的明文最小快照，只读、内存态，服务绝不持久化它；
 *   - dek 是会话 DEK 副本，仅 onSaveRequest 期间 copyOf 一份使用，finally 里 fill(0) 清零；
 *   - 保存成功后 AutofillBridge.refresh(newSnapshot) 让填充端立即看到新数据。
 * 与 PasswordManager 参考实现的差异清单：
 *   1. 数据源：Room DAO 挂起查询 → AutofillBridge.snapshot 纯内存列表；填充路径无线程池。
 *   2. 认证门：isPrivacyEnabled && canUseBiometric() → 只看 AutofillBridge.active（桥仅在
 *      开关开且已解锁时活跃）；且 requireAuth 恒为 true——每个数据集都挂认证，满足
 *      「指纹/锁屏密码解锁后填充」的产品要求，服务端无需探测生物识别硬件。
 *   3. 网页保存：PM 在 onSaveRequest 直接跳过 web 上下文；本实现 web/app 双轨都支持
 *      （web 轨匹配键=根域名写 url 字段，app 轨匹配键=包名写 packageName 字段）。
 *   4. 保存并发：PM 靠 Room 自身线程安全；本实现文件级加密读写无事务保护，用
 *      synchronized(saveLock) 把 load→改→save 串成单飞，防止并发写坏 vault.bin。
 *   5. 认证后的取数：PM 的 AutofillAuthActivity 按 Long id 查 Room；本实现的认证 Activity
 *      用 AutofillBridge.findAccount(id)（同进程直读快照）。
 *   6. 认证 Activity 以类名字符串显式组件启动（ComponentName(packageName, "..."))而非
 *      ::class 引用：该 Activity 由另一条并行任务线交付，字符串引用保证本文件可独立编译。
 *   7. 删除 PM 的死代码：extractTextValue / buildTitle / matchScore 不再移植。
 * Python 类比：AutofillService ≈ 一个由系统回调的守护进程服务类；onFillRequest/onSaveRequest
 *           ≈ 系统调用的 handler——入口在 binder 线程，网络/磁盘类工作自行挪后台。
 */
package com.copy.account.service

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.widget.Toast
import com.copy.account.R
import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountField
import com.copy.account.data.model.PersistedVault
import com.copy.account.runtime.AutofillAccount
import com.copy.account.runtime.AutofillBridge
import com.copy.account.security.SecureVaultStore
import com.copy.account.util.cleanDomain
import com.copy.account.util.extractRootDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppAutofillService : AutofillService() {

    // 保存单飞锁：vault.bin 是「整体解密→改→整体加密回写」的文件格式，两个并发保存会
    // 丢失其中一个的修改（读改写竞态），必须把整段 load→modify→save 串行化。
    private val saveLock = Any()

    /** 只在保存协程里用一次的 vault 存储实例；无状态包装，构造即持有 context 引用。 */
    private val vaultStore by lazy { SecureVaultStore(this) }

    // ==================== 填充路径 ====================

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        try {
            Log.d(TAG, "onFillRequest: id=${request.id}")
            // 桥未活跃 = 开关关/未解锁/已锁定/进程死亡后重启。旧契约是任何情况下静默放弃；
            // 新契约追加自愈分支：只要「生物识别解封 DEK」的包装密文还在（即用户配置过
            // 生物识别解锁），就回一个占位的认证型数据集——用户点它 → AutofillAuthActivity
            // 自愈模式（CryptoObject 验证 + 灌桥 + 一次性完整回包），见 AutofillSelfHeal。
            // 从未配置生物识别时包装密文不存在，自愈无从发起，维持旧的静默 onSuccess(null)
            // （设置页对此有文档说明）。
            if (!AutofillBridge.active) {
                val selfHealResponse = buildSelfHealResponse(request)
                if (selfHealResponse != null) {
                    Log.d(TAG, "onFillRequest: bridge inactive, offer self-heal dataset")
                    callback.onSuccess(selfHealResponse)
                } else {
                    Log.d(TAG, "onFillRequest: bridge inactive, silent skip")
                    callback.onSuccess(null)
                }
                return
            }
            val contexts = request.fillContexts.orEmpty()
            val structures = contexts.mapNotNull { it.structure }
            val structure = structures.lastOrNull()
            if (structure == null) {
                callback.onSuccess(null)
                return
            }

            // 最新一个 context 才描述「当前还能写入的字段」；旧 context 里的控件可能已被
            // 登录模式切换移除，仅用它的 focusedId 参与焦点合并（与 PM 一致）。
            val focusedIds = listOfNotNull(contexts.lastOrNull()?.focusedId)

            val (targetPackage, webDomain) = parseTarget(structures)

            // 快照是 @Volatile 只读引用，取一次本地副本避免遍历途中被原子替换成不一致列表。
            val snapshot = AutofillBridge.snapshot

            val fillable = collectFillableNodes(listOf(structure), focusedIds.toSet())
            val nonAreaCodeFillable = fillable.filterNot { isAreaCodeNode(it) }
            val accountCandidates = nonAreaCodeFillable.filterNot {
                isOneTimeCodeNode(it) || isSearchNode(it)
            }
            val hasAreaCodeAnchor = fillable.any { isAreaCodeNode(it) }
            val passwordNodes = fillable
                .filter { !isSearchNode(it) && isPasswordNode(it) }
                .map { it.id }
                .distinct()
            val hintedAccountNodes = accountCandidates
                .filter {
                    !isPasswordNode(it) &&
                            isFallbackEditableTextNode(it) &&
                            isAccountNode(it)
                }
                .map { it.id }
                .distinct()
            val hasLoginSignal = passwordNodes.isNotEmpty() || hintedAccountNodes.isNotEmpty()
            if (!hasLoginSignal) {
                // 非登录页（无密码框也无账号候选框）：抑制填充，避免在任何表单上弹气泡。
                Log.d(
                    TAG,
                    "no login signal, suppress autofill: targetPackage=$targetPackage " +
                            "webDomain=$webDomain total=${fillable.size}"
                )
                callback.onSuccess(null)
                return
            }
            val fallback = resolveAccountNode(accountCandidates, focusedIds)
            var accountNodes = if (fallback != null &&
                (passwordNodes.isNotEmpty() || hintedAccountNodes.isEmpty())
            ) {
                listOf(fallback.id)
            } else {
                hintedAccountNodes
            }
            if (accountNodes.isEmpty() && fallback != null) {
                accountNodes = listOf(fallback.id)
                Log.d(
                    TAG,
                    "fallback username node: id=${fallback.id} viewId=${fallback.viewId} " +
                            "order=${fallback.order} focused=${fallback.isFocused}"
                )
            }
            Log.d(
                TAG,
                "field scan: contexts=${contexts.size} focused=${focusedIds.size} " +
                        "total=${fillable.size} account=${accountNodes.size} " +
                        "password=${passwordNodes.size} otp=${fillable.count { isOneTimeCodeNode(it) }}"
            )
            val nodeById = fillable.associateBy { it.id }
            val emailNodes = accountNodes.filter { id ->
                nodeById[id]?.let { isEmailNode(it) } == true
            }
            val phoneNodes = accountNodes.filter { id ->
                nodeById[id]?.let { node ->
                    !isAreaCodeNode(node) && !isEmailNode(node) &&
                            (isPhoneNode(node) || (hasAreaCodeAnchor && isFallbackEditableTextNode(node)))
                } == true
            }
            if (accountNodes.isEmpty() && passwordNodes.isEmpty()) {
                Log.d(
                    TAG,
                    "no fillable nodes, targetPackage=$targetPackage webDomain=$webDomain " +
                            "focusedIds=${focusedIds.size}"
                )
                callback.onSuccess(null)
                return
            }

            // 匹配：web 域名优先，空了或没匹配上再按包名兜底；只展示当前页面匹配的账号。
            val rawWebDomain = rawWebDomainOf(structures)
            var matched = emptyList<AutofillAccount>()
            if (!rawWebDomain.isNullOrBlank()) {
                matched = queryWebAccounts(snapshot, rawWebDomain)
            }
            if (matched.isEmpty() && targetPackage != null) {
                matched = queryPackageAccounts(snapshot, targetPackage)
                if (matched.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "package fallback matched=${matched.size} package=$targetPackage " +
                                "webDomain=$webDomain"
                    )
                }
            }
            val response = buildFillResponse(
                matched,
                accountNodes,
                phoneNodes,
                emailNodes,
                passwordNodes
            )
            Log.d(
                TAG,
                "onFillRequest: accounts=${matched.size} response=${response != null} " +
                        "targetPackage=$targetPackage webDomain=$webDomain"
            )
            callback.onSuccess(response)
        } catch (e: Exception) {
            Log.e(TAG, "onFillRequest error", e)
            callback.onSuccess(null)
        }
    }

    // ==================== 网页/应用匹配 ====================
    // 匹配与构建的纯逻辑已上移到 AutofillMatcher（自愈回包路径需共用），此处仅委托。

    private fun queryWebAccounts(
        snapshot: List<AutofillAccount>,
        rawWebDomain: String
    ): List<AutofillAccount> = AutofillMatcher.queryWebAccounts(snapshot, rawWebDomain)

    private fun queryPackageAccounts(
        snapshot: List<AutofillAccount>,
        targetPackage: String
    ): List<AutofillAccount> = AutofillMatcher.queryPackageAccounts(this, snapshot, targetPackage)

    /** 保存路径的同轨去重仍在用：委托共享实现。 */
    private fun matchesPackageName(storedPackage: String, targetPackage: String): Boolean =
        AutofillMatcher.matchesPackageName(storedPackage, targetPackage)

    // ==================== 保存路径 ====================

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Log.d(TAG, "onSaveRequest: contexts=${request.fillContexts?.size ?: 0}")
        try {
            // 桥未活跃或无 DEK：无法解密库文件，也不可能拿到可信数据，静默取消。
            if (!AutofillBridge.active || AutofillBridge.dek == null) {
                Log.d(TAG, "onSaveRequest: bridge inactive, silent skip")
                callback.onSuccess()
                return
            }
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                callback.onSuccess()
                return
            }
            // 系统 SaveUI 弹窗发生在 onSaveRequest 之前，此处网关只能静默阻止确认后的写入；
            // 「密码框是否残留」不参与判定：SaveRequest 快照通常仍带密码框，会误杀正确保存。
            if (scanErrorTextAcrossContexts(request)) {
                Log.d(TAG, "onSaveRequest: login failure state detected, silent skip")
                callback.onSuccess()
                return
            }
            val packageName = structure.activityComponent?.packageName ?: ""

            var extractedUsername = ""
            var extractedPassword = ""
            var extractedEmail = ""
            var extractedPhone = ""
            val hasAreaCodeAnchor = structureHasAreaCodeAnchor(structure)
            fun traverseNode(node: AssistStructure.ViewNode) {
                val nodeText = node.text?.toString()?.trim() ?: ""
                val autofillText = node.autofillValue?.takeIf { it.isText }?.textValue?.toString()?.trim() ?: ""
                val text = nodeText.ifBlank { autofillText }
                val autofillHints = node.autofillHints
                if (text.isNotBlank() && AREA_CODE_REGEX.matches(text.trim())) {
                    // 区号框（+86 等）自身不参与提取，但其存在让兄弟输入框按手机号对待。
                    for (i in 0 until node.childCount) {
                        traverseNode(node.getChildAt(i))
                    }
                    return
                }
                val isEditableInput = node.className?.contains("EditText", true) == true ||
                        node.className?.contains("TextField", true) == true ||
                        node.idEntry?.contains("user", true) == true ||
                        node.idEntry?.contains("email", true) == true
                val isEmailInput = autofillHints.orEmpty().any { isEmailHint(it) } ||
                        isEmailHintText(node.hint) ||
                        isEmailViewId(node.idEntry)
                if (isEmailInput && text.isNotBlank()) {
                    extractedEmail = text
                }
                val isPhoneInput = isEditableInput && (
                        autofillHints.orEmpty().any { isPhoneHint(it) } ||
                                isPhoneHintText(node.hint) ||
                                isPhoneViewId(node.idEntry) ||
                                hasAreaCodeAnchor
                        )
                if (isPhoneInput && text.isNotBlank()) {
                    extractedPhone = text
                }

                val isPasswordNode = isPasswordInputType(node.inputType) ||
                        (autofillHints != null && autofillHints.any {
                            it.contains("password", true) || it.contains("pwd", true) || it.contains("pass", true)
                        })
                if (isPasswordNode) {
                    if (text.isNotBlank()) extractedPassword = text
                }

                val isAccountHinted = autofillHints != null && autofillHints.any {
                    it.contains("username", true) ||
                            it.contains("name", true) || it.contains("personName", true)
                }
                if (isAccountHinted) {
                    if (text.isNotBlank() && !text.contains("密码")) {
                        extractedUsername = text
                    }
                } else {
                    if (text.isNotBlank() && !isPasswordNode && !isEmailInput && !isPhoneInput &&
                            text.length < 60 &&
                            !text.contains("密码") && extractedUsername.isBlank() && isEditableInput
                    ) {
                        extractedUsername = text
                    }
                }

                for (i in 0 until node.childCount) {
                    traverseNode(node.getChildAt(i))
                }
            }
            for (i in 0 until structure.windowNodeCount) {
                structure.getWindowNodeAt(i).rootViewNode?.let { traverseNode(it) }
            }

            val newPassword = extractedPassword
            val usernameText = extractedUsername.trim()
            val emailText = extractedEmail.trim()
            val purePhone = if (extractedPhone.isNotBlank()) {
                val candidate = extractPurePhone(extractedPhone)
                if (looksLikePhone(candidate)) candidate else ""
            } else if (looksLikePhone(usernameText)) {
                extractPurePhone(usernameText)
            } else {
                ""
            }
            val primaryAccount = when {
                usernameText.isNotBlank() -> usernameText
                emailText.isNotBlank() -> emailText
                purePhone.isNotBlank() -> purePhone
                else -> ""
            }
            val shouldDualSaveEmail = emailText.isNotEmpty() && primaryAccount == emailText
            val shouldDualSavePhone = purePhone.isNotEmpty() && primaryAccount == purePhone

            // 库 IO（PBKDF2/AES/磁盘）不能占 binder 线程；转到 IO 协程，回调前不返回。
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                var dekCopy: ByteArray? = null
                try {
                    val cleanPassword = newPassword.trim()
                    if (cleanPassword.isBlank()) {
                        toast("未捕获到密码，已取消保存")
                        callback.onSuccess()
                        return@launch
                    }

                    // 保存期间再次确认桥仍活跃；DEK 只 copyOf 一份、finally 统一清零。
                    // 从这里开始禁止直读 AutofillBridge.dek/snapshot（可能随时被锁定清零），
                    // 后续一律用局部量。
                    val dek = AutofillBridge.dek?.copyOf() ?: run {
                        callback.onSuccess()
                        return@launch
                    }
                    dekCopy = dek

                    // 上下文分类：web 轨（匹配键=根域名，写 url 字段）/ app 轨（匹配键=包名）。
                    val rawWebDomain = request.fillContexts.orEmpty()
                        .mapNotNull { it.structure }
                        .let { rawWebDomainOf(it) }
                    val webTrack = rawWebDomain != null && cleanDomain(rawWebDomain).isNotEmpty()
                    val targetRootDomain = if (webTrack) extractRootDomain(rawWebDomain) else ""
                    val targetPackageName = if (webTrack) "" else packageName

                    // 保存后的新快照在锁内构造；锁外仅 refresh（桥非活跃时是空操作）。
                    var newSnapshot: List<AutofillAccount>? = null
                    synchronized(saveLock) {
                        val state = vaultStore.load(dek)
                        if (state == null) {
                            Log.w(TAG, "onSaveRequest: vault load failed, cancel")
                            return@synchronized
                        }

                        // 三段去重只在与本次保存同轨的账号里进行（web 轨看根域名，app 轨看包名）。
                        val trackAccounts = state.accounts.filter { account ->
                            if (webTrack) {
                                val cardRoot = extractRootDomain(account.url)
                                cardRoot.isNotEmpty() && cardRoot == targetRootDomain
                            } else {
                                account.packageName?.trim().orEmpty().let { stored ->
                                    stored.isNotBlank() && matchesPackageName(stored, targetPackageName)
                                }
                            }
                        }
                        val existing = findExistingAccount(trackAccounts, primaryAccount)

                        if (existing != null) {
                            if (existing.password == cleanPassword) {
                                // 密码未变化：只记日志不落盘（PM 同款静默跳过）。
                                Log.d(TAG, "onSaveRequest: password unchanged, skip save silently")
                            } else {
                                var updated = existing.copy(
                                    password = cleanPassword,
                                    // web 轨补全空 url；app 轨补全空包名——老数据缺匹配键时兜底。
                                    url = if (webTrack && existing.url.isBlank()) rawWebDomain.orEmpty() else existing.url,
                                    packageName = if (!webTrack && existing.packageName == null) {
                                        targetPackageName.ifBlank { null }
                                    } else {
                                        existing.packageName
                                    }
                                )
                                if (shouldDualSaveEmail) {
                                    updated = updated.copy(
                                        customFields = upsertField(updated.customFields, EMAIL_FIELD_LABEL, emailText)
                                    )
                                }
                                if (shouldDualSavePhone) {
                                    updated = updated.copy(
                                        customFields = upsertField(updated.customFields, PHONE_FIELD_LABEL, purePhone)
                                    )
                                }
                                // PersistedVault 全 val：以 copy 重建整体状态再原子回写。
                                val nextState = state.copy(
                                    accounts = state.accounts.map { if (it.id == existing.id) updated else it }
                                )
                                vaultStore.save(nextState, dek)
                                newSnapshot = AutofillBridge.toAutofillAccounts(nextState.accounts)
                                Log.d(TAG, "onSaveRequest: password updated for ${existing.name}")
                                toast("已覆盖更新密码")
                            }
                        } else {
                            // 新建：id 生成约定与 AccountEditScreen 保存完全一致。
                            val displayName = when {
                                webTrack -> cleanDomain(rawWebDomain)
                                packageName.isNotBlank() -> appLabel(packageName)
                                else -> ""
                            }.ifBlank { "新建账号卡" }
                            val now = System.currentTimeMillis()
                            val newAccount = Account(
                                id = "account-$now",
                                name = displayName,
                                username = primaryAccount,
                                password = cleanPassword,
                                url = if (webTrack) rawWebDomain.orEmpty() else "",
                                packageName = targetPackageName.ifBlank { null },
                                groups = emptySet(),
                                customFields = buildList {
                                    if (shouldDualSaveEmail) {
                                        add(AccountField("field-email-$now", EMAIL_FIELD_LABEL, emailText))
                                    }
                                    if (shouldDualSavePhone) {
                                        add(AccountField("field-phone-$now", PHONE_FIELD_LABEL, purePhone))
                                    }
                                }
                            )
                            val nextState = state.copy(accounts = state.accounts + newAccount)
                            vaultStore.save(nextState, dek)
                            newSnapshot = AutofillBridge.toAutofillAccounts(nextState.accounts)
                            Log.d(TAG, "onSaveRequest: new account card created")
                            toast("新账号已保存")
                        }
                    }

                    // 落盘成功才刷新桥快照；失败/跳过保持旧快照，下次解锁自会纠正。
                    newSnapshot?.let { AutofillBridge.refresh(it) }
                    callback.onSuccess()
                } catch (e: Exception) {
                    Log.e(TAG, "onSaveRequest inner error", e)
                    toast("保存异常: ${e.message}")
                    callback.onFailure(e.message ?: "save failed")
                } finally {
                    dekCopy?.fill(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onSaveRequest outer error", e)
            callback.onSuccess()
        }
    }

    // ==================== 保存去重 ====================

    /**
     * 在同轨账号里找已存在项（PM 的三段式）：用户名精确（忽略大小写）→ 双向包含模糊 →
     * 自定义手机/邮箱字段值相等兜底；primaryAccount 为空时退化为「找用户名为空的卡片」。
     */
    private fun findExistingAccount(
        trackAccounts: List<Account>,
        primaryAccount: String
    ): Account? {
        return if (primaryAccount.isNotBlank()) {
            trackAccounts.find {
                it.username.trim().equals(primaryAccount, ignoreCase = true)
            } ?: trackAccounts.find {
                val cardUsername = it.username.trim()
                cardUsername.contains(primaryAccount, ignoreCase = true) ||
                        primaryAccount.contains(cardUsername, ignoreCase = true)
            } ?: trackAccounts.find { account ->
                account.customFields.any { field ->
                    field.label.trim() in PHONE_FIELD_LABELS && field.value.trim() == primaryAccount
                } || account.customFields.any { field ->
                    field.label.trim() in EMAIL_FIELD_LABELS &&
                            field.value.trim().equals(primaryAccount, ignoreCase = true)
                }
            }
        } else {
            trackAccounts.find { it.username.isBlank() }
        }
    }

    /** 自定义字段 upsert：同标签覆盖值，否则追加（PM 的 mergeEmailField/mergePhoneField 合一）。 */
    private fun upsertField(
        fields: List<AccountField>,
        label: String,
        value: String
    ): List<AccountField> {
        val index = fields.indexOfFirst { it.label.trim() == label }
        return if (index >= 0) {
            fields.mapIndexed { i, f -> if (i == index) f.copy(value = value) else f }
        } else {
            fields + AccountField(id = "field-${System.currentTimeMillis()}", label = label, value = value)
        }
    }

    // ==================== 错误文案网关 ====================

    /** 任一 context 命中错误关键词即视为登录失败页：静默放弃保存（PM 同款）。 */
    private fun scanErrorTextAcrossContexts(request: SaveRequest): Boolean {
        return request.fillContexts.orEmpty().any { ctx ->
            ctx.structure?.let { scanNodeForErrorText(it) } == true
        }
    }

    private fun scanNodeForErrorText(structure: AssistStructure): Boolean {
        fun traverse(node: AssistStructure.ViewNode): Boolean {
            val nodeText = node.text?.toString().orEmpty()
            val autofillText = node.autofillValue
                ?.takeIf { it.isText }
                ?.textValue
                ?.toString()
                .orEmpty()
            val text = "$nodeText $autofillText".lowercase()
            if (text.isNotBlank() && ERROR_HINT_KEYWORDS.any { text.contains(it) }) {
                return true
            }
            for (i in 0 until node.childCount) {
                if (traverse(node.getChildAt(i))) return true
            }
            return false
        }
        for (i in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(i).rootViewNode?.let { if (traverse(it)) return true }
        }
        return false
    }

    // ==================== 结构遍历辅助 ====================

    private fun parseTarget(structures: List<AssistStructure>): Pair<String?, String?> {
        val structure = structures.lastOrNull() ?: return null to null
        val targetPackage = structure.activityComponent?.packageName
        val cleaned = cleanDomain(rawWebDomainOf(structures))
        return Pair(targetPackage, cleaned.ifBlank { null })
    }

    /** 取最后一个非空 webDomain（WebView 浏览器场景；多窗口时后写的赢）。 */
    private fun rawWebDomainOf(structures: List<AssistStructure>): String? {
        return structures.asSequence()
            .mapNotNull { rawWebDomainOf(it) }
            .lastOrNull()
    }

    private fun rawWebDomainOf(structure: AssistStructure): String? {
        var extractedWebDomain: String? = null
        fun traverse(node: AssistStructure.ViewNode) {
            if (node.webDomain != null) {
                extractedWebDomain = node.webDomain
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChildAt(i))
            }
        }
        for (i in 0 until structure.windowNodeCount) {
            traverse(structure.getWindowNodeAt(i).rootViewNode)
        }
        return extractedWebDomain
    }

    /** 区号锚点：页面存在 +86/+1 样式区号框时，普通文本框大概率是手机号输入。 */
    private fun structureHasAreaCodeAnchor(structure: AssistStructure): Boolean {
        fun traverse(node: AssistStructure.ViewNode): Boolean {
            val nodeText = node.text?.toString()?.trim().orEmpty()
            val autofillText = node.autofillValue
                ?.takeIf { it.isText }
                ?.textValue
                ?.toString()
                ?.trim()
                .orEmpty()
            val text = nodeText.ifBlank { autofillText }
            if (text.isNotBlank() && AREA_CODE_REGEX.matches(text.trim())) return true
            val label = ((node.idEntry ?: "") + " " + (node.hint ?: "")).lowercase()
            if (label.contains("country") || label.contains("area_code") ||
                label.contains("区号") || label.contains("国家/地区")
            ) {
                return true
            }
            for (i in 0 until node.childCount) {
                if (traverse(node.getChildAt(i))) return true
            }
            return false
        }
        for (i in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(i).rootViewNode?.let { if (traverse(it)) return true }
        }
        return false
    }

    /** 应用显示名；查询失败回退包名（保存卡标题用）。 */
    private fun appLabel(targetPackage: String): String {
        return try {
            packageManager.getApplicationInfo(targetPackage, 0)
                .let { packageManager.getApplicationLabel(it).toString() }
        } catch (_: Exception) {
            targetPackage
        }
    }

    private fun toast(message: String) {
        // Toast 必须在主线程弹；服务运行在 binder 线程，统一 post 到 main。
        val looper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == looper) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            android.os.Handler(looper).post {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 表单节点收集与分类（PM 移植） ====================

    private data class FillableNode(
        val id: AutofillId,
        val hints: List<String>,
        val viewId: String?,
        val hint: String?,
        val inputType: Int,
        val autofillType: Int,
        val order: Int,
        val className: String?,
        val isEnabled: Boolean,
        val text: String?,
        val isFocused: Boolean
    )

    /**
     * 收集可填充节点：按 AutofillId 去重合并（同一控件在多窗口/多次遍历中出现时，
     * 提示取并集、文本取最新非空、焦点取或）——支撑登录/注册模式切换时旧窗口的残留结构。
     */
    private fun collectFillableNodes(
        structures: List<AssistStructure>,
        forcedIds: Set<AutofillId>
    ): List<FillableNode> {
        val result = linkedMapOf<AutofillId, FillableNode>()
        var order = 0
        fun traverse(node: AssistStructure.ViewNode) {
            val id = node.autofillId
            val hints = node.autofillHints
            if (id != null && (
                    !hints.isNullOrEmpty() ||
                            isUsefulViewId(node.idEntry) ||
                            isUsefulHintText(node.hint) ||
                            isPasswordInputType(node.inputType) ||
                            isTextInputType(node.inputType) ||
                            isEditableViewNode(node) ||
                            id in forcedIds
                    )
            ) {
                val nodeText = node.text?.toString()?.trim().orEmpty()
                val autofillText = node.autofillValue?.takeIf { it.isText }?.textValue?.toString()?.trim().orEmpty()
                val candidate = FillableNode(
                    id = id,
                    hints = hints?.toList() ?: emptyList(),
                    viewId = node.idEntry,
                    hint = node.hint,
                    inputType = node.inputType,
                    autofillType = node.autofillType,
                    order = order++,
                    className = node.className,
                    isEnabled = node.isEnabled,
                    text = nodeText.ifBlank { autofillText },
                    isFocused = node.isFocused || id in forcedIds
                )
                val previous = result[id]
                result[id] = if (previous == null) {
                    candidate
                } else {
                    previous.copy(
                        hints = (previous.hints + candidate.hints).distinct(),
                        viewId = previous.viewId ?: candidate.viewId,
                        hint = previous.hint ?: candidate.hint,
                        inputType = if (previous.inputType != 0) previous.inputType else candidate.inputType,
                        autofillType = if (previous.autofillType != View.AUTOFILL_TYPE_NONE) {
                            previous.autofillType
                        } else {
                            candidate.autofillType
                        },
                        className = previous.className ?: candidate.className,
                        isEnabled = previous.isEnabled || candidate.isEnabled,
                        text = candidate.text?.takeIf { it.isNotBlank() } ?: previous.text,
                        isFocused = previous.isFocused || candidate.isFocused
                    )
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChildAt(i))
            }
        }
        for (structure in structures) {
            for (i in 0 until structure.windowNodeCount) {
                traverse(structure.getWindowNodeAt(i).rootViewNode)
            }
        }
        return result.values.toList()
    }

    private fun isTextInputType(inputType: Int): Boolean {
        if (inputType == 0) return false
        val cls = inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_TEXT || cls == InputType.TYPE_CLASS_NUMBER
    }

    private fun isFallbackEditableTextNode(node: FillableNode): Boolean {
        if (!node.isEnabled) return false
        val className = node.className?.lowercase().orEmpty()
        if (className.contains("edittext") ||
                className.contains("textfield") ||
                className.contains("input")
        ) {
            return true
        }
        return node.autofillType == View.AUTOFILL_TYPE_TEXT ||
                node.isFocused ||
                (isTextInputType(node.inputType) && !isPasswordInputType(node.inputType))
    }

    private fun isEditableViewNode(node: AssistStructure.ViewNode): Boolean {
        if (!node.isEnabled) return false
        val className = node.className?.lowercase().orEmpty()
        return className.contains("edittext") ||
                className.contains("textfield") ||
                className.contains("input") ||
                node.isFocused ||
                node.autofillType == View.AUTOFILL_TYPE_TEXT ||
                isTextInputType(node.inputType)
    }

    /**
     * 选定「账号输入框」：焦点框优先；否则若有密码框，取密码框之前最近的账号样候选，
     * 再退化为任意最近候选；无密码框时取唯一候选。启发式顺序与 PM 逐条对齐。
     */
    private fun resolveAccountNode(
        fillable: List<FillableNode>,
        focusedIds: List<AutofillId>
    ): FillableNode? {
        val byId = fillable.associateBy { it.id }
        focusedIds.asReversed()
            .asSequence()
            .mapNotNull { byId[it] }
            .firstOrNull {
                !isPasswordNode(it) &&
                        !isOneTimeCodeNode(it) &&
                        !isSearchNode(it) &&
                        !isAreaCodeNode(it) &&
                        isFallbackEditableTextNode(it)
            }
            ?.let { return it }
        val password = fillable.firstOrNull { isPasswordNode(it) }
        val candidates = fillable.filter {
                !isPasswordNode(it) &&
                        !isOneTimeCodeNode(it) &&
                        !isSearchNode(it) &&
                        !isAreaCodeNode(it) &&
                        isFallbackEditableTextNode(it)
        }
        if (password != null) {
            candidates.filter {
                it.order < password.order &&
                        !isPhoneNode(it) &&
                        isAccountNode(it)
            }
                .maxByOrNull { it.order }
                ?.let { return it }
            candidates.filter { it.order < password.order && isAccountNode(it) }
                .maxByOrNull { it.order }
                ?.let { return it }
            candidates.filter { it.order < password.order }
                .maxByOrNull { it.order }
                ?.let { return it }
            candidates.filter { isAccountNode(it) }
                .minByOrNull { kotlin.math.abs(it.order - password.order) }
                ?.let { return it }
            return candidates.minByOrNull { kotlin.math.abs(it.order - password.order) }
        }
        return candidates.filter { isAccountNode(it) }.singleOrNull()
                ?: candidates.singleOrNull()
    }

    private fun isUsefulViewId(viewId: String?): Boolean =
        !viewId.isNullOrBlank() && (isAccountViewId(viewId) || isPasswordViewId(viewId))

    private fun isUsefulHintText(hint: String?): Boolean =
        !hint.isNullOrBlank() && (
                isAccountHintText(hint) || isPasswordHintText(hint) ||
                        isAccountHintTextCompat(hint) || isPasswordHintTextCompat(hint)
                )

    private fun isSearchNode(node: FillableNode): Boolean {
        val label = buildString {
            append(node.hints.joinToString(" "))
            append(' ')
            append(node.viewId.orEmpty())
            append(' ')
            append(node.hint.orEmpty())
        }.lowercase()
        return SEARCH_FIELD_TOKENS.any { label.contains(it) }
    }

    private fun isAccountNode(node: FillableNode): Boolean =
        !isPasswordNode(node) &&
                !isOneTimeCodeNode(node) && (
                node.hints.any { isAccountHint(it) } ||
                        isAccountViewId(node.viewId) ||
                        isAccountHintText(node.hint) || isAccountHintTextCompat(node.hint)
                )

    private fun isOneTimeCodeNode(node: FillableNode): Boolean {
        val label = buildString {
            append(node.hints.joinToString(" "))
            append(' ')
            append(node.viewId.orEmpty())
            append(' ')
            append(node.hint.orEmpty())
        }.lowercase()
        val hasCodeToken = label.contains("otp") ||
                label.contains("smsotp") ||
                label.contains("sms_otp") ||
                label.contains("one-time-code") ||
                label.contains("one_time_code") ||
                label.contains("verification") ||
                label.contains("verifycode") ||
                label.contains("verify_code") ||
                label.contains("captcha") ||
                label.contains("securitycode") ||
                label.contains("security_code") ||
                label.contains("验证码") ||
                label.contains("校验码")
        if (hasCodeToken) return true
        return (label.contains("code") || label.contains("验证")) &&
                (node.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
    }

    private fun isPasswordNode(node: FillableNode): Boolean =
        node.hints.any { isPasswordHint(it) } ||
                isPasswordViewId(node.viewId) ||
                isPasswordHintText(node.hint) || isPasswordHintTextCompat(node.hint) ||
                isPasswordInputType(node.inputType)

    private fun isAccountHintTextCompat(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("账号") || h.contains("用户名") ||
                h.contains("用户") || h.contains("邮箱") ||
                h.contains("手机") || h.contains("email") ||
                h.contains("username") || h.contains("user") ||
                h.contains("login") || h.contains("account")
    }

    private fun isPasswordHintTextCompat(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("密码") || h.contains("password") ||
                h.contains("pwd") || h.contains("pass")
    }

    private fun isAccountHintText(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("账号") || h.contains("用户名") || h.contains("用户") ||
                h.contains("邮箱") || h.contains("手机") ||
                h.contains("email") || h.contains("username") || h.contains("user") ||
                h.contains("login") || h.contains("account")
    }

    private fun isPasswordHintText(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("密码") || h.contains("password") || h.contains("pwd") || h.contains("pass")
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        if (inputType == 0) return false
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isAccountHint(hint: String): Boolean {
        val h = hint.lowercase()
        return h.contains("username") || h.contains("newusername") ||
                h.contains("email") || h.contains("phone") ||
                h.contains("login") || h.contains("account") ||
                h == "name" || h == "user_name" || h == "displayname" ||
                h == "userid" || h == "user_id" || h == "loginid" ||
                h == "login_id" || h == "identifier"
    }

    private fun isPasswordHint(hint: String): Boolean {
        val h = hint.lowercase()
        return h.contains("password") || h.contains("pwd") || h == "pass" ||
                h.contains("passcode") || h.contains("pin")
    }

    private fun isAccountViewId(viewId: String?): Boolean {
        val v = viewId?.lowercase() ?: return false
        return v.contains("user") || v.contains("email") || v.contains("login") ||
                v.contains("account") || v.contains("phone") || v.contains("mobile") ||
                v.contains("identifier")
    }

    private fun isPasswordViewId(viewId: String?): Boolean {
        val v = viewId?.lowercase() ?: return false
        return v.contains("password") || v.contains("pwd") || v.contains("pass") || v.contains("pin")
    }

    private fun isPhoneHint(hint: String): Boolean {
        val h = hint.lowercase()
        return h == "phonenumber" ||
                h.contains("phone") || h.contains("mobile") || h.contains("tel")
    }

    private fun isEmailHint(hint: String): Boolean {
        val h = hint.lowercase()
        return h == "emailaddress" || h.contains("email") || h.contains("mail")
    }

    private fun isEmailHintTextCompat(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("邮箱") || h.contains("email") || h.contains("mail")
    }

    private fun isEmailHintText(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("邮箱") || h.contains("email") || h.contains("mail")
    }

    private fun isEmailViewId(viewId: String?): Boolean {
        val v = viewId?.lowercase() ?: return false
        return v.contains("email") || v.contains("mail") || v.contains("邮箱")
    }

    private fun isEmailNode(node: FillableNode): Boolean =
        node.hints.any { isEmailHint(it) } ||
                isEmailViewId(node.viewId) ||
                isEmailHintText(node.hint) || isEmailHintTextCompat(node.hint)

    private fun isPhoneHintTextCompat(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("手机") || h.contains("电话") ||
                h.contains("phone") || h.contains("mobile") || h.contains("tel")
    }

    private fun isPhoneHintText(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("手机") || h.contains("电话") ||
                h.contains("phone") || h.contains("mobile") || h.contains("tel")
    }

    private fun isPhoneViewId(viewId: String?): Boolean {
        val v = viewId?.lowercase() ?: return false
        return v.contains("phone") || v.contains("mobile") || v.contains("tel") ||
                v.contains("手机") || v.contains("电话")
    }

    private fun isPhoneNode(node: FillableNode): Boolean =
        isFallbackEditableTextNode(node) && (
                node.hints.any { isPhoneHint(it) } ||
                        isPhoneViewId(node.viewId) ||
                        isPhoneHintText(node.hint) || isPhoneHintTextCompat(node.hint)
                )

    private fun isAreaCodeNode(node: FillableNode): Boolean {
        val text = node.text?.trim().orEmpty()
        if (AREA_CODE_REGEX.matches(text)) return true
        val label = ((node.viewId ?: "") + " " + (node.hint ?: "")).lowercase()
        if (label.contains("区号") || label.contains("国家")) return true
        return label.contains("country") || label.contains("area_code") ||
                label.contains("区号") || label.contains("国家/地区")
    }

    // ==================== 手机号规整 ====================

    /** 剥国家码与分隔符，留纯数字串（+86 138-0013-8000 → 13800138000）。 */
    private fun extractPurePhone(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var value = raw.trim()
        val countryCode = COUNTRY_CODE_REGEX.find(value)
        if (countryCode != null) {
            value = value.substring(countryCode.value.length).trimStart(' ', '-', '(', ')', '（', '）')
        }
        return value.replace(SEPARATOR_REGEX, "")
    }

    /** 粗判是否手机号：剥国家码/分隔符后是 6..15 位纯数字。 */
    private fun looksLikePhone(value: String): Boolean {
        var candidate = value.trim()
        val countryCode = COUNTRY_CODE_REGEX.find(candidate)
        if (countryCode != null) {
            candidate = candidate.substring(countryCode.value.length)
        }
        val digits = candidate.replace(SEPARATOR_REGEX, "")
        return digits.length in 6..15 && digits.all { it.isDigit() }
    }

    // ==================== 响应/数据集构建 ====================

    /**
     * 组装 FillResponse：每个匹配账号一个认证型数据集（上限 MAX_DATASETS）——
     * 单账号 1 张卡、多账号 N 张卡，与单账号同一条构建路径，系统填充列表原生逐条
     * 展示（每条只显示账号名称）。响应组装与 SaveInfo 形态已上移到
     * AutofillMatcher.buildResponse，此处只委托。
     */
    private fun buildFillResponse(
        accounts: List<AutofillAccount>,
        accountNodes: List<AutofillId>,
        phoneNodes: List<AutofillId>,
        emailNodes: List<AutofillId>,
        passwordNodes: List<AutofillId>
    ): FillResponse? {
        val datasets = AutofillMatcher.buildDatasets(accounts) { account ->
            buildDataset(account, accountNodes, phoneNodes, emailNodes, passwordNodes)
        }
        return AutofillMatcher.buildResponse(datasets, passwordNodes, accountNodes)
    }

    /**
     * 桥未活跃时的自愈占位数据集：结构与正常数据集完全一致（同一批节点 id、同样
     * 无值 + 认证型），仅认证 Intent 换成自愈契约（EXTRA_SELF_HEAL=true + 目标
     * 包名/域名，不带 account_id）。展示卡文案固定为「解锁账号本子/验证后填充匹配的
     * 账号」，让用户明白点下去会发生什么。用户认证通过后由 AutofillAuthActivity 的
     * 自愈模式灌桥并一次性回完整 FillResponse。
     * 仅当包装 DEK 存在（配置过生物识别解锁）才返回占位响应；否则返回 null 维持
     * 旧的静默放弃。此占位不携带 SaveInfo：桥尚未活跃，本路径不承诺保存能力。
     */
    private fun buildSelfHealResponse(request: FillRequest): FillResponse? {
        return try {
            if (SecureVaultStore(this).biometricCiphertext() == null) return null
            val contexts = request.fillContexts.orEmpty()
            val structures = contexts.mapNotNull { it.structure }
            val structure = structures.lastOrNull() ?: return null
            // 自愈只解决「登录页等填充」一件事，不复制完整登录信号判定；带不上
            // 表单节点（无解析）也无所谓——占位数据集的值全靠认证页回包时重建。
            val (targetPackage, webDomain) = parseTarget(structures)
            if (targetPackage.isNullOrBlank() && webDomain.isNullOrBlank()) return null
            val fillable = collectFillableNodes(listOf(structure), contexts.lastOrNull()?.focusedId?.let { setOf(it) }.orEmpty())
            val passwordNodes = fillable
                .filter { !isSearchNode(it) && isPasswordNode(it) }
                .map { it.id }
                .distinct()
            val accountCandidates = fillable.filterNot { isAreaCodeNode(it) || isOneTimeCodeNode(it) || isSearchNode(it) }
            val hinted = accountCandidates
                .filter { !isPasswordNode(it) && isFallbackEditableTextNode(it) && isAccountNode(it) }
                .map { it.id }
                .distinct()
            val fallback = resolveAccountNode(accountCandidates, contexts.lastOrNull()?.focusedId?.let { listOf(it) }.orEmpty())
            var accountNodes = if (fallback != null && (passwordNodes.isNotEmpty() || hinted.isEmpty())) {
                listOf(fallback.id)
            } else {
                hinted
            }
            if (accountNodes.isEmpty() && fallback != null) accountNodes = listOf(fallback.id)
            if (accountNodes.isEmpty() && passwordNodes.isEmpty()) return null
            val phoneNodes = accountNodes.filter { id ->
                fillable.firstOrNull { it.id == id }?.let { node ->
                    !isAreaCodeNode(node) && !isEmailNode(node) &&
                            isPhoneNode(node)
                } == true
            }
            val emailNodes = accountNodes.filter { id ->
                fillable.firstOrNull { it.id == id }?.let { isEmailNode(it) } == true
            }
            val presentation = AutofillMatcher.buildPresentation(this, "解锁账号本子")
            val dataset = AutofillMatcher.buildAuthDataset(
                presentation,
                selfHealIntentSender(
                    targetPackage ?: "",
                    webDomain.orEmpty(),
                    accountNodes,
                    phoneNodes,
                    emailNodes,
                    passwordNodes
                ),
                accountNodes,
                passwordNodes
            )
            FillResponse.Builder().addDataset(dataset).build()
        } catch (e: Exception) {
            Log.e(TAG, "buildSelfHealResponse failed", e)
            null
        }
    }

    /**
     * 自愈认证 PendingIntent：同样指向 AutofillAuthActivity（Mode B 由 EXTRA_SELF_HEAL
     * 区分）。FLAG_MUTABLE 与普通认证路径同理：系统回传结果时要附加 fill-in intent，
     * 且 BiometricPrompt 的 CryptoObject 通道要求 intent 可变。
     */
    private fun selfHealIntentSender(
        targetPackage: String,
        webDomain: String,
        accountNodes: List<AutofillId>,
        phoneNodes: List<AutofillId>,
        emailNodes: List<AutofillId>,
        passwordNodes: List<AutofillId>
    ): IntentSender {
        val intent = Intent().apply {
            component = ComponentName(packageName, AUTH_ACTIVITY_CLASS)
            putExtra(AutofillSelfHeal.EXTRA_SELF_HEAL, true)
            putExtra(AutofillSelfHeal.EXTRA_TARGET_PACKAGE, targetPackage)
            putExtra(AutofillSelfHeal.EXTRA_TARGET_WEB_DOMAIN, webDomain)
            putParcelableArrayListExtra(EXTRA_USERNAME_IDS, ArrayList(accountNodes))
            putParcelableArrayListExtra(EXTRA_PHONE_IDS, ArrayList(phoneNodes))
            putParcelableArrayListExtra(EXTRA_EMAIL_IDS, ArrayList(emailNodes))
            putParcelableArrayListExtra(EXTRA_PASSWORD_IDS, ArrayList(passwordNodes))
        }
        return PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        ).intentSender
    }

    /**
     * 每个数据集都走认证型：setValue 全部传 null 值（系统先展示卡片，认证通过后才回填），
     * 认证 IntentSender 指向 AutofillAuthActivity。
     */
    private fun buildDataset(
        account: AutofillAccount,
        accountNodes: List<AutofillId>,
        phoneNodes: List<AutofillId>,
        emailNodes: List<AutofillId>,
        passwordNodes: List<AutofillId>
    ): Dataset {
        val presentation = AutofillMatcher.buildPresentation(
            this,
            account.name.ifBlank { account.username }
        )
        return AutofillMatcher.buildAuthDataset(
            presentation,
            newIntentSender(account, accountNodes, phoneNodes, emailNodes, passwordNodes),
            accountNodes,
            passwordNodes
        )
    }

    /**
     * 认证 PendingIntent：指向 AutofillAuthActivity。以类名字符串构造显式组件而非
     * ::class 引用——该 Activity 由并行任务线交付，字符串引用保证本文件先行独立编译；
     * 集成后类真实存在，运行时解析不受影响。FLAG_MUTABLE：系统要在回传结果时附加
     * 远端凭证（fill-in intent），不可用 IMMUTABLE。
     */
    private fun newIntentSender(
        account: AutofillAccount,
        accountNodes: List<AutofillId>,
        phoneNodes: List<AutofillId>,
        emailNodes: List<AutofillId>,
        passwordNodes: List<AutofillId>
    ): IntentSender {
        val intent = Intent().apply {
            component = ComponentName(packageName, AUTH_ACTIVITY_CLASS)
            putExtra(EXTRA_ACCOUNT_ID, account.id)
            putParcelableArrayListExtra(EXTRA_USERNAME_IDS, ArrayList(accountNodes))
            putParcelableArrayListExtra(EXTRA_PHONE_IDS, ArrayList(phoneNodes))
            putParcelableArrayListExtra(EXTRA_EMAIL_IDS, ArrayList(emailNodes))
            putParcelableArrayListExtra(EXTRA_PASSWORD_IDS, ArrayList(passwordNodes))
        }
        return PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        ).intentSender
    }

    companion object {
        private const val TAG = "AppAutofillService"
        /** 认证回填页组件名：与 manifest 注册的 .service.AutofillAuthActivity 保持一致。 */
        private const val AUTH_ACTIVITY_CLASS = "com.copy.account.service.AutofillAuthActivity"

        // extras 键名：AutofillAuthActivity 端按同名常量读取，两端契约。
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_USERNAME_IDS = "username_ids"
        private const val EXTRA_PHONE_IDS = "phone_ids"
        private const val EXTRA_EMAIL_IDS = "email_ids"
        private const val EXTRA_PASSWORD_IDS = "password_ids"

        private val AREA_CODE_REGEX = Regex("""^\+\d{1,4}$""")
        private val COUNTRY_CODE_REGEX = Regex("""^\+\d{1,4}""")
        private val SEPARATOR_REGEX = Regex("""[\s\-()（）]""")
        private const val PHONE_FIELD_LABEL = "电话"
        private const val EMAIL_FIELD_LABEL = "邮箱"
        private val PHONE_FIELD_LABELS = setOf("电话", "手机号")
        private val EMAIL_FIELD_LABELS = setOf("邮箱", "email")
        private val ERROR_HINT_KEYWORDS = listOf(
            "密码错误", "密码不正确", "密码有误", "登录失败", "账号不存在", "用户名或密码",
            "incorrect", "wrong", "failed", "invalid"
        )
        private val SEARCH_FIELD_TOKENS = listOf(
            "search", "query", "filter", "find",
            "搜索", "查找", "筛选", "过滤", "搜寻", "查询"
        )
    }
}
