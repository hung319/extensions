package recloudstream

import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// ================================================================
// --- ĐỊNH NGHĨA CÁC DATA CLASS ĐỂ XỬ LÝ JSON TỪ API ---
// ================================================================

// --- Data Classes cho API Search ---
data class SearchItem(
    @SerializedName("name") val name: String?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("poster_url") val posterUrl: String?,
    @SerializedName("thumb_url") val thumbUrl: String?,
    @SerializedName("total_episodes") val totalEpisodes: Int?,
    @SerializedName("current_episode") val currentEpisode: String?
)

data class SearchApiResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("items") val items: List<SearchItem>?
)

// --- Data Classes cho API Load (Chi tiết phim) ---
data class EpisodeItem(
    @SerializedName("name") val name: String?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("embed") val embed: String?,
    @SerializedName("m3u8") val m3u8: String?
)

data class ServerItem(
    @SerializedName("server_name") val serverName: String?,
    @SerializedName("items") val items: List<EpisodeItem>?
)

data class MovieDetails(
    @SerializedName("name") val name: String?,
    @SerializedName("original_name") val originName: String?,
    @SerializedName("thumb_url") val thumbUrl: String?,
    @SerializedName("poster_url") val posterUrl: String?,
    @SerializedName("content") val plot: String?,
    @SerializedName("year") val year: String?,
    @SerializedName("episodes") val episodes: List<ServerItem>?
)

data class FilmDetails(
    @SerializedName("status") val status: String?,
    @SerializedName("movie") val movie: MovieDetails?
)


// ================================================================
// --- CLASS PLUGIN CHÍNH ---
// ================================================================

class NguoncProvider : MainAPI() {

    override var name = "Phim Nguồn C"
    override var mainUrl = "https://phim.nguonc.com"
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun getAbsoluteUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return if (url.startsWith("http")) url else "$mainUrl/$url"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl/api/films/phim-moi-cap-nhat?page=$page"
        val response = app.get(url).parsed<SearchApiResponse>()

        val homeList = response.items?.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val tvType = if ((item.totalEpisodes ?: 0) > 1 || item.currentEpisode?.contains("Tập") == true) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }
            newMovieSearchResponse(item.name ?: "Unknown", slug, tvType) {
                this.posterUrl = getAbsoluteUrl(item.posterUrl ?: item.thumbUrl)
            }
        } ?: return null

        return newHomePageResponse(
            list = HomePageList("Phim Mới Cập Nhật", homeList),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/films/search?keyword=$query"
        val response = app.get(url).parsed<SearchApiResponse>()
        if (response.status != "success" || response.items.isNullOrEmpty()) {
            return emptyList()
        }

        return response.items.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val tvType = if ((item.totalEpisodes ?: 0) > 1 || item.currentEpisode?.contains("Tập") == true) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }
            newMovieSearchResponse(item.name ?: "Unknown", slug, tvType) {
                this.posterUrl = getAbsoluteUrl(item.posterUrl ?: item.thumbUrl)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = "$mainUrl/api/film/$url"
        val response = app.get(apiUrl).parsed<FilmDetails>()
        val details = response.movie ?: return null

        val title = details.name ?: details.originName ?: "Unknown"
        val poster = getAbsoluteUrl(details.posterUrl ?: details.thumbUrl)
        val plot = details.plot
        val year = details.year?.toIntOrNull()

        // Trích xuất các tập phim từ các server
        val episodes = details.episodes?.flatMap { server ->
            server.items?.mapNotNull { ep ->
                // THAY ĐỔI QUAN TRỌNG:
                // Vì link m3u8 không hoạt động, chúng ta sẽ không truyền nó vào `newEpisode`.
                // Thay vào đó, ta truyền một đối tượng chứa cả link embed và slug,
                // để `loadLinks` có thể thử nhiều cách khác nhau.
                // Ở đây, đơn giản nhất là truyền slug của tập phim.
                val episodeSlug = ep.slug ?: return@mapNotNull null
                
                newEpisode(episodeSlug) {
                    this.name = "${ep.name} - ${server.serverName}"
                }
            } ?: emptyList()
        } ?: emptyList()

        val tvType = if(episodes.size > 1) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    /**
     * HÀM LOADLINKS - PLACEHOLDER
     * Do link m3u8 trực tiếp không hoạt động, hàm này cần được điều tra thêm.
     */
    override suspend fun loadLinks(
        data: String, // `data` giờ là slug của tập phim (ví dụ: "tap-full")
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: VIỆC CẦN LÀM CỦA BẠN
        // `data` đang chứa slug của tập phim. Bạn cần tìm ra cách lấy link video từ slug này.
        // GỢI Ý:
        // 1. Link `embed` trong API (`https://embed14.streamc.xyz/embed.php?hash=...`) là một đầu mối quan trọng.
        //    Hãy truy cập link embed này bằng trình duyệt (bật F12).
        // 2. Quan sát tab "Network" xem trang embed đó gọi đến những file nào. Rất có thể nó sẽ gọi đến một link m3u8 hợp lệ.
        // 3. Bạn cần viết code để "giải mã" link embed đó. Có thể bạn sẽ cần:
        //    - Tải nội dung HTML của trang embed: `app.get(embedUrl).text`
        //    - Dùng regex hoặc các hàm chuỗi để tìm link m3u8 hoặc một link API khác bên trong.
        //    - Một số trang embed sẽ dùng JavaScript để tạo link, trường hợp này sẽ phức tạp hơn.

        // Ví dụ về luồng logic bạn có thể thử:
        // val embedUrl = "..." // Bạn cần cách để lấy được link embed tương ứng với slug `data`
        // val embedPageHtml = app.get(embedUrl).text
        // val m3u8Link = // Dùng regex để tìm link .m3u8 trong `embedPageHtml`
        
        // callback.invoke(
        //     ExtractorLink(
        //         source = this.name,
        //         name = "Server Embed",
        //         url = m3u8Link,
        //         referer = embedUrl, // Referer có thể là trang embed
        //         quality = Qualities.Unknown.value,
        //         isM3u8 = true
        //     )
        // )
        
        return false // Trả về false vì chưa có logic hoàn chỉnh
    }
}
