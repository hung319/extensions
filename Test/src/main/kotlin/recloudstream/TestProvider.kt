package com.lagradost.cloudstream3.plugins.local

// THAY ĐỔI QUAN TRỌNG:
// Import cả 2 package bằng wildcard (*) để đảm bảo mọi thứ được nạp đúng
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Import này sẽ chứa ExtractorLink và Qualities

import org.jsoup.nodes.Element
import java.lang.Exception

class TestProvider : MainAPI() {
    override var mainUrl = "https://sextop1.la"
    override var name = "SexTop1"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/").document
        val homePageList = ArrayList<HomePageList>()
        val mainItems = document.select("article.dp-item").mapNotNull { it.toSearchResult() }
        if (mainItems.isNotEmpty()) {
            homePageList.add(HomePageList("Phim Mới", mainItems))
        }
        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): MovieSearchResponse? {
        val title = this.selectFirst("h2.entry-title a")?.attr("title")?.trim() ?: return null
        val href = this.selectFirst("a.dp-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.lazy")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.dp-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.video-item.dp-entry-box > article > p")?.text()
        val tags = document.select("div.the_tag_list a[rel=tag]").map { it.text() }
        val postId = document.selectFirst("div#video")?.attr("data-id") ?: return null
        val recommendations = document.select("section.related-movies article.dp-item").mapNotNull { it.toSearchResult() }
        return newMovieLoadResponse(title, url, TvType.Movie, postId) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // Hàm loadLinks giờ sẽ có chữ ký đúng vì ExtractorLink đã được import chính xác
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val postData = mapOf("action" to "top1tube_get_video", "post_id" to data)

        try {
            val responseText = app.post(ajaxUrl, data = postData).text
            val ajaxResponse = parseJson<VideoResponse>(responseText)

            ajaxResponse.link?.let { videoUrl ->
                callback.invoke(
                    ExtractorLink( // Lớp này giờ đã được nhận diện
                        source = this.name,
                        name = "${this.name} Server",
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }

    data class VideoResponse(
        val status: Boolean?,
        val link: String?,
        val sources: List<VideoSource>?
    )

    data class VideoSource(
        val file: String,
        val label: String,
        val type: String
    )
}
