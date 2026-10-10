package xyz.szyyw.livetv.update

import org.json.JSONObject

/** 更新清单：{"versionCode":7,"versionName":"0.3.0","apk":"https://…","notes":"…"} */
data class UpdateInfo(val versionCode: Int, val versionName: String, val apk: String, val notes: String) {
    fun isNewerThan(currentVersionCode: Int) = versionCode > currentVersionCode

    companion object {
        /** 格式不对、缺字段、地址非 http(s) 一律返回 null（静默跳过）。 */
        fun parse(text: String?): UpdateInfo? {
            if (text.isNullOrBlank()) return null
            return try {
                val o = JSONObject(text.trim())
                val code = o.optInt("versionCode", -1)
                val apk = o.optString("apk", "").trim()
                if (code <= 0) return null
                if (!(apk.startsWith("http://") || apk.startsWith("https://"))) return null
                UpdateInfo(code, o.optString("versionName", "").ifBlank { code.toString() }, apk, o.optString("notes", ""))
            } catch (e: Exception) {
                null
            }
        }
    }
}
