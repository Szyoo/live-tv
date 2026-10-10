package xyz.szyyw.livetv.playlist

/**
 * 频道清单解析：自动识别 m3u / txt。纯 Kotlin，不依赖 Android。
 * 同名频道合并为多条线路，保持首次出现顺序，全局连续编号。
 */
object PlaylistParser {
    const val UNGROUPED = "未分组"

    fun parse(raw: String): Playlist {
        val text = raw.removePrefix("﻿")
        val lines = text.split('\n').map { it.trim('\r', ' ', '\t', '﻿') }
        val isM3u = lines.any { it.startsWith("#EXTM3U", ignoreCase = true) || it.startsWith("#EXTINF", ignoreCase = true) }
        val entries = if (isM3u) parseM3u(lines) else parseTxt(lines)
        return merge(entries)
    }

    private class Entry(val name: String, val group: String, val logo: String?, val stream: Stream)

    private fun merge(entries: List<Entry>): Playlist {
        val byName = LinkedHashMap<String, MutableList<Entry>>()
        for (e in entries) byName.getOrPut(e.name) { mutableListOf() }.add(e)
        var n = 0
        val channels = byName.map { (name, list) ->
            val streams = LinkedHashMap<String, Stream>()
            for (e in list) streams.putIfAbsent(e.stream.url, e.stream)
            n++
            Channel(
                number = n,
                name = name,
                group = list.first().group,
                logo = list.firstNotNullOfOrNull { it.logo },
                streams = streams.values.toList(),
            )
        }
        return Playlist(channels)
    }

    // ---------- m3u ----------

    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,"']+))""")

    private fun parseM3u(lines: List<String>): List<Entry> {
        val out = ArrayList<Entry>()
        var name: String? = null
        var group: String? = null
        var logo: String? = null
        var extGrp: String? = null
        var headers = LinkedHashMap<String, String>()

        fun reset() {
            name = null; group = null; logo = null; extGrp = null; headers = LinkedHashMap()
        }

        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        reset()
                        val body = line.substringAfter(':', "")
                        val comma = firstCommaOutsideQuotes(body)
                        val meta = if (comma >= 0) body.substring(0, comma) else body
                        val display = if (comma >= 0) body.substring(comma + 1).trim() else ""
                        val attrs = HashMap<String, String>()
                        for (m in attrRegex.findAll(meta)) {
                            val v = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
                            attrs[m.groupValues[1].lowercase()] = v.trim()
                        }
                        val resolved = display.ifEmpty { attrs["tvg-name"].orEmpty() }
                        name = resolved.trim().ifEmpty { null }
                        group = attrs["group-title"]?.ifEmpty { null }
                        logo = attrs["tvg-logo"]?.ifEmpty { null }
                    }
                    line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                        val kv = line.substringAfter(':', "")
                        val key = kv.substringBefore('=', "").trim().lowercase()
                        val value = kv.substringAfter('=', "").trim().trim('"')
                        if (value.isNotEmpty()) when (key) {
                            "http-user-agent" -> headers["User-Agent"] = value
                            "http-referrer", "http-referer" -> headers["Referer"] = value
                        }
                    }
                    line.startsWith("#EXTGRP", ignoreCase = true) -> {
                        extGrp = line.substringAfter(':', "").trim().ifEmpty { null }
                    }
                }
                continue
            }
            // 地址行：必须是合法 URL 且前面有名字，否则跳过
            if (!looksLikeUrl(line)) continue
            val chName = name
            if (chName == null) { reset(); continue }
            val (url, extra) = splitPipeHeaders(line)
            val h = LinkedHashMap(headers)
            h.putAll(extra)
            out += Entry(chName, group ?: extGrp ?: UNGROUPED, logo, Stream(url, h))
            reset()
        }
        return out
    }

    private fun firstCommaOutsideQuotes(s: String): Int {
        var quote: Char? = null
        for (i in s.indices) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                // 只把紧跟在 = 后的引号当作属性值引号，避免显示名里的撇号误判
                if (i > 0 && s[i - 1] == '=') quote = c
            } else if (c == ',') return i
        }
        return -1
    }

    // ---------- txt ----------

    private fun parseTxt(lines: List<String>): List<Entry> {
        val out = ArrayList<Entry>()
        var group = UNGROUPED
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
            val comma = line.indexOf(',')
            if (comma <= 0) continue
            val left = line.substring(0, comma).trim()
            val right = line.substring(comma + 1).trim()
            if (right.equals("#genre#", ignoreCase = true)) {
                group = left.ifEmpty { UNGROUPED }
                continue
            }
            if (left.isEmpty()) continue
            for (part in right.split('#')) {
                val u = part.trim()
                if (!looksLikeUrl(u)) continue
                val (url, extra) = splitPipeHeaders(u)
                out += Entry(left, group, null, Stream(url, extra))
            }
        }
        return out
    }

    // ---------- 共用 ----------

    private val schemeRegex = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://\S+""")

    private fun looksLikeUrl(s: String) = schemeRegex.containsMatchIn(s)

    /** Kodi 风格 `url|User-Agent=xx&Referer=yy`。 */
    private fun splitPipeHeaders(s: String): Pair<String, Map<String, String>> {
        val i = s.indexOf('|')
        if (i < 0) return s to emptyMap()
        val url = s.substring(0, i)
        val map = LinkedHashMap<String, String>()
        for (kv in s.substring(i + 1).split('&')) {
            val k = kv.substringBefore('=', "").trim()
            val v = kv.substringAfter('=', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) map[canonicalHeader(k)] = v
        }
        return url to map
    }

    private fun canonicalHeader(k: String) = when (k.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        else -> k
    }
}
