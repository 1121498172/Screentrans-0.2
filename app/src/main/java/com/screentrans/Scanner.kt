package com.screentrans

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把当前窗口的节点树压平成「一段段可翻译的文本 + 它在屏幕上的矩形」。
 * 关键点：
 *  1. 跳过输入框 / 密码框，绝不把用户输入的内容送去翻译；
 *  2. 父子节点文本相同时只保留一个，避免译文叠两层；
 *  3. 数量设上限，Discord 长列表不会一次扒几千个节点。
 */
object Scanner {

    private const val MAX_ITEMS = 80
    private const val MAX_DEPTH = 60
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
        out.sortBy { it.rect.top }
        return out
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
        if (text.length < 2 || text.length > 1200) return false
        if (!letterRegex.containsMatchIn(text)) return false
        // 中英混排且以中文为主（例如「@nom 点击查看附件」）说明本来就是中文界面，跳过
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (cjk > 0 && cjk >= latin) return false
        if (node.isPassword || node.isEditable) return false
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText") || cls.contains("AutoComplete")) return false
        if (!node.isVisibleToUser) return false
        if (rect.width() < 24 * density || rect.height() < 10 * density) return false
        if (rect.bottom <= 0 || rect.top >= TotalScreen.height) return false
        return true
    }

    /** 只在判断节点是否还在屏幕内时用到，由 Service 在启动时填充。 */
    object TotalScreen {
        var height: Int = 2400
    }
}
