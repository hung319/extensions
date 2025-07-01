package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

class PhimHHTQProvider : MainAPI() {
    override var mainUrl = "https://phimhhtq.com"
    override var name = "PhimHHTQ"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "moi-cap-nhat" to "Mới Cập Nhật",
        "xem-nhieu" to "Phim Xem Nhiều"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}/"
        } else {
            "$mainUrl/${request.data}/page/$page/"
        }

        val document = app.get(url).document
        val items = document.select("div.halim_box article.thumb").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a.halim-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("figure img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }

        val episodeStr = this.selectFirst("span.episode")?.text()?.trim()
        return newAnimeSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to mainUrl)
            addQuality(episodeStr ?: "")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.halim_box article.thumb").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img.movie-thumb")?.attr("src")
        val plot = document.selectFirst("div.entry-content article")?.text()?.trim()
        val year = document.selectFirst("div.more-info")?.text()?.let {
            Regex("(\\d{4})").find(it)?.value?.toIntOrNull()
        }

        val episodes = document.select("ul.halim-list-eps li.halim-episode a").map {
            val epName = it.text().trim()
            val epHref = it.attr("href")
            newEpisode(epHref) {
                this.name = "Tập $epName"
            }
        } // THAY ĐỔI: Đã xóa .reversed() để đảo lại thứ tự tập phim theo yêu cầu.

        val recommendations = document.select("section.related-movies div.halim_box article.thumb").mapNotNull {
            it.toSearchResult()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to mainUrl)
            this.year = year
            this.plot = plot
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageSource = app.get(data).text
        val postId = Regex("""post_id:(\d+)""").find(watchPageSource)?.groupValues?.get(1) ?: return false
        val nonce = Regex("""nonce":"([a-zA-Z0-9]+)"""").find(watchPageSource)?.groupValues?.get(1) ?: return false
        val epDataRegex = Regex("""-tap-(\d+)-sv-(\d+)""")
        val matchResult = epDataRegex.find(data)
        val episode = matchResult?.groupValues?.get(1) ?: "1"
        val server = matchResult?.groupValues?.get(2) ?: "1"
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val ajaxData = mapOf(
            "action" to "halim_ajax_player",
            "nonce" to nonce,
            "postid" to postId,
            "episode" to episode,
            "server" to server
        )
        val ajaxResponseText = app.post(
            ajaxUrl,
            data = ajaxData,
            headers = mapOf("Referer" to data)
        ).text
        val videoUrl = Regex("""file":"([^"]+)"""").find(ajaxResponseText)?.groupValues?.get(1)?.replace("\\", "")
        if (videoUrl != null) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "PhimHHTQ Server",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = getQualityFromName(""),
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf("Referer" to mainUrl)
                )
            )
            return true
        }
        return false
    }
}
