package xyz.szyyw.livetv.logic

/** 播放失败的类别，及面向用户的中文说明。纯 Kotlin，可单测。 */
enum class FailureKind { HTTP, TIMEOUT, NETWORK, FORMAT, DECODER, STALL, ENDED, OTHER }

data class Failure(val kind: FailureKind, val httpStatus: Int? = null) {
    fun describe(): String = when (kind) {
        FailureKind.HTTP -> when (httpStatus) {
            null -> "服务器返回错误"
            401, 403, 451 -> "服务器拒绝访问（HTTP $httpStatus，可能有地区或权限限制）"
            404, 410 -> "线路不存在或已失效（HTTP $httpStatus）"
            in 500..599 -> "服务器故障（HTTP $httpStatus）"
            else -> "服务器返回 HTTP $httpStatus"
        }
        FailureKind.TIMEOUT -> "连接超时"
        FailureKind.NETWORK -> "无法连接：网络不可用或服务器无响应"
        FailureKind.FORMAT -> "格式不支持，或该线路返回的不是视频内容"
        FailureKind.DECODER -> "本机无法解码该视频"
        FailureKind.STALL -> "缓冲超时，长时间没有数据"
        FailureKind.ENDED -> "直播流已结束"
        FailureKind.OTHER -> "播放出错"
    }

    companion object {
        /**
         * @param errorCode media3 的 PlaybackException.errorCode（以数值判断，不依赖 Android 类）
         * @param httpStatus 若底层是 HTTP 状态错误，则为状态码
         */
        fun classify(errorCode: Int, httpStatus: Int? = null): Failure {
            if (httpStatus != null) return Failure(FailureKind.HTTP, httpStatus)
            return when (errorCode) {
                2002 -> Failure(FailureKind.TIMEOUT)
                2001, 2007 -> Failure(FailureKind.NETWORK)
                2004 -> Failure(FailureKind.HTTP)
                2005 -> Failure(FailureKind.HTTP, 404)
                2003, in 3001..3004 -> Failure(FailureKind.FORMAT)
                in 4001..4005, in 5001..5003 -> Failure(FailureKind.DECODER)
                else -> Failure(FailureKind.OTHER)
            }
        }
    }
}
