package io.github.yangyang2857.afguard.sample

import android.app.Application
import io.github.yangyang2857.afguard.GuardConfig
import io.github.yangyang2857.afguard.Policy
import io.github.yangyang2857.afguard.internal.RingBufferAuditSink

/**
 * 接入成本演示：一个 Application.onCreate 里全部配完，没有 DI、没有 Builder。
 */
class SampleApp : Application() {

    lateinit var confirmationHandler: DialogConfirmationHandler
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. 必须最先调用，Guard 取 App 名与调用方包名都依赖它
        GuardConfig.init(this)

        // 2. 默认策略：未标注 @Guard 的函数也走保守策略，而不是悄悄放行
        GuardConfig.defaultPolicy = Policy.CONFIRM_ONCE

        // 3. 确认超时。超时按拒绝处理，避免 Agent 挂死在这里
        GuardConfig.confirmationTimeoutMillis = 20_000L

        // 4. 宿主自己的确认 UI
        confirmationHandler = DialogConfirmationHandler()
        GuardConfig.confirmationHandler = confirmationHandler

        // 5. 审计出口：换成文件 / 上报 / SQLite 都可以，接口只有一个 record()
        GuardConfig.auditSink = RingBufferAuditSink(capacity = 500)

        // 6. 挂上出口。漏掉这一行，审计记录会静默丢失
        GuardConfig.apply()
    }
}
