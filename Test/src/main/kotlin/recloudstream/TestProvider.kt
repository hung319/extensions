package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

class AnimexProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    // --- LOGGING TAG ---
    // Bạn filter logcat bằng từ khóa "BLUE_DEBUG" để xem lỗi nhé
    private val TAG = "BLUE_DEBUG"

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/catalog?sort=TRENDING_DESC" to "Trending",
        "$mainUrl/catalog?sort=POPULARITY_DESC" to "Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("a.item-link").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href") ?: return null
        val img = this.selectFirst("img")?.attr("src")
        val title = this.selectFirst(".title-text")?.text() 
            ?: this.selectFirst("h5")?.text() 
            ?: return null

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = img
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/catalog?search=$query"
        val document = app.get(url).document
        return document.select("a.item-link").mapNotNull {
            it.toSearchResult()
        }
    }

    // --- JSON DATA CLASSES ---
    data class EpisodeTitle(
        @JsonProperty("en") val en: String?,
        @JsonProperty("ja") val ja: String?,
        @JsonProperty("x-jat") val xJat: String?
    )

    data class EpisodeData(
        @JsonProperty("number") val number: Int?, // Đổi sang Int cho chuẩn JSON
        @JsonProperty("titles") val titles: EpisodeTitle?,
        @JsonProperty("img") val img: String?,
        @JsonProperty("description") val description: String?
    )

    override suspend fun load(url: String): LoadResponse {
        // Log URL đầu vào
        println("$TAG: Loading URL: $url")
        
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("title")?.text()?.substringBefore(" -") 
            ?: "Unknown"
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val bg = document.selectFirst(".absolute.inset-0.bg-cover")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("')")

        // 1. Recommendations
        val recommendations = document.select("swiper-slide a").mapNotNull {
            it.toSearchResult()
        }

        // 2. Episodes (API Logic)
        val episodes = mutableListOf<Episode>()
        
        // Xử lý ID an toàn hơn: bỏ query param (nếu có), bỏ dấu / ở cuối
        val animeId = url.substringBefore("?").trimEnd('/').substringAfterLast("-")
        
        println("$TAG: Extracted ID: $animeId") // Kiểm tra xem ID lấy đúng chưa

        val watchButton = document.selectFirst("a[href^='/watch/']")
        val watchUrlTemplate = watchButton?.attr("href")?.let { fixUrl(it) }

        if (animeId.all { it.isDigit() }) {
            try {
                val apiUrl = "$mainUrl/api/anime/episodes/$animeId"
                println("$TAG: Calling API: $apiUrl") // Kiểm tra URL API

                // Thêm headers giống browser
                val headers = mapOf(
                    "Referer" to url,
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest"
                )
                
                // Lấy raw text trước để debug
                val response = app.get(apiUrl, headers = headers)
                val jsonText = response.text
                
                println("$TAG: API Response Code: ${response.code}")
                // println("$TAG: API Response Body: $jsonText") // Bỏ comment dòng này nếu muốn xem full JSON

                // Parse thủ công để bắt lỗi
                val apiResponse = AppUtils.parseJson<List<EpisodeData>>(jsonText)

                apiResponse.forEach { epData ->
                    val epNum = epData.number ?: 0
                    
                    // Logic tạo link tập phim
                    val epUrl = if (watchUrlTemplate != null) {
                        watchUrlTemplate.replace(Regex("episode-\\d+$"), "episode-$epNum")
                    } else {
                        "$mainUrl/watch/${url.substringAfter("/anime/")}-episode-$epNum"
                    }

                    val epName = epData.titles?.en ?: epData.titles?.xJat ?: "Episode $epNum"
                    
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.episode = epNum
                            this.posterUrl = epData.img
                            this.description = epData.description
                        }
                    )
                }
                println("$TAG: Parsed ${episodes.size} episodes")

            } catch (e: Exception) {
                println("$TAG: Error loading API: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("$TAG: Error - ID contains non-digits: $animeId")
        }

        // Fallback: Nếu list rỗng, dùng Watch Now
        if (episodes.isEmpty()) {
            println("$TAG: Episode list empty, using Fallback Watch Now")
            if (watchUrlTemplate != null) {
                episodes.add(newEpisode(watchUrlTemplate) {
                    this.name = "Watch Now"
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = description
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Logic link ảo -> link thật
        val urlToLoad = if (data.contains("/watch/")) {
            data
        } else {
            data.replace("/anime/", "/watch/")
        }
        
        println("$TAG: Loading Link for: $urlToLoad")

        val document = app.get(urlToLoad).document
        val iframe = document.selectFirst("iframe")
        
        if (iframe != null) {
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            
            println("$TAG: Found iframe: $src")
            if (src.isNotEmpty()) {
                loadExtractor(src, subtitleCallback, callback)
                return true
            }
        } else {
            println("$TAG: No iframe found in $urlToLoad")
        }
        return false
    }
}
