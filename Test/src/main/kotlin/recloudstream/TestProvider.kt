package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

class AnimetmProvider : MainAPI() {
    override var name = "AnimeTM"
    override var mainUrl = "https://animetm.tv"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    private val killer = CloudflareKiller()

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.film-poster-ahref") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.attr("title")
        val posterUrl = this.selectFirst("img.film-poster-img")?.attr("data-src")
        
        val episodesText = this.selectFirst("div.tick-rate")?.text()
        val episodeCount = episodesText?.substringBefore("/")?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (episodeCount != null) {
                this.addDubStatus(DubStatus.Subbed, episodeCount)
            }
        }
    }

    private suspend fun getPage(url: String): List<SearchResponse> {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From SIX [H4RS]\nTelegram/Discord: hung319", Toast.LENGTH_LONG)
            }
        }
        val document = app.get(url, interceptor = killer).document
        return document.select("div.flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = getPage("$mainUrl/moi-cap-nhat?page=$page")
        return newHomePageResponse("Phim Mới Cập Nhật", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keysearch=$query"
        return getPage(searchUrl)
    }

    // === Giữ nguyên hàm load theo yêu cầu ===
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = killer).document
        val title = document.selectFirst("h2.film-name")?.text()?.trim() ?: "Không rõ"
        val poster = document.selectFirst("div.anisc-poster img")?.attr("src")
        val plot = document.selectFirst("div.film-description > div.text")?.text()?.trim()

        val episodePageUrl = document.selectFirst("a.btn-play")?.attr("href")

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot

            if (episodePageUrl != null) {
                val episodeListDocument = app.get(episodePageUrl, interceptor = killer).document
                
                val episodes = episodeListDocument.select("div.ep-range a.ssl-item").map {
                    val episodeNumber = it.attr("title")
                    newEpisode(it.attr("href")) {
                        name = "Tập $episodeNumber"
                    }
                }.reversed()
                
                addEpisodes(DubStatus.Subbed, episodes)
            }

            recommendations = document.select("section.block_area_category div.flw-item").mapNotNull {
                it.toSearchResult()
            }
        }
    }

    // === Cập nhật hàm loadLinks với logic xử lý iframe player ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = killer).document
        val scriptContent = document.select("script").html()

        val servers = document.select("div.ps__-list a.btn3dsv")

        servers.forEach { serverElement ->
            try {
                val name = serverElement.attr("name")
                val qualityLabel = serverElement.text()
                
                val serverId = name.removePrefix("LINK")

                val linkRegex = Regex("""var\s*\${'$'}checkLink$serverId\s*=\s*"([^"]+)"""")
                val url = linkRegex.find(scriptContent)?.groupValues?.get(1)

                if (!url.isNullOrBlank()) {
                    // Trường hợp 1: Link là M3U8 trực tiếp
                    if (url.contains(".m3u8")) {
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = qualityLabel,
                                url = url,
                                referer = "$mainUrl/",
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    // Trường hợp 2: Link là iframe player cần bóc tách
                    } else if (url.contains("/play-fb-v7/play/")) {
                        val playerPage = app.get(url, referer = data).document
                        val streamUrl = playerPage.selectFirst("#player")?.attr("data-stream-url")
                        if (streamUrl != null) {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = qualityLabel,
                                    url = streamUrl,
                                    referer = "$mainUrl/",
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                    // Trường hợp 3: Các loại link khác (VD: link rút gọn), để loadExtractor xử lý
                    } else {
                        loadExtractor(url, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi
            }
        }

        return true
    }
}
