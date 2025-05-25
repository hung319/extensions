package com.lagradost.cloudstream3.plugins.hhninjaprovider // Thêm dòng này

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
// import com.lagradost.cloudstream3.network.CloudflareKiller // Bỏ comment nếu cần CloudflareKiller
// import com.lagradost.cloudstream3.syncproviders.AccountManager // Bỏ comment nếu cần AccountManager

class HHNinjaProvider : MainAPI() {
    override var mainUrl = "https://hhninja25.tv"
    override var name = "HHNinja25"
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
        "$mainUrl/phim-moi.html?p=" to "Phim Mới Cập Nhật",
        "$mainUrl/the-loai/phim-2d.html?p=" to "Phim 2D",
        "$mainUrl/the-loai/phim-3d.html?p=" to "Phim 3D",
        "$mainUrl/loc-phim/W1tdLFtdLFsxXSxbXV0=?p=" to "Phim Lẻ", // Phim Lẻ
        "$mainUrl/loc-phim/W1tdLFtdLFsyXSxbXV0=?p=" to "Phim Bộ"  // Phim Bộ
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
        // Đảm bảo href bắt đầu bằng scheme hoặc /
        var href = this.selectFirst("a")?.attr("href") ?: return null
        if (!href.startsWith("http") && !href.startsWith("/")) {
            href = "/$href" // Thêm / nếu cần thiết để fixUrl hoạt động đúng
        }


