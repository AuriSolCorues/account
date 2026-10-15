/**
 * 职责：自动填充的纯逻辑共享层——桥快照上的账号匹配（web 域名优先 / 包名兜底）与
 *       数据集/响应构建（RemoteViews 展示卡、认证型无值数据集、已回填数据集、
 *       FillResponse + SaveInfo 组装）。AppAutofillService（服务端首发）与
 *       AutofillAuthActivity（认证回包 / 自愈回包）两端共用同一份实现，保证对同一
 *       页面、同一批账号，两条路径产出完全一致的数据集结构与展示卡。
 * 架构位置：service/ 内部共享对象（internal object），无状态、纯函数 + 无 IO 的构建器；
 *           域名规整复用 util/DomainCleaner，包名标签解析需调用方传入 Context。
 * 迁移说明：匹配与构建逻辑原为 AppAutofillService 的私有方法，为支撑自愈回包
 *           （AutofillAuthActivity Mode B 需要在认证通过后重建完整 FillResponse）
 *           原样上移至此，行为与迁移前逐行等价，服务端改为委托调用。
 * Python 类比：≈ 一组 @staticmethod 组成的工具命名空间；对象本身不持有任何状态。
 */
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

    // ==================== 两端契约 ====================

    /**
     * 认证回包 extras 键名：AppAutofillService（写入端）与 AutofillAuthActivity（读取端）
     * 共用此单点定义，杜绝双份维护漂移。节点 id 列表与 account_id 两端同名。
     */
    const val EXTRA_ACCOUNT_ID = "account_id"
    const val EXTRA_USERNAME_IDS = "username_ids"
    const val EXTRA_PHONE_IDS = "phone_ids"
    const val EXTRA_EMAIL_IDS = "email_ids"
    const val EXTRA_PASSWORD_IDS = "password_ids"

    private const val TAG = "AutofillMatcher"

    /** 单个 FillResponse 里的数据集上限：服务端首发与自愈回包共用同一上限。 */
    const val MAX_DATASETS = 10

    // ==================== 账号匹配 ====================

    /**
     * web 匹配：目标域名（清洗值 + 根域名 + SSO 回退根域名）逐一与账号 url 的清洗值/
     * 根域名比对（相等或双向包含）。对应 PM 的 queryWebAccounts + queryWebAccountsFuzzy，
     * 数据源换快照后两条查询合一。
     * 泛型 + 选择器：同一份逻辑服务全量快照（AutofillAccount）与锁定态公开索引
     * （AutofillAccountLight），两者都以 url/packageName 参与匹配。
     */
    fun <T> queryWebAccounts(
        source: List<T>,
        rawWebDomain: String,
        urlOf: (T) -> String
    ): List<T> {
        val cleaned = cleanDomain(rawWebDomain)
        if (cleaned.isEmpty()) return emptyList()
        val rootDomain = extractRootDomain(cleaned)
        // 目标域名集合：清洗值、其根域名、SSO 跳转参数里解析出的回退根域名。
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

    /**
     * app 兜底匹配：包名精准匹配（忽略大小写、剥 :process 后缀），外加 PM 的
     * 「存的名与目标应用标签相等」的兼容路径（老数据可能存的是应用名）。
     */
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

    /** 包名比较：trim + 小写 + 剥掉 `:process` 子进程后缀后全等。 */
    fun matchesPackageName(storedPackage: String, targetPackage: String): Boolean {
        val stored = storedPackage.trim().lowercase().substringBefore(":")
        val target = targetPackage.trim().lowercase().substringBefore(":")
        return stored == target
    }

    /** 域名比较：相等或双向包含（PM 同款宽松策略，容忍子域/裸域互指）。 */
    fun matchesWebDomain(cardDomain: String, targetDomain: String): Boolean {
        if (cardDomain.isBlank() || targetDomain.isBlank()) return false
        return cardDomain == targetDomain ||
                cardDomain.contains(targetDomain, ignoreCase = true) ||
                targetDomain.contains(cardDomain, ignoreCase = true)
    }

    // ==================== 展示卡 ====================

    /**
     * 数据集展示卡：与服务端/认证页共用同一张 item_autofill_card 布局，仅一行账号名称
     * （名称空白回退用户名，由调用方负责传入最终标题）。
     */
    fun buildPresentation(context: Context, title: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.item_autofill_card).apply {
            setTextViewText(R.id.tv_autofill_title, title)
        }

    // ==================== 数据集构建 ====================

    /**
     * 认证型无值数据集：账号框与密码框的值一律置 null（系统先展示卡片，认证通过后才
     * 回填），并挂上认证 IntentSender。服务端首发路径与自愈占位路径共用。
     * 与 AutofillAuthActivity 的 extras 契约（Activity 端按此读取，改动须两端同步）：
     *   - account_id: String          —— 桥快照行 id，认证通过后经 AutofillBridge.findAccount 取明文；
     *   - username_ids: ArrayList<AutofillId> —— 待回填的账号输入框；
     *   - phone_ids / email_ids: ArrayList<AutofillId> —— 手机/邮箱框（可为空表）；
     *   - password_ids: ArrayList<AutofillId> —— 待回填的密码输入框；
     * 不传明文值：Activity 与服务同进程，认证后直接读桥快照，避免凭据经由 Intent 泄露面。
     */
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

    /**
     * 已回填数据集：直接带明文值、不挂认证。两个使用方：
     *   - AutofillAuthActivity Mode A：逐账号认证通过后本地重建的单个数据集；
     *   - AutofillAuthActivity Mode B（自愈）：用户刚通过 CryptoObject 验证、桥刚灌活，
     *     一次性回包的所有命中数据集（见 AutofillSelfHeal 的 KDoc）。
     * 取值约定与迁移前认证页实现逐行等价：用户名/手机/邮箱三类框填 username（为空则
     * 跳过该框），密码框填 password。
     */
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

    // ==================== 响应组装 ====================

    /**
     * 每账号构建一个数据集（上限 MAX_DATASETS）；单卡构建失败只跳过该卡，
     * 不拖垮整个响应（迁移前服务端 buildFillResponse 的逐卡 try/catch 原样上移）。
     */
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

    /**
     * 组装 FillResponse：数据集全部塞入 + SaveInfo 无条件附加（web/app 双轨都支持保存，
     * web 上下文的域名由系统自动回显）；SaveInfo 的既定形态=密码必填 + 用户名可选，
     * FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE = 页面全部视图不可见时触发保存。
     * 无数据集且无任何节点时返回 null（调用方静默放弃）。
     */
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
