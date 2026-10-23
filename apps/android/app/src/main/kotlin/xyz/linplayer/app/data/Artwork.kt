package xyz.linplayer.app.data

import android.net.Uri

/** 只生成展示候选，沿用原图片的服务器与代理尺寸，不改条目的图片归属。 */
fun artworkFallbacks(primaryUrl: String?, item: Item): List<String> {
    if (primaryUrl.isNullOrBlank() || item.type !in listOf("Episode", "Season")) return emptyList()
    val proxy = Uri.parse(primaryUrl)
    if (!proxy.isHierarchical) return emptyList()
    val source = proxy.getQueryParameter("src") ?: return emptyList()
    val upstream = Uri.parse(source)
    val suffix = "/Items/${item.id}/Images/Primary"
    val path = upstream.path ?: return emptyList()
    if (!path.endsWith(suffix)) return emptyList()
    val candidates = buildList {
        if (item.isEpisode) {
            if (item.hasBackdrop) add(item.id to "Backdrop/0")
            item.seasonId?.takeIf { it.isNotBlank() && it != item.id }?.let { add(it to "Primary") }
            item.seriesId?.takeIf { it.isNotBlank() && it != item.id }?.let { add(it to "Backdrop/0") }
        }
        item.seriesId?.takeIf { it.isNotBlank() && it != item.id }?.let { add(it to "Primary") }
    }
    return candidates.distinct().map { (id, kind) ->
        val next = upstream.buildUpon().path(path.removeSuffix(suffix) + "/Items/$id/Images/$kind").build()
        proxy.buildUpon().clearQuery().apply {
            proxy.queryParameterNames.forEach { name ->
                proxy.getQueryParameters(name).forEach { value ->
                    appendQueryParameter(name, if (name == "src") next.toString() else value)
                }
            }
        }.build().toString()
    }
}
