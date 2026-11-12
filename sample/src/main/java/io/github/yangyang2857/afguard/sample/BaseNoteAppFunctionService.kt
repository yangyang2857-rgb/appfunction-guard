package io.github.yangyang2857.afguard.sample

import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import io.github.yangyang2857.afguard.AfGuard
import io.github.yangyang2857.afguard.GuardResult
import io.github.yangyang2857.afguard.GuardedCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阶段 B：平台接线。
 *
 * 这里只做三件事——把系统调用翻译成 guard 的 [AfGuard.intercept]，把结果翻译回字符串，
 * 以及在协程里让出 UI 线程。
 *
 * 为什么是 abstract：rc01 之后 App Function 走编译期入口点架构，[AppFunctionServiceEntryPoint]
 * 只标注在抽象类上，KSP 生成具体实现（`NoteAppFunctionService`）与
 * `res/xml/note_app_function_service.xml`，所以业务代码里没有一行 `onExecuteFunction` 的
 * when 分支——分发、参数反序列化、错误映射都由生成代码承担。
 *
 * 为什么全部包在 withContext(Dispatchers.IO)：函数默认跑在 UI 线程，而 guard 的确认链路
 * 是阻塞等用户点按钮（`DialogConfirmationHandler` 内部再用 runOnUiThread 弹窗）。
 * 混在一起就是一个必死的死锁：主线程等 latch，弹窗等主线程。切到 IO 之后，
 * 调用线程阻塞、弹窗线程自由，这也是 sample UI 里按钮走 executor 的同一个理由。
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "NoteAppFunctionService",
    appFunctionXmlFileName = "note_app_function_service",
)
abstract class BaseNoteAppFunctionService : AppFunctionService() {

    /** 列出最近的笔记标题，只读查询，直接放行。 */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun listNotes(limit: Int): String =
        dispatch("listNotes", Bundle().apply { putInt("limit", limit) }) { call ->
            NoteFunctions.listNotes(call.requireInt("limit")).joinToString("\n")
        }

    /** 按 id 删除一条笔记，首次调用需要用户确认。 */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun deleteNote(id: String): String =
        dispatch("deleteNote", Bundle().apply { putString("id", id) }) { call ->
            NoteFunctions.deleteNote(call.requireString("id"))
        }

    /** 导出全部笔记，含手机号，属于高风险外带，每次都要确认且返回值脱敏。 */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun exportNotes(format: String): String =
        dispatch("exportNotes", Bundle().apply { putString("format", format) }) { call ->
            NoteFunctions.exportNotes(call.requireString("format"))
        }

    /** 清空所有笔记，不可逆操作，对 Agent 永久拒绝。 */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun wipeAllNotes(): String =
        dispatch("wipeAllNotes", Bundle()) { _ ->
            NoteFunctions.wipeAllNotes()
        }

    /**
     * 统一出入口：拦截发生在业务函数之前，返回值用带前缀的文本表达拦截原因，
     * 让 Agent 拿到的是"被 guard 拒绝了，原因是 X"，而不是一个不透明的异常。
     */
    private suspend fun dispatch(
        functionId: String,
        args: Bundle,
        block: (GuardedCall) -> Any?,
    ): String = withContext(Dispatchers.IO) {
        when (val result = AfGuard.intercept(functionId, args, block)) {
            is GuardResult.Ok -> result.value?.toString().orEmpty()
            is GuardResult.Err -> "AFGUARD_BLOCKED ${result.error.toJson()}"
        }
    }
}
