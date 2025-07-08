package com.lagradost.cloudstream3.movieprovider // QUAN TRỌNG: Sử dụng package chuẩn

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

    // THAY ĐỔI LỚN: Hàm load bây giờ sẽ trực tiếp lấy link embed
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

        // Lấy server đầu tiên
        val firstServer = document.select("ul.muvipro-player-tabs li a").firstOrNull() 
            ?: throw ErrorLoadingException("No servers found")

        val serverName = firstServer.text()
        val tabValue = firstServer.attr("href").removePrefix("#")

        val episodes = mutableListOf<Episode>()

        if (tabValue.isNotBlank()) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val postData = mapOf(
                "action" to "muvipro_player_content",
                "post_id" to postId,
                "tab" to tabValue 
            )

            // Gọi AJAX ngay tại đây
            val ajaxResponse = app.post(ajaxUrl, data = postData, referer = url).document
            val iframeSrc = ajaxResponse.selectFirst("iframe")?.attr("src")

            if (iframeSrc != null) {
                // Tạo một episode duy nhất với dữ liệu là link iframe
                episodes.add(Episode(
                    data = iframeSrc,
                    name = serverName
                ))
            }
        }
        
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("Could not extract any video links")
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    // THAY ĐỔI LỚN: Hàm loadLinks bây giờ rất đơn giản
    override suspend fun loadLinks(
        data: String, // 'data' bây giờ là link iframe trực tiếp
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Chỉ cần gọi loadExtractor với link iframe
        return loadExtractor(data, subtitleCallback, callback)
    }
}
