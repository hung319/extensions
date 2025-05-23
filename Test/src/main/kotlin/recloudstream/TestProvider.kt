package com.example.nangcucprovider

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.MainPageRequest // <--- THÊM HOẶC KIỂM TRA IMPORT NÀY

import org.jsoup.Jsoup

class NangCucProvider : MainAPI() {
    override var mainUrl = "https://nangcuc.cc"
    override var name = "Nắng Cực TV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"
    override val hasMainPage = true

    // Đảm bảo signature của hàm getMainPage khớp hoàn toàn
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest // Đảm bảo kiểu dữ liệu này được import đúng
    ): HomePageResponse? {
        val url = if (page > 1) "$mainUrl/moi-nhat/$page" else "$mainUrl/moi-nhat/"

        // Thử try-catch để bắt lỗi nếu có vấn đề với network hoặc parsing
        try {
            val document = app.get(url).document 
            val lists = mutableListOf<HomePageList>()

            val newestSection = document.selectFirst("section.block_area_home.section-id-02") 
                               ?: document.selectFirst(".block_area-content.block_area-list.film_list.film_list-grid")

            if (newestSection != null) {
                val title = newestSection.selectFirst("h1.cat-heading")?.text() ?: "Phim Mới Cập Nhật"
                val movies = newestSection.select("div.flw-item").mapNotNull { item ->
                    val filmPoster = item.selectFirst("div.film-poster")
                    val name = filmPoster?.selectFirst("img.film-poster-img")?.attr("title")
                    val href = filmPoster?.selectFirst("a.film-poster-ahref")?.attr("href")
                    var posterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("src")

                    if (posterUrl == "/images/1px.gif" || posterUrl?.startsWith("data:image") == true) {
                        posterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("data-src")
                    }

                    if (name != null && href != null && posterUrl != null) {
                        MovieSearchResponse(
                            name,
                            if (href.startsWith("http")) href else mainUrl + href,
                            this.name,
                            TvType.Movie,
                            posterUrl,
                            null 
                        )
                    } else {
                        null
                    }
                }
                if (movies.isNotEmpty()) {
                    lists.add(HomePageList(title, movies))
                }
            }

            if (lists.isEmpty()) return null
            return HomePageResponse(lists)
        } catch (e: Exception) {
            e.printStackTrace() // In lỗi ra console để debug
            return null // Trả về null nếu có lỗi
        }
    }

    // ... (các hàm search và load giữ nguyên như trước) ...

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        try {
            val document = app.get(searchUrl).document

            val searchResults = document.select("div.block_area-content div.flw-item").mapNotNull { item ->
                val filmPoster = item.selectFirst("div.film-poster")
                val name = filmPoster?.selectFirst("img.film-poster-img")?.attr("title")
                val href = filmPoster?.selectFirst("a.film-poster-ahref")?.attr("href")
                var posterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("src")

                if (posterUrl == "/images/1px.gif" || posterUrl?.startsWith("data:image") == true) {
                     posterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("data-src")
                }

                if (name != null && href != null && posterUrl != null) {
                    MovieSearchResponse(
                        name,
                        if (href.startsWith("http")) href else mainUrl + href,
                        this.name,
                        TvType.Movie,
                        posterUrl,
                        null
                    )
                } else {
                    null
                }
            }
            return searchResults.ifEmpty { null }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document

            val title = document.selectFirst("h1.video-title")?.text()
                ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            val plot = document.selectFirst("div.about span p")?.text()
                ?: document.selectFirst("meta[property=\"og:description\"]")?.attr("content")

            val genres = document.select("div.genres a")?.mapNotNull { it.text() }

            var videoApiId: String? = null
            var dataUrlForLoadLinks: String? = null

            document.select("script").forEach { script ->
                val scriptData = script.data()
                if (scriptData.contains("api.nangdata.xyz")) {
                    val fullUrlRegex = Regex("""["'](https?://api\.nangdata\.xyz/v2/[a-zA-Z0-9\-]+)["']""")
                    dataUrlForLoadLinks = fullUrlRegex.find(scriptData)?.groupValues?.getOrNull(1)

                    if (dataUrlForLoadLinks == null) {
                        val idRegexV2 = Regex("""api\.nangdata\.xyz/v2/([a-zA-Z0-9\-]+)""")
                        val idRegexShort = Regex("""api\.nangdata\.xyz/([a-zA-Z0-9\-]+)""") 

                        videoApiId = idRegexV2.find(scriptData)?.groupValues?.getOrNull(1)
                                     ?: idRegexShort.find(scriptData)?.groupValues?.getOrNull(1)

                        if (videoApiId != null && videoApiId != "v2") {
                            dataUrlForLoadLinks = "https://api.nangdata.xyz/v2/$videoApiId"
                        }
                    }
                    if (dataUrlForLoadLinks != null) return@forEach
                }
            }

            val recommendations = document.select("section.block_area-related div.flw-item").mapNotNull { item ->
                val filmPosterRec = item.selectFirst("div.film-poster")
                val nameRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("title")
                val hrefRec = filmPosterRec?.selectFirst("a.film-poster-ahref")?.attr("href")
                var posterUrlRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("data-src") 
                if (posterUrlRec == "/images/1px.gif" || posterUrlRec?.startsWith("data:image") == true) {
                    posterUrlRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("src") 
                }

                if (nameRec != null && hrefRec != null && posterUrlRec != null) {
                    MovieSearchResponse(
                        nameRec,
                        if (hrefRec.startsWith("http")) hrefRec else mainUrl + hrefRec,
                        this.name,
                        TvType.Movie,
                        posterUrlRec,
                        null
                    )
                } else {
                    null
                }
            }

            if (title == null || dataUrlForLoadLinks == null) {
                 println("Lỗi load(): Không tìm thấy title hoặc dataUrlForLoadLinks. Title: $title, DataUrl: $dataUrlForLoadLinks, Extracted API ID: $videoApiId, Page URL: $url")
                return null
            }

            return MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = dataUrlForLoadLinks,
                posterUrl = poster,
                year = null,
                plot = plot,
                tags = genres,
                recommendations = recommendations
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
