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

        // Discord 的每条消息外面套了一层容器：容器文本只是「昵称 + 时间」，
        // 矩形却覆盖整条消息。如果它内部有文本更长、又不被它包含的子节点，
        // 说明正文在子节点里 —— 这个容器标签必须丢掉，否则会糊出一个
        // 巨大色块盖住正文，而块里只有昵称。
        val dropped = HashSet<Int>()
        for (i in out.indices) {
            val outer = out[i]
            val outerArea = outer.rect.width().toLong() * outer.rect.height()
            for (j in out.indices) {
                if (i == j) continue
                val inner = out[j]
                val innerArea = inner.rect.width().toLong() * inner.rect.height()
                if (innerArea >= outerArea) continue
                if (!outer.rect.contains(inner.rect)) continue
                if (inner.text.length > outer.text.length &&
                    !outer.text.contains(inner.text, ignoreCase = true)
                ) {
                    dropped.add(i)
                    break
                }
            }
        }
        val candidates = if (dropped.isEmpty()) out else out.filterIndexed { index, _ -> index !in dropped }

        // 大块优先保留：只有「大块矩形包住小块」且「大块文本确实包含小块文本」才算重复
        val byArea = candidates.sortedByDescending { it.rect.width().toLong() * it.rect.height() }
        val kept = ArrayList<RawItem>(byArea.size)
        for (item in byArea) {
            var covered = false
            for (k in kept) {
                if (k.rect.contains(item.rect) &&
                    k.text.length > item.text.length &&
                    k.text.contains(item.text, ignoreCase = true)
                ) {
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
        // 中英混排且以中文为主（例如「未读消息，SillyTavern」「rules（文字频道）」）说明
        // 本来就是中文界面，跳过。留一点余量，避免把「Discord 服务器」这种也误杀。
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (cjk > 0 && cjk * 2 >= latin) return false
        if (node.isPassword || node.isEditable) return false
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText") || cls.contains("AutoComplete")) return false
        if (rect.width() < 24 * density || rect.height() < 10 * density) return false
        // 侧边栏抽屉关着的时候，里面的节点坐标还在，但大部分在屏幕外。
        // 只保留至少一半面积落在屏幕内的节点，避免抓一堆看不见的东西。
        val visibleW = minOf(rect.right, TotalScreen.width) - maxOf(rect.left, 0)
        val visibleH = minOf(rect.bottom, TotalScreen.height) - maxOf(rect.top, 0)
        if (visibleW <= 0 || visibleH <= 0) return false
        if (visibleW * 2 < rect.width() || visibleH * 2 < rect.height()) return false
        return true
    }

    /** 只在判断节点是否还在屏幕内时用到，由 Service 在启动时填充。 */
    object TotalScreen {
        var width: Int = 1080
        var height: Int = 2400
    }
}
