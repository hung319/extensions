package com.lagradost.cloudstream3.plugins.local

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType

import org.jsoup.nodes.Element
import java.lang.Exception

class TestProvider : MainAPI() {
    // THAY ĐỔI: Sử dụng Bitly để tự động cập nhật domain
    override var mainUrl = "https://bit.ly/xemtop1"
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
        return newHomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): MovieSearchResponse? {
        val title = this.selectFirst("h2.entry-title a")?.attr("title")?.trim() ?: return null
        val href = this.selectFirst("a.dp-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.lazy")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
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
        return newMovieLoadResponse(title, url, TvType.NSFW, postId) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy domain đã được redirect từ Bitly để xây dựng URL API
        val redirectedUrl = app.get(mainUrl).url
        val apiUrl = "$redirectedUrl/wp-json/sextop1/player/?id=$data&server=1"
        
        val responseText = app.get(apiUrl, referer = "$redirectedUrl/").text
        val apiResponse = tryParseJson<ApiResponse>(responseText)
        val jsData = apiResponse?.data ?: return false

        val regex = Regex("file: '(https?://[^']+\\.m3u8)'")
        val matchResult = regex.find(jsData)
        val videoUrl = matchResult?.groups?.get(1)?.value

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

    data class ApiResponse(
        val success: Boolean?,
        val data: String?
    )
}
