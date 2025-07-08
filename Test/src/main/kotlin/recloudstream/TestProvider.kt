package com.lagradost.cloudstream3.movieprovider // QUAN TRỌNG: Sử dụng package chuẩn

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Khai báo lớp provider
class Kurakura21Provider : MainAPI() {
    override var mainUrl = "https://kurakura21.net"
    override var name = "Kurakura21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    
    // Lớp dữ liệu để lưu thông tin cho lời gọi AJAX
    private data class EpisodeData(
        @JsonProperty("ajaxUrl") val ajaxUrl: String,
        @JsonProperty("postData") val postData: Map<String, String>
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title a")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(this.selectFirst(".gmr-quality-item a")?.text())
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.home-widget").forEach { block ->
            val header = block.selectFirst("h3.homemodule-title")?.text() ?: return@forEach
            val movies = block.select("article, div.gmr-item-modulepost").mapNotNull {
                it.toSearchResult()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(header, movies))
            }
        }
        
        val latestMoviesHeader = document.selectFirst("#primary h3.homemodule-title")?.text() ?: "Latest Movies"
        val latestMovies = document.select("#gmr-main-load article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
        if(latestMovies.isNotEmpty()) {
            homePageList.add(HomePageList(latestMoviesHeader, latestMovies))
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        val poster = document.selectFirst("figure.pull-left img")?.attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val year = document.selectFirst("div.gmr-moviedata")?.text()?.let {
            Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        val postId = document.body().attr("class").let {
            Regex("postid-(\\d+)").find(it)?.groupValues?.get(1)
        } ?: throw ErrorLoadingException("Failed to get post ID")

        // Lấy danh sách các server
        val servers = document.select("ul.muvipro-player-tabs li a")

        val episodes = servers.mapNotNull { serverElement ->
            val serverName = serverElement.text()
            // SỬA LỖI: Lấy giá trị của tham số 'tab' từ thuộc tính href (ví dụ: #p1 -> p1)
            val tabValue = serverElement.attr("href").removePrefix("#")
            if (tabValue.isBlank()) return@mapNotNull null

            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            // SỬA LỖI: Sử dụng tham số 'tab' thay vì 'player'
            val postData = mapOf(
                "action" to "muvipro_player_content",
                "post_id" to postId,
                "tab" to tabValue 
            )
            
            val episodeDataJson = AppUtils.toJson(EpisodeData(ajaxUrl, postData))

            Episode(data = episodeDataJson, name = serverName)
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = AppUtils.parseJson<EpisodeData>(data)
        
        // Gửi yêu cầu AJAX với dữ liệu đã được sửa chính xác
        val ajaxResponse = app.post(
            episodeData.ajaxUrl,
            data = episodeData.postData,
            referer = this.mainUrl // Thêm referer để yêu cầu hợp lệ hơn
        ).document
        
        val iframeSrc = ajaxResponse.selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("Failed to find iframe source")
            
        // loadExtractor sẽ xử lý link iframe (ví dụ: filemoon.to)
        return loadExtractor(iframeSrc, subtitleCallback, callback)
    }
}
