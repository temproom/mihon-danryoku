package eu.kanade.tachiyomi.extension.all.danryoku

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(
    displayName: String,
    private val valuePair: Array<Pair<String, String>>,
) : Filter.Select<String>(displayName, valuePair.map { it.first }.toTypedArray()) {
    fun toUriPart() = valuePair[state].second
}

class CategoryFilter(categories: Array<Pair<String, String>>) : UriPartFilter("Category", categories)

// id 字段现在存储的是标签的 URL 路径（如 "sayo-momo"），而不是数字 ID
class TagFilter(name: String, val id: String) : Filter.TriState(name)

class TagGroup(tags: List<TagFilter>) : Filter.Group<TagFilter>("Tags", tags)