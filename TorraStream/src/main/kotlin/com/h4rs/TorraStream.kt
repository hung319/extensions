package com.h4rs

import android.content.SharedPreferences
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale

class TorraStream(private val sharedPref: SharedPreferences) : TmdbProvider() {

    private fun metadataLanguage(): String {
        return sharedPref.getString("metadata_language", "en-US")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "en-US"
    }
    override var name = "TorraStream"
    override var mainUrl = "https://torrentio.strem.fun"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Torrent)
    override var lang = "en"
    override val supportedSyncNames = setOf(SyncIdName.Trakt)
    override val hasMainPage = true
    override val hasQuickSearch = false

    companion object {
        const val OnethreethreesevenxAPI = "https://proxy.phisher2.workers.dev/?url=https://1337x.to"
        const val MediafusionApi = "https://mediafusion.elfhosted.com/D-_ru4-xVDOkpYNgdQZ-gA6whxWtMNeLLsnAyhb82mkks4eJf4QTlrAksSeBnwFAbIGWQLaokCGFxxsHupxSVxZO8xhhB2UYnyc5nnLeDnIqiLajtkmaGJMB_ZHqMqSYIU2wcGhrw0s4hlXeRAfnnbDywHCW8DLF_ZZfOXYUGPzWS-91cvu7kA2xPs0lJtcqZO"
        const val ThePirateBayApi = "https://thepiratebay-plus.strem.fun"
        const val AIOStreams = "https://aiostreams.elfhosted.com/E2-xLzptGhmwLnA9L%2FOUHyZJg%3D%3D-Io2cJBStOrbqlmGwGz2ZwBbMGBj5enyJFgN5XcslkuiUS5KSjJrv90yd4HHLj1fyq6hJm7QpnCxDiPqbeOwdGA2yySllUQh2T%2B5qPqgtPt2sWBN5zdeetbiFFLHvVqq0PZOhKGM7pv2LzCoMLAk%2BSo86mcrzWIeszmvHuRMoKX3zBO6hUDvH6oqK2hFfbUF7ZONMdm9jE7lHp0LuXKPzHSwKUvDZroJ9iRgBkvHIGjJL65oBv2PxfQK%2Fu4gYEuLVhH3dQ7Xu6i1AshdxycCPRQOO2LcDDZkBC84zLXoy3DDPkvDkWBv2icVZIs2dnQlwvtfu7fFiXaGxWJxtYvbBALIhey8SaaeCKts8xMEyuJvSZiKBbkiTblb0NbqfRyGoJz5rJkiCPzlnX6S%2BpNHKNXVYRj2QZmmvN47fdteAZfhvCuNRW1XBP%2FhTr5ufzCQ9tC8ao%2F4ZhoVXPje45mgPpeJy%2FqYGkX36%2BDgjUMGM1SIvm416pHFL1fVG9MQlIdTn2T4VaUHA0dZHXxznaSQDB%2F1GIkDCHOp2iWUl8zceINOE08AI%2BUwmWCnVXsvsXYaTbFnsE%2F0n1zQwN19ULRCnO4AN2KKLfWKHCz9q5YwQG6y9r%2BXTkjtAXoju764x1f2UlFZT8aavjX1oAcPiTC5vA%3D%3D"
        const val PeerflixApi = "https://peerflix.mov"
        const val CometAPI = "https://comet.elfhosted.com"
        const val SubtitlesAPI = "https://opensubtitles-v3.strem.io"
        const val AnimetoshoAPI = "https://feed.animetosho.org"
        const val TorrentioAnimeAPI = "https://torrentio.strem.fun/providers=nyaasi,tokyotosho,anidex%7Csort=seeders"
        const val TorboxAPI= "https://stremio.torbox.app"
        val TRACKER_LIST_URL = listOf(
            "https://raw.githubusercontent.com/ngosang/trackerslist/refs/heads/master/trackers_best.txt",
            "https://raw.githubusercontent.com/ngosang/trackerslist/refs/heads/master/trackers_best_ip.txt",
        )
        private const val Uindex = "https://uindex.org"
        private const val Knaben = "https://knaben.org"
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "1865f43a0549ca50d341dd9ab8b29f49"

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?api_key=$apiKey&region=US&language=${metadataLanguage()}" to "Trending",
        "$tmdbAPI/trending/movie/week?api_key=$apiKey&region=US&with_original_language=en&language=${metadataLanguage()}" to "Popular Movies",
        "$tmdbAPI/trending/tv/week?api_key=$apiKey&region=US&with_original_language=en&language=${metadataLanguage()}" to "Popular TV Shows",
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&region=US&with_original_language=en&language=${metadataLanguage()}" to "Airing Today TV Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=213&language=${metadataLanguage()}" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=1024&language=${metadataLanguage()}" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2739&language=${metadataLanguage()}" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=453&language=${metadataLanguage()}" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2552&language=${metadataLanguage()}" to "Apple TV+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=49&language=${metadataLanguage()}" to "HBO",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=4330&language=${metadataLanguage()}" to "Paramount+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=3353&language=${metadataLanguage()}" to "Peacock",
        "$tmdbAPI/discover/movie?api_key=$apiKey&language=${metadataLanguage()}&page=1&sort_by=popularity.desc&with_origin_country=IN&release_date.gte=${getDate().lastWeekStart}&release_date.lte=${getDate().today}" to "Trending Indian Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&sort_by=popularity.desc&air_date.lte=${getDate().today}&air_date.gte=${getDate().today}&language=${metadataLanguage()}" to "Airing Today Anime",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&sort_by=popularity.desc&air_date.lte=${getDate().nextWeek}&air_date.gte=${getDate().today}&language=${metadataLanguage()}" to "On The Air Anime",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_keywords=210024|222243&language=${metadataLanguage()}" to "Anime Movies",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=US&language=${metadataLanguage()}" to "Top Rated Movies",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=US&language=${metadataLanguage()}" to "Top Rated TV Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&language=${metadataLanguage()}" to "Korean Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=99&language=${metadataLanguage()}" to "Documentary",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val adultQuery =
            if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669|190370"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get("${request.data}$adultQuery&page=$page")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score= Score.from10(voteAverage)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query,1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return app.get(
            "$tmdbAPI/search/multi?api_key=$apiKey&language=${metadataLanguage()}&query=$query&page=$page&include_adult=${settingsForProvider.enableAdult}"
        ).parsedSafe<Results>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        }?.toNewSearchResponseList()
    }


    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = getType(data.type)
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&language=${metadataLanguage()}&append_to_response=keywords,credits,external_ids,videos,recommendations"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&language=${metadataLanguage()}&append_to_response=keywords,credits,external_ids,videos,recommendations"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val isAnime =
            genres?.contains("Animation") == true && (res.original_language == "zh" || res.original_language == "ja")
        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null,
                    getImageUrl(cast.profilePath)
                ), roleString = cast.character
            )
        } ?: return null
        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer =
            res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }?.randomOrNull()

        return if (type == TvType.TvSeries) {
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=${metadataLanguage()}")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        newEpisode(LoadData(
                            res.title,
                            year,
                            isAnime,
                            res.external_ids?.imdb_id,
                            eps.seasonNumber,
                            eps.episodeNumber
                        ).toJson())
                        {
                            this.name = eps.name + if (isUpcoming(eps.airDate)) " • [UPCOMING]" else ""
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath)
                            this.score = Score.from10(eps.voteAverage)
                            this.description = eps.overview
                            this.addDate(eps.airDate)
                        }
                    }
            }?.flatten() ?: listOf()

            newTvSeriesLoadResponse(
                title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags =  keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(res.vote_average.toString())
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                this.episodes = episodes
                //this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addImdbId(res.external_ids?.imdb_id)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadData(res.title,year,isAnime,res.external_ids?.imdb_id).toJson()
            ) {
                this.posterUrl = poster
                this.comingSoon = isUpcoming(releaseDate)
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(res.vote_average.toString())
                this.recommendations = recommendations
                this.actors = actors
                //this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addImdbId(res.external_ids?.imdb_id)
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val provider = sharedPref.getString("debrid_provider", null)
        val key = sharedPref.getString("debrid_key", null)
        val dataObj = parseJson<LoadData>(data)
        val isAnime = dataObj.isAnime
        val title = dataObj.title
        val season = dataObj.season
        var episode = dataObj.episode
        val id = dataObj.imdbId
        val year = dataObj.year
        val anijson = app.get("https://api.ani.zip/mappings?imdb_id=$id").toString()
        val mappings = runCatching {
            val response = app.get("https://api.ani.zip/mappings?imdb_id=$id")
            JSONObject(response.text).optJSONObject("mappings")
        }.getOrNull()
        val kitsuId = runCatching {
            val response = app.get("https://api.ani.zip/mappings?imdb_id=$id")
            val json = JSONObject(response.text)
            json.optJSONObject("mappings")?.optInt("kitsu_id")
        }.getOrNull()

        val isMovie = mappings
            ?.optString("type", "")
            ?.contains("MOVIE", ignoreCase = true) == true

        episode = if (isMovie) 1 else episode
        val anidbEid = getAnidbEid(anijson, episode) ?: 0

        suspend fun runAllAsync(vararg tasks: suspend () -> Unit) {
            coroutineScope {
                tasks.map { async { it() } }.awaitAll()
            }
        }

        val apiUrl = buildApiUrl(sharedPref, mainUrl)

        val hasKey = !key.isNullOrEmpty()
        val isTorrServer = provider == "TorrServer"
        val torrServerBaseUrl = if (isTorrServer) normalizeTorrServerBaseUrl(key) else null
        val torrServerCategory = if (season == null) "movie" else "tv"
        val linkCallback: (ExtractorLink) -> Unit = if (isTorrServer && torrServerBaseUrl != null) {
            { link -> callback(toTorrServerLink(link, torrServerBaseUrl, title, torrServerCategory)) }
        } else {
            callback
        }

        if (provider == "AIO Streams" && hasKey) {
            runAllAsync(
                { invokeAIOStreamsDebian(key, id, season, episode, callback) }
            )
        }

        if (provider == "TorBox" && hasKey) {
            val torboxUrl = buildApiUrl(sharedPref, TorboxAPI)
            runAllAsync(
                { invokeDebianTorbox(torboxUrl, key, id, season, episode, callback) }
            )
        }

        if (hasKey && !isTorrServer) {
            runAllAsync(
                { invokeTorrentioDebian(apiUrl, id, season, episode, callback) }
            )
        } else {
            runAllAsync(
                { if (!dataObj.isAnime) invokeTorrentio(apiUrl, id, season, episode, linkCallback) },
                { if (!dataObj.isAnime) invokeThepiratebay(ThePirateBayApi, id, season, episode, linkCallback) },
                { if (dataObj.isAnime) invokeAnimetosho(anidbEid, linkCallback) },
                { if (dataObj.isAnime) invokeTorrentioAnime(TorrentioAnimeAPI, kitsuId, season, episode, linkCallback) },
                { if (!dataObj.isAnime) invokeUindex(Uindex, title, year, season, episode, linkCallback) },
                { invokeKnaben(Knaben, isAnime, title, year, season, episode, linkCallback) },
                { invokeSubtitleAPI(id, season, episode, subtitleCallback) }
            )
        }


        // Subtitles
        val subApiUrl = "https://opensubtitles-v3.strem.io"
        val url = if (season == null) "$subApiUrl/subtitles/movie/$id.json"
        else "$subApiUrl/subtitles/series/$id:$season:$episode.json"

        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        )

        app.get(url, headers = headers, timeout = 100L)
            .parsedSafe<Subtitles>()?.subtitles?.amap {
                val lan = getLanguage(it.lang) ?: it.lang
                subtitleCallback(
                    newSubtitleFile(
                        lan,
                        it.url
                    )
                )
            }

        return true
    }


    private fun getStatus(t: String?): ShowStatus {
        return when (t) {
            "returning series" -> ShowStatus.Ongoing
            "continuing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }


    private fun isUpcoming(dateString: String?): Boolean {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
            unixTimeMS < dateTime
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }

    private fun fixPath(url: String?): String? {
        url ?: return null
        return "https://$url"
    }
    private fun buildApiUrl(sharedPref: SharedPreferences, apiBase: String): String {
        val sort = sharedPref.getString("sort", "qualitysize")
        val languageOption = sharedPref.getString("language", "")
        val qualityFilter = sharedPref.getString("qualityfilter", "")
        val limit = sharedPref.getString("limit", "")
        val sizeFilter = sharedPref.getString("sizefilter", "")
        val linkLimit = sharedPref.getString("link_limit", "") // max links to load
        val debridProvider = sharedPref.getString("debrid_provider", "") // e.g., "easydebrid"
        val debridKey = sharedPref.getString("debrid_key", "") // e.g., "12345abc"
        
        val params = mutableListOf<String>()
        if (!sort.isNullOrEmpty()) params += "sort=$sort"
        if (!languageOption.isNullOrEmpty()) params += "language=${languageOption.lowercase()}"
        if (!qualityFilter.isNullOrEmpty()) params += "qualityfilter=$qualityFilter"
        if (!limit.isNullOrEmpty()) params += "limit=$limit"
        if (!sizeFilter.isNullOrEmpty()) params += "sizefilter=$sizeFilter"
        if (!linkLimit.isNullOrEmpty()) params += "link_limit=$linkLimit"
        
        if (!debridProvider.isNullOrEmpty() && !debridKey.isNullOrEmpty() && debridProvider != "TorrServer") {
            params += "$debridProvider=$debridKey"
        }
        
        val query = params.joinToString("%7C")
        return "$apiBase/$query"
    }

    private fun normalizeTorrServerBaseUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        val base = if (trimmed.isBlank()) {
            "http://127.0.0.1:8090"
        } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return base.trimEnd('/')
    }

    private fun isTorrentLink(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("magnet:?", ignoreCase = true) || url.endsWith(".torrent", ignoreCase = true)
    }

    private fun buildTorrServerStreamUrl(
        baseUrl: String,
        link: String,
        title: String?,
        category: String?,
    ): String {
        val params = mutableListOf(
            "link=${URLEncoder.encode(link, StandardCharsets.UTF_8.name())}",
            "play=1",
            "save=1",
        )
        if (!title.isNullOrBlank()) {
            params += "title=${URLEncoder.encode(title, StandardCharsets.UTF_8.name())}"
        }
        if (!category.isNullOrBlank()) {
            params += "category=${URLEncoder.encode(category, StandardCharsets.UTF_8.name())}"
        }
        return "${baseUrl.trimEnd('/')}/stream?${params.joinToString("&")}"
    }

    private fun buildTorrServerPlayUrl(baseUrl: String, hash: String, fileId: Int): String {
        return "${baseUrl.trimEnd('/')}/play/$hash/$fileId"
    }

    private fun pickTorrServerFileId(files: List<TorrServerFileStat>?): Int? {
        val videoExtensions = setOf("mkv", "mp4", "avi", "webm", "mov", "flv", "ts", "m4v")
        val candidates = files.orEmpty().filter { file ->
            val path = file.path?.lowercase().orEmpty()
            val ext = path.substringAfterLast('.', "")
            file.id != null && ext in videoExtensions
        }
        val best = candidates.maxByOrNull { it.length ?: 0L } ?: files?.firstOrNull { it.id != null }
        return best?.id
    }

    private suspend fun addTorrentToTorrServer(
        baseUrl: String,
        link: String,
        title: String?,
        category: String?,
    ): TorrServerTorrentStatus? {
        val url = "${baseUrl.trimEnd('/')}/torrents"
        val headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json")
        val request = TorrServerTorrentsRequest(
            action = "add",
            link = link,
            title = title,
            category = category,
            save_to_db = true
        )
        return app.post(
            url,
            headers = headers,
            requestBody = request.toJson().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        ).parsedSafe<TorrServerTorrentStatus>()
    }

    private fun toTorrServerLink(
        link: ExtractorLink,
        baseUrl: String,
        title: String?,
        category: String?,
    ): ExtractorLink {
        if (!isTorrentLink(link.url)) return link

        val streamUrl = runBlocking {
            val status = addTorrentToTorrServer(baseUrl, link.url, title, category)
            val hash = status?.hash
            val fileId = pickTorrServerFileId(status?.file_stats)
            if (!hash.isNullOrBlank() && fileId != null) {
                buildTorrServerPlayUrl(baseUrl, hash, fileId)
            } else {
                buildTorrServerStreamUrl(baseUrl, link.url, title, category)
            }
        }
        return runBlocking {
            newExtractorLink(
                "TorrServer",
                link.name,
                streamUrl,
                ExtractorLinkType.VIDEO
            ).apply {
                this.quality = link.quality
            }
        }
    }

    private data class TorrServerTorrentsRequest(
        val action: String,
        val link: String? = null,
        val title: String? = null,
        val category: String? = null,
        val save_to_db: Boolean? = null,
    )

    private data class TorrServerTorrentStatus(
        val hash: String? = null,
        val file_stats: List<TorrServerFileStat>? = null,
    )

    private data class TorrServerFileStat(
        val id: Int? = null,
        val length: Long? = null,
        val path: String? = null,
    )
}



suspend fun generateMagnetLink(
    trackerUrls: List<String>,
    hash: String?,
): String {
    require(hash?.isNotBlank() == true)

    val trackers = mutableSetOf<String>()

    trackerUrls.forEach { url ->
        try {
            val response = app.get(url)
            response.text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { trackers.add(it) }
        } catch (_: Exception) {
            // ignore bad sources
        }
    }

    return buildString {
        append("magnet:?xt=urn:btih:").append(hash)

        if (hash.isNotBlank()) {
            append("&dn=")
            append(URLEncoder.encode(hash, StandardCharsets.UTF_8.name()))
        }

        trackers
            .take(10) // practical limit
            .forEach { tracker ->
                append("&tr=")
                append(URLEncoder.encode(tracker, StandardCharsets.UTF_8.name()))
            }
    }
}
