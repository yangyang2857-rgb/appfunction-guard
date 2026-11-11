package io.github.yangyang2857.afguard.sample

/**
 * 纯内存仓库，仅用于演示。
 *
 * 刻意在数据里塞了手机号和邮箱：这样"返回值脱敏"不是一句空话，
 * 而是真的能在界面上看到 `138****8000`。
 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val phone: String? = null
)

object NoteRepository {

    private val notes = linkedMapOf<String, Note>()

    init {
        seed()
    }

    private fun seed() {
        notes["n_001"] = Note(
            id = "n_001",
            title = "房东电话",
            body = "下个月房租到期，记得提前一周联系。",
            phone = "13800138000"
        )
        notes["n_002"] = Note(
            id = "n_002",
            title = "体检报告",
            body = "复查时间 11 月 20 日，报告编号 R-2291。",
            phone = "13912345678"
        )
        notes["n_003"] = Note(
            id = "n_003",
            title = "快递代收点",
            body = "驿站老板姓陈，晚上九点关门。",
            phone = "13700001111"
        )
    }

    @Synchronized
    fun recent(limit: Int): List<Note> = notes.values.take(limit)

    @Synchronized
    fun find(id: String): Note? = notes[id]

    @Synchronized
    fun delete(id: String): Boolean = notes.remove(id) != null

    @Synchronized
    fun wipe(): Int {
        val count = notes.size
        notes.clear()
        return count
    }

    @Synchronized
    fun reset() {
        notes.clear()
        seed()
    }
}
