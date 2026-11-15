package com.screentrans

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把当前窗口的节点树压平成「一段段可翻译的文本 + 它在屏幕上的矩形」。
 *
 * 抓取策略的演进（都是踩过的坑）：
 *  1. 上限从 80 提到 200：Discord 一行消息就有昵称、徽章、时间、回复前缀、正文、
 *     反应、已编辑……树里文本节点比想象的多，80 会在下半屏就截断；
 *  2. 父子重复节点按「矩形包含 + 文本更长」去重：React Native 常把整条消息和每一行
 *     都暴露成节点，重复占坑会把后面的消息挤掉；
 *  3. 不再信 isVisibleToUser（RN 在滚动容器里经常误报 false），改用屏幕矩形判断；
 *  4. 长消息上限 1200 → 3000 字。
 */
object Scanner {

    private const val MAX_ITEMS = 200
    private const val MAX_KEPT = 120
    private const val MAX_DEPTH = 80
    private val letterRegex = Regex("[A-Za-z]")

    fun collect(
        root: AccessibilityNodeInfo,
        selfPackage: String,
        pkg: String,
        density: Float
    ): List<RawItem> {
        val out = ArrayList<RawItem>()
        val seen = HashSet<String>()
        walk(root, null, selfPackage, pkg, density, out, seen, 0)
        if (out.isEmpty()) return emptyList()

        // 大块优先保留：被大块完全包住、且文本更短的节点是重复项
        val byArea = out.sortedByDescending { it.rect.width().toLong() * it.rect.height() }
        val kept = ArrayList<RawItem>(byArea.size)
        for (item in byArea) {
            var covered = false
            for (k in kept) {
                if (k.rect.contains(item.rect) && k.text.length >= item.text.length) {
                    covered = true
                    break
                }
            }
            if (!covered) kept.add(item)
        }
        kept.sortBy { it.rect.top }
        return if (kept.size > MAX_KEPT) kept.subList(0, MAX_KEPT) else kept
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        parentText: String?,
        selfPackage: String,
        pkg: String,
        density: Float,
        out: MutableList<RawItem>,
        seen: MutableSet<String>,
        depth: Int
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_ITEMS) return
        if (node.packageName?.toString() == selfPackage) return

        val ownText = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val text: String? = when {
            ownText != null -> ownText
            // 纯图标的 contentDescription 通常是「发送消息」这类按钮标签，值得翻；
            // 不可点击节点上的描述往往是噪音，跳过。
            desc != null && node.isClickable && desc.length in 2..80 -> desc
            else -> null
        }

        if (text != null && text != parentText) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (isTranslatable(text, node, rect, density)) {
                val viewKey = "$pkg|${rect.left},${rect.top},${rect.width()}x${rect.height()}|${text.hashCode()}"
                if (seen.add(viewKey)) {
                    out.add(
                        RawItem(
                            pkg = pkg,
                            viewKey = viewKey,
                            text = text,
                            rect = rect,
                            className = node.className?.toString(),
                            clickable = node.isClickable
                        )
                    )
                }
            }
        }

        val childParentText = ownText ?: parentText
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), childParentText, selfPackage, pkg, density, out, seen, depth + 1)
        }
    }

    private fun isTranslatable(
        text: String,
        node: AccessibilityNodeInfo,
        rect: Rect,
        density: Float
    ): Boolean {
        if (text.length < 2 || text.length > 3000) return false
        if (!letterRegex.containsMatchIn(text)) return false
        // 中英混排且以中文为主（例如「@nom 点击查看附件」）说明本来就是中文界面，跳过
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (cjk > 0 && cjk >= latin) return false
        if (node.isPassword || node.isEditable) return false
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText") || cls.contains("AutoComplete")) return false
        if (rect.width() < 24 * density || rect.height() < 10 * density) return false
        if (rect.bottom <= 0 || rect.top >= TotalScreen.height) return false
        return true
    }

    /** 只在判断节点是否还在屏幕内时用到，由 Service 在启动时填充。 */
    object TotalScreen {
        var height: Int = 2400
    }
}
