package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KingBokep : MainAPI() {
    override var mainUrl = "https://kingbokep.tv"
    override var name = "KingBokep"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/category/indonesia/" to "Bokep Indo",
        "$mainUrl/category/viral/" to "Indo Viral",
        "$mainUrl/category/jilbab/" to "Jilbab",
        "$mainUrl/category/scandal/" to "Scandal"
    )

    // Hàm chuyển đổi thời gian sang Phút (Int)
    private fun getDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return try {
            val parts = text.trim().split(":").map { it.toInt() }
            when (parts.size) {
                2 -> parts[0] // MM:SS -> Lấy phút
                3 -> parts[0] * 60 + parts[1] // HH:MM:SS -> Đổi ra phút
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // 1. Lấy thẻ A, nếu không có -> bỏ qua
        val link = this.selectFirst("a.group") ?: return null
        
        // 2. Lấy href raw, nếu null/blank -> bỏ qua
        val rawHref = link.attr("href")
        if (rawHref.isNullOrBlank()) return null
        
        // 3. Fix URL và ép kiểu String (Non-null)
        val href = fixUrl(rawHref)

        // 4. Lấy Title, nếu null/blank -> bỏ qua
        val rawTitle = this.selectFirst(".video-card-title")?.text()?.trim()
        if (rawTitle.isNullOrBlank()) return null
        val title = rawTitle!! // Khẳng định non-null

        // 5. Xử lý Poster (An toàn với fixUrl)
        val imgTag = this.selectFirst("img")
        val rawPoster = imgTag?.attr("data-src") ?: imgTag?.attr("src")
        val posterUrl = if (!rawPoster.isNullOrBlank()) fixUrl(rawPoster!!) else null

        // 6. Return (Title và Href chắc chắn là String)
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            // Không set duration ở đây để tránh lỗi unresolved reference trong SearchResponse
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?keyword=$query"
        return app.get(url).document.select("li.video-card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("li.video-card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Xử lý Title và Description
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val description = document.select("meta[name=description]").attr("content")
        
        // Xử lý Metadata
        val date = document.selectFirst("span:contains(Tanggal:)")?.nextSibling()?.toString()?.trim()
        val durationText = document.selectFirst("span[data-pagefind-meta=duration]")?.text()
        
        // Xử lý Poster
        val rawPoster = document.selectFirst("video#bokep-player")?.attr("poster") 
            ?: document.select("meta[property=og:image]").attr("content")
        val poster = if (!rawPoster.isNullOrBlank()) fixUrl(rawPoster) else null

        val recommendations = document.select("li.video-card").mapNotNull {
            it.toSearchResult()
        }
        
        val tags = document.select("meta[name=keywords]").attr("content")
            .split(",").map { it.trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = date?.takeLast(4)?.toIntOrNull()
            this.recommendations = recommendations
            this.duration = getDurationMinutes(durationText) // Trả về Int (phút)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val videoTag = document.selectFirst("#bokep-player")
        val rawPlaylistUrl = videoTag?.attr("data-playlist")

        if (!rawPlaylistUrl.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixUrl(rawPlaylistUrl),
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = mainUrl
                    quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }
}
