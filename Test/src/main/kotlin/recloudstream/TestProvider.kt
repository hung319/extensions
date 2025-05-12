package com.lagradost.cloudstream3.plugins // Hoặc package name bạn muốn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Xvv1deosProvider : MainAPI() {
    override var mainUrl = "https://www.xvv1deos.com"
    override var name = "Xvv1deos"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("p.title a")
        val title = titleElement?.attr("title")?.ifBlank { titleElement.text() } ?: titleElement?.text() ?: return null
        val href = titleElement?.attr("href") ?: return null
        val fullUrl = if (href.startsWith("http")) href else mainUrl + href


        val imageElement = this.selectFirst("div.thumb a img")
        var image = imageElement?.attr("data-src")
        if (image.isNullOrBlank() || image.contains("lightbox/lightbox-blank.gif")) {
            image = imageElement?.attr("src")
        }
        if (image.isNullOrBlank() || image.contains("lightbox/lightbox-blank.gif")){
            image = null
        }

        return newAnimeSearchResponse(title, fullUrl, TvType.Movie) {
            this.posterUrl = image
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageNumber = if (page > 1) "/new/${page - 1}" else ""
        val document = app.get("$mainUrl$pageNumber").document

        val items = document.select("div.mozaique div.thumb-block")?.mapNotNull {
            it.toSearchResponse()
        }

        return HomePageResponse(listOf(HomePageList("Videos", items ?: emptyList())))
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/?k=$query").document

        return document.select("div.mozaique div.thumb-block")?.mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h2.page-title")?.ownText()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        var description = document.selectFirst("meta[name=description]")?.attr("content")
        if (description.isNullOrBlank()) {
            description = document.selectFirst("div.video-description-text")?.text()
        }

        var tags: List<String>? = null
        val scriptTagWithConfig = document.select("script").find { it.html().contains("xv.conf") }?.html()
        if (scriptTagWithConfig != null) {
            val tagsRegex = Regex("""video_tags"\s*:\s*(\[.*?\])""") // Regex để trích video_tags
            val tagsMatch = tagsRegex.find(scriptTagWithConfig)
            if (tagsMatch != null && tagsMatch.groupValues.size > 1) {
                try {
                    val tagsJsonArrayString = tagsMatch.groupValues[1]
                    tags = tagsJsonArrayString.removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotBlank() }
                } catch (_: Exception) { /* Bỏ qua nếu parse lỗi */ }
            }
        }
        if (tags.isNullOrEmpty()) { // Nếu không lấy được từ script, thử từ HTML
            tags = document.select("div.video-tags-list li a.is-keyword")?.map { it.text() }?.filter { it.isNotBlank() }
        }

        val uploaderName = document.selectFirst("div.video-tags-list li.main-uploader a.uploader-tag span.name")?.text()
        val durationString = document.selectFirst("h2.page-title span.duration")?.text()
        val durationMinutes = durationString?.let { parseDuration(it) }

        val recommendations = mutableListOf<SearchResponse>()
        val scriptContent = document.select("script").find { it.html().contains("var video_related") }?.html()
        if (scriptContent != null) {
            val videoRelatedRegex = Regex("""var video_related=\[(.*?)\];""")
            val matchResult = videoRelatedRegex.find(scriptContent)

            if (matchResult != null) {
                val jsonArrayString = matchResult.groupValues[1]
                val itemRegex = Regex("""\{\s*"id":\s*\d+.*?,"u"\s*:\s*"(.*?)",\s*"i"\s*:\s*"(.*?)",.*?tf"\s*:\s*"(.*?)",.*?d"\s*:\s*"(.*?)"(?:.*?)\}""")
                itemRegex.findAll(jsonArrayString).forEach { itemMatch ->
                    try {
                        var recTitle = itemMatch.groupValues[3].replace("\\/", "/")
                        recTitle = unescapeUnicode(recTitle)
                        var recHref = itemMatch.groupValues[1].replace("\\/", "/")
                        var recImage = itemMatch.groupValues[2].replace("\\/", "/")

                        if (recTitle.isNotBlank() && recHref.isNotBlank()) {
                             if (!recHref.startsWith("http")) recHref = mainUrl + recHref
                             if (recImage.isNotBlank()) {
                                if (recImage.startsWith("//")) {
                                   recImage = "https:$recImage"
                                } else if (!recImage.startsWith("http") && recImage.startsWith("/")) {
                                   recImage = mainUrl + recImage
                                } else if (!recImage.startsWith("http") && !recImage.startsWith("/")){
                                    // Trường hợp này có thể image là một URL đầy đủ nhưng thiếu scheme, hoặc là path tương đối không chuẩn
                                    // Để an toàn, có thể không gán nếu không chắc chắn, hoặc thử thêm mainUrl
                                    // Nếu video_related.i luôn là URL đầy đủ hoặc bắt đầu bằng // hoặc /, thì logic trên là ổn
                                }
                             }


                            recommendations.add(newAnimeSearchResponse(recTitle, recHref, TvType.Movie) {
                                this.posterUrl = if (recImage.isNotBlank()) recImage else null
                            })
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            this.duration = durationMinutes
            uploaderName?.let { name ->
                val actor = Actor(name) // Tạo đối tượng Actor
                this.actors = listOf(ActorData(actor = actor)) // SỬA LỖI Ở ĐÂY: Bọc Actor trong ActorData
            }
        }
    }
    
    private fun parseDuration(durationString: String): Int? {
        var totalMinutes = 0
        val hourMatch = Regex("""(\d+)\s*h""").find(durationString)
        hourMatch?.let {
            totalMinutes += it.groupValues[1].toIntOrNull()?.times(60) ?: 0
        }
        val minMatch = Regex("""(\d+)\s*min""").find(durationString)
        minMatch?.let {
            totalMinutes += it.groupValues[1].toIntOrNull() ?: 0
        }
        val secMatch = Regex("""(\d+)\s*sec""").find(durationString)
        if (totalMinutes == 0 && secMatch != null) {
            if ((secMatch.groupValues[1].toIntOrNull() ?: 0) > 0) {
                totalMinutes = 1
            }
        }
        return if (totalMinutes > 0) totalMinutes else null
    }
    
    private fun unescapeUnicode(str: String): String {
        var result = str
        try {
            result = Regex("""\\u([0-9a-fA-F]{4})""").replace(str) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
        } catch (_: Exception) {}
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Hàm này sẽ được triển khai sau theo yêu cầu của bạn.
        // Hiện tại, nó sẽ không làm gì và trả về false.
        return false
    }
}
