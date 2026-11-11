package io.github.yangyang2857.afguard

import android.os.Bundle

/**
 * 把 [GuardResult] 转成回给平台的 Bundle。
 *
 * 这层存在的唯一理由是隔离：库内部用 Kotlin 类型表达结果，
 * 边界上一次性转成 Bundle，不让平台类型渗进业务代码。
 *
 * 返回约定（写进文档，方便 Agent 侧解析）：
 * - 成功：`afg_status = "ok"`，值放在 `afg_value` 或原始键
 * - 失败：`afg_status = "error"`，`afg_error` 是 [GuardError.toJson] 的 JSON 字符串
 */
object BundleCodec {

    const val KEY_STATUS = "afg_status"
    const val KEY_VALUE = "afg_value"
    const val KEY_ERROR = "afg_error"

    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "error"

    fun toBundle(result: GuardResult): Bundle = Bundle().apply {
        when (result) {
            is GuardResult.Ok -> {
                putString(KEY_STATUS, STATUS_OK)
                putBoolean("afg_redacted", result.redacted)
                when (val v = result.value) {
                    null -> Unit
                    is String -> putString(KEY_VALUE, v)
                    is Int -> putInt(KEY_VALUE, v)
                    is Long -> putLong(KEY_VALUE, v)
                    is Boolean -> putBoolean(KEY_VALUE, v)
                    is Bundle -> putBundle(KEY_VALUE, v)
                    is List<*> -> putStringArrayList(
                        KEY_VALUE,
                        ArrayList(v.map { it?.toString() ?: "" })
                    )
                    else -> putString(KEY_VALUE, v.toString())
                }
            }

            is GuardResult.Err -> {
                putString(KEY_STATUS, STATUS_ERROR)
                putString(KEY_ERROR, result.error.toJson())
                putBoolean("afg_retryable", result.error.retryable)
                result.error.retryAfterMs?.let { putLong("afg_retry_after_ms", it) }
            }
        }
    }

    /** 反向解析，主要给测试与 CLI 用。 */
    fun fromBundle(bundle: Bundle): GuardResult {
        val status = bundle.getString(KEY_STATUS) ?: return GuardResult.Ok(null)
        return if (status == STATUS_ERROR) {
            val json = bundle.getString(KEY_ERROR).orEmpty()
            GuardResult.Err(
                GuardError(
                    GuardErrorCode.UPSTREAM_ERROR,
                    function = "",
                    message = json
                )
            )
        } else {
            GuardResult.Ok(bundle.get(KEY_VALUE))
        }
    }
}
