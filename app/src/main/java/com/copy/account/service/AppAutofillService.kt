package com.copy.account.service

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.service.autofill.FillContext
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
import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountField
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.newUid
import com.copy.account.runtime.AutofillAccount
import com.copy.account.runtime.AutofillAccountLight
import com.copy.account.runtime.AutofillBridge
import com.copy.account.runtime.LogEvents
import com.copy.account.runtime.appendLog
import com.copy.account.security.SecureVaultStore
import com.copy.account.util.cleanDomain
import com.copy.account.util.extractRootDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
class AppAutofillService : AutofillService() {

    private val saveLock = Any()

    private val vaultStore by lazy { SecureVaultStore(this) }

    private var lastDiagKey: String? = null
    private var lastDiagAtMs: Long = 0L

    private fun diag(message: String) {
        try {
            appendLog(this, LogEvents.AUTOFILL, message)
        } catch (_: Exception) {
        }
    }

    private fun diagThrottled(key: String, message: () -> String) {
        val now = System.currentTimeMillis()
        if (key == lastDiagKey && now - lastDiagAtMs < 15_000L) return
        lastDiagKey = key
        lastDiagAtMs = now
        diag(message())
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        try {
            Log.d(TAG, "onFillRequest: id=${request.id}")
            diagThrottled("fill-in-${request.id}") { "填充请求进入（id=${request.id}）" }
            if (isOwnApp(request.fillContexts)) {
                Log.d(TAG, "onFillRequest: own app, suppress")
                callback.onSuccess(null)
                return
            }
            if (!AutofillBridge.active) {
                AutofillBridge.loadPublicIndexIfEmpty(this)
                val indexSize = AutofillBridge.publicIndex.size
                val (pkg, dom) = parseTarget(request.fillContexts.orEmpty().mapNotNull { it.structure })
                diag("锁定态分支：bridge=inactive 索引=$indexSize 目标=${pkg ?: "?"}/dom=${dom ?: "?"}")
                val lockedResponse = buildLockedResponse(request)
                if (lockedResponse != null) {
                    Log.d(TAG, "onFillRequest: bridge inactive, locked response offered")
                    callback.onSuccess(lockedResponse)
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

            val focusedIds = listOfNotNull(contexts.lastOrNull()?.focusedId)

            val (targetPackage, webDomain) = parseTarget(structures)

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

            val rawWebDomain = rawWebDomainOf(structures)
            var matched = emptyList<AutofillAccount>()
            if (!rawWebDomain.isNullOrBlank()) {
                matched = AutofillMatcher.queryWebAccounts(snapshot, rawWebDomain) { it.url }
            }
            if (matched.isEmpty() && targetPackage != null) {
                matched = AutofillMatcher.queryPackageAccounts(
                    this, snapshot, targetPackage,
                    packageNameOf = { it.packageName },
                    nameOf = { it.name }
                )
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
            diag("解锁态分支：命中=${matched.size} 出卡=${response != null} 目标=${targetPackage ?: "?"}/dom=${webDomain ?: "?"}")
            callback.onSuccess(response)
        } catch (e: Exception) {
            Log.e(TAG, "onFillRequest error", e)
            diag("填充异常：${e.javaClass.simpleName}: ${e.message?.take(80)}")
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Log.d(TAG, "onSaveRequest: contexts=${request.fillContexts?.size ?: 0}")
        try {
            if (isOwnApp(request.fillContexts)) {
                Log.d(TAG, "onSaveRequest: own app, silent skip")
                callback.onSuccess()
                return
            }
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

            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                var dekCopy: ByteArray? = null
                try {
                    val cleanPassword = newPassword.trim()
                    if (cleanPassword.isBlank()) {
                        toast("未捕获到密码，已取消保存")
                        callback.onSuccess()
                        return@launch
                    }

                    val dek = AutofillBridge.dek?.copyOf() ?: run {
                        callback.onSuccess()
                        return@launch
                    }
                    dekCopy = dek

                    val rawWebDomain = request.fillContexts.orEmpty()
                        .mapNotNull { it.structure }
                        .let { rawWebDomainOf(it) }
                    val webTrack = rawWebDomain != null && cleanDomain(rawWebDomain).isNotEmpty()
                    val targetRootDomain = if (webTrack) extractRootDomain(rawWebDomain) else ""
                    val targetPackageName = if (webTrack) "" else packageName

                    var newSnapshot: List<AutofillAccount>? = null
                    synchronized(saveLock) {
                        val state = vaultStore.load(dek)
                        if (state == null) {
                            Log.w(TAG, "onSaveRequest: vault load failed, cancel")
                            return@synchronized
                        }

                        val trackAccounts = state.accounts.filter { account ->
                            if (webTrack) {
                                val cardRoot = extractRootDomain(account.url)
                                cardRoot.isNotEmpty() && cardRoot == targetRootDomain
                            } else {
                                account.packageName?.trim().orEmpty().let { stored ->
                                    stored.isNotBlank() && AutofillMatcher.matchesPackageName(stored, targetPackageName)
                                }
                            }
                        }
                        val existing = findExistingAccount(trackAccounts, primaryAccount)

                        if (existing != null) {
                            if (existing.password == cleanPassword) {
                                Log.d(TAG, "onSaveRequest: password unchanged, skip save silently")
                            } else {
                                var updated = existing.copy(
                                    password = cleanPassword,
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
                                val nextState = state.copy(
                                    accounts = state.accounts.map { if (it.id == existing.id) updated else it }
                                )
                                vaultStore.save(nextState, dek)
                                newSnapshot = AutofillBridge.toAutofillAccounts(nextState.accounts)
                                Log.d(TAG, "onSaveRequest: password updated for ${existing.name}")
                                toast("已覆盖更新密码")
                            }
                        } else {
                            val displayName = when {
                                webTrack -> cleanDomain(rawWebDomain)
                                packageName.isNotBlank() -> appLabel(packageName)
                                else -> ""
                            }.ifBlank { "新建账号卡" }
                            val now = System.currentTimeMillis()
                            val newAccount = Account(
                                id = "account-$now",
                                uid = newUid(now),
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

                    newSnapshot?.let { AutofillBridge.refresh(applicationContext, it) }
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

    private fun isOwnApp(contexts: List<FillContext>?): Boolean {
        val source = contexts?.lastOrNull()?.structure?.activityComponent?.packageName
        return source == packageName
    }

    private fun parseTarget(structures: List<AssistStructure>): Pair<String?, String?> {
        val structure = structures.lastOrNull() ?: return null to null
        val targetPackage = structure.activityComponent?.packageName
        val cleaned = cleanDomain(rawWebDomainOf(structures))
        return Pair(targetPackage, cleaned.ifBlank { null })
    }

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

    private fun appLabel(targetPackage: String): String {
        return try {
            packageManager.getApplicationInfo(targetPackage, 0)
                .let { packageManager.getApplicationLabel(it).toString() }
        } catch (_: Exception) {
            targetPackage
        }
    }

    private fun toast(message: String) {
        val looper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == looper) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            android.os.Handler(looper).post {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                isAccountHintText(hint) || isPasswordHintText(hint)
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
                        isAccountHintText(node.hint)
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
                isPasswordHintText(node.hint) ||
                isPasswordInputType(node.inputType)

    private fun isAccountHintText(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("账号") || h.contains("用户名") ||
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

    private fun isEmailViewId(viewId: String?): Boolean {
        val v = viewId?.lowercase() ?: return false
        return v.contains("email") || v.contains("mail") || v.contains("邮箱")
    }

    private fun isEmailHintText(hint: String?): Boolean {
        val h = hint?.lowercase() ?: return false
        return h.contains("邮箱") || h.contains("email") || h.contains("mail")
    }

    private fun isEmailNode(node: FillableNode): Boolean =
        node.hints.any { isEmailHint(it) } ||
                isEmailViewId(node.viewId) ||
                isEmailHintText(node.hint)

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
                        isPhoneHintText(node.hint)
                )

    private fun isAreaCodeNode(node: FillableNode): Boolean {
        val text = node.text?.trim().orEmpty()
        if (AREA_CODE_REGEX.matches(text)) return true
        val label = ((node.viewId ?: "") + " " + (node.hint ?: "")).lowercase()
        if (label.contains("区号") || label.contains("国家")) return true
        return label.contains("country") || label.contains("area_code") ||
                label.contains("区号") || label.contains("国家/地区")
    }

    private fun extractPurePhone(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var value = raw.trim()
        val countryCode = COUNTRY_CODE_REGEX.find(value)
        if (countryCode != null) {
            value = value.substring(countryCode.value.length).trimStart(' ', '-', '(', ')', '（', '）')
        }
        return value.replace(SEPARATOR_REGEX, "")
    }

    private fun looksLikePhone(value: String): Boolean {
        var candidate = value.trim()
        val countryCode = COUNTRY_CODE_REGEX.find(candidate)
        if (countryCode != null) {
            candidate = candidate.substring(countryCode.value.length)
        }
        val digits = candidate.replace(SEPARATOR_REGEX, "")
        return digits.length in 6..15 && digits.all { it.isDigit() }
    }

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

    private fun buildLockedResponse(request: FillRequest): FillResponse? {
        return try {
            val contexts = request.fillContexts.orEmpty()
            val structures = contexts.mapNotNull { it.structure }
            val structure = structures.lastOrNull() ?: return null
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
            if (passwordNodes.isEmpty() && hinted.isEmpty()) return null
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
            AutofillBridge.loadPublicIndexIfEmpty(this)
            val index = AutofillBridge.publicIndex
            if (index.isEmpty()) {
                Log.d(TAG, "buildLockedResponse: public index empty, silent skip")
                diag("锁定态出卡失败：公开索引为空（冷启动恢复失败/索引被过期自检删除/写盘失败）→ 静默")
                return null
            }
            var matched = emptyList<AutofillAccountLight>()
            if (!webDomain.isNullOrBlank()) {
                matched = AutofillMatcher.queryWebAccounts(index, webDomain) { it.url }
            }
            if (matched.isEmpty() && !targetPackage.isNullOrBlank()) {
                matched = AutofillMatcher.queryPackageAccounts(
                    this, index, targetPackage,
                    packageNameOf = { it.packageName },
                    nameOf = { it.name }
                )
            }
            if (matched.isEmpty()) {
                Log.d(TAG, "buildLockedResponse: no index match, silent skip")
                diag("锁定态出卡失败：索引=${index.size} 条但无匹配（目标=$targetPackage/dom=$webDomain）→ 静默")
                return null
            }
            val response = FillResponse.Builder()
            for (account in matched.take(AutofillMatcher.MAX_DATASETS)) {
                val presentation = AutofillMatcher.buildPresentation(
                    this,
                    account.name.ifBlank { account.url }
                )
                response.addDataset(
                    AutofillMatcher.buildAuthDataset(
                        presentation,
                        authIntentSender(
                            selfHeal = true,
                            accountId = account.id,
                            accountNodes = accountNodes,
                            phoneNodes = phoneNodes,
                            emailNodes = emailNodes,
                            passwordNodes = passwordNodes
                        ),
                        accountNodes,
                        passwordNodes
                    )
                )
            }
            response.build()
        } catch (e: Exception) {
            Log.e(TAG, "buildLockedResponse failed", e)
            null
        }
    }

    private fun authIntentSender(
        selfHeal: Boolean,
        accountId: String?,
        accountNodes: List<AutofillId>,
        phoneNodes: List<AutofillId>,
        emailNodes: List<AutofillId>,
        passwordNodes: List<AutofillId>
    ): IntentSender {
        val intent = Intent().apply {
            component = ComponentName(packageName, AUTH_ACTIVITY_CLASS)
            if (selfHeal) putExtra(AutofillSelfHeal.EXTRA_SELF_HEAL, true)
            if (!accountId.isNullOrBlank()) putExtra(AutofillMatcher.EXTRA_ACCOUNT_ID, accountId)
            putParcelableArrayListExtra(AutofillMatcher.EXTRA_USERNAME_IDS, ArrayList(accountNodes))
            putParcelableArrayListExtra(AutofillMatcher.EXTRA_PHONE_IDS, ArrayList(phoneNodes))
            putParcelableArrayListExtra(AutofillMatcher.EXTRA_EMAIL_IDS, ArrayList(emailNodes))
            putParcelableArrayListExtra(AutofillMatcher.EXTRA_PASSWORD_IDS, ArrayList(passwordNodes))
        }
        val requestCode = if (selfHeal) {
            accountId.orEmpty().hashCode() * 2 + 1
        } else {
            accountId.orEmpty().hashCode() * 2
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        ).intentSender
    }

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
            authIntentSender(
                selfHeal = false,
                accountId = account.id,
                accountNodes = accountNodes,
                phoneNodes = phoneNodes,
                emailNodes = emailNodes,
                passwordNodes = passwordNodes
            ),
            accountNodes,
            passwordNodes
        )
    }

    companion object {
        private const val TAG = "AppAutofillService"
        private const val AUTH_ACTIVITY_CLASS = "com.copy.account.service.AutofillAuthActivity"

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
