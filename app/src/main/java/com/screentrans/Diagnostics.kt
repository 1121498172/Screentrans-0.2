package com.screentrans

/**
 * 一次扫描的逐条诊断：每条消息是被抓到了没翻、翻了没画，还是压根没进列表。
 * 没有这个，只能对着截图猜。
 */
object Diagnostics {

    class Row(val source: String, val target: String?, val note: String)

    private val rows = ArrayList<Row>()

    @Volatile
    var captured: Int = 0

    @Volatile
    var rendered: Int = 0

    @Volatile
    var at: Long = 0L

    @Synchronized
    fun begin(capturedCount: Int) {
        captured = capturedCount
        rows.clear()
        at = System.currentTimeMillis()
    }

    @Synchronized
    fun add(source: String, target: String?, note: String) {
        if (rows.size >= 80) return
        rows.add(Row(source.replace('\n', ' ').take(90), target?.replace('\n', ' ')?.take(90), note))
    }

    @Synchronized
    fun setRendered(count: Int) {
        rendered = count
    }

    @Synchronized
    fun dump(): String {
        if (at == 0L) return "还没有扫描记录。打开 Discord 停留 3 秒再回来。"
        val sb = StringBuilder()
        sb.append("抓到 ").append(captured).append(" 条，画出 ").append(rendered).append(" 条\n")
        sb.append("时间：").append(at).append("\n\n")
        for (row in rows) {
            sb.append('[').append(row.note).append("] ")
            sb.append(row.source)
            if (row.target != null) sb.append("  →  ").append(row.target)
            sb.append('\n')
        }
        return sb.toString()
    }
}
