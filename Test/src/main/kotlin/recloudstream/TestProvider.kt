package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.base64Decode // <-- ĐÃ SỬA LẠI DÒNG NÀY
import org.jsoup.nodes.Element
import java.util.regex.Pattern

/**
 * Helper class to unpack the popular JS packer format `eval(function(p,a,c,k,e,d))`
 */
private class JsUnpacker(private val packedJS: String) {
    fun unpack(): String? {
        return try {
            val p = packedJS.substringAfter("eval(function(p,a,c,k,e,d){").substringBefore("}))")
            val pattern = Pattern.compile("'.*?'.*?,''.*?'")
            val matcher = pattern.matcher(p)
            if (!matcher.find()) return null

            val all = matcher.group(0)
            val payload = all.substringBeforeLast(",")
            val dictionary = all.substringAfterLast(",").substringAfter("'").substringBefore("'")

            val p2 = payload.substringBeforeLast("'")
            val a = payload.substringAfterLast(",").toIntOrNull() ?: return null
            val c = payload.substringAfter(",").substringBefore(",").toIntOrNull() ?: return null
            val k = dictionary.split('|')

            var unpacked = p2.replace("'", "")

            fun base(num: Int): String {
                return if (num < a) "" else base(num / a) + when (val rem = num % a) {
                    in 0..9 -> rem.toString()
                    else -> (rem + 29).toChar().toString()
                }
            }

            for (i in (c - 1) downTo 0) {
                if (k.getOrNull(i)?.isNotEmpty() == true) {
                    unpacked = unpacked.replace(Regex("\\b${base(i)}\\b"), k[i])
                }
            }
            unpacked
        } catch (e: Exception) {
            null
        }
    }
}


class TvPhimProvider : MainAPI() {
    override var mainUrl = "https://tvphim.bid"
    override var name = "TvPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val movieItemSelector = "div.item.movies"
    
    private suspend fun getDomain(): String {
        val bitlyUrl = "aHR0cHM6Ly9iaXQubHkvZmFudHhwaGlt".base64Decode()
        return app.get(bitlyUrl, allowRedirects = true).url
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (mainUrl.isBlank() || !mainUrl.startsWith("http")) {
            mainUrl = getDomain()
        }
        val url = if (page > 1) "$mainUrl/${request.data}/page/$page/" else "$mainUrl/${request.data}"
        val document = app.get(url).document

        val sections = document.select("div.section")
        if (sections.isEmpty()) throw Exception("Không thể tải trang chủ. Có thể do Cloudflare.")

        val homePageList = sections.mapNotNull { section ->
            val title = section.selectFirst("div.section-title")?.text() ?: return@mapNotNull null
            val movies = section.select(movieItemSelector).mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) HomePageList(title, movies) else null
        }
        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title")
        val posterUrl = this.selectFirst("div.poster img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (mainUrl.isBlank() || !mainUrl.startsWith("http")) {
            mainUrl = getDomain()
        }
        val url = "$mainUrl/tim-kiem/$query/"
        val document = app.get(url).document
        return document.select(movieItemSelector).mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: "N/A"
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
        val isTvSeries = document.select("div#list_episodes").isNotEmpty()

        if (isTvSeries) {
            val episodes = document.select("div#list_episodes a").map {
                val epUrl = fixUrl(it.attr("href"))
                val epName = it.text().trim()
                newEpisode(epUrl) { this.name = epName }
            }.reversed()
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val packedScript = document.select("script").find { it.data().contains("eval(function(w,i,s,e)") }?.data()
        if (packedScript != null) {
            val unpacked = JsUnpacker(packedScript).unpack()
            val sourceUrl = unpacked?.let {
                Regex("""sources:\[\{file:'(.*?)'""").find(it)?.groupValues?.get(1)
            }
            if (sourceUrl != null) {
                callback.invoke(
                    ExtractorLink(
                        this.name, "S.PRO", sourceUrl, mainUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8
                    )
                )
            }
        }

        document.select("a[title*='Server R.PRO']").firstOrNull()?.attr("href")?.let { rProUrl ->
            if (rProUrl.contains("ok.ru")) {
                loadExtractor(rProUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
