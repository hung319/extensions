package recloudstream

import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import android.util.Log // THÊM IMPORT NÀY

class HHKungfuProvider : MainAPI() {
    override var name = "HHKungfu"
    override var mainUrl = "https://hhkungfu.ee"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    // Thêm TAG để lọc log cho dễ
    private val TAG = "HHKungfuProvider"

    override val mainPage = mainPageOf(
        "moi-cap-nhat/page/" to "Mới cập nhật",
        "top-xem-nhieu/page/" to "Top Xem Nhiều",
        "hoan-thanh/page/" to "Hoàn Thành",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
            }
        }
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url).document

        val home = document.select("div.halim_box article.thumb").mapNotNull {
            it.toSearchResponse()
        }
        
        val hasNext = document.selectFirst("a.next.page-numbers") != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a.halim-thumb, a.thumbnail-link") ?: return null
        val href = a.attr("href")
        val title = this.selectFirst("h2.entry-title, h3.title")?.text() ?: return null
        val posterUrl = this.selectFirst("img.wp-post-image")?.attr("src")
        val episodeText = this.selectFirst("span.episode")?.text()

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl ?: ""

            if (episodeText != null) {
                val episodeRegex = Regex("""\d+""")
                episodeRegex.find(episodeText)?.value?.toIntOrNull()?.let {
                    this.episodes = it
                }
                
                this.quality = when {
                    episodeText.contains("4K", true) -> SearchQuality.FourK
                    episodeText.contains("FULL HD", true) -> SearchQuality.HD
                    episodeText.contains("HD", true) -> SearchQuality.HD
                    else -> null
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("#loop-content article.thumb").mapNotNull {
            it.toSearchResponse()
        }
    }

    private data class EpisodeInfo(val url: String, val serverLabel: String)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".movie-poster img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()
        val tags = document.select(".list_cate a").map { it.text() }

        val episodeMap = mutableMapOf<String, MutableList<EpisodeInfo>>()
        val serverBlocks = document.select(".halim-server")
        
        serverBlocks.forEach { server ->
            val serverName = server.selectFirst(".halim-server-name")?.text()
            val serverLabel = when {
                serverName?.contains("Vietsub", true) == true -> "VS"
                serverName?.contains("Thuyết Minh", true) == true -> "TM"
                else -> "RAW"
            }

            server.select("ul.halim-list-eps li a").forEach { ep ->
                val epName = ep.selectFirst("span")?.text()?.trim() ?: ep.text().trim()
                val epUrl = ep.attr("href")
                
                episodeMap.getOrPut(epName) { mutableListOf() }.add(EpisodeInfo(epUrl, serverLabel))
            }
        }
        
        val numberRegex = Regex("""\d+""")
        val episodes = episodeMap.entries
            .map { (epName, infoList) ->
                val sortKey = numberRegex.find(epName)?.value?.toIntOrNull() ?: 0
                Triple(sortKey, epName, infoList)
            }
            .sortedBy { it.first }
            .map { (_, epName, infoList) ->
                val data = infoList.toJson()
                val serverTags = infoList.joinToString(separator = "+") { it.serverLabel }.let { "($it)" }
                
                newEpisode(data) {
                    this.name = "$epName $serverTags"
                }
            }

        val recommendations = document.select("aside#sidebar .popular-post .item").mapNotNull {
            it.toSearchResponse()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster ?: ""
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun base64UrlDecode(input: String): ByteArray {
        val a = input.replace('-', '+').replace('_', '/')
        val b = when (a.length % 4) {
            0 -> ""
            2 -> "=="
            3 -> "="
            else -> throw IllegalArgumentException("Illegal base64url string!")
        }
        return Base64.decode(a + b, Base64.DEFAULT)
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + key[i % key.size].toInt() and 0xff) and 0xff
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }

        var i = 0
        j = 0
        val result = ByteArray(data.size)
        for (index in data.indices) {
            i = (i + 1) and 0xff
            j = (j + s[i]) and 0xff
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
            result[index] = (data[index].toInt() xor s[(s[i] + s[j]) and 0xff]).toByte()
        }
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks called with data: $data")
        val listType = object : TypeToken<List<EpisodeInfo>>() {}.type
        val infoList = parseJson<List<EpisodeInfo>>(data)
        Log.d(TAG, "Parsed infoList: $infoList")

        coroutineScope {
            infoList.forEach { info ->
                async {
                    Log.d(TAG, "Processing server: ${info.serverLabel} with URL: ${info.url}")
                    try {
                        val watchPageDoc = app.get(info.url, referer = mainUrl).document
                        Log.d(TAG, "Successfully fetched watch page for ${info.url}")
                        val activeEpisode = watchPageDoc.selectFirst(".halim-episode.active a") ?: return@async
                        val postId = watchPageDoc.selectFirst("main.watch-page")?.attr("data-id") ?: return@async
                        val chapterSt = activeEpisode.attr("data-ep") ?: return@async
                        val sv = activeEpisode.attr("data-sv") ?: return@async
                        Log.d(TAG, "Parsed params: postId=$postId, chapterSt=$chapterSt, sv=$sv")

                        val langPrefix = "${info.serverLabel} "

                        val serverButtons = watchPageDoc.select("#halim-ajax-list-server .get-eps")
                        for (button in serverButtons) {
                            // SỬA LỖI: Khai báo serverName ở ngoài để catch có thể truy cập
                            var serverName = "Unknown"
                            try {
                                val type = button.attr("data-type")
                                serverName = button.text() // Gán giá trị ở đây
                                Log.d(TAG, "Attempting to get link from server button: $serverName (type=$type)")
                                val playerAjaxUrl = "$mainUrl/player/player.php"

                                val ajaxResponse = app.get(
                                    playerAjaxUrl,
                                    params = mapOf(
                                        "action" to "dox_ajax_player",
                                        "post_id" to postId,
                                        "chapter_st" to chapterSt,
                                        "type" to type,
                                        "sv" to sv
                                    ),
                                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                    referer = info.url
                                ).document

                                val iframeSrc = ajaxResponse.selectFirst("iframe")?.attr("src") ?: continue
                                Log.d(TAG, "Found iframeSrc: $iframeSrc")

                                val extractorPage = app.get(iframeSrc, referer = info.url).document
                                Log.d(TAG, "Successfully fetched iframe page.")

                                val hrmPlayer = extractorPage.selectFirst("div#hrm-player")
                                val dataId = hrmPlayer?.attr("data-id")
                                val dataNonce = hrmPlayer?.attr("data-nonce")
                                val bytecode = extractorPage.selectFirst("meta[name=bytecode]")?.attr("content")
                                Log.d(TAG, "dataId: $dataId")
                                Log.d(TAG, "dataNonce: $dataNonce")
                                Log.d(TAG, "bytecode: $bytecode")

                                if (dataId != null && dataNonce != null && bytecode != null) {
                                    val encryptedData = base64UrlDecode(dataNonce)
                                    val key = bytecode.toByteArray(Charsets.UTF_8)
                                    
                                    val decryptedData = rc4(key, encryptedData)
                                    val decryptedJson = String(decryptedData)
                                    Log.d(TAG, "Decrypted JSON: $decryptedJson")

                                    val fileUrl = parseJson<Map<String, Any>>(decryptedJson)["file"] as? String

                                    if (fileUrl != null) {
                                        Log.d(TAG, "SUCCESS! Extracted fileUrl: $fileUrl")
                                        callback.invoke(
                                            ExtractorLink(
                                                source = this@HHKungfuProvider.name,
                                                name = "$langPrefix$serverName",
                                                url = fileUrl,
                                                referer = iframeSrc,
                                                quality = Qualities.Unknown.value,
                                                type = ExtractorLinkType.M3U8
                                            )
                                        )
                                    } else {
                                        Log.w(TAG, "Decryption successful but 'file' key not found in JSON.")
                                    }
                                } else {
                                    Log.w(TAG, "One or more crypto parameters are null. Skipping this server button.")
                                }
                            } catch (e: Exception) {
                                // Giờ đây 'serverName' đã có thể được truy cập ở đây
                                Log.e(TAG, "Error processing server button '$serverName'", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing main server '${info.serverLabel}'", e)
                    }
                }
            }
        }
        return true
    }
}
