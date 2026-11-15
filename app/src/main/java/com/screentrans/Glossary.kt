package com.screentrans

/**
 * Discord / Patreon / 游戏圈的固定译法。
 * 只收「在聊天语境里基本不会歧义」的词；like / post / save 这类多义词刻意不收，
 * 避免把 "I like this" 翻成「我点赞这个」。
 */
object Glossary {

    private val builtin: LinkedHashMap<String, String> = linkedMapOf(
        // Discord 结构
        "server" to "服务器",
        "channel" to "频道",
        "thread" to "子区",
        "dm" to "私信",
        "direct message" to "私信",
        "nitro" to "Nitro",
        "boost" to "助力",
        "role" to "身份组",
        "emoji" to "表情",
        "sticker" to "贴纸",
        "embed" to "嵌入卡片",
        "mention" to "提及",
        "ping" to "延迟",
        "mute" to "静音",
        "unmute" to "取消静音",
        "deafen" to "关闭语音",
        "afk" to "暂离",
        "lfg" to "找队友",
        "lfm" to "缺人",
        // Patreon 结构
        "tier" to "档位",
        "patron" to "赞助人",
        "pledge" to "赞助",
        "exclusive" to "专属",
        "membership" to "会员",
        "creator" to "创作者",
        "benefit" to "权益",
        "billing" to "账单",
        "renew" to "续订",
        "early access" to "抢先体验",
        // 网络缩写
        "gg" to "打得漂亮",
        "wp" to "干得漂亮",
        "glhf" to "祝好运",
        "ngl" to "说实话",
        "tbh" to "说实话",
        "imo" to "我觉得",
        "imho" to "恕我直言",
        "iirc" to "如果我没记错",
        "brb" to "马上回来",
        "bbl" to "待会儿聊",
        "lol" to "哈哈",
        "lmao" to "笑死",
        "rofl" to "笑翻",
        "wtf" to "搞什么",
        "omg" to "天哪",
        "based" to "有态度",
        "cringe" to "尴尬",
        "sus" to "可疑",
        "flex" to "炫耀",
        "mid" to "很一般",
        "goat" to "最强",
        // 游戏
        "grind" to "肝",
        "nerf" to "削弱",
        "buff" to "增强",
        "camp" to "蹲点",
        "respawn" to "复活",
        "speedrun" to "速通",
        "noob" to "新手",
        "tryhard" to "太较真",
        "clutch" to "关键发挥",
        "aggro" to "仇恨",
        "dps" to "输出",
        "tank" to "坦克",
        "healer" to "治疗",
        "boss" to "首领",
        "raid" to "团本",
        "dungeon" to "地下城",
        "loot" to "战利品",
        "quest" to "任务",
        "pvp" to "玩家对战",
        "pve" to "玩家对环境",
        "co-op" to "合作",
        "meta" to "版本主流",
        "patch" to "补丁",
        "hotfix" to "热修",
        "dlc" to "追加内容",
        "f2p" to "免费游玩",
        "p2w" to "付费即赢",
        "whale" to "氪佬",
        "gacha" to "抽卡",
        "pity" to "保底",
        "banner" to "卡池",
        "reroll" to "刷初始",
        "stamina" to "体力",
        "rng" to "随机",
        "crit" to "暴击",
        "aoe" to "范围伤害",
        "cc" to "控制",
        "dot" to "持续伤害",
        "kiting" to "放风筝",
        "griefing" to "恶意捣乱",
        "smurf" to "小号虐菜",
        "toxic" to "嘴臭",
        "throw" to "摆烂",
        "carry" to "带飞",
        "feed" to "送人头",
        "inting" to "故意送",
        "ward" to "插眼",
        "jungler" to "打野",
        "gank" to "抓人",
        "ult" to "大招",
        "cd" to "冷却",
        "hp" to "血量",
        "mp" to "蓝量",
        "xp" to "经验",
        "level up" to "升级",
        "prestige" to "转生",
        "battle pass" to "通行证",
        "cosmetic" to "外观",
        "skin" to "皮肤",
        "emote" to "表情动作",
        "lag" to "卡顿",
        "fps" to "帧率"
    )

    private val patterns: Map<String, Regex> = builtin.keys.associateWith { term ->
        Regex("(?<![a-z0-9])" + Regex.escape(term) + "(?![a-z0-9])")
    }

    /** 只返回命中的词条，避免把整张表塞进 prompt。 */
    fun matched(text: String, extras: Map<String, String> = emptyMap()): Map<String, String> {
        val lower = text.lowercase()
        val out = LinkedHashMap<String, String>()
        for ((term, zh) in builtin) {
            val pattern = patterns[term] ?: continue
            if (pattern.containsMatchIn(lower)) out[term] = zh
        }
        for ((term, zh) in extras) {
            val t = term.lowercase()
            if (t.isBlank()) continue
            val p = Regex("(?<![a-z0-9])" + Regex.escape(t) + "(?![a-z0-9])")
            if (p.containsMatchIn(lower)) out[t] = zh
        }
        return out
    }

    /** 用户自定义术语表，一行一条：english=中文 */
    fun extra(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val en = line.substring(0, idx).trim()
            val zh = line.substring(idx + 1).trim()
            if (en.isNotEmpty() && zh.isNotEmpty()) out[en] = zh
        }
        return out
    }
}
