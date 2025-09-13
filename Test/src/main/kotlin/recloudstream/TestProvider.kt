package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import android.util.Log

class Yanhh3dProvider : MainAPI() {
    override var mainUrl = "https://yanhh3d.vip"
    override var name = "YanHH3D"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Cartoon)

    private fun toSearchQuality(qualityString: String?): SearchQuality? {
        return when {
            qualityString == null -> null
            qualityString.contains("4K") || qualityString.contains("UHD") -> SearchQuality.FourK
            qualityString.contains("1080") || qualityString.contains("FullHD") -> SearchQuality.HD
            qualityString.contains("720") -> SearchQuality.HD
            qualityString.contains("HD") -> SearchQuality.HD
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From SIX [H4RS]\nTelegram/Discord: hung319", Toast.LENGTH_LONG)
            }
        }
        val url = "$mainUrl/moi-cap-nhat?page=$page"
        val document = app.get(url).document
        val newMovies = document.select("div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult() }
        // Sửa: Dùng đúng tên tham số `hasNext`
        return newHomePageResponse(listOf(HomePageList("Phim Mới Cập Nhật", newMovies)), hasNext = newMovies.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.film-name a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img.film-poster-img")?.attr("data-src"))
        val episodeStr = this.selectFirst("div.tick-rate")?.text()?.trim()
        val episodeNum = episodeStr?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val qualityText = this.selectFirst(".tick-dub")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
            this.quality = toSearchQuality(qualityText)
            addDubStatus(dubExist = true, subExist = true, episodeNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keysearch=$query"
        val document = app.get(searchUrl).document
        return document.select("div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult() }
    }
    
    private fun getEpisodeData(doc: Document?, type: String): List<Pair<String, String>> {
        val selector = if (type == "TM") "div#top-comment div.ss-list a.ssl-item" else "div#new-comment div.ss-list a.ssl-item"
        return doc?.select(selector)
            ?.mapNotNull {
                val epUrl = it.attr("href")
                val orderText = it.selectFirst(".ssli-order")?.text()?.trim()
                if (epUrl.isNullOrBlank() || orderText.isNullOrBlank()) return@mapNotNull null
                Pair(orderText, fixUrl(epUrl))
            } ?: emptyList()
    }

    private data class MergedEpisodeInfo(var dubUrl: String? = null, var subUrl: String? = null)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.film-name")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.anisc-poster img")?.attr("src") ?: document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("div.film-description div.text")?.text()?.trim()
        val tags = document.select("div.anisc-info a.genre").map { it.text() }
        val year = document.select("div.anisc-info span.item-head:contains(Năm:) + span.name")?.text()?.toIntOrNull()

        val dubWatchUrl = fixUrlNull(document.selectFirst("a.btn-play")?.attr("href"))
        val subWatchUrl = fixUrlNull(document.selectFirst("a.custom-button-sub")?.attr("href"))
        
        val recommendations = document.select("section.block_area_category div.flw-item").mapNotNull { it.toSearchResult() }
        
        val episodes = coroutineScope {
            val dubDataDeferred = async { dubWatchUrl?.let { getEpisodeData(app.get(it).document, "TM") } ?: emptyList() }
            val subDataDeferred = async { subWatchUrl?.let { getEpisodeData(app.get(it).document, "VS") } ?: emptyList() }

            val dubData = dubDataDeferred.await()
            val subData = subDataDeferred.await()

            val mergedMap = mutableMapOf<String, MergedEpisodeInfo>()
            dubData.forEach { (name, epUrl) -> mergedMap.getOrPut(name) { MergedEpisodeInfo() }.dubUrl = epUrl }
            subData.forEach { (name, epUrl) -> mergedMap.getOrPut(name) { MergedEpisodeInfo() }.subUrl = epUrl }

            mergedMap.map { (name, info) ->
                val tag = when {
                    info.dubUrl != null && info.subUrl != null -> "(TM+VS)"
                    info.dubUrl != null -> "(TM)"
                    info.subUrl != null -> "(VS)"
                    else -> ""
                }
                val episodeName = "Tập $name $tag".trim()
                val episodeUrl = info.dubUrl ?: info.subUrl!!
                newEpisode(episodeUrl) { this.name = episodeName }
            // Sửa: Logic sắp xếp an toàn hơn để không còn lỗi
            }.sortedBy { episode ->
                episode.name?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0
            }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Cartoon, dubWatchUrl ?: subWatchUrl ?: url) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
                this.recommendations = recommendations
            }
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
            this.recommendations = recommendations
        }
    }

    private suspend fun extractLinksFromPage(
        url: String,
        prefix: String, // Tham số "tag" (TM hoặc VS)
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG = "Yanhh3dProvider"
        Log.d(TAG, "[$prefix] Bắt đầu trích xuất từ URL: $url")
        try {
            val document = app.get(url).document
            val scriptContent = document.select("script:containsData(checkLink)").html()
            if (scriptContent.isBlank()) {
                throw Exception("[$prefix] Không tìm thấy script chứa link server tại $url")
            }
            Log.d(TAG, "[$prefix] Đã lấy được script content.")

            coroutineScope {
                val serverElements = document.select("div#list_sv a")
                Log.d(TAG, "[$prefix] Tìm thấy ${serverElements.size} server element(s).")

                serverElements.forEach { serverElement ->
                    launch {
                        val serverName = serverElement.text().trim()
                        val serverId = serverElement.attr("name")
                        try {
                            Log.d(TAG, "[$prefix] -> Đang xử lý server: '$serverName' (ID: $serverId)")

                            val link = Regex("""var\s*\${'$'}check$serverId\s*=\s*"([^"]+)"""")
                                .find(scriptContent)?.groupValues?.get(1)

                            if (link.isNullOrBlank()) {
                                Log.w(TAG, "[$prefix] -> Server '$serverName': Không tìm thấy link trong script.")
                                return@launch
                            }
                            Log.d(TAG, "[$prefix] -> Server '$serverName': Link gốc trích xuất được: $link")
                            
                            var finalUrl = link
                            if (finalUrl.contains("short.icu")) {
                                Log.d(TAG, "[$prefix] -> Server '$serverName': Phát hiện link rút gọn, đang giải mã...")
                                finalUrl = app.get(finalUrl, allowRedirects = false).headers["location"] ?: ""
                                Log.d(TAG, "[$prefix] -> Server '$serverName': Link sau khi giải mã: $finalUrl")
                            }
                            
                            finalUrl = fixUrl(finalUrl)
                            if (finalUrl.isBlank()) {
                                throw Exception("Link sau khi xử lý của server '$serverName' bị rỗng.")
                            }

                            val finalName = "$prefix - $serverName"

                            when {
                                finalUrl.contains("helvid.net") -> {
                                    Log.d(TAG, "[$prefix] -> Server '$serverName': Logic Helvid, URL: $finalUrl")
                                    val helvidDoc = app.get(finalUrl).document
                                    val playerScript = helvidDoc.selectFirst("script:containsData('playerInstance.setup')")?.data()
                                    val videoPath = Regex("""file:\s*"(.*?)"""").find(playerScript ?: "")?.groupValues?.get(1)

                                    if (videoPath?.isNotBlank() == true) {
                                        val videoUrl = "https://helvid.net$videoPath"
                                        Log.d(TAG, "[$prefix] -> Server '$serverName': Bóc tách thành công: $videoUrl")
                                        callback(/*...*/) // Giữ nguyên callback của bạn
                                        Log.i(TAG, "[$prefix] -> Server '$serverName': SUCCESS")
                                    } else {
                                        throw Exception("Không bóc tách được videoPath từ Helvid cho server '$serverName'")
                                    }
                                }
                                finalUrl.contains("/play-fb-v") -> {
                                    Log.d(TAG, "[$prefix] -> Server '$serverName': Logic Play-FB-V, URL: $finalUrl")
                                    val playerDocument = app.get(finalUrl, referer = url).document
                                    val scrapedUrl = playerDocument.selectFirst("#player")?.attr("data-stream-url")
                                        ?: Regex("""var cccc = "([^"]+)""").find(playerDocument.html())?.groupValues?.get(1)

                                    if (scrapedUrl?.isNotBlank() == true) {
                                        Log.d(TAG, "[$prefix] -> Server '$serverName': Bóc tách thành công: $scrapedUrl")
                                        loadExtractor(scrapedUrl, mainUrl, subtitleCallback, callback)
                                        Log.i(TAG, "[$prefix] -> Server '$serverName': SUCCESS (via loadExtractor)")
                                    } else {
                                        throw Exception("Không bóc tách được scrapedUrl từ Play-FB-V cho server '$serverName'")
                                    }
                                }
                                finalUrl.contains("fbcdn.cloud/video/") -> {
                                    val m3u8Url = "$finalUrl/master.m3u8"
                                    Log.d(TAG, "[$prefix] -> Server '$serverName': Logic FBCDN Video, URL cuối: $m3u8Url")
                                    callback(/*...*/) // Giữ nguyên callback của bạn
                                    Log.i(TAG, "[$prefix] -> Server '$serverName': SUCCESS")
                                }
                                finalUrl.contains("fbcdn.") -> {
                                    Log.d(TAG, "[$prefix] -> Server '$serverName': Logic FBCDN, URL: $finalUrl")
                                    if (finalUrl.contains(".m3u8")) {
                                        val m3u8Url = finalUrl.replace("/o1/v/t2/f2/m3m3u8/")
                                        Log.d(TAG, "[$prefix] -> Server '$serverName': Biến đổi thành M3U8: $m3u8Url")
                                        callback(/*...*/) // Giữ nguyên callback của bạn
                                    } else {
                                        Log.d(TAG, "[$prefix] -> Server '$serverName': Xử lý như link video trực tiếp.")
                                        callback(/*...*/) // Giữ nguyên callback của bạn
                                    }
                                    Log.i(TAG, "[$prefix] -> Server '$serverName': SUCCESS")
                                }
                                else -> {
                                    Log.d(TAG, "[$prefix] -> Server '$serverName': Không khớp logic nào, dùng loadExtractor mặc định. URL: $finalUrl")
                                    loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[$prefix] ### LỖI ### khi xử lý server '$serverName' (ID: $serverId): ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "### LỖI NGHIÊM TRỌNG ### trong extractLinksFromPage cho URL: $url. Message: ${e.message}", e)
            throw e // Ném lại lỗi để Cloudstream biết và xử lý
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "Yanhh3dProvider"
        Log.d(TAG, "Bắt đầu loadLinks với data: $data")
        try {
            val path = try {
                URI(data).path.removePrefix("/sever2")
            } catch (e: Exception) {
                Log.e(TAG, "URL của tập phim không hợp lệ: $data", e)
                throw Exception("URL của tập phim không hợp lệ: $data", e)
            }
            if (path.isBlank()) return false
            
            val dubUrl = "$mainUrl$path"
            val subUrl = "$mainUrl/sever2$path"

            Log.d(TAG, "Chuẩn bị lấy link song song. TM: $dubUrl || VS: $subUrl")

            coroutineScope {
                launch { extractLinksFromPage(dubUrl, "TM", subtitleCallback, callback) }
                launch { extractLinksFromPage(subUrl, "VS", subtitleCallback, callback) }
            }
            Log.d(TAG, "loadLinks hoàn tất.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "### LỖI CUỐI CÙNG ### trong hàm loadLinks: ${e.message}", e)
            throw e
        }
    }
}
