package com.lagradost.cloudstream3.plugins.hhninjaprovider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller // Bỏ comment nếu cần
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Log

class HHNinjaProvider : MainAPI() {
    override var mainUrl = "https://hhninja.top"
    override var name = "HHNinja.top"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-cap-nhap.html?p=" to "Mới Cập Nhật",
        "$mainUrl/the-loai/phim-2d.html?p=" to "Phim 2D",
        "$mainUrl/the-loai/phim-3d.html?p=" to "Phim 3D",
        "$mainUrl/loc-phim/W1tdLFtdLFsxXSxbXV0=?p=" to "Phim Lẻ",
        "$mainUrl/loc-phim/W1tdLFtdLFsyXSxbXV0=?p=" to "Phim Bộ"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.name-movie")?.text()?.trim() ?: return null
        var href = this.selectFirst("a")?.attr("href") ?: return null
        if (!href.startsWith("http") && !href.startsWith("/")) {
            href = "/$href"
        }

        val posterUrl = this.selectFirst("img")?.attr("src")
           ?: this.selectFirst("img")?.attr("data-src")

        val episodeStr = this.selectFirst("div.episode-latest span")?.text()?.trim()

        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
            if (episodeStr != null) {
                val epRegex = Regex("""(?:Tập\s*)?(\d+)(?:(?:[/| ]\s*\d+\s*)?|$)""")
                val matchResult = epRegex.find(episodeStr)
                val currentEpisode = matchResult?.groupValues?.get(1)?.toIntOrNull()

                val isDub = episodeStr.contains("Lồng tiếng", ignoreCase = true) || episodeStr.contains("Thuyết Minh", ignoreCase = true)
                val isSub = episodeStr.contains("Vietsub", ignoreCase = true) || !isDub 

                addDubStatus(
                    dubExist = isDub,
                    subExist = isSub,
                    dubEpisodes = if (isDub) currentEpisode else null,
                    subEpisodes = if (isSub) currentEpisode else null 
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query.replace(" ", "-")}.html"
        val document = app.get(searchUrl).document

        return document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.info-movie div.head div.first img")?.attr("src")
            ?: document.selectFirst("div.info-movie div.head div.first img")?.attr("data-src")
        val plot = document.selectFirst("div.desc div[style*='overflow:auto'] p")?.text()?.trim()
        
        val yearText = document.select("div.info-movie div.head div.last div.update_time > div:contains(Năm) + div")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        val statusText = document.select("div.info-movie div.head div.last div.status > div:contains(Trạng Thái) + div")?.text()?.trim()
        val showStatus = when {
            statusText?.contains("Đang Cập Nhật", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Hoàn Thành", ignoreCase = true) == true || statusText?.contains("Full", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }

        val episodes = document.select("div.list_episode div.list-item-episode a").mapNotNull { el ->
            val epNameFull = el.selectFirst("span")?.text()?.trim() ?: el.text().trim()
            val epNumRegex = Regex("""Tập\s*(\S+)""")
            val epNumMatch = epNumRegex.find(epNameFull)
            val epNumString = epNumMatch?.groupValues?.get(1)
            
            val epName = if (epNumString != null && !epNumString.contains("Trailer", ignoreCase = true) && !epNumString.contains("PV", ignoreCase = true) ) "Tập $epNumString" else epNameFull

            val epHref = el.attr("href")
            if (epName.contains("Trailer", ignoreCase = true) || epName.contains("PV", ignoreCase = true)) {
                null 
            } else {
                Episode(
                    data = fixUrl(epHref), 
                    name = epName,
                    episode = epNumString?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                )
            }
        }.reversed()

        val tvType = if (episodes.size > 1 || episodes.any { it.name?.contains("Tập", ignoreCase = true) == true && it.name?.contains("Full", ignoreCase = true) == false}) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        val recommendations = document.select("div.list_episode_relate div.movies-list div.movie-item").mapNotNull {
            it.toSearchResult()
        }
        
        val genres = document.select("div.info-movie div.head div.last div.list_cate > div:contains(Thể Loại) + div a").mapNotNull { it.text() }

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
                this.showStatus = showStatus
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, fixUrl(episodes.firstOrNull()?.data ?: "")) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
                this.tags = genres
            }
        }
    }

    private data class InitialPlayerResponseInfo(
        @JsonProperty("Movie_Title") val movieTitle: String?,
        @JsonProperty("Movie_Vote") val movieVote: String?,
        @JsonProperty("Movie_Year") val movieYear: String?
    )
    private data class InitialPlayerResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("info") val info: InitialPlayerResponseInfo?,
        @JsonProperty("message") val message: String?,
        @JsonProperty("src_fbk") val srcFbk: String?,
        @JsonProperty("src_vip_1") val srcVip1: String?,
        @JsonProperty("src_vip_2") val srcVip2: String?,
        @JsonProperty("src_vip_3") val srcVip3: String?, 
        @JsonProperty("src_hyd") val srcHyd: String?,   
        @JsonProperty("src_dlm") val srcDlm: String? = null,
        @JsonProperty("src_vip_4") val srcVip4: String? = null,
        @JsonProperty("src_vip_5") val srcVip5: String? = null,
        @JsonProperty("src_vip_6") val srcVip6: String? = null
    )

    private data class Hh4dVideoSource(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )
    private data class Hh4dMessage(
        @JsonProperty("type") val type: String?,
        @JsonProperty("source") val source: String?
    )
    private data class Hh4dApiResponse(
        @JsonProperty("status") val status: Boolean?,
        @JsonProperty("message") val message: Hh4dMessage?
    )

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePageDocument = app.get(data, referer = data).document

        val episodeIdRegex = Regex("""episode-id-(\d+)""")
        val episodeId = episodeIdRegex.find(data)?.groupValues?.get(1) ?: return false

        val movieId = episodePageDocument.selectFirst("form#episode_error input[name=movie_id]")?.attr("value")
            ?: episodePageDocument.select("script").mapNotNull { script ->
                val scriptText = script.html()
                Regex("""(?:MovieID|movie_id):\s*(\d+)""").find(scriptText)?.groupValues?.get(1)
            }.firstOrNull()
            ?: return false
        
        val ajaxUrl = "$mainUrl/server/ajax/player"
        val initialAjaxResponse = app.post(
            ajaxUrl,
            data = mapOf("MovieID" to movieId, "EpisodeID" to episodeId),
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<InitialPlayerResponse>()

        if (initialAjaxResponse?.code != 200) {
            return false
        }

        var foundStream = false
        val serverSources = listOfNotNull(
            initialAjaxResponse.srcFbk?.let { it to "FBK" },
            initialAjaxResponse.srcVip1?.let { it to "VIP_1" },
            initialAjaxResponse.srcVip2?.let { it to "VIP_2 (hh4d.site)" },
            initialAjaxResponse.srcDlm?.let { it to "DLM" },
            initialAjaxResponse.srcVip4?.let { it to "VIP_4" },
            initialAjaxResponse.srcVip5?.let { it to "VIP_5" },
            initialAjaxResponse.srcVip6?.let { it to "VIP_6" }
        ).filter { it.first.isNotBlank() }

        for ((videoUrl, serverName) in serverSources) {
            Log.d(this.name, "Đang thử server $serverName với URL: $videoUrl")

            if (videoUrl.endsWith(".m3u8", ignoreCase = true)) {
                val hlsLinks = M3u8Helper.generateM3u8(
                    name = "${this.name} - $serverName", 
                    streamUrl = videoUrl,
                    referer = mainUrl 
                )
                hlsLinks.forEach { link ->
                    callback(
                        ExtractorLink(
                            source = link.source, name = link.name, url = link.url,
                            referer = link.referer, quality = link.quality,
                            headers = link.headers ?: emptyMap(), extractorData = link.extractorData,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    foundStream = true
                }
            } else if (serverName == "VIP_2 (hh4d.site)" && videoUrl.contains("hh4d.site/v/")) {
                Log.d(this.name, "Đang xử lý $serverName: $videoUrl")
                try {
                    val hh4dPageDoc = app.get(videoUrl, referer = data).document
                    val hh4dVideoId = hh4dPageDoc.body().attr("data-video")
                    
                    if (hh4dVideoId.isNotBlank()) {
                        val hh4dSourcesApiUrl = "https://hh4d.site/players/sources?videoId=$hh4dVideoId"
                        val hh4dApiResponse = app.post(hh4dSourcesApiUrl, referer = videoUrl).parsedSafe<Hh4dApiResponse>()

                        if (hh4dApiResponse?.status == true && hh4dApiResponse.message != null) {
                            val messageData = hh4dApiResponse.message
                            if (messageData.type == "direct" && messageData.source != null) {
                                Log.d(this.name, "Nguồn JSON từ hh4d.site: ${messageData.source}")
                                val videoSourcesList = tryParseJson<List<Hh4dVideoSource>>(messageData.source)
                                
                                videoSourcesList?.forEach { videoSource ->
                                    val streamUrl = videoSource.file ?: return@forEach
                                    val streamType = videoSource.type?.lowercase()
                                    val label = videoSource.label ?: Qualities.Unknown.name
                                    val quality = qualityFromLabel(label)

                                    Log.d(this.name, "Đã parse source từ hh4d.site: file=${streamUrl}, type=${streamType}, label=${label}")

                                    if (streamUrl.endsWith(".m3u8", ignoreCase = true) || streamType == "hls") {
                                        M3u8Helper.generateM3u8(
                                            name = "${this.name} - $serverName - $label",
                                            streamUrl = streamUrl,
                                            referer = videoUrl 
                                        ).forEach { link ->
                                            callback(
                                                ExtractorLink(
                                                    source = link.source, name = link.name, url = link.url,
                                                    referer = link.referer, quality = quality, 
                                                    headers = link.headers ?: emptyMap(), extractorData = link.extractorData,
                                                    type = ExtractorLinkType.M3U8
                                                )
                                            )
                                            foundStream = true
                                        }
                                    } 
                                    // Đã loại bỏ nhánh else if cho MP4 từ hh4d.site direct source
                                }
                            } else if (messageData.type == "embed" && messageData.source != null) {
                                val embedUrl = messageData.source
                                Log.d(this.name, "$serverName là embed: $embedUrl. Thử giải quyết...")
                                try {
                                    val embedRes = app.get(embedUrl, referer = videoUrl, allowRedirects = true)
                                    val finalEmbedUrl = embedRes.url
                                     if (finalEmbedUrl.endsWith(".m3u8", ignoreCase = true)) {
                                        M3u8Helper.generateM3u8(
                                            name = "${this.name} - $serverName (Embed Resolved)",
                                            streamUrl = finalEmbedUrl,
                                            referer = embedUrl
                                        ).forEach { link -> 
                                            callback(ExtractorLink(source = link.source, name = link.name, url = link.url, referer = link.referer, quality = link.quality, headers = link.headers ?: emptyMap(), extractorData = link.extractorData, type = ExtractorLinkType.M3U8))
                                            foundStream = true 
                                        }
                                    } 
                                    // Đã loại bỏ nhánh else if cho MP4 từ resolved embed
                                     else {
                                         Log.d(this.name, "Link embed $embedUrl không giải quyết thành M3U8 trực tiếp.")
                                    }
                                } catch (e: Exception) {
                                    Log.e(this.name, "Lỗi khi giải quyết link embed $embedUrl: $e")
                                }
                            }
                        } else {
                             Log.d(this.name, "API call tới hh4d.site thất bại hoặc status false. Chi tiết: ${hh4dApiResponse?.message?.type} - ${hh4dApiResponse?.message?.source}")
                        }
                    } else {
                        Log.d(this.name, "Không tìm thấy data-video ID trên trang: $videoUrl")
                    }
                } catch (e: Exception) {
                    Log.e(this.name, "Lỗi khi xử lý link $serverName ($videoUrl): $e")
                }
            } else if (!videoUrl.endsWith(".m3u8", ignoreCase = true)) {
                // Nếu không phải m3u8 trực tiếp và không phải VIP_2 đã xử lý ở trên
                // thì thử GET và kiểm tra xem có redirect sang m3u8 không
                 Log.d(this.name, "Đang thử giải quyết link gián tiếp cho $serverName (không phải VIP_2): $videoUrl")
                try {
                    val response = app.get(videoUrl, referer = data, allowRedirects = true)
                    val finalUrl = response.url
                    val contentType = response.headers["Content-Type"]?.lowercase() ?: ""
                     Log.d(this.name, "Link $serverName ($videoUrl) giải quyết thành: $finalUrl (Content-Type: $contentType)")

                    if (finalUrl.endsWith(".m3u8", ignoreCase = true) || contentType.contains("application/vnd.apple.mpegurl") || contentType.contains("application/x-mpegurl")) {
                        val hlsLinks = M3u8Helper.generateM3u8(
                            name = "${this.name} - $serverName (Resolved)",
                            streamUrl = finalUrl,
                            referer = videoUrl 
                        )
                        hlsLinks.forEach { link ->
                             callback(
                                ExtractorLink(
                                    source = link.source, name = link.name, url = link.url,
                                    referer = link.referer, quality = link.quality,
                                    headers = link.headers ?: emptyMap(), extractorData = link.extractorData,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                            foundStream = true
                        }
                    } else {
                        Log.d(this.name, "Server $serverName ($finalUrl) không phải M3U8 trực tiếp và cần extractor riêng hoặc logic khác.")
                    }
                } catch (e: Exception) {
                    Log.e(this.name, "Lỗi khi giải quyết link $serverName ($videoUrl): $e")
                }
            }
            if (foundStream) break 
        }
        return foundStream
    }

    private fun qualityFromLabel(label: String?): Int {
        return when {
            label == null -> Qualities.Unknown.value
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
