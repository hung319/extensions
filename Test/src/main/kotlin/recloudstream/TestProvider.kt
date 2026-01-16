package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class AnimexProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    private val anilistApi = "https://graphql.anilist.co"
    
    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // --- Helpers ---
    private fun getAnimeIdFromUrl(url: String): String {
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        return if (cleanUrl.contains("-episode-")) {
            cleanUrl.substringBefore("-episode-").substringAfterLast("-")
        } else {
            cleanUrl.substringAfterLast("-")
        }
    }

    // --- GraphQL Query ---
    private val queryMedia = """
        query (${"$"}page: Int = 1, ${"$"}perPage: Int = 20, ${"$"}type: MediaType = ANIME, ${"$"}search: String, ${"$"}sort: [MediaSort]) {
          Page(page: ${"$"}page, perPage: ${"$"}perPage) {
            media(type: ${"$"}type, sort: ${"$"}sort, search: ${"$"}search) {
              id
              title { english romaji }
              coverImage { extraLarge large medium }
              bannerImage
            }
          }
        }
    """.trimIndent()

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending",
        "POPULARITY_DESC" to "Popular",
        "FAVOURITES_DESC" to "Top Rated",
        "UPDATED_AT_DESC" to "Just Updated"
    )

    // --- Main Functions ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val variables = mapOf("page" to page, "perPage" to 20, "type" to "ANIME", "sort" to listOf(request.data))
        return fetchAnilist(GraphQlQuery(queryMedia, variables), request.name)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val variables = mapOf("page" to 1, "perPage" to 20, "type" to "ANIME", "search" to query)
        val response = app.post(anilistApi, json = GraphQlQuery(queryMedia, variables), headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")).parsedSafe<AnilistResponse>()
        return response?.data?.page?.media?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private suspend fun fetchAnilist(body: GraphQlQuery, name: String): HomePageResponse {
        return try {
            val response = app.post(anilistApi, json = body, headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json", "Origin" to mainUrl)).parsedSafe<AnilistResponse>()
            val list = response?.data?.page?.media?.mapNotNull { it.toSearchResponse() } ?: emptyList()
            newHomePageResponse(name, list)
        } catch (e: Exception) {
            newHomePageResponse(name, emptyList())
        }
    }

    private fun AnilistMedia.toSearchResponse(): SearchResponse {
        val titleEn = this.title?.english ?: this.title?.romaji ?: "Unknown"
        val slug = titleEn.replace(Regex("[^a-zA-Z0-9]"), "-").replace(Regex("-+"), "-").lowercase().trim('-')
        val image = this.coverImage?.extraLarge ?: this.coverImage?.large ?: this.coverImage?.medium ?: ""
        return newAnimeSearchResponse(titleEn, "$mainUrl/anime/$slug-${this.id}", TvType.Anime) { this.posterUrl = image }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: "" 
        val bg = document.selectFirst(".absolute.inset-0.bg-cover")?.attr("style")?.substringAfter("url('")?.substringBefore("')") ?: ""

        val recommendations = document.select("a[href^='/anime/']").mapNotNull { element ->
            val href = element.attr("href")
            val img = element.selectFirst("img")?.attr("src") ?: ""
            val name = element.selectFirst("span.font-medium")?.text() ?: element.selectFirst(".text-sm")?.text()
            if (!name.isNullOrBlank() && img.isNotBlank()) {
                newAnimeSearchResponse(name, fixUrl(href), TvType.Anime) { this.posterUrl = img }
            } else null
        }.distinctBy { it.url }

        val episodes = mutableListOf<Episode>()
        val animeId = getAnimeIdFromUrl(url)

        if (animeId.all { it.isDigit() }) {
            try {
                // SỬA ĐỔI: Dùng ID trần thay vì payload mã hóa
                val apiUrl = "$mainUrl/api/anime/episodes/$animeId"
                
                val apiHeaders = mapOf(
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url, // Header Referer rất quan trọng
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                val responseText = app.get(apiUrl, headers = apiHeaders).text

                if (!responseText.contains("\"error\"") && responseText.trim().startsWith("[")) {
                    val apiResponse: List<AnimexEpData> = mapper.readValue(responseText, object : TypeReference<List<AnimexEpData>>() {})
                    
                    apiResponse.forEach { epData ->
                        val epNum = epData.number?.toInt() ?: 0
                        val titleEn = epData.titles?.en
                        val epName = if (!titleEn.isNullOrBlank()) "Episode $epNum - $titleEn" else "Episode $epNum"
                        val epImage = if (!epData.img.isNullOrBlank()) epData.img else poster

                        val episodeData = mapOf(
                            "id" to animeId,
                            "epNum" to epNum,
                            "subs" to (epData.subProviders ?: emptyList()),
                            "dubs" to (epData.dubProviders ?: emptyList())
                        )
                        val episodeDataJson = mapper.writeValueAsString(episodeData)

                        episodes.add(newEpisode(episodeDataJson) {
                            this.name = epName
                            this.episode = epNum
                            this.posterUrl = epImage
                            this.description = epData.description
                        })
                    }
                }
            } catch (e: Exception) { 
                e.printStackTrace()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = description
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            var animeId: Int? = null
            var epNum: Int? = null
            var subProviders = listOf<String>()
            var dubProviders = listOf<String>()

            if (data.trim().startsWith("{")) {
                try {
                    val epData = mapper.readValue(data, Map::class.java)
                    animeId = epData["id"]?.toString()?.toIntOrNull()
                    epNum = epData["epNum"]?.toString()?.toIntOrNull()
                    subProviders = (epData["subs"] as? List<*>)?.map { it.toString() } ?: emptyList()
                    dubProviders = (epData["dubs"] as? List<*>)?.map { it.toString() } ?: emptyList()
                } catch (e: Exception) { e.printStackTrace() }
            } else {
                val cleanUrl = data.substringBefore("?")
                animeId = cleanUrl.substringBefore("-episode-").substringAfterLast("-").toIntOrNull()
                val epRegex = Regex("episode-(\\d+)")
                epNum = epRegex.find(cleanUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                subProviders = listOf("pahe", "dih", "neko")
            }

            if (animeId == null || epNum == null) return false

            suspend fun fetchSource(host: String, type: String) {
                try {
                    // Logic source vẫn cần mã hóa
                    val payloadMap = mapOf(
                        "id" to animeId,
                        "host" to host,
                        "epNum" to epNum,
                        "type" to type,
                        "cache" to "true",
                        "timestamp" to System.currentTimeMillis()
                    )
                    
                    val encryptedId = AnimexCrypto.encrypt(mapper.writeValueAsString(payloadMap))
                    if (encryptedId.isEmpty()) return

                    val apiHeaders = mapOf(
                        "Accept" to "*/*",
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )

                    val apiResponseText = app.get(
                        "$mainUrl/api/anime/sources/$encryptedId",
                        headers = apiHeaders
                    ).text
                    
                    if (apiResponseText.contains("\"error\"")) return

                    val sourceData = mapper.readValue(apiResponseText, AnimexSources::class.java)

                    sourceData.subtitles?.forEach { sub ->
                        val url = sub.url ?: return@forEach
                        subtitleCallback.invoke(SubtitleFile(sub.label ?: sub.lang ?: "Unknown", url))
                    }

                    sourceData.sources?.forEach { source ->
                        val link = source.url ?: return@forEach
                        val linkType = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        
                        callback.invoke(
                            newExtractorLink(name, "$name $host ($type)", link, type = linkType) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName(source.quality)
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            if (subProviders.isNotEmpty()) subProviders.forEach { fetchSource(it, "sub") }
            if (dubProviders.isNotEmpty()) dubProviders.forEach { fetchSource(it, "dub") }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}

// --- Data Classes ---
data class AnilistTitle(@JsonProperty("english") val english: String?, @JsonProperty("romaji") val romaji: String?)
data class AnilistCover(@JsonProperty("extraLarge") val extraLarge: String?, @JsonProperty("large") val large: String?, @JsonProperty("medium") val medium: String?)
data class AnilistMedia(@JsonProperty("id") val id: Int, @JsonProperty("title") val title: AnilistTitle?, @JsonProperty("coverImage") val coverImage: AnilistCover?, @JsonProperty("bannerImage") val bannerImage: String?)
data class AnilistPage(@JsonProperty("media") val media: List<AnilistMedia>?)
data class AnilistData(@JsonProperty("Page") val page: AnilistPage?)
data class AnilistResponse(@JsonProperty("data") val data: AnilistData?)
data class GraphQlQuery(val query: String, val variables: Map<String, Any?>)

data class AnimexEpTitle(@JsonProperty("en") val en: String?, @JsonProperty("ja") val ja: String?, @JsonProperty("x-jat") val xJat: String?)
data class AnimexEpData(
    @JsonProperty("number") val number: Double?,
    @JsonProperty("titles") val titles: AnimexEpTitle?,
    @JsonProperty("img") val img: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("subProviders") val subProviders: List<String>?,
    @JsonProperty("dubProviders") val dubProviders: List<String>?
)

data class SourceData(@JsonProperty("url") val url: String?, @JsonProperty("quality") val quality: String?)
data class SubtitleData(@JsonProperty("url") val url: String?, @JsonProperty("lang") val lang: String?, @JsonProperty("label") val label: String?)
data class AnimexSources(@JsonProperty("sources") val sources: List<SourceData>?, @JsonProperty("subtitles") val subtitles: List<SubtitleData>?)

// --- Crypto Logic ---
object AnimexCrypto {
    private val d = intArrayOf(231, 59, 146, 95, 193, 70, 218, 142, 39, 245, 105, 179, 20, 168, 124, 208)
    private val U = intArrayOf(77, 241, 104, 156, 35, 183, 90, 230, 49, 205, 132, 31, 170, 118, 217, 82)
    private val j = intArrayOf(150, 42, 222, 113, 181, 73, 252, 131, 30, 167, 100, 216, 53, 201, 143, 18)
    private val A = intArrayOf(91, 239, 114, 166, 25, 205, 72, 180, 39, 251, 147, 110, 210, 129, 58, 197)
    private val Dollar = intArrayOf(169, 99, 215, 28, 240, 132, 59, 207, 82, 230, 149, 42, 190, 65, 120, 220)
    private val E = intArrayOf(52, 200, 93, 225, 118, 186, 47, 147, 78, 210, 135, 27, 175, 101, 249, 38)

    private fun f(n: Int): Int = ((n xor 1553869343) + (n shl 7 xor (n ushr 11)))

    private fun g(n: Int): Int = (n * 2654435769L).toInt()

    private fun x(n: Int): Int {
        var o = n
        o = o xor (o shl 13)
        o = o xor (o ushr 17)
        o = o xor (o shl 5)
        return o and 255
    }

    private fun u(n: Int, o: Int): Int = (n shl o or (n ushr (8 - o))) and 255

    private val b: IntArray by lazy {
        val n = IntArray(256)
        for (o in 0 until 256) {
            val e = o xor 170
            val c = x(e)
            val t = g(e + 23130)
            val s = f(o + c) and 255
            n[o] = (c xor (t and 255) xor s xor (o * 19)) and 255
        }
        for (o in 0 until 11) {
            for (e in 0 until 256) {
                val c = n[e]
                val t = n[(e + 37) % 256]
                val s = n[(e + 73) % 256]
                val a = n[(e + 139) % 256]
                val r = u(c, 3) xor u(t, 5) xor u(s, 7)
                val underscore = f(c + o) and 255
                n[e] = (r xor a xor underscore xor (o * 17 + e * 23)) and 255
            }
        }
        for (o in 0 until 128) {
            val e = 255 - o
            val c = (n[o] + n[e]) and 255
            val t = (n[o] xor n[e]) and 255
            n[o] = (u(c, 2) xor t) and 255
            n[e] = (u(t, 3) xor c) and 255
        }
        n
    }

    private fun p(n: Int, o: Int): IntArray {
        val e = IntArray(n)
        var c = o
        for (t in 0 until n) {
            c = g(c + t * 40503)
            val s = x(c)
            val a = d[t % 16] xor U[(t + 3) % 16] xor j[(t + 7) % 16]
            val r = A[(t + 2) % 16] xor Dollar[(t + 5) % 16] xor E[(t + 11) % 16]
            val underscore = b[c.ushr(8) and 255]
            e[t] = (s xor a xor r xor underscore xor (t * 19)) and 255
        }
        for (t in 0 until 7) {
            for (s in 0 until n) {
                val a = e[s]
                val r = e[(s + 19) % n]
                val underscore = e[(s + 37) % n]
                e[s] = (u(a, t + 1) xor (a xor r xor underscore)) and 255
            }
        }
        return e
    }

    private fun generateKeys(): Triple<ByteArray, IntArray, IntArray> {
        val y = 2052799517
        val ah = p(64, y)
        val ai = p(48, y xor 1515870810)

        val o = ah
        val e = IntArray(32)
        for (t in 0 until 32) {
            val s = o[t]
            val a = o[(t + 11) % 64]
            val r = o[(t + 23) % 64]
            val underscore = o[(t + 37) % 64]
            val i = f(s + t) and 255
            e[t] = (u(s, 3) xor a xor r xor underscore xor i xor (t * 25)) and 255
        }
        for (t in 0 until 5) {
            for (s in 0 until 32) {
                val a = e[s]
                val r = e[(s + 13) % 32]
                val underscore = e[(s + 19) % 32]
                val i = f(a + t * 7) and 255
                e[s] = (a xor ((r + underscore) and 255) xor i xor (t * 17)) and 255
            }
        }
        for (t in 0 until 4) {
            for (s in 0 until 16) {
                val a = e[s]
                val r = e[s + 16]
                // Fix: Priority of operations
                val underscore = (u(r, 4) xor ((a xor r) and 255) xor (t * 41 + s * 19)) and 255
                val i = f(a + r + t) and 255
                e[s] = r
                e[s + 16] = (a xor underscore xor i) and 255
            }
        }
        val c = IntArray(32)
        for (t in 0 until 32) {
            val s = e[t]
            val a = e[(t * 17 + 7) % 32]
            val r = o[t % 64]
            val underscore = b[(t * 13) % 256]
            val i = (t % 7) + 1
            val rotated = (s shl i) or (s ushr (8 - i))
            val h = f(s + a + t) and 255
            c[t] = (rotated xor a xor r xor underscore xor h xor (t * 37)) and 255
        }
        val keyBytes = ByteArray(32)
        for (i in 0 until 32) keyBytes[i] = c[i].toByte()
        
        return Triple(keyBytes, ah, ai)
    }

    private fun q(n: ByteArray): ByteArray {
        val o = ByteArray(n.size)
        for (e in n.indices) {
            val c = n[e].toInt() and 0xFF
            val t = (e * 23) and 255
            val swapped = (c shl 4) or (c ushr 4)
            o[e] = (swapped xor t).toByte()
        }
        return o
    }

    private fun T(n: ByteArray, o: IntArray, e: IntArray): ByteArray {
        val c = ByteArray(n.size)
        for (t in n.indices) {
            val s = o[t % o.size]
            val a = o[(t + 7) % o.size]
            val r = o[(t + 13) % o.size]
            val underscore = e[t % e.size]
            val i = e[(t + 11) % e.size]
            val h = b[(t * 7) % 256]
            val nt = n[t].toInt() and 0xFF
            val value = nt xor s xor a xor r xor underscore xor i xor h xor (t * 23)
            c[t] = value.toByte()
        }
        return c
    }

    fun encrypt(jsonString: String): String {
        return try {
            val (keyBytes, at, au) = generateKeys()
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)

            val s = jsonString.toByteArray(StandardCharsets.UTF_8)
            val a = q(s)
            val r = T(a, at, au)

            val spec = GCMParameterSpec(128, iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
            val encrypted = cipher.doFinal(r)

            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

            Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}
