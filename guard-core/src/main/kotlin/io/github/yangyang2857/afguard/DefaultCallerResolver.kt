package io.github.yangyang2857.afguard

import android.os.Binder
import android.os.Process

/**
 * 默认调用方解析。
 *
 * 已知局限（写在文档里，不藏）：
 * 1. AppFunctions 的 Executor 可能在同进程执行，此时 [Binder.getCallingUid] 返回自身 uid，
 *    无法区分"哪个 Agent 在调"。这种情况下 Guard 退化为"不区分调用方"，但限流与审计仍然生效。
 * 2. 不同 OEM 的 Executor 实现有差异（论文在 Pixel 10 Pro 与 Galaxy S26 上就观察到了行为不同），
 *    所以这个类故意做成可替换的 [CallerResolver]，宿主可以按机型注入更精确的实现。
 */
object DefaultCallerResolver : CallerResolver {

    override fun resolve(functionId: String): CallerIdentity {
        val uid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        val privileged = uid in 0 until Process.FIRST_APPLICATION_UID

        val packageName = if (uid >= 0) {
            GuardConfig.appContextOrNull()
                ?.packageManager
                ?.getPackagesForUid(uid)
                ?.firstOrNull()
                ?: "uid:$uid"
        } else {
            "unknown"
        }

        return CallerIdentity(
            packageName = packageName,
            uid = uid,
            isPrivileged = privileged,
            signatureHash = null
        )
    }
}
