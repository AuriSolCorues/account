package com.copy.account.service

import android.content.Context
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.copy.account.R
import com.copy.account.runtime.AutofillAccount
import com.copy.account.util.cleanDomain
import com.copy.account.util.extractFallbackRootDomains
import com.copy.account.util.extractRootDomain

internal object AutofillMatcher {

    const val EXTRA_ACCOUNT_ID = "account_id"
    const val EXTRA_USERNAME_IDS = "username_ids"
    const val EXTRA_PHONE_IDS = "phone_ids"
    const val EXTRA_EMAIL_IDS = "email_ids"
    const val EXTRA_PASSWORD_IDS = "password_ids"

    private const val TAG = "AutofillMatcher"

    const val MAX_DATASETS = 10

    fun <T> queryWebAccounts(
        source: List<T>,
        rawWebDomain: String,
        urlOf: (T) -> String
    ): List<T> {
        val cleaned = cleanDomain(rawWebDomain)
        if (cleaned.isEmpty()) return emptyList()
        val rootDomain = extractRootDomain(cleaned)
        val targetDomains = linkedSetOf(cleaned)
        if (rootDomain.isNotEmpty()) targetDomains.add(rootDomain)
        for (fallback in extractFallbackRootDomains(rawWebDomain)) {
            if (fallback.isNotBlank()) targetDomains.add(fallback)
        }
        return source.filter { card ->
            val cardDomain = cleanDomain(urlOf(card))
            if (cardDomain.isEmpty()) return@filter false
            val cardRoot = extractRootDomain(cardDomain)
            targetDomains.any { target ->
                matchesWebDomain(cardDomain, target) ||
                        (cardRoot.isNotEmpty() && cardRoot == extractRootDomain(target))
            }
        }
    }

    fun <T> queryPackageAccounts(
        context: Context,
        source: List<T>,
        targetPackage: String,
        packageNameOf: (T) -> String?,
        nameOf: (T) -> String
    ): List<T> {
        val targetLabel = try {
            context.packageManager.getApplicationInfo(targetPackage, 0)
                .let { context.packageManager.getApplicationLabel(it).toString() }
        } catch (_: Exception) {
            ""
        }
        return source.filter { card ->
            val storedPackage = packageNameOf(card)?.trim().orEmpty()
            storedPackage.isNotBlank() && (
                    matchesPackageName(storedPackage, targetPackage) ||
                            (targetLabel.isNotBlank() && storedPackage.equals(targetLabel, ignoreCase = true))
                    )
        }
    }

    fun matchesPackageName(storedPackage: String, targetPackage: String): Boolean {
        val stored = storedPackage.trim().lowercase().substringBefore(":")
        val target = targetPackage.trim().lowercase().substringBefore(":")
        return stored == target
    }

    fun matchesWebDomain(cardDomain: String, targetDomain: String): Boolean {
        if (cardDomain.isBlank() || targetDomain.isBlank()) return false
        return cardDomain == targetDomain ||
                cardDomain.contains(targetDomain, ignoreCase = true) ||
                targetDomain.contains(cardDomain, ignoreCase = true)
    }

    fun buildPresentation(context: Context, title: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.item_autofill_card).apply {
            setTextViewText(R.id.tv_autofill_title, title)
        }

    fun buildAuthDataset(
        presentation: RemoteViews,
        authentication: android.content.IntentSender,
        accountNodes: List<AutofillId>,
        passwordNodes: List<AutofillId>
    ): Dataset {
        val datasetBuilder = Dataset.Builder(presentation)
        for (id in accountNodes) {
            datasetBuilder.setValue(id, null, presentation)
        }
        for (id in passwordNodes) {
            datasetBuilder.setValue(id, null, presentation)
        }
        datasetBuilder.setAuthentication(authentication)
        return datasetBuilder.build()
    }

    fun buildFilledDataset(
        presentation: RemoteViews,
        account: AutofillAccount,
        usernameIds: List<AutofillId>,
        phoneIds: List<AutofillId>,
        emailIds: List<AutofillId>,
        passwordIds: List<AutofillId>
    ): Dataset {
        val builder = Dataset.Builder(presentation)
        for (id in usernameIds) {
            if (account.username.isNotBlank()) builder.setValue(id, AutofillValue.forText(account.username))
        }
        for (id in phoneIds) {
            if (account.username.isNotBlank()) builder.setValue(id, AutofillValue.forText(account.username))
        }
        for (id in emailIds) {
            if (account.username.isNotBlank()) builder.setValue(id, AutofillValue.forText(account.username))
        }
        for (id in passwordIds) {
            builder.setValue(id, AutofillValue.forText(account.password))
        }
        return builder.build()
    }

    fun buildDatasets(
        accounts: List<AutofillAccount>,
        perAccount: (AutofillAccount) -> Dataset
    ): List<Dataset> {
        val result = mutableListOf<Dataset>()
        for (account in accounts) {
            if (result.size >= MAX_DATASETS) break
            try {
                result.add(perAccount(account))
            } catch (e: Exception) {
                Log.e(TAG, "buildDataset failed for ${account.name}", e)
            }
        }
        return result
    }

    fun buildResponse(
        datasets: List<Dataset>,
        passwordNodes: List<AutofillId>,
        usernameNodes: List<AutofillId>
    ): FillResponse? {
        if (datasets.isEmpty() && passwordNodes.isEmpty() && usernameNodes.isEmpty()) {
            return null
        }
        val builder = FillResponse.Builder()
        for (dataset in datasets) {
            builder.addDataset(dataset)
        }
        val pwdId = passwordNodes.firstOrNull()
        val usrId = usernameNodes.firstOrNull()
        if (pwdId != null) {
            val saveInfoBuilder = SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                arrayOf(pwdId)
            )
            saveInfoBuilder.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            if (usrId != null) {
                saveInfoBuilder.setOptionalIds(arrayOf(usrId))
            }
            builder.setSaveInfo(saveInfoBuilder.build())
        } else if (usrId != null) {
            val saveInfoBuilder = SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_USERNAME,
                arrayOf(usrId)
            )
            saveInfoBuilder.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            builder.setSaveInfo(saveInfoBuilder.build())
        }
        return builder.build()
    }
}
