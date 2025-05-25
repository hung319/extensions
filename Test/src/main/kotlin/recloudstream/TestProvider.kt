package com.lagradost.cloudstream3.plugins.hhninjaprovider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLinkType
// import com.lagradost.cloudstream3.network.CloudflareKiller // Bỏ comment nếu cần CloudflareKiller

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
        @JsonProperty("src_vip_2") val srcVip2: String?, // Sẽ được bỏ qua
        @JsonProperty("src_vip_3") val srcVip3: String?, 
        @JsonProperty("src_hyd") val srcHyd: String?,   
        @JsonProperty("src_dlm") val srcDlm: String? = null,
        @JsonProperty("src_vip_4") val srcVip4: String? = null,
        @JsonProperty("src_vip_5") val srcVip5: String? = null,
        @JsonProperty("src_vip_6") val srcVip6: String? = null
    )

    // Data classes cho hh4d.site không còn cần thiết nữa
    // private data class Hh4dVideoSource(...)
    // private data class Hh4dMessage(...)
    // private data class Hh4dApiResponse(...)

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
        // Loại bỏ src_vip_2, src_vip_3, src_hyd khỏi danh sách xử lý chính
        val serverSources = listOfNotNull(
            initialAjaxResponse.srcFbk?.let { it to "FBK" },
            initialAjaxResponse.srcVip1?.let { it to "VIP_1" },
            // initialAjaxResponse.srcVip2 là của hh4d.site, đã loại bỏ
            initialAjaxResponse.srcDlm?.let { it to "DLM" },
            initialAjaxResponse.srcVip4?.let { it to "VIP_4" },
            initialAjaxResponse.srcVip5?.let { it to "VIP_5" },
            initialAjaxResponse.srcVip6?.let { it to "VIP_6" }
        ).filter { it.first.isNotBlank() }

        for ((videoUrl, serverName) in serverSources) {
            if (videoUrl.endsWith(".m3u8", ignoreCase = true)) {
                callback(
                    ExtractorLink(
                        source = "${this.name} - $serverName",
                        name = "${this.name} - $serverName M3U8",
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        headers = emptyMap(), 
                        extractorData = null, 
                        type = ExtractorLinkType.M3U8
                    )
                )
                foundStream = true
            } else { 
                // Nếu không phải M3U8 trực tiếp từ AJAX ban đầu, thử GET và kiểm tra
                // (Giả định rằng tất cả các link cuối cùng đều là M3U8)
                try {
                    val response = app.get(videoUrl, referer = data, allowRedirects = true)
                    val finalUrl = response.url
                    val contentType = response.headers["Content-Type"]?.lowercase() ?: ""

                    if (finalUrl.endsWith(".m3u8", ignoreCase = true) || contentType.contains("application/vnd.apple.mpegurl") || contentType.contains("application/x-mpegurl")) {
                         callback(
                            ExtractorLink(
                                source = "${this.name} - $serverName (Resolved)",
                                name = "${this.name} - $serverName M3U8",
                                url = finalUrl,
                                referer = videoUrl, // Link gốc trước khi redirect
                                quality = Qualities.Unknown.value,
                                headers = emptyMap(), 
                                extractorData = null,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        foundStream = true
                    }
                } catch (e: Exception) {
                    // Bỏ qua lỗi nếu không giải quyết được link
                }
            }
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
