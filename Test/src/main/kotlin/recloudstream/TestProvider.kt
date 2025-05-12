package com.lagradost.cloudstream3.plugins // Hoặc package name bạn muốn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
// import android.util.Log // Bỏ comment để dùng Log.d cho debug

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
        var originalHref = titleElement?.attr("href") ?: return null

        val urlCleanRegex = Regex("^(/video\\.[^/]+)/[^/]+/[^/]+/(.+)$")
        var cleanedHref = originalHref
        val matchResult = urlCleanRegex.find(originalHref)
        if (matchResult != null && matchResult.groupValues.size == 3) {
            val videoIdPath = matchResult.groupValues[1]
            val slug = matchResult.groupValues[2]
            cleanedHref = "$videoIdPath/$slug"
        }
        val fullUrl = if (cleanedHref.startsWith("http")) cleanedHref else mainUrl + cleanedHref

        var posterUrl: String? = null
        val imgTag = this.selectFirst("div.thumb a img")

        if (imgTag != null) {
            val dataSrc = imgTag.attr("data-src")
            val src = imgTag.attr("src")

            if (!dataSrc.isNullOrBlank() && !dataSrc.contains("lightbox-blank.gif")) {
                posterUrl = dataSrc
            }
            else if (!src.isNullOrBlank() && !src.contains("lightbox-blank.gif")) {
                posterUrl = src
            }

            posterUrl?.let {
                if (it.startsWith("//")) {
                    posterUrl = "https:$it"
                } else if (it.startsWith("/") && !it.startsWith("//")) {
                    posterUrl = "$mainUrl$it"
                }
            }
        }
        // Log.d("Xvv1deosProvider", "Title: $title, Poster: $posterUrl, FullURL: $fullUrl")

        return newAnimeSearchResponse(title, fullUrl, TvType.Movie) {
            this.posterUrl = posterUrl
            // === THÊM POSTER HEADERS VÀO ĐÂY ===
            if (!posterUrl.isNullOrBlank()) { // Chỉ thêm header nếu có posterUrl
                this.posterHeaders = mapOf("Referer" to mainUrl)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageNumber = if (page > 1) "/new/${page - 1}" else ""
        val document = app.get("$mainUrl$pageNumber").document // Thêm header nếu cần: app.get(url, headers = mapOf("Referer" to mainUrl)).document

        val items = document.select("div.mozaique div.thumb-block")?.mapNotNull {
            it.toSearchResponse()
        } ?: emptyList()

        val hasNextPage = document.selectFirst("div.pagination a.next-page") != null
        
        return newHomePageResponse(listOf(HomePageList("Videos", items)), hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/?k=$query").document

        return document.select("div.mozaique div.thumb-block")?.mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document // Thêm header nếu cần: app.get(url, headers = mapOf("Referer" to mainUrl)).document

        val title = document.selectFirst("h2.page-title")?.ownText()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null

        // Poster cho trang load, thường là ảnh lớn hơn, có thể cần header tương tự nếu từ CDN giống nhau
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        poster?.let {
            if (it.startsWith("//")) poster = "https:$it"
            else if (it.startsWith("/") && !it.startsWith("//")) poster = "$mainUrl$it"
        }

        var description = document.selectFirst("meta[name=description]")?.attr("content")
        if (description.isNullOrBlank()) {
            description = document.selectFirst("div.video-description-text")?.text()
        }

        var tags: List<String>? = null
        val scriptTagWithConfig = document.select("script").find { it.html().contains("xv.conf") }?.html()
        if (scriptTagWithConfig != null) {
            val tagsRegex = Regex("""video_tags"\s*:\s*(\[.*?\])""")
            val tagsMatch = tagsRegex.find(scriptTagWithConfig)
            if (tagsMatch != null && tagsMatch.groupValues.size > 1) {
                try {
                    val tagsJsonArrayString = tagsMatch.groupValues[1]
                    tags = tagsJsonArrayString.removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotBlank() }
                } catch (_: Exception) { }
            }
        }
        if (tags.isNullOrEmpty()) {
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
                                }
                             }
                            // Video liên quan cũng có thể cần posterHeaders
                            recommendations.add(newAnimeSearchResponse(recTitle, recHref, TvType.Movie) {
                                this.posterUrl = if (recImage.isNotBlank()) recImage else null
                                if (!this.posterUrl.isNullOrBlank()) {
                                    this.posterHeaders = mapOf("Referer" to mainUrl)
                                }
                            })
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            if (!this.posterUrl.isNullOrBlank()) {
                this.posterHeaders = mapOf("Referer" to mainUrl) // Thêm header cho poster chính của trang load
            }
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            this.duration = durationMinutes
            uploaderName?.let { name ->
                val actor = Actor(name)
                this.actors = listOf(ActorData(actor = actor))
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
        return false
    }
}
