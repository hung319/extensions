// Đặt package của tệp là "recloudstream"
package recloudstream

// Import các thư viện từ package gốc "com.lagradost.cloudstream3"
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.text.DecimalFormat

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

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pages = listOf(
            Triple("$mainUrl/nhom/anime.html", "Anime Mới", TvType.Anime),
            Triple("$mainUrl/bang-xep-hang.html", "Bảng Xếp Hạng", TvType.Anime),
            Triple("$mainUrl/nhom/japanese-drama.html", "Live Action", TvType.TvSeries),
            Triple("$mainUrl/nhom/sieu-nhan.html", "Siêu Nhân", TvType.Cartoon),
            Triple("$mainUrl/nhom/cartoon.html", "Cartoon", TvType.Cartoon)
        )

        val all = coroutineScope {
            pages.map { (url, name, type) ->
                async {
                    try {
                        val pageUrl = "$url?page=$page"
                        val document = app.get(pageUrl).document

                        val home = if (name == "Bảng Xếp Hạng") {
                            document.select("ul.rank-film-list > li.item").mapNotNull {
                                it.toRankingSearchResult(type)
                            }
                        } else {
                            document.select("div.film_item").mapNotNull {
                                it.toSearchResult(type)
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

    private fun Element.toSearchResult(type: TvType): SearchResponse? {
        val titleElement = this.selectFirst("h3.title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }
    
    private fun Element.toRankingSearchResult(type: TvType): SearchResponse? {
        val linkElement = this.selectFirst("a.image") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.selectFirst("h3.title")?.text() ?: return null
        val posterUrl = linkElement.selectFirst("img.thumb")?.attr("src")

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query}.html?page=1"
        val document = app.get(searchUrl).document
        return document.select("div.film_item").mapNotNull {
            it.toSearchResult(TvType.Anime)
        }
    }

    /**
     * CẬP NHẬT: 
     * 1. Đảo ngược thứ tự danh sách tập (bỏ .reversed()).
     * 2. Định dạng lại tên tập để loại bỏ số 0 ở đầu (09.5 -> 9.5).
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
                val epText = ep.text().replace("_",".") // "09.5", "26_End" -> "26.End"

                // Cố gắng chuyển đổi text thành số để định dạng lại
                val formattedEpNumber = try {
                    // Định dạng để loại bỏ ".0" cho số nguyên nhưng giữ lại cho số thập phân
                    val number = epText.toFloat()
                    if (number == number.toInt().toFloat()) {
                        number.toInt().toString()
                    } else {
                        number.toString()
                    }
                } catch (e: NumberFormatException) {
                    // Nếu không phải là số (ví dụ: "26.End"), giữ nguyên text gốc
                    epText
                }
                
                newEpisode(epUrl) {
                    this.name = "Tập $formattedEpNumber"
                    this.episode = null
                }
            } // ĐÃ BỎ `.reversed()` ĐỂ CÁC TẬP MỚI NHẤT HIỂN THỊ TRƯỚC
        } else {
            listOf()
        }

        val isLiveAction = genres.any { it.equals("Live Action", ignoreCase = true) || it.equals("Japanese Drama", ignoreCase = true) }
        val isTokusatsuOrCartoon = genres.any { it.equals("Siêu Nhân", ignoreCase = true) || it.equals("Tokusatsu", ignoreCase = true) || it.equals("Cartoon", ignoreCase = true) }

        if (isLiveAction) {
            return if (episodes.size > 1) newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            } else newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }

        if (isTokusatsuOrCartoon) {
            return if (episodes.size > 1) newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            } else newMovieLoadResponse(title, url, TvType.Cartoon, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }

        return if (episodes.isNotEmpty()) newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        } else newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }

    data class VideoResponse(val link: List<VideoLink>)
    data class VideoLink(val file: String, val label: String, val type: String)
}
