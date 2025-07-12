package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlin.text.RegexOption

class LongTiengPhimProvider : MainAPI() {
    // Thông tin cơ bản về provider
    override var mainUrl = "https://longtiengphim.com"
    override var name = "Lồng Tiếng Phim"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Danh sách trang chính
    override val mainPage = mainPageOf(
        "$mainUrl/tat-ca-phim/page/" to "Tất Cả Phim",
        "$mainUrl/phim-hoat-hinh/page/" to "Phim Hoạt Hình",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.halim_box > article").mapNotNull {
            val tvType = if (request.name == "Phim Hoạt Hình") TvType.Anime else TvType.Movie
            it.toSearchResult(tvType)
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(tvType: TvType = TvType.Movie): SearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a.halim-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("figure.film-poster img, figure.lazy.img-responsive")?.attr("src") ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.halim_box > article").mapNotNull {
            it.toSearchResult(TvType.Movie)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img.movie-thumb")?.attr("src")
        val plot = document.selectFirst("div.entry-content > article")?.text()?.trim()
        val year = document.selectFirst("a[href*=/release/]")?.text()?.trim()?.toIntOrNull()

        // === CẬP NHẬT: Lấy tag trực tiếp từ thẻ meta "article:section" ===
        val tags = document.selectFirst("meta[property=article:section]")
            ?.attr("content")
            ?.split(',')
            ?.map { it.trim() }
            ?: emptyList()
        
        val isAnime = tags.any { it.contains("Hoạt Hình", ignoreCase = true) }
        val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
        // ==============================================================

        val recommendations = document.select("div#halim_related_movies-3 article").mapNotNull {
            it.toSearchResult(tvType)
        }

        val episodes = document.select("div#halim-list-server ul.halim-list-eps li.halim-episode a").mapNotNull {
            val episodeUrl = it.attr("href").replace(" ", "")
            newEpisode(episodeUrl) {
                this.name = it.text().trim()
            }
        }.ifEmpty {
            val slug = url.trim('/').substringAfterLast('/')
            val watchUrl = "$mainUrl/watch/$slug-eps-1-server-1"
            listOf(newEpisode(watchUrl) {
                this.name = "Xem phim"
            })
        }
        
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = data.replace(" ", "")
        val watchPageSource = app.get(fixedData).text

        val postId = Regex("""'postid':\s*'(\d+)'""").find(watchPageSource)?.groupValues?.get(1)
            ?: Regex("""post_id:\s*(\d+)""").find(watchPageSource)?.groupValues?.get(1)
            ?: return false
        val nonce = Regex(""""nonce":"([^"]+)"""").find(watchPageSource)?.groupValues?.get(1) ?: return false

        val postData = mapOf(
            "action" to "halim_ajax_player",
            "nonce" to nonce,
            "postid" to postId,
        )

        val playerScript = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = postData,
            referer = fixedData,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val videoUrlRegex = Regex("""file":"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        val match = videoUrlRegex.find(playerScript) ?: return false

        val videoUrl = match.groupValues[1]
            .replace("\\/", "/")
            .replace("\n", "")
            .replace("\r", "")

        if (!videoUrl.startsWith("http")) return false

        val isM3u8 = videoUrl.contains(".m3u8")

        callback(
            ExtractorLink(
                source = this.name,
                name = "Server Lồng Tiếng",
                url = videoUrl,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            )
        )
        return true
    }
}
