package eu.kanade.tachiyomi.extension.all.danryoku

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Danryoku : KeiSource() {

    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    // 提取图片真实地址：优先 data-lazy-src，过滤 data: 占位符
    private val Element.realImgSrc: String?
        get() {
            val lazy = attr("data-lazy-src").ifBlank { null }
            if (!lazy.isNullOrBlank() && !lazy.startsWith("data:")) return lazy
            val src = attr("src").ifBlank { null }
            if (!src.isNullOrBlank() && !src.startsWith("data:")) return src
            return null
        }

    // ========================= Popular (HTML) =========================
    override suspend fun getPopularManga(page: Int): MangasPage {
        return fetchArticleList(pageUrl(page))
    }

    // ========================= Latest (HTML) =========================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return fetchArticleList(pageUrl(page))
    }

    private fun pageUrl(page: Int): HttpUrl =
        if (page <= 1) baseUrl.toHttpUrl()
        else "$baseUrl/page/$page/".toHttpUrl()

    private suspend fun fetchArticleList(url: HttpUrl): MangasPage {
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val articles = document.select("article.masonry-post")
        val mangas = articles.mapNotNull { article ->
            val link = article.selectFirst("h2 a, figure a") ?: return@mapNotNull null
            val href = link.absUrl("href").ifEmpty { link.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            SManga.create().apply {
                title = article.selectFirst("h2")?.text()?.trim().orEmpty()
                setUrlWithoutDomain(href)
                thumbnail_url = article.selectFirst("figure img")?.realImgSrc
            }
        }
        /*val hasNext = document.selectFirst("nav.nav-links a.next") != null
        return MangasPage(mangas, hasNext)*/
        // 只要能找到带有 next 类的 <a> 标签，就说明有下一页
        val hasNext = document.selectFirst("a.next") != null
        return MangasPage(mangas, hasNext)
    }

    // ========================= Search (HTML) =========================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        val tagGroup = filters.firstInstanceOrNull<TagGroup>()

        // 确定基础路径（不带页码）
        val basePath = if (categoryFilter != null && categoryFilter.state != 0) {
            val catSlug = categoryFilter.toUriPart()
            "$baseUrl/category/$catSlug/"
        } else {
            val includedTags = tagGroup?.state?.filter { it.state == Filter.TriState.STATE_INCLUDE }?.map { it.id } ?: emptyList()
            if (includedTags.isNotEmpty()) {
                // 取第一个包含的标签进行筛选
                "$baseUrl/tag/${includedTags.first()}/"
            } else {
                "$baseUrl/" // 默认首页
            }
        }

        // 拼装正确的分页 URL 结构： /page/N/ 或 /category/xxx/page/N/
        val pageUrlString = if (page <= 1) {
            basePath
        } else {
            if (basePath == "$baseUrl/") {
                "$baseUrl/page/$page/"
            } else {
                "${basePath}page/$page/"
            }
        }

        // 最后加上搜索关键词
        val finalUrl = pageUrlString.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addQueryParameter("s", query)
            }
        }.build()

        return fetchArticleList(finalUrl)
    }

    // ========================= Details (HTML) =========================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga), Fetch.DOCUMENT)
        val document = response.asJsoup()

        if (fetchDetails) {
            manga.apply {
                title = document.selectFirst("h1.post-title-danryoku")?.text()?.trim()
                    ?.ifEmpty { manga.title } ?: manga.title
                thumbnail_url = document.selectFirst("#post-image img")?.realImgSrc
                    ?: manga.thumbnail_url
                // 从 "Model name or Studio" 和 "Model country" 区域提取标签和分类
                genre = document.select(".dynamic-term-class a")
                    .joinToString { it.text().trim() }
                    .ifEmpty { null }
                description = document.selectFirst(".disclaimer_danryoku p")?.text()?.trim()
                status = SManga.COMPLETED
            }
        }

        if (fetchChapters) {
            val canonical = document.selectFirst("link[rel=canonical]")?.attr("href")
                ?: response.request.url.toString()
            val published = document.selectFirst("time.entry-date")?.attr("datetime")
            val chapter = SChapter.create().apply {
                setUrlWithoutDomain(canonical)
                name = "Gallery"
                chapter_number = 1f
                date_upload = runCatching { dateFormat.parse(published.orEmpty())?.time }.getOrNull() ?: 0L
            }
            return SMangaUpdate(manga, listOf(chapter))
        }
        return SMangaUpdate(manga, chapters)
    }

    // ========================= Pages (HTML) =========================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        return parsePageImages(document)
            .distinct()
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun parsePageImages(document: Document): List<String> {
        // 移除 noscript 以避免重复图片
        document.select("noscript").remove()
        // 限定在正文内容容器内，避免抓取到侧边栏/相关推荐中的图片
        val container = document.selectFirst(".dynamic-entry-content")
            ?: document.selectFirst("#post-content")
            ?: return emptyList()
        return container.select("img")
            .mapNotNull { it.realImgSrc }
            .filter { url ->
                val lower = url.substringBefore('?').lowercase()
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") ||
                lower.endsWith(".gif") || lower.endsWith(".avif")
            }
    }

    /*override fun imageUrlRequest(page: Page): Request {
        return Request.Builder()
            .url(page.url)
            .header("Referer", "$baseUrl/")
            .build()
    }*/

    // ========================= Filters (静态配置) =========================
    // 不再从 API 动态获取，直接根据首页导航栏硬编码
    override val supportsFilterFetching get() = false

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Note: Category and Tag filters work by redirecting to the corresponding archive page."),
            Filter.Separator(),
            // 分类选项，valuePair 的第二个元素是 URL 中的路径片段
            CategoryFilter(arrayOf(
                "Any" to "",
                "NSFW" to "nsfw",
                "COSPLAY" to "cosplay",
                "Japan" to "country/japan",
                "China" to "country/china",
                "Korea" to "country/korea",
                "Taiwan" to "country/taiwan",
                "Western" to "country/western",
                "AI" to "ai",
            )),
        )
        // 标签组：由于无法动态枚举，提供一个通用的标签输入提示
        // 或者，如果你想支持一些常见标签，可以在这里硬编码
        // 示例：仅提供基于首页文章的少量标签
        filters.add(TagGroup(listOf(
            TagFilter("Sayo Momo", "sayo-momo"),
            TagFilter("Senya Miku", "senya-miku"),
            TagFilter("Sexylil069", "sexylil069"),
            // 可以继续添加...
        )))
        return FilterList(filters)
    }
}
