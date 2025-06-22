// Đặt package của tệp là "recloudstream"
package recloudstream

// Import các thư viện từ package gốc "com.lagradost.cloudstream3"
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

/**
 * Đây là lớp chính của plugin.
 */
class AnimeTVNProvider : MainAPI() {
    // Thông tin cơ bản của nhà cung cấp
    override var mainUrl = "https://animetvn4.com"
    override var name = "AnimeTVN"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true

    // CẬP NHẬT: Thêm các loại được hỗ trợ
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // CẬP NHẬT: Thêm TvType vào từng danh mục
        val pages = listOf(
            Triple("$mainUrl/nhom/anime.html", "Anime Mới", TvType.Anime),
            Triple("$mainUrl/bang-xep-hang.html", "Bảng Xếp Hạng", TvType.Anime),
            Triple("$mainUrl/nhom/japanese-drama.html", "Live Action", TvType.TvSeries),
            Triple("$mainUrl/nhom/sieu-nhan.html", "Siêu Nhân", TvType.Cartoon),
            Triple("$mainUrl/nhom/cartoon.html", "Cartoon", TvType.Cartoon)
        )

        val all = coroutineScope {
            pages.map { (url, name, type) -> // Lấy thêm 'type'
                async {
                    try {
                        val pageUrl = "$url?page=$page"
                        val document = app.get(pageUrl).document

                        val home = if (name == "Bảng Xếp Hạng") {
                            document.select("ul.rank-film-list > li.item").mapNotNull {
                                it.toRankingSearchResult(type) // Truyền 'type' vào
                            }
                        } else {
                            document.select("div.film_item").mapNotNull {
                                it.toSearchResult(type) // Truyền 'type' vào
                            }
                        }
                        
                        if (home.isNotEmpty()) HomePageList(name, home) else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
        
        if (all.isEmpty() && page > 1) {
            return HomePageResponse(emptyList(), false)
        }
        
        return HomePageResponse(all, true)
    }

    // CẬP NHẬT: Hàm nhận thêm tham số 'type'
    private fun Element.toSearchResult(type: TvType): SearchResponse? {
        val titleElement = this.selectFirst("h3.title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        // Sử dụng 'type' được truyền vào
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    // CẬP NHẬT: Hàm nhận thêm tham số 'type'
    private fun Element.toRankingSearchResult(type: TvType): SearchResponse? {
        val linkElement = this.selectFirst("a.image") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.selectFirst("h3.title")?.text() ?: return null
        val posterUrl = linkElement.selectFirst("img.thumb")?.attr("src")

        // Sử dụng 'type' được truyền vào
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query}.html?page=1"
        val document = app.get(searchUrl).document
        // Mặc định kết quả tìm kiếm là Anime, vì không có cách phân biệt rõ ràng ở trang tìm kiếm
        return document.select("div.film_item").mapNotNull {
            it.toSearchResult(TvType.Anime)
        }
    }

    /**
     * CẬP NHẬT: Phân loại TvType dựa trên thể loại (genre) của phim.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.name-vi")?.text() ?: return null
        val poster = document.selectFirst("div.small_img img")?.attr("src")
        val description = document.selectFirst("div#tab-film-content div.content")?.text()
        val genres = document.select("li.has-color:contains(Thể loại) a").map { it.text() }
        val watchPageUrl = document.selectFirst("a.btn.play-now")?.attr("href")

        val episodes = if (watchPageUrl != null) {
            val watchPageDocument = app.get(watchPageUrl).document
            watchPageDocument.select("div.eplist a.tapphim").mapNotNull { ep ->
                val epUrl = ep.attr("href")
                val epText = ep.text()
                newEpisode(epUrl) {
                    this.name = "Tập $epText"
                    this.episode = null
                }
            }.reversed()
        } else {
            listOf()
        }

        // Kiểm tra thể loại để quyết định TvType
        val isLiveAction = genres.any { it.equals("Live Action", ignoreCase = true) || it.equals("Japanese Drama", ignoreCase = true) }
        val isTokusatsuOrCartoon = genres.any { it.equals("Siêu Nhân", ignoreCase = true) || it.equals("Tokusatsu", ignoreCase = true) || it.equals("Cartoon", ignoreCase = true) }

        // Logic cho Live Action
        if (isLiveAction) {
            return if (episodes.size > 1) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = genres
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = genres
                }
            }
        }

        // Logic cho Siêu Nhân và Cartoon
        if (isTokusatsuOrCartoon) {
            return if (episodes.size > 1) {
                newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = genres
                }
            } else {
                 newMovieLoadResponse(title, url, TvType.Cartoon, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = genres
                }
            }
        }

        // Mặc định là Anime
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Hiện tại hàm này không làm gì cả (placeholder).
        return true
    }

    data class VideoResponse(
        val link: List<VideoLink>
    )

    data class VideoLink(
        val file: String,
        val label: String,
        val type: String
    )
}
