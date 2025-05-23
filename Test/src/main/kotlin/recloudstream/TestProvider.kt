package com.example.nangcucprovider // Thay thế bằng package name của bạn

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.apmap 
import com.lagradost.cloudstream3.app // Import để sử dụng app.get()
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile

import org.jsoup.Jsoup // Jsoup vẫn cần thiết để parse document sau khi fetch

class NangCucProvider : MainAPI() {
    override var mainUrl = "https://nangcuc.cc"
    override var name = "Nắng Cực TV"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "vi"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Fetch HTML trực tiếp từ web.
        // Trang chủ có thể là mainUrl hoặc một đường dẫn cụ thể như /moi-nhat/
        // Nếu trang của bạn hỗ trợ phân trang dạng /moi-nhat/page/2/, page sẽ là số trang.
        // Nếu trang dùng query param (?page=2), bạn cần thay đổi URL cho phù hợp.
        // Dựa trên cấu trúc file main.html, có vẻ nó là trang "Mới nhất".
        val url = if (page > 1) "$mainUrl/moi-nhat/$page" else "$mainUrl/moi-nhat/" 
        // Hoặc nếu trang chủ chính là nơi lấy dữ liệu: val url = mainUrl
        
        val document = app.get(url).document // Fetch và parse HTML

        val lists = mutableListOf<HomePageList>()

        // Ví dụ: Lấy section "Phim Sex hay mới nhất..."
        // Selector này dựa trên main.html bạn cung cấp.
        val newestSection = document.selectFirst("section.block_area_home.section-id-02") 
                           ?: document.selectFirst(".block_area-content.block_area-list.film_list.film_list-grid") // Thêm một selector dự phòng nếu cấu trúc thay đổi nhẹ

        if (newestSection != null) {
            // Trên trang /moi-nhat/, tiêu đề có thể không cần thiết hoặc lấy từ một element khác.
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
                        if (href.startsWith("http")) href else mainUrl + href, // Đảm bảo URL là tuyệt đối
                        this.name,
                        TvType.Movie,
                        posterUrl,
                        null // year
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
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        val document = app.get(searchUrl).document // Fetch và parse HTML
        
        val searchResults = document.select("div.block_area-content div.flw-item").mapNotNull { item -> // Selector cụ thể hơn cho trang search
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
                    null // year
                )
            } else {
                null
            }
        }
        return searchResults.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document // Fetch và parse HTML từ URL chi tiết phim

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
                // Ưu tiên tìm ID từ cấu trúc URL đầy đủ trong AJAX call hoặc khai báo biến
                val fullUrlRegex = Regex("""["'](https?://api\.nangdata\.xyz/v2/[a-zA-Z0-9\-]+)["']""")
                dataUrlForLoadLinks = fullUrlRegex.find(scriptData)?.groupValues?.getOrNull(1)

                if (dataUrlForLoadLinks == null) {
                    // Nếu không tìm thấy URL đầy đủ, thử trích xuất ID
                    val idRegexV2 = Regex("""api\.nangdata\.xyz/v2/([a-zA-Z0-9\-]+)""")
                    val idRegexShort = Regex("""api\.nangdata\.xyz/([a-zA-Z0-9\-]+)""") 
                    
                    videoApiId = idRegexV2.find(scriptData)?.groupValues?.getOrNull(1)
                                 ?: idRegexShort.find(scriptData)?.groupValues?.getOrNull(1)
                    
                    if (videoApiId != null && videoApiId != "v2") { // Đảm bảo videoApiId không phải là "v2"
                        dataUrlForLoadLinks = "https://api.nangdata.xyz/v2/$videoApiId"
                    }
                }
                if (dataUrlForLoadLinks != null) return@forEach // Thoát sớm nếu tìm thấy
            }
        }
        
        val recommendations = document.select("section.block_area-related div.flw-item").mapNotNull { item ->
            val filmPosterRec = item.selectFirst("div.film-poster")
            val nameRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("title")
            val hrefRec = filmPosterRec?.selectFirst("a.film-poster-ahref")?.attr("href")
            // Trang load.html dùng data-src cho ảnh recommendations
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
            url = url, // url gốc của trang phim
            apiName = this.name,
            type = TvType.Movie,
            dataUrl = dataUrlForLoadLinks, // URL này sẽ được truyền cho loadLinks
            posterUrl = poster,
            year = null, // Lấy năm nếu có
            plot = plot,
            tags = genres,
            recommendations = recommendations
        )
    }

    // Hàm loadLinks sẽ được xây dựng sau
    // override suspend fun loadLinks(
    //     data: String, 
    //     isCasting: Boolean,
    //     subtitleCallback: (SubtitleFile) -> Unit,
    //     callback: (ExtractorLink) -> Unit
    // ): Boolean {
    //     // val responseJson = app.get(data).text 
    //     // ... parse JSON ...
    //     return true 
    // }
}
