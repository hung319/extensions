// Tên file: HoatHinhKungfuProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import java.util.regex.Pattern

/**
 * Senior Software Engineer's Note:
 * This is an updated provider for hhkungfu.click.
 * Version 15 Changelog:
 * - CRITICAL FIX: Corrected a major bug in the homepage/pagination selector that caused `getMainPage` and `search` to fail.
 * - Unified selectors for `getMainPage` and `search` for better stability.
 * - All other features and optimizations remain intact.
 */
class HoatHinhKungfuProvider : MainAPI() {
    override var mainUrl = "https://hhkungfu.click"
    override var name = "Hoạt Hình Kungfu"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        // Selector bên trong hàm này đã đúng, không cần thay đổi
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val href = this.selectFirst("a.halim-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("figure > img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
        }
    }

    // ĐÃ SỬA LỖI: Sử dụng selector chính xác
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/latest-movie/page/$page/"
        val document = app.get(url).document

        // Sử dụng selector `div.halim_box article.thumb` chung và chính xác
        val articles = document.select("div.halim_box article.thumb")
        
        val homePageList = articles.mapNotNull {
            it.toSearchResponse()
        }

        val hasNext = document.selectFirst("a.next.page-numbers") != null
        val items = listOf(HomePageList("Mới Cập Nhật", homePageList))
        
        return newHomePageResponse(items, hasNext)
    }

    // ĐÃ SỬA LỖI: Đồng bộ selector với getMainPage để đảm bảo hoạt động
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.halim_box article.thumb").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề")

        val posterUrl = document.selectFirst("img.movie-thumb")?.attr("src")
        val plot = document.select("div.video-item.halim-entry-box article p").joinToString("\n\n") { it.text() }
        val tags = document.select("p.category a").map { it.text() }

        val episodes = document.select("ul.halim-list-eps li a").mapNotNull {
            val href = it.attr("href")
            val name = it.text().trim()
            if (href.isNotBlank() && name.isNotBlank()) {
                newEpisode(href) {
                    this.name = "Tập $name"
                }
            } else {
                null
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = mutableListOf<String>()

        document.selectFirst("div#ajax-player iframe")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.let { sources.add(it) }

        document.select("div#halim-ajax-list-serverX span.get-eps").forEach {
            it.attr("data-link")?.let { link ->
                if (link.isNotBlank()) sources.add(link)
            }
        }

        coroutineScope {
            sources.map { sourceUrl ->
                async {
                    if ("viupload.net" in sourceUrl) {
                        try {
                            val embedContent = app.get(sourceUrl, referer = mainUrl).text
                            val m3u8Regex = Regex("""sources:\s*\[\{file:"([^"]+)""")
                            m3u8Regex.find(embedContent)?.groupValues?.get(1)?.let { m3u8Link ->
                                callback.invoke(
                                    ExtractorLink("ViUpload", "ViUpload", m3u8Link, sourceUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                                )
                            }
                        } catch (e: Exception) { /* Bỏ qua lỗi */ }
                    } 
                    else if ("ssplay.net" in sourceUrl) {
                        try {
                            val embedPage = app.get(sourceUrl, referer = mainUrl).text
                            val packedRegex = Regex("""(eval\(function\(p,a,c,k,e,d\)\{.*sources.*?\}\\)\))""")
                            val packedScript = packedRegex.find(embedPage)?.groupValues?.get(1)
                            if (packedScript != null) {
                                val unpacked = JsUnpacker(packedScript).unpack()
                                if (unpacked != null) {
                                    val m3u8Regex = Regex("""sources:\s*\[\s*\{\s*file:\s*"([^"]+)"""")
                                    var m3u8Link = m3u8Regex.find(unpacked)?.groupValues?.get(1)
                                    if (m3u8Link != null) {
                                        if (m3u8Link.startsWith("//")) {
                                            m3u8Link = "https:$m3u8Link"
                                        } else if (m3u8Link.startsWith("/")) {
                                            m3u8Link = "https://ssplay.net$m3u8Link"
                                        }
                                        callback.invoke(
                                            ExtractorLink("SSPlay", "SSPlay", m3u8Link, sourceUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) { /* Bỏ qua lỗi */ }
                    }
                    else if ("player.cloudbeta.win" in sourceUrl) {
                        try {
                            val uuid = sourceUrl.substringAfterLast('/')
                            val m3u8Link = "https://play.cloudbeta.win/file/play/$uuid.m3u8"
                            callback.invoke(
                                ExtractorLink("CloudBeta", "CloudBeta", m3u8Link, sourceUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                            )
                        } catch (e: Exception) { /* Bỏ qua lỗi */ }
                    }
                    else {
                        loadExtractor(sourceUrl, mainUrl, subtitleCallback, callback)
                    }
                }
            }.awaitAll()
        }
        return sources.isNotEmpty()
    }
}

private class JsUnpacker(private val packedJS: String) {
    fun unpack(): String? {
        var js = packedJS
        try {
            val matcher = Pattern.compile("eval\\(function\\(p,a,c,k,e,d\\)(.*)\\)").matcher(js)
            if (matcher.find()) {
                js = matcher.group(0)
            }
            val p_a_c_k_e_d = js.replaceFirst("eval(function(p,a,c,k,e,d)".toRegex(), "").dropLast(1)
            val all = p_a_c_k_e_d.replace("'.split('|'))".toRegex(), "####")
            val payload = all.substringBefore("####").substringBeforeLast(",'")
            val dict = all.substringBefore("####").substringAfterLast(",'").split("|")
            val base = all.substringAfter("####").substringAfter(",").substringBefore(",")
            val k = dict.toTypedArray()
            val p = payload.drop(1)
            val a = base.toInt()
            val c = k.size

            fun getAlpha(value: Int): String {
                return when {
                    value < a -> value.toString(a)
                    else -> (value / a).let {
                        getAlpha(it) + (value % a).let {
                            if (it > 35) (it + 29).toChar() else it.toString(36)
                        }.toString()
                    }
                }
            }

            val pMatcher = Pattern.compile("\\b\\w+\\b").matcher(p)
            val decoded = StringBuilder(p)
            var offset = 0
            while (pMatcher.find()) {
                val word = pMatcher.group(0)
                val value = word.toIntOrNull(a)
                if (value != null) {
                    if (value < c) {
                        val replacement = k[value]
                        if (replacement.isNotEmpty()) {
                            decoded.replace(
                                pMatcher.start() + offset,
                                pMatcher.end() + offset,
                                replacement
                            )
                            offset += replacement.length - word.length
                        }
                    }
                }
            }
            return decoded.toString()
        } catch (e: Exception) {
            return null
        }
    }
}
