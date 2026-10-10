package xyz.szyyw.livetv.playlist

/** 一条线路：地址 + 该线路专属的请求头（键为 User-Agent / Referer 等）。 */
data class Stream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 一个频道：同名线路已合并；number 为全局连续编号（从 1 开始）。 */
data class Channel(
    val number: Int,
    val name: String,
    val group: String,
    val logo: String?,
    val streams: List<Stream>,
)

data class Playlist(val channels: List<Channel>) {
    val isEmpty: Boolean get() = channels.isEmpty()
}
