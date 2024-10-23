package eu.kanade.tachiyomi.extension.fr.mangamana

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class MangaMana : ParsedHttpSource() {

    override val name = "Manga Mana"

    override val baseUrl = "https://www.manga-mana.com"

    private val croppedBaseUrl = baseUrl.substringAfter("//").removePrefix("www")

    override val lang = "fr"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val json: Json by injectLazy()

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesSelector() = "div.col_home"

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        title = element.selectFirst("h4 a")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("data-src")
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
    }

    override fun latestUpdatesNextPageSelector() = "a[rel=next]"

    // ============================== Manga Details =========================

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select("h1[itemprop=name]").text()
        thumbnail_url = document.selectFirst("img[itemprop=image]")?.absUrl("data-src")
        genre = document.select("li > a[itemprop=genre]").joinToString { it.text() }
        description = document.selectFirst("dd[itemprop=description]")?.text()
        setUrlWithoutDomain(document.location())
    }

    // ============================== Chapters ===============================

    override fun chapterListSelector() = "a.chapter_link"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.selectFirst(".chapter > div")!!.ownText()
        setUrlWithoutDomain(element.absUrl("href"))
    }

    private fun chapterListNextPageSelector() = popularMangaNextPageSelector()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val chapters = mutableListOf<SChapter>()
        var page = 0
        do {
            val document = fetchChapterListPage(manga, page++)
            chapters += document.select(chapterListSelector()).map(::chapterFromElement)
        } while (document.selectFirst(chapterListNextPageSelector()) != null)
        return Observable.just(chapters)
    }

    private fun fetchChapterListPage(manga: SManga, page: Int): Document {
        if (page > 1) {
            return client.newCall(GET("$baseUrl${manga.url}&page=$page", headers)).execute().asJsoup()
        }
        return client.newCall(GET("$baseUrl${manga.url}", headers)).execute().asJsoup()
    }

    // ============================== Pages ===============================

    override fun pageListParse(document: Document): List<Page> {
        val chapterSlug = Regex("""var chapter_slug = "([^"]*)";""").find(document.toString())?.groupValues?.get(1)
        val mangaSlug = Regex("""var oeuvre_slug = "([^"]*)";""").find(document.toString())?.groupValues?.get(1)
        val cdnPrefix = Regex("""var cdn = "([^"]*)";""").find(document.toString())?.groupValues?.get(1)

        val pages = document.toString().substringAfter("var pages = ").substringBefore(";")

        return json.parseToJsonElement(pages).jsonArray.mapIndexed { i, it ->
            Page(
                i,
                imageUrl = "https://" + cdnPrefix + "$croppedBaseUrl/uploads/manga/" +
                    mangaSlug + "/chapters_fr/" + chapterSlug + "/" +
                    it.jsonObject["image"]!!.jsonPrimitive.content + "?" +
                    it.jsonObject["version"]!!.jsonPrimitive.content
            )
        }
    }

    override fun imageUrlParse(document: Document) = ""

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun popularMangaSelector(): String = "div.col_home"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        title = element.selectFirst("h4 a")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("data-src")
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
    }

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/search-live".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()

        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.FRENCH)
}
