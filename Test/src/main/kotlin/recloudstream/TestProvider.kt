package com.example.nangcucprovider // Giữ nguyên package name của bạn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse // Vẫn cần thiết cho kiểu dữ liệu trong recommendations
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainPageRequest

// Import các hàm tiện ích mới
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
// import com.lagradost.cloudstream3.LoadResponse // Không cần trực tiếp nếu dùng newMovieLoadResponse
// import com.lagradost.cloudstream3.HomePageResponse // Không cần trực tiếp nếu dùng newHomePageResponse

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile

import org.jsoup.Jsoup

class NangCucProvider : MainAPI() { // Đổi tên class này nếu bạn muốn
    override var mainUrl = "https://nangcuc.cc"
    override var name = "Nắng Cực TV" // Tên này sẽ được tự động dùng bởi các hàm newXxxResponse
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "vi"
    override val hasMainPage = true

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? { // Kiểu trả về vẫn là HomePageResponse?
        val url = if (page > 1) "$mainUrl/moi-nhat/$page" else "$mainUrl/moi-nhat/"
        
        try {
            val document = app.get(url).document
            val lists = mutableListOf<HomePageList>()

            val newestSection = document.selectFirst("section.block_area_home.section-id-02")
                               ?: document.selectFirst(".block_area-content.block_area-list.film_list.film_list-grid")

            if (newestSection != null) {
                val sectionTitle = newestSection.selectFirst("h1.cat-heading")?.text() ?: "Phim Mới Cập Nhật"
                val movies = newestSection.select("div.flw-item").mapNotNull { item ->
                    val filmPoster = item.selectFirst("div.film-poster")
                    val name = filmPoster?.selectFirst("img.film-poster-img")?.attr("title")
                    val href = filmPoster?.selectFirst("a.film-poster-ahref")?.attr("href")
                    var posterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("src")
                    
                    if (posterUrl == "/images/1px.gif" || posterUrl?.startsWith("data:image") == true) {
                        posterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("data-src")
                    }

                    if (name != null && href != null && posterUrl != null) {
                        // Sử dụng newMovieSearchResponse
                        newMovieSearchResponse(
                            name = name,
                            url = if (href.startsWith("http")) href else mainUrl + href,
                            tvType = TvType.Movie,
                        ) {
                            this.posterUrl = posterUrl
                            // this.apiName = this@NangCucProvider.name // Thường được tự động gán
                        }
                    } else {
                        null
                    }
                }
                if (movies.isNotEmpty()) {
                    lists.add(HomePageList(sectionTitle, movies))
                }
            }
            
            if (lists.isEmpty()) return null
            // Sử dụng newHomePageResponse
            return newHomePageResponse(request.name, lists) // request.name thường là tiêu đề chung của trang
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

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
                    // Sử dụng newMovieSearchResponse
                    newMovieSearchResponse(
                        name = name,
                        url = if (href.startsWith("http")) href else mainUrl + href,
                        tvType = TvType.Movie
                    ) {
                        this.posterUrl = posterUrl
                    }
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

    override suspend fun load(url: String): LoadResponse? { // Kiểu trả về vẫn là LoadResponse?
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
            
            val recommendationsList = document.select("section.block_area-related div.flw-item").mapNotNull { item ->
                val filmPosterRec = item.selectFirst("div.film-poster")
                val nameRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("title")
                val hrefRec = filmPosterRec?.selectFirst("a.film-poster-ahref")?.attr("href")
                var posterUrlRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("data-src") 
                if (posterUrlRec == "/images/1px.gif" || posterUrlRec?.startsWith("data:image") == true) {
                    posterUrlRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("src") 
                }

                if (nameRec != null && hrefRec != null && posterUrlRec != null) {
                    newMovieSearchResponse(
                        name = nameRec,
                        url = if (hrefRec.startsWith("http")) hrefRec else mainUrl + hrefRec,
                        tvType = TvType.Movie
                    ) {
                         this.posterUrl = posterUrlRec
                    }
                } else {
                    null
                }
            }

            if (title == null || dataUrlForLoadLinks == null) {
                 println("Lỗi load(): Không tìm thấy title hoặc dataUrlForLoadLinks. Title: $title, DataUrl: $dataUrlForLoadLinks, Extracted API ID: $videoApiId, Page URL: $url")
                return null
            }
            
            // Sử dụng newMovieLoadResponse
            return newMovieLoadResponse(
                name = title,
                url = url, // url gốc của trang phim
                type = TvType.Movie,
                dataUrl = dataUrlForLoadLinks!! // Đảm bảo không null ở đây sau khi đã kiểm tra
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendationsList // Đảm bảo đây là List<SearchResponse>
                // this.contentRating = null // Thêm nếu bạn có thông tin, nếu không có thể bỏ qua hoặc đặt là null
                // this.apiName = this@NangCucProvider.name // Thường được tự động gán
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ... (loadLinks giữ nguyên để làm sau) ...
}
