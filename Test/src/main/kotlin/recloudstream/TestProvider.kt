package com.lagradost.cloudstream3.plugins.local

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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
        // Chúng ta vẫn lấy ID từ trang phim như cũ
        val postId = document.selectFirst("div#video")?.attr("data-id") ?: return null
        val recommendations = document.select("section.related-movies article.dp-item").mapNotNull { it.toSearchResult() }
        return newMovieLoadResponse(title, url, TvType.Movie, postId) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // =================================================================================
    // HÀM LOADLINKS ĐÃ ĐƯỢC VIẾT LẠI HOÀN TOÀN
    // =================================================================================
    override suspend fun loadLinks(
        data: String, // data ở đây vẫn là postId
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Xây dựng URL API mới và chính xác
        val apiUrl = "$mainUrl/wp-json/sextop1/player/?id=$data&server=1"

        // 2. Gửi yêu cầu GET đến API và lấy nội dung text
        val responseText = app.get(apiUrl, referer = "$mainUrl/").text

        // 3. Parse JSON ban đầu để lấy trường 'data'
        val apiResponse = tryParseJson<ApiResponse>(responseText)
        val jsData = apiResponse?.data ?: return false // Thoát nếu không có dữ liệu

        // 4. Dùng Regex để tìm link .m3u8 bên trong chuỗi JavaScript
        val regex = Regex("file: '(https?://[^']+\\.m3u8)'")
        val matchResult = regex.find(jsData)
        val videoUrl = matchResult?.groups?.get(1)?.value // Lấy kết quả từ group 1 của regex

        // 5. Nếu tìm thấy link, tạo ExtractorLink và gửi đi
        videoUrl?.let { url ->
            val link = newExtractorLink(
                source = this.name,
                name = "${this.name} Server",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
            callback.invoke(link)
        }

        return true
    }

    // Data class mới để khớp với cấu trúc JSON của API
    data class ApiResponse(
        val success: Boolean?,
        val data: String?
    )
}
