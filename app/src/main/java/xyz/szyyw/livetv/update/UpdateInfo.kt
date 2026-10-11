package xyz.szyyw.livetv.update

import org.json.JSONObject

/** 更新清单：{"versionCode":7,"versionName":"0.3.0","apk":"https://…","notes":"…"} */
/** 手动检查更新的结果。 */
sealed interface CheckResult {
    data class Newer(val info: UpdateInfo) : CheckResult
    data object UpToDate : CheckResult
    data class Failed(val reason: String) : CheckResult
}

data class UpdateInfo(val versionCode: Int, val versionName: String, val apk: String, val notes: String) {
    fun isNewerThan(currentVersionCode: Int) = versionCode > currentVersionCode

    companion object {
        /** 对已拿到的清单文本做判定：格式错 → Failed，不比当前新 → UpToDate。 */
        fun evaluate(text: String?, currentVersionCode: Int): CheckResult {
            val info = parse(text) ?: return CheckResult.Failed("更新清单格式不正确")
            return if (info.isNewerThan(currentVersionCode)) CheckResult.Newer(info) else CheckResult.UpToDate
        }

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
