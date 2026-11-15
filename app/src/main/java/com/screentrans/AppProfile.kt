package com.screentrans

/**
 * 覆盖层配色。Discord 深色是 #313338，浅色是 #FFF，尽量贴合原 App，
 * 让译文看起来像原生文本而不是贴纸。
 */
object AppProfile {

    data class Style(val surface: Int, val text: Int)

    private val discordDark = Style(0xFF313338.toInt(), 0xFFDBDEE1.toInt())
    private val discordLight = Style(0xFFFFFFFF.toInt(), 0xFF060607.toInt())

    private val patreonDark = Style(0xFF1A1A1A.toInt(), 0xFFF2F2F2.toInt())
    private val patreonLight = Style(0xFFFFFFFF.toInt(), 0xFF111111.toInt())

    private val socialDark = Style(0xFF000000.toInt(), 0xFFE7E9EA.toInt())
    private val socialLight = Style(0xFFFFFFFF.toInt(), 0xFF0F1419.toInt())

    private val genericDark = Style(0xFF1B1B1F.toInt(), 0xFFE6E1E5.toInt())
    private val genericLight = Style(0xFFFFFBFE.toInt(), 0xFF1C1B1F.toInt())

    fun styleFor(pkg: String, theme: String, systemNight: Boolean): Style {
        val dark = when (theme) {
            "dark" -> true
            "light" -> false
            else -> systemNight
        }
        return when {
            pkg == "com.discord" -> if (dark) discordDark else discordLight
            pkg == "com.patreon.android" -> if (dark) patreonDark else patreonLight
            pkg.startsWith("com.twitter") || pkg == "com.reddit.frontpage" ->
                if (dark) socialDark else socialLight
            else -> if (dark) genericDark else genericLight
        }
    }
}
