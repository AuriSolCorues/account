/**
 * 职责：系统通行密钥提供方服务（Android 14+ CredentialProviderService）——把系统凭据管理器的
 *       「创建/登录」请求翻译成透明 Activity 的 PendingIntent 出卡。三类回调：
 *       ① onBeginCreateCredentialRequest（网站要求注册通行密钥）→ 一张 CreateEntry
 *       ② onBeginGetCredentialRequest（网站要求登录断言）→ 每个匹配凭据一张 PublicKeyCredentialEntry
 *       ③ onClearCredentialStateRequest（凭据状态清理）→ 无会话状态，直接成功
 *       本类只做系统协议胶水；CBOR 组包/生物识别在两透明 Activity，算法在 WebAuthn.kt。
 * 架构位置：service/ 系统入口组件（manifest 注册 BIND_CREDENTIAL_PROVIDER_SERVICE + providers
 *           元数据；API<34 设备不认此声明，App 内入口在 UI 层按 SDK_INT 隐藏）。
 *           与透明 Activity 的契约：Intent extras 传 requestJson（Create 的 createJson / Get 的
 *           requestJson 原文）+ optionId + callingPackage + origin；Activity 验证完成后以
 *           setResult(RESULT_OK) 结束，响应须经 PendingIntentHandler.setGetCredentialResponse /
 *           setCreateCredentialResponse 打包进 result Intent——系统只认 androidx 约定的
 *           BUNDLE 键（自定义 extras 键系统读不到，曾因此 GitHub 不识别）。
 * Python 类比：≈ RPC 分发器——解析协议包、挑参数、返回「回调地址」（PendingIntent）；
 *           自身不实现业务，系统是调用方，本服务是被注册的端点。
 */
package com.copy.account.service

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.ClearCredentialException

/**
 * 系统凭据提供方。所有回调都在主线程进、主线程出（系统保证）；注册表读文件是
 * 毫秒级小 IO，容忍同步读（照 AutofillMatcher 直读快照的先例）。
 */
class PasskeyProviderService : CredentialProviderService() {

    companion object {
        /** → 透明 Activity 的 extras：请求原文（Create 用 CREATE_KEY，Get 用 GET_KEY）。 */
        const val EXTRA_CREATE_JSON = "com.copy.account.passkey.CREATE_JSON"
        const val EXTRA_GET_JSON = "com.copy.account.passkey.GET_JSON"
        const val EXTRA_OPTION_ID = "com.copy.account.passkey.OPTION_ID"
        const val EXTRA_CALLING_PACKAGE = "com.copy.account.passkey.CALLING_PACKAGE"

        // ← Activity 回包不走自定义 extras：系统契约要求经 PendingIntentHandler 打包
        //   响应对象（见 PasskeyGetActivity/PasskeyCreateActivity 的 setResult 处）。
    }

    /** Get：网站登录请求 → 出卡（每条本机 rpId 匹配凭据一张）。 */
    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        runCatching {
            val registry = com.copy.account.security.PasskeyRegistry(applicationContext)
            val entries = request.beginGetCredentialOptions.mapNotNull { option ->
                // 只处理 public-key 类型选项；密码/自定义类型不是本提供方的职责
                val pkOption = option as? BeginGetPublicKeyCredentialOption ?: return@mapNotNull null
                val rpId = com.copy.account.security.parseWebAuthnRequest(pkOption.requestJson)?.rpId
                    ?: return@mapNotNull null
                val matches = registry.byRpId(rpId)
                if (matches.isEmpty()) return@mapNotNull null
                // 每条凭据一张卡（真实场景账号≈个位数；系统按 rpId 过滤后才会到这）
                matches.map { entry ->
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        entry.credentialId.hashCode(),
                        Intent(this, PasskeyGetActivity::class.java).apply {
                            putExtra(EXTRA_GET_JSON, pkOption.requestJson)
                            putExtra(EXTRA_OPTION_ID, option.id)
                            putExtra(EXTRA_CALLING_PACKAGE, request.callingAppInfo?.packageName.orEmpty())
                        },
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    // 第二参是「用户名」显示位（不是 userHandle——那是二进制 Base64URL，
                    // 传它会在系统弹窗上显示乱码）；空则退化显示 rpId。
                    PublicKeyCredentialEntry.Builder(
                        this,
                        entry.userName.ifBlank { entry.rpId },
                        pendingIntent,
                        pkOption
                    ).build()
                }
            }.flatten()
            if (entries.isEmpty()) {
                // 无匹配凭据：回空响应（系统继续问下一个提供方/显示无通行密钥 UI）
                callback.onResult(BeginGetCredentialResponse.Builder().build())
            } else {
                callback.onResult(BeginGetCredentialResponse.Builder().setCredentialEntries(entries).build())
            }
        }.onFailure { callback.onError(GetCredentialUnknownException(it.message.orEmpty())) }
    }

    /** Create：网站注册请求 → 一张「保存到账号本子」卡。 */
    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        runCatching {
            val pkRequest = request as? BeginCreatePublicKeyCredentialRequest
            if (pkRequest == null) {
                // 非 public-key 创建请求（如密码保存）不是本提供方职责：回空响应
                callback.onResult(BeginCreateCredentialResponse.Builder().build())
                return
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                pkRequest.requestJson.hashCode(),
                Intent(this, PasskeyCreateActivity::class.java).apply {
                    putExtra(EXTRA_CREATE_JSON, pkRequest.requestJson)
                    putExtra(EXTRA_CALLING_PACKAGE, request.callingAppInfo?.packageName.orEmpty())
                },
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            callback.onResult(
                BeginCreateCredentialResponse.Builder()
                    .addCreateEntry(CreateEntry.Builder("保存到账号本子", pendingIntent).build())
                    .build()
            )
        }.onFailure { callback.onError(CreateCredentialUnknownException(it.message.orEmpty())) }
    }

    /** 状态清理：注册表是设备本地文件、无跨调用会话状态，恒成功。 */
    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        callback.onResult(null)
    }
}
