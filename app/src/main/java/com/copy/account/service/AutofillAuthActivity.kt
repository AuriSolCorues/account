package com.copy.account.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.copy.account.runtime.AutofillAccount
import com.copy.account.runtime.AutofillBridge
import com.copy.account.R
import com.copy.account.security.SecureVaultStore
import com.copy.account.runtime.toAutofillAccount
import com.copy.account.ui.components.AppBottomSheet
import com.copy.account.ui.components.AutofillPasswordSheetItems
import com.copy.account.ui.components.OverlayThemeHost
import javax.crypto.Cipher

class AutofillAuthActivity : FragmentActivity() {

    private val vaultStore by lazy { SecureVaultStore(this) }

    private var selfHeal = false
    private var accountId: String = ""
    private var usernameIds: List<AutofillId> = emptyList()
    private var phoneIds: List<AutofillId> = emptyList()
    private var emailIds: List<AutofillId> = emptyList()
    private var passwordIds: List<AutofillId> = emptyList()
    private var authenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selfHeal = intent.getBooleanExtra(AutofillSelfHeal.EXTRA_SELF_HEAL, false)
        accountId = intent.getStringExtra(AutofillMatcher.EXTRA_ACCOUNT_ID).orEmpty()
        usernameIds = readIds(AutofillMatcher.EXTRA_USERNAME_IDS)
        phoneIds = readIds(AutofillMatcher.EXTRA_PHONE_IDS)
        emailIds = readIds(AutofillMatcher.EXTRA_EMAIL_IDS)
        passwordIds = readIds(AutofillMatcher.EXTRA_PASSWORD_IDS)
        val hasTargets = usernameIds.isNotEmpty() || passwordIds.isNotEmpty() ||
                phoneIds.isNotEmpty() || emailIds.isNotEmpty()
        val hasAccountRef = accountId.isNotBlank()
        val valid = hasAccountRef && hasTargets
        if (!valid) {
            finishCancel()
            return
        }
        if (savedInstanceState != null) {
            finishCancel()
            return
        }
        window.decorView.post {
            if (!authenticating && !isFinishing) startAuthentication()
        }
    }

    private fun readIds(key: String): List<AutofillId> =
        IntentCompat.getParcelableArrayListExtra(intent, key, AutofillId::class.java).orEmpty()

    private fun beginSelfHealCipher(): Cipher? = try {
        vaultStore.beginBiometricDecrypt()
    } catch (_: Exception) {
        null
    }

    private fun lockedModeUsesBiometric(): Boolean =
        runCatching { vaultStore.biometricCiphertext() }.getOrNull() != null

    private fun startAuthentication() {
        authenticating = true
        when {
            !selfHeal -> promptBiometric(selfHeal = false, cipher = null)
            lockedModeUsesBiometric() -> {
                val cipher = beginSelfHealCipher()
                if (cipher == null) {
                    Toast.makeText(this, getString(R.string.autofill_selfheal_need_unlock), Toast.LENGTH_SHORT).show()
                    finishCancel()
                    return
                }
                promptBiometric(selfHeal = true, cipher = cipher)
            }
            else -> showPasswordEntry()
        }
    }

    private fun showPasswordEntry() {
        setContent {
            OverlayThemeHost {
                AppBottomSheet(onDismiss = { finishCancel() }) {
                    AutofillPasswordSheetItems(
                        onConfirm = { entered ->
                            Thread {
                                val acc = AutofillSelfHeal.unlockAccountWithPassword(vaultStore, entered, accountId)
                                runOnUiThread {
                                    if (acc != null) {
                                        replyFilled(listOf(acc))
                                    } else {
                                        Toast.makeText(this@AutofillAuthActivity, getString(R.string.autofill_password_wrong), Toast.LENGTH_SHORT).show()
                                        finishCancel()
                                    }
                                }
                            }.start()
                        },
                        onCancel = { finishCancel() }
                    )
                }
            }
        }
    }

    private fun promptBiometric(selfHeal: Boolean, cipher: Cipher?) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    when {
                        this@AutofillAuthActivity.selfHeal && result.cryptoObject != null ->
                            selfHealAndReply(result.cryptoObject?.cipher)
                        this@AutofillAuthActivity.selfHeal -> {
                            Toast.makeText(this@AutofillAuthActivity, getString(R.string.autofill_selfheal_retry), Toast.LENGTH_SHORT).show()
                            finishCancel()
                        }
                        else -> loadAccountAndReply()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false
                    finishCancel()
                }
            }
        )
        val combined = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (selfHeal) getString(R.string.autofill_prompt_title_app) else getString(R.string.autofill_prompt_title_fill))
            .setSubtitle(if (selfHeal) getString(R.string.autofill_prompt_subtitle_app) else getString(R.string.autofill_prompt_subtitle_credential))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        val strongOnly = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (selfHeal) getString(R.string.autofill_prompt_title_app) else getString(R.string.autofill_prompt_title_fill))
            .setSubtitle(if (selfHeal) getString(R.string.autofill_prompt_subtitle_app) else getString(R.string.autofill_prompt_subtitle_biometric))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.action_cancel))
            .build()
        try {
            if (selfHeal) {
                prompt.authenticate(combined, BiometricPrompt.CryptoObject(cipher!!))
            } else {
                prompt.authenticate(combined)
            }
        } catch (_: IllegalArgumentException) {
            try {
                if (selfHeal) {
                    prompt.authenticate(strongOnly, BiometricPrompt.CryptoObject(cipher!!))
                } else {
                    prompt.authenticate(strongOnly)
                }
            } catch (_: Exception) {
                authenticating = false
                finishCancel()
            }
        } catch (_: Exception) {
            authenticating = false
            finishCancel()
        }
    }

    private fun loadAccountAndReply() {
        val acc = loadFreshAccount(accountId) ?: AutofillBridge.findAccount(accountId)
        if (acc == null) {
            Toast.makeText(this, getString(R.string.autofill_session_invalid), Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        replyFilled(listOf(acc))
    }

    private fun loadFreshAccount(accountId: String): AutofillAccount? {
        val dek = AutofillBridge.dek ?: return null
        return runCatching { vaultStore.loadAccount(dek, accountId)?.toAutofillAccount() }.getOrNull()
    }

    private fun selfHealAndReply(cipher: Cipher?) {
        if (cipher == null) {
            Toast.makeText(this, getString(R.string.autofill_selfheal_retry), Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        val acc = AutofillSelfHeal.unlockAccount(cipher, vaultStore, accountId)
        if (acc == null) {
            Toast.makeText(this, getString(R.string.autofill_account_missing), Toast.LENGTH_SHORT).show()
            finishCancel()
            return
        }
        replyFilled(listOf(acc))
    }

    private fun replyFilled(accounts: List<AutofillAccount>) {
        val datasets = runCatching {
            AutofillMatcher.buildDatasets(accounts) { acc -> buildFilledDatasetFor(acc) }
        }.getOrNull().orEmpty()
        if (datasets.isEmpty()) {
            finishCancel()
            return
        }
        val result = if (datasets.size == 1) {
            datasets.single()
        } else {
            FillResponse.Builder().apply { for (dataset in datasets) addDataset(dataset) }.build()
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result))
        finish()
    }

    private fun buildFilledDatasetFor(acc: AutofillAccount): Dataset {
        val presentation = AutofillMatcher.buildPresentation(
            this,
            acc.name.ifBlank { acc.username }
        )
        return AutofillMatcher.buildFilledDataset(
            presentation,
            acc,
            usernameIds,
            phoneIds,
            emailIds,
            passwordIds
        )
    }

    private fun finishCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object
}
