package recloudstream

import android.net.Uri
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

class HHNinjaProvider : MainAPI() {
    override var mainUrl = "https://hhtq.me"
    override var name = "HHNinja"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    private var dynamicUrl: String? = null

    private suspend fun getBaseUrl(): String {
        if (dynamicUrl != null) return dynamicUrl!!
        return try {
            val response = app.get(mainUrl, allowRedirects = false)
            if (response.code == 301 || response.code == 302) {
                val location = response.headers["location"] ?: response.headers["Location"]
                val finalUrl = location?.trim()?.trimEnd('/') ?: mainUrl
                dynamicUrl = finalUrl
                finalUrl
            } else {
                dynamicUrl = mainUrl
                mainUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mainUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-cap-nhap.html?p=" to "Mới Cập Nhật",
        "$mainUrl/the-loai/phim-2d.html?p=" to "Phim 2D",
        "$mainUrl/the-loai/phim-3d.html?p=" to "Phim 3D",
        "$mainUrl/loc-phim/W1tdLFtdLFsxXSxbXV0=?p=" to "Phim Lẻ",
        "$mainUrl/loc-phim/W1tdLFtdLFsyXSxbXV0=?p=" to "Phim Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
            }
        }
        val currentDomain = getBaseUrl()
        val url = (request.data.replace(mainUrl, currentDomain)) + page
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

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Cartoon) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
            if (episodeStr != null) {
                val epRegex = Regex("""(?:Tập\s*)?(\d+)(?:(?:[/| ]\s*\d+\s*)?|$)""")
                val matchResult = epRegex.find(episodeStr)
                val currentEpisode = matchResult?.groupValues?.get(1)?.toIntOrNull()
                val isDub = episodeStr.contains("Lồng tiếng", ignoreCase = true) || episodeStr.contains("Thuyết Minh", ignoreCase = true)
                val isSub = episodeStr.contains("Vietsub", ignoreCase = true) || !isDub
                addDubStatus(isDub, isSub, if (isDub) currentEpisode else null, if (isSub) currentEpisode else null)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val currentDomain = getBaseUrl()
        val searchUrl = "$currentDomain/tim-kiem/${query.replace(" ", "-")}.html"
        val document = app.get(searchUrl).document
        return document.select("div.movies-list div.movie-item").mapNotNull { it.toSearchResult() }
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
        val recommendations = document.select("div.list_episode_relate div.movies-list div.movie-item").mapNotNull { it.toSearchResult() }
        val genres = document.select("div.info-movie div.head div.last div.list_cate > div:contains(Thể Loại) + div a").mapNotNull { it.text() }
        
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
                newEpisode(fixUrl(epHref)) {
                    this.name = epName
                    this.episode = epNumString?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                }
            }
        }.reversed()

        val structuralTypeIsSeries = episodes.size > 1 || episodes.any { it.name?.contains("Tập", ignoreCase = true) == true && it.name?.contains("Full", ignoreCase = true) == false }

        return if (structuralTypeIsSeries) {
            newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
                this.showStatus = showStatus
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Cartoon, fixUrl(episodes.firstOrNull()?.data ?: "")) {
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
        @JsonProperty("src_vip") val srcVip: String?,
        @JsonProperty("src_ssp") val srcSsp: String?,
        @JsonProperty("src_dlm") val srcDlm: String?,
        @JsonProperty("src_hyd") val srcHyd: String?,
        @JsonProperty("src_vip_6") val srcVip6: String?
    )

    private data class TokenResponse(
        @JsonProperty("token") val token: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val currentDomain = getBaseUrl()
        Log.d("HHNinja", "Loading links for: $data")

        // 1. Tải trang chính
        val pageResponse = app.get(data, referer = data)
        val document = pageResponse.document
        
        // --- QUAN TRỌNG: Lấy Cookies từ Response (bao gồm PHPSESSID) ---
        // Sử dụng cookies từ response này để đảm bảo đồng bộ với CSRF
        val currentCookies = pageResponse.cookies.toMutableMap()
        
        val csrfToken = document.select("meta[name=csrf-token]").attr("content")
        Log.d("HHNinja", "CSRF Token: $csrfToken")

        // 2. Gọi API để lấy Token
        try {
            val tokenUrl = "$currentDomain/server/token"
            // Dùng cookies vừa lấy được để gọi token (để server biết là cùng 1 session)
            val tokenResponse = app.get(
                tokenUrl,
                headers = mapOf(
                    "Referer" to data,
                    "X-Requested-With" to "XMLHttpRequest",
                    "x-csrf-token" to csrfToken,
                    "Accept" to "application/json, text/plain, */*"
                ),
                cookies = currentCookies 
            ).parsedSafe<TokenResponse>()

            val token = tokenResponse?.token
            if (token != null) {
                Log.d("HHNinja", "Got Token: $token")
                
                // Encode TokenTime: {"abc":"abc"} -> %7B%22abc...
                val rawJson = "{\"$token\":\"$token\"}"
                val encodedToken = URLEncoder.encode(rawJson, "UTF-8")
                
                // Thêm TokenTime vào danh sách cookie hiện tại
                currentCookies["TokenTime"] = encodedToken
            } else {
                Log.d("HHNinja", "Failed to get token from $tokenUrl")
            }
        } catch (e: Exception) {
            Log.e("HHNinja", "Error fetching token", e)
        }

        val episodeIdRegex = Regex("""episode-id-(\d+)""")
        val episodeId = episodeIdRegex.find(data)?.groupValues?.get(1)
            ?: document.selectFirst("input[name=Episode_id]")?.attr("value")
            ?: return false

        var movieId = document.selectFirst("form#episode_error input[name=movie_id]")?.attr("value")
        if (movieId == null) {
            movieId = document.select("script").mapNotNull { script ->
                val scriptText = script.html()
                Regex("""(?:MovieID|movie_id):\s*(\d+)""").find(scriptText)?.groupValues?.get(1)
            }.firstOrNull()
        }
        if (movieId == null) {
            Log.d("HHNinja", "MovieID not found")
            return false
        }

        // --- 3. Xây dựng Header Cookie thủ công ---
        // Biến map currentCookies (đã có PHPSESSID + TokenTime) thành chuỗi "key=value; key=value"
        val cookieHeader = StringBuilder()
        for ((k, v) in currentCookies) {
            if (cookieHeader.isNotEmpty()) cookieHeader.append("; ")
            cookieHeader.append("$k=$v")
        }
        
        val ajaxUrl = "$currentDomain/server/ajax/player"
        Log.d("HHNinja", "Posting to: $ajaxUrl with Cookie Header: $cookieHeader")

        // 4. Request POST với Header Cookie thủ công (Bỏ tham số 'cookies')
        val response = app.post(
            ajaxUrl,
            data = mapOf("MovieID" to movieId, "EpisodeID" to episodeId),
            referer = data,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-TOKEN" to csrfToken,
                "x-csrf-token" to csrfToken,
                "Origin" to currentDomain,
                "Accept" to "*/*",
                "Cookie" to cookieHeader.toString() // Gửi tất cả cookie ở đây
            )
        )

        Log.d("HHNinja", "Ajax Response HTTP Code: ${response.code}")
        
        val initialAjaxResponse = response.parsedSafe<InitialPlayerResponse>()

        if (initialAjaxResponse?.code != 200) {
            Log.d("HHNinja", "Server Error. Code: ${initialAjaxResponse?.code}, Message: ${initialAjaxResponse?.message}")
            // Fallback: Nếu vẫn lỗi 401, thử in ra body để xem chi tiết
            if (response.code == 401) {
                 Log.d("HHNinja", "401 Body: ${response.text}")
            }
            return false
        }

        val serverSources = mutableListOf<Pair<String, String>>()
        initialAjaxResponse.srcVip?.takeIf { it.isNotBlank() }?.let { serverSources.add(it to "VIP (Vidmmo)") }
        initialAjaxResponse.srcVip6?.takeIf { it.isNotBlank() }?.let { serverSources.add(it to "VIP 6 (Vevocloud)") }
        initialAjaxResponse.srcSsp?.takeIf { it.isNotBlank() }?.let { serverSources.add(it to "SSP") }
        initialAjaxResponse.srcDlm?.takeIf { it.isNotBlank() }?.let { serverSources.add(it to "DLM") }
        initialAjaxResponse.srcHyd?.takeIf { it.isNotBlank() }?.let { serverSources.add(it to "HYD") }

        Log.d("HHNinja", "Found sources: ${serverSources.size}")

        var foundStream = false

        for ((url, name) in serverSources) {
            Log.d("HHNinja", "Processing source: $name - $url")
            
            // --- 1. XỬ LÝ VIDMMO ---
            if (url.contains("vidmmo.com") || name.contains("Vidmmo", true)) {
                try {
                    val vidmmoResponse = app.get(url, referer = currentDomain).text
                    val hlsMatch = Regex("""hlsUrl\s*:\s*"(.*?)"""").find(vidmmoResponse)
                    val hlsRaw = hlsMatch?.groupValues?.get(1)

                    if (hlsRaw != null) {
                        var masterUrl = hlsRaw.replace("\\/", "/")
                        if (masterUrl.startsWith("/")) {
                            masterUrl = "https://vidmmo.com$masterUrl"
                        }
                        val link = newExtractorLink(
                            source = "${this.name} - $name",
                            name = "${this.name} - $name",
                            url = masterUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                        callback(link)
                        foundStream = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // --- 2. XỬ LÝ VEVOCLOUD ---
            else if (url.contains("vevocloud.com")) {
                try {
                    val vevoResponse = app.get(url, referer = currentDomain).text
                    val sourceMatch = Regex("""\"sourceUrl\"\s*:\s*\"(https:[^\"]+)\"""").find(vevoResponse)
                    val sourceUrl = sourceMatch?.groupValues?.get(1)

                    if (sourceUrl != null) {
                        val masterUrl = sourceUrl.replace("\\/", "/")
                        val link = newExtractorLink(
                            source = "${this.name} - $name",
                            name = "${this.name} - $name",
                            url = masterUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.headers = mapOf("Origin" to "https://vevocloud.com")
                            this.quality = Qualities.Unknown.value
                        }
                        callback(link)
                        foundStream = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // --- 3. XỬ LÝ DLM (Dailymotion) ---
            else if (url.contains("hh4d.site") && url.contains("dlm.php")) {
                try {
                    val dlmId = Uri.parse(url).getQueryParameter("url")
                    if (!dlmId.isNullOrBlank()) {
                        val dailymotionUrl = "https://www.dailymotion.com/video/$dlmId"
                        loadExtractor(dailymotionUrl, data, subtitleCallback) { link ->
                            callback(link)
                            foundStream = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // --- 4. CÁC SERVER KHÁC (SSP, HYD...) ---
            else if (url.startsWith("http")) {
                loadExtractor(url, data, subtitleCallback) { link ->
                    callback(link)
                    foundStream = true
                }
            }
        }
        return foundStream
    }
}
