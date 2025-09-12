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

    // === Cập nhật: Đổi MP4 thành VIDEO và tối ưu logic lặp ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = killer).document
        val scriptContent = document.select("script").html()
        var extracted = false

        // Dùng forEach để xử lý tuần tự, ổn định hơn
        document.select("div.ps__-list a.btn3dsv").forEach { serverElement ->
            try {
                val name = serverElement.attr("name")
                val qualityLabel = serverElement.text()
                val serverId = name.removePrefix("LINK")

                val url = Regex("""var\s*\${'$'}checkLink$serverId\s*=\s*"([^"]+)"""")
                    .find(scriptContent)?.groupValues?.get(1)

                if (url.isNullOrBlank()) return@forEach

                // Trường hợp 1: Link là iframe player cần bóc tách
                if (url.contains("/play-fb-v7/play/")) {
                    val playerDocument = app.get(url, referer = data).document
                    var streamUrl = playerDocument.selectFirst("#player")?.attr("data-stream-url")
                    
                    if (streamUrl.isNullOrBlank()) {
                        streamUrl = Regex("""var cccc = "([^"]+)""").find(playerDocument.html())?.groupValues?.get(1)
                    }

                    if (!streamUrl.isNullOrBlank()) {
                        val isM3u8 = streamUrl.contains(".m3u8")
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = qualityLabel,
                                url = streamUrl,
                                referer = "$mainUrl/",
                                quality = Qualities.Unknown.value,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        extracted = true
                    }
                }
                // Trường hợp 2: Link short-cdn.ink cần nối thêm /master.m3u8
                else if (url.contains("short-cdn.ink/video/")) {
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = qualityLabel,
                            url = "$url/master.m3u8",
                            referer = url,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    extracted = true
                }
                // Trường hợp 3: Link M3U8 của fbcdn cần chuyển đổi
                else if (url.contains("fbcdn.cloud") && url.contains("/o1/v/")) {
                    val realM3u8Url = url.replace(Regex("/o1/v/t2/f2/m366/"), "/stream/m3u8/")
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = qualityLabel,
                            url = realM3u8Url,
                            referer = "$mainUrl/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    extracted = true
                }
                // Trường hợp 4: Các link video trực tiếp khác
                else if (url.endsWith(".mp4", true) || url.endsWith(".m3u8", true) || url.endsWith(".webm", true) || url.endsWith(".mkv", true)) {
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = qualityLabel,
                            url = url,
                            referer = "$mainUrl/",
                            quality = Qualities.Unknown.value,
                            type = if (url.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    extracted = true
                }
                // Trường hợp 5: Các loại link còn lại (VD: link rút gọn), để loadExtractor xử lý
                else {
                    loadExtractor(url, data, subtitleCallback, callback)
                    extracted = true
                }
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi ở một server
            }
        }

        return extracted
    }
}
