// Provider for HoatHinhQQ
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class HoatHinhQQProvider : MainAPI() {
    // Basic provider information
    override var mainUrl = "https://hoathinhqq.com"
    override var name = "HoatHinhQQ"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    private val gson = Gson()
    data class EpisodeData(val url: String, val dataId: String)

    // Function to get the homepage with correct pagination
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = ArrayList<HomePageList>()
        val hasNextPage: Boolean

        if (page <= 1) {
            val document = app.get("$mainUrl/").document
            val sections = document.select("div.w-full.lg\\:w-3\\/4")

            sections.forEach { section ->
                val header = section.selectFirst("div.gradient-title h3")?.text() ?: "Unknown Section"
                val movies = section.select("div.grid > a").mapNotNull { it.toSearchResult() }
                if (movies.isNotEmpty()) {
                    lists.add(HomePageList(header, movies))
                }
            }
            hasNextPage = document.select("ul.pagination a[href='/phim?page=2']").isNotEmpty()
        } else {
            val document = app.get("$mainUrl/phim?page=$page").document
            val movies = document.select("div.grid > a").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                lists.add(HomePageList("Phim Mới Cập Nhật (Trang $page)", movies))
            }
            hasNextPage = document.select("ul.pagination a[href='/phim?page=${page + 1}']").isNotEmpty()
        }

        if (lists.isEmpty()) throw ErrorLoadingException("Không tìm thấy dữ liệu trang chủ")

        return HomePageResponse(lists, hasNext = hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        
        val title = this.selectFirst("h3.capitalize")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("srcset")?.substringBefore(" ") ?: this.selectFirst("img")?.attr("src")
        
        return newAnimeSearchResponse(title, "$mainUrl$href", TvType.Cartoon) {
            this.posterUrl = posterUrl
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/$query"
        val document = app.get(url).document

        return document.select("div.grid > a").mapNotNull {
            it.toSearchResult()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - HoatHinhQQ", "")
            ?: "Không tìm thấy tiêu đề"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val year = document.select("div.film-info-list-title:contains(Năm) + div")?.text()?.toIntOrNull()

        // ===== LOGIC TẠO EPISODE MỚI ĐƯỢC ÁP DỤNG Ở ĐÂY =====
        val episodes = parseEpisodes(document, url)
        // =======================================================
        
        val isMovie = episodes.isEmpty()
        
        // Placeholder for recommendations
        // val recommendations = document.select("your_recommendations_selector_here").mapNotNull { it.toSearchResult() }

        return if (isMovie) {
             newMovieLoadResponse(title, url, TvType.Cartoon, dataUrl = url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = document.select("li.film-info-list:contains(Thể loại) a").map { it.text() }
                // this.recommendations = recommendations
            }
        } else {
             newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = document.select("li.film-info-list:contains(Thể loại) a").map { it.text() }
                // this.recommendations = recommendations
            }
        }
    }

    // HÀM PARSE EPISODES MỚI THEO LOGIC JSON
    private fun parseEpisodes(document: Document, originalUrl: String): List<Episode> {
        // ### BẠN CÓ THỂ CẦN THAY ĐỔI CSS SELECTOR Ở ĐÂY ###
        // Dựa trên code cũ của bạn, selector này có vẻ đúng.
        val episodeElements = document.selectFirst("div:has(p:contains(Tìm tập nhanh))")
            ?.select("ul > li > a") ?: return emptyList()

        return episodeElements.mapNotNull { el ->
            try {
                // ### BẠN CẦN KIỂM TRA VÀ THAY ĐỔI CÁC THUỘC TÍNH NẾU CẦN ###
                // Hãy dùng "Inspect Element" trên trang web để xem các nút bấm tập phim
                // có thuộc tính nào chứa ID không, ví dụ: 'data-id', 'data-episode-id',...
                // Ở đây tôi giả định nó nằm ngay trong href.
                val href = el.attr("href")
                if (href.isBlank()) return@mapNotNull null
                
                // Lấy ID từ href, ví dụ: /xem-phim/ly-luan-vo-dich-tap-51-8933.html -> 8933
                val dataId = href.substringAfterLast("-").substringBefore(".html")
                if(dataId.isBlank()) return@mapNotNull null

                val epNumStr = el.text().trim()
                val name = "Tập $epNumStr"
                
                // Đóng gói các thông tin trên vào data class và chuyển thành JSON
                val episodeData = EpisodeData(url = "$mainUrl$href", dataId = dataId)
                val jsonData = gson.toJson(episodeData)
                
                newEpisode(jsonData) {
                    this.name = name
                }
            } catch (e: Exception) {
                // Nếu có lỗi khi parse một item, ghi log và bỏ qua
                null
            }
        }.reversed() // Đảo ngược danh sách để tập 1 ở đầu
    }
    
    override suspend fun loadLinks(
        data: String, // 'data' bây giờ là một chuỗi JSON
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Bước 1: Parse chuỗi JSON trở lại thành đối tượng EpisodeData
        val episodeData = gson.fromJson(data, EpisodeData::class.java)

        // Bây giờ bạn có thể truy cập các thuộc tính đã lưu
        val episodePageUrl = episodeData.url
        val episodeId = episodeData.dataId
        
        // Bước 2: Dùng dữ liệu đã parse để lấy link M3U8
        // Logic dưới đây là ví dụ, bạn cần điều chỉnh cho phù hợp với cách HoatHinhQQ lấy link
        val m3u8Regex = """https?://[^\s"'<>]+\.m3u8""".toRegex()
        val documentHtml = app.get(episodePageUrl).document.html()

        m3u8Regex.find(documentHtml)?.let { matchResult ->
            val m3u8Url = matchResult.value
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value, 
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }
        return false
    }
}