        val posterUrl = this.selectFirst("img")?.attr("src")
        val episodeStr = this.selectFirst("div.episode-latest span")?.text()?.trim()

        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = fixUrl(posterUrl ?: "")
            if (episodeStr != null) {
                 // Cố gắng trích xuất số tập. Ví dụ: "148/157" -> 148, "Tập 5" -> 5
                val epRegex = Regex("""(?:Tập\s*)?(\d+)(?:[/| ]\s*\d+\s*)?""")
                val matchResult = epRegex.find(episodeStr)
                val currentEpisode = matchResult?.groupValues?.get(1)?.toIntOrNull()

                addDubStatus(
                    dubExist = episodeStr.contains("Lồng tiếng", ignoreCase = true) || episodeStr.contains("Thuyết Minh", ignoreCase = true),
                    subExist = episodeStr.contains("Vietsub", ignoreCase = true) || true, // Giả định luôn có sub nếu không có thông tin rõ
                    ep = currentEpisode
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Trang web có vẻ sử dụng path parameter cho tìm kiếm thay vì query parameter
        // ví dụ: /tim-kiem/TENPHIM.html
        val searchUrl = "$mainUrl/tim-kiem/${query.replace(" ", "-")}.html" // Thay thế khoảng trắng bằng gạch nối nếu cần
        val document = app.get(searchUrl).document

        return document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.info-movie div.head div.first img")?.attr("src")
        val plot = document.selectFirst("div.desc div[style*='overflow:auto'] p")?.text()?.trim()
        
        val yearText = document.select("div.info-movie div.head div.last div.update_time > div:contains(Năm) + div")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        val statusText = document.select("div.info-movie div.head div.last div.status > div:contains(Trạng Thái) + div")?.text()?.trim()
        val showStatus = when {
            statusText?.contains("Đang Cập Nhật", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Hoàn Thành", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }


        val episodes = document.select("div.list_episode div.list-item-episode a").mapNotNull { el ->
            val epNameFull = el.selectFirst("span")?.text()?.trim() ?: el.text().trim()
            // Trích xuất chỉ số tập từ "Tập Xyz"
            val epNumRegex = Regex("""Tập\s*(\S+)""")
            val epNumMatch = epNumRegex.find(epNameFull)
            val epNumString = epNumMatch?.groupValues?.get(1) // Lấy phần sau "Tập "
            
            val epName = if (epNumString != null) "Tập $epNumString" else epNameFull

            val epHref = el.attr("href")
            Episode(
                data = fixUrl(epHref),
                name = epName,
                episode = epNumString?.replace(Regex("[^0-9]"), "")?.toIntOrNull() // Chỉ giữ lại số cho episode number
            )
        }.reversed()

        val tvType = if (episodes.size > 1 || episodes.any { it.name?.contains("Tập", ignoreCase = true) == true && it.name?.contains("Full", ignoreCase = true) == false}) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        val recommendations = document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResult()
        }
        
        // Trích xuất thể loại
        val genres = document.select("div.info-movie div.head div.last div.list_cate > div:contains(Thể Loại) + div a").mapNotNull { it.text() }


        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
                this.showStatus = showStatus
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, fixUrl(episodes.firstOrNull()?.data ?: "")) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String, // Đây là URL của trang xem phim (episode URL)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Vì bạn đã cung cấp loadlinks.html, tôi sẽ giả định data là URL dẫn đến trang đó.
        // Và dựa vào HTML đó, server video được lấy qua AJAX call.
        // Ví dụ: /server/ajax/player với MovieID và EpisodeID
        
        // Cần trích xuất MovieID và EpisodeID từ 'data' URL hoặc từ trang episode đó.
        // URL của trang xem phim thường có dạng: https://hhninja25.tv/xem-phim/ten-phim-episode-id-XXXXX.html
        // XXXXX là EpisodeID. MovieID có thể cần lấy từ trang load() hoặc từ trang xem phim.

        // Phân tích URL để lấy ID tập phim
        val episodeIdRegex = Regex("""episode-id-(\d+)""")
        val episodeIdMatch = episodeIdRegex.find(data)
        val episodeId = episodeIdMatch?.groupValues?.get(1) ?: run {
            // Log.d(name, "Không thể trích xuất EpisodeID từ: $data")
            return false
        }

        // Tải trang xem phim để có thể tìm MovieID nếu nó có trong script hoặc HTML
        val episodePageDocument = app.get(data).document
        
        // Tìm MovieID - có thể nằm trong script như var $info_play_video = { movie_id: ..., episode_id: ... }
        // Hoặc một cách khác là tìm trong nút báo lỗi
        var movieId: String? = null
        episodePageDocument.select("script").forEach { script ->
            val scriptText = script.html()
            if (scriptText.contains("\$info_play_video")) {
                val movieIdRegex = Regex("""MovieID:\s*(\d+)""") // Hoặc movie_id tùy theo JS
                movieId = movieIdRegex.find(scriptText)?.groupValues?.get(1)
                if (movieId != null) return@forEach
            }
        }
        
        // Nếu không tìm thấy trong script, thử tìm trong form báo lỗi
        if (movieId == null) {
            movieId = episodePageDocument.selectFirst("form#episode_error input[name=movie_id]")?.attr("value")
        }

        if (movieId == null) {
            // Log.d(name, "Không thể trích xuất MovieID từ trang: $data")
            return false
        }

        // Gọi AJAX để lấy thông tin server
        val serverAjaxUrl = "$mainUrl/server/ajax/player"
        val ajaxResponse = app.post(
            serverAjaxUrl,
            data = mapOf(
                "MovieID" to movieId,
                "EpisodeID" to episodeId
            ),
            referer = data, // Referer là trang xem phim
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<PlayerResponse>() // PlayerResponse là data class bạn cần định nghĩa

        if (ajaxResponse == null || ajaxResponse.code != 200) {
            // Log.d(name, "AJAX call đến $serverAjaxUrl thất bại hoặc trả về code không phải 200. Phản hồi: ${app.post(serverAjaxUrl, data = mapOf("MovieID" to movieId, "EpisodeID" to episodeId)).text}")
            return false
        }

        // Duyệt qua các server có thể có (ví dụ: fbk, vip_1, vip_2,...)
        // Ưu tiên server nào đó nếu muốn
        val serverSources = mutableListOf<Pair<String, String?>>()
        ajaxResponse.src_fbk?.let { serverSources.add("FBK" to it) }
        ajaxResponse.src_vip_1?.let { serverSources.add("VIP_1" to it) }
        ajaxResponse.src_vip_2?.let { serverSources.add("VIP_2" to it) }
        ajaxResponse.src_vip_3?.let { serverSources.add("VIP_3" to it) }
        ajaxResponse.src_hyd?.let { serverSources.add("HYD" to it) }
        // Thêm các server khác nếu có

        var foundStream = false
        for ((serverName, videoUrl) in serverSources) {
            if (videoUrl.isNullOrBlank()) continue

            // Log.d(name, "Đang thử server: $serverName với URL: $videoUrl")

            if (videoUrl.endsWith(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name = "$name - $serverName",
                    streamUrl = videoUrl,
                    referer = mainUrl // Hoặc data (trang xem phim) nếu cần
                ).forEach { link -> callback(link); foundStream = true }
            } else if (videoUrl.endsWith(".mp4")) {
                callback(
                    ExtractorLink(
                        name = "$name - $serverName",
                        name = "$name - $serverName MP4",
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value, // Hoặc cố gắng xác định quality
                        isM3u8 = false
                    )
                )
                foundStream = true
            } else {
                // Nếu là iframe hoặc link không trực tiếp, có thể cần extractor
                // Ví dụ: app.get(videoUrl).document để lấy link từ iframe
                // Hoặc sử dụng các extractor có sẵn nếu phù hợp
                // Log.d(name, "Server $serverName có URL không trực tiếp: $videoUrl. Cần logic phức tạp hơn hoặc extractor.")
                // Đây là nơi bạn có thể cần gọi extractor hoặc phân tích thêm
                // Dưới đây là một ví dụ rất cơ bản nếu đó là link player nổi tiếng
                 val directLinks = VideoExtractorManager.extractUrl(videoUrl, referer = mainUrl)
                 if (directLinks.isNotEmpty()) {
                     directLinks.forEach { link -> callback(link); foundStream = true }
                 } else {
                    // Log.d(name, "Không thể trích xuất link trực tiếp từ $videoUrl cho server $serverName")
                 }
            }
            if (foundStream) break // Nếu tìm thấy link từ một server thì dừng
        }
        
        return foundStream
    }

    // Data class để parse JSON response từ AJAX /server/ajax/player
    data class PlayerResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("message") val message: String?,
        @JsonProperty("src_fbk") val src_fbk: String?,
        @JsonProperty("src_vip_1") val src_vip_1: String?,
        @JsonProperty("src_vip_2") val src_vip_2: String?,
        @JsonProperty("src_vip_3") val src_vip_3: String?,
        @JsonProperty("src_hyd") val src_hyd: String?,
        // Thêm các src_vip_X khác nếu có
    )

}
