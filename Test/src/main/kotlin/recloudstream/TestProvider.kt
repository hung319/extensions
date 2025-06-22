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
     * CẬP NHẬT: Logic lấy và sắp xếp tập phim đã được làm lại hoàn toàn
     * để xử lý các phim có nhiều danh sách tập như One Piece.
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

            // 1. Lấy tất cả các thẻ <a> chứa tập phim
            val episodeElements = watchPageDocument.select("div.eplist a.tapphim")

            // 2. Chuyển đổi chúng thành một danh sách tạm thời có chứa số tập để sắp xếp
            data class TempEpisode(val url: String, val epText: String, val epNum: Float)
            
            val tempEpisodes = episodeElements.mapNotNull { ep ->
                val epUrl = ep.attr("href")
                val epText = ep.text().replace("_", ".")
                val epNum = epText.toFloatOrNull()
                
                if (epNum != null) {
                    TempEpisode(epUrl, epText, epNum)
                } else {
                    null // Bỏ qua nếu không phải là số (vd: "Movie")
                }
            }

            // 3. Sắp xếp danh sách tạm thời theo số tập giảm dần và loại bỏ tập trùng lặp
            tempEpisodes.distinctBy { it.epNum }
                .sortedByDescending { it.epNum }
                .map { tempEp ->
                    // 4. Tạo đối tượng Episode cuối cùng với định dạng tên chính xác
                    val formattedEpNumber = if (tempEp.epNum == tempEp.epNum.toInt().toFloat()) {
                        tempEp.epNum.toInt().toString() // "10.0" -> "10"
                    } else {
                        tempEp.epNum.toString() // "9.5" -> "9.5"
                    }
                    newEpisode(tempEp.url) {
                        this.name = "Tập $formattedEpNumber"
                        this.episode = null
                    }
                }
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
        // Hiện tại hàm này không làm gì cả (placeholder).
        return true
    }

    data class VideoResponse(val link: List<VideoLink>)
    data class VideoLink(val file: String, val label: String, val type: String)
}
