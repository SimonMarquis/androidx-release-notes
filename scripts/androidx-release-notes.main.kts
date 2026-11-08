#!/usr/bin/env kotlin

@file:CompilerOptions("-jvm-target", "25")
@file:DependsOn(
    "com.github.ajalt.clikt:clikt-jvm:5.1.0",
    "org.jsoup:jsoup:1.24.1",
    "org.jspecify:jspecify:1.0.1", // jsoup nullability annotations
    "org.jetbrains.kotlinx:kotlinx-html-jvm:0.12.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.12.0",
)

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

//region Commands

class ReleaseNotesCommand : NoOpCliktCommand(__FILE__.name) {
    override fun help(context: Context) = "Build and maintain the AndroidX release-notes archive."

    init {
        subcommands(ImportHistoryCommand(), SyncRssCommand(), IgnoreCommand(), BuildSiteCommand(), SyncReleasesCommand())
    }
}

class ImportHistoryCommand : CliktCommand(name = "import-history") {
    override fun help(context: Context) = "Import all dated release groups from the all-channel page."
    private val input by option(help = "HTML file or page URL.").default(ALL_CHANNEL_URL)
    private val minimumEntries by option(help = "Minimum dated groups expected.").int().default(100)

    override fun run() {
        Log.info("📜 Importing history from $input")
        val entries = loadDocument(input, ALL_CHANNEL_URL).allChannelEntries()
        Log.info("📅 Extracted ${entries.size} dated release groups")
        require(entries.size >= minimumEntries) {
            "Expected at least $minimumEntries dated entries, but extracted ${entries.size}."
        }
        Importer().import(entries, fromRss = false)
    }
}

class SyncRssCommand : CliktCommand(name = "sync-rss") {
    override fun help(context: Context) = "Import new RSS entries and entries whose RSS updated value changed."
    private val input by option(help = "Atom file or feed URL.").default(FEED_URL)

    override fun run() = Importer().import(loadDocument(input, FEED_URL, Parser.xmlParser()).atomEntries(), fromRss = true)
}

class IgnoreCommand : CliktCommand(name = "ignore") {
    override fun help(context: Context) = "Hide every cached entry on an ISO date and prevent subsequent imports."
    private val date by argument(help = "ISO date (YYYY-MM-DD).").convert { value ->
        runCatching { LocalDate.parse(value) }.getOrElse { fail("Expected an ISO date (YYYY-MM-DD): $value") }
    }

    override fun run() {
        val count = Archive().ignore(date)
        echo("🙈 Ignored $count release ${if (count == 1) "entry" else "entries"} on $date.")
    }
}

class BuildSiteCommand : CliktCommand(name = "build-site") {
    override fun help(context: Context) = "Rebuild all static pages from stored intermediate entry files."
    private val embedded by option(help = "Inline CSS, JS, and favicon so each page works on its own.").flag()

    override fun run() {
        val records = Archive().load()
        Log.info("📂 Loaded ${records.size} cached entries")
        SiteRenderer(Path(OUTPUT_DIR), embedded).render(records)
    }
}

class SyncReleasesCommand : CliktCommand(name = "sync-releases") {
    override fun help(context: Context) = "Reconcile dated GitHub releases with the cached entries."
    private val commitSha = Regex("^[0-9a-f]{40}$")
    private val changedDataFile = Regex("^$DATA_DIR/(\\d{4}-\\d{2}-\\d{2})(?:-[a-z0-9-]+)?\\.json$")
    private val changedFrom by option(help = "Reconcile only JSON dates changed since this commit.")
        .check("expected a full 40-character commit SHA") { commitSha.matches(it) }

    override fun run() {
        val changedDates = changedFrom?.let { before ->
            // GitHub sends an all-zero SHA when the pushed ref has no previous commit.
            if (before.all { it == '0' }) return echo("⏭️ No previous commit; skipped GitHub release sync.")
            changedReleaseDates(before).also {
                if (it == null) Log.warn("$before is not in the local history; reconciling all releases")
            }
        }
        if (changedDates?.isEmpty() == true) return echo("⏭️ No JSON dates changed; skipped GitHub release sync.")
        val expected = Archive().load().filterNot(EntryRecord::ignored).groupBy(EntryRecord::releasedOn)
        Log.info("📂 ${expected.size} dated releases expected, ${changedDates?.let { "${it.size} changed" } ?: "reconciling all"}")
        val releases = GitHubReleases()
        val (dates, current) = when (changedDates) {
            null -> releases.list().let { existing -> expected.keys + existing.keys to existing::get }
            else -> changedDates to releases::find
        }
        dates.sorted().forEach { releases.reconcile(it, expected[it], current(it)) }
        echo("✅ Reconciled ${dates.size} dated releases.")
    }

    /** Returns null when [before] is missing from the local history (e.g. after a force push). */
    private fun changedReleaseDates(before: String): Set<LocalDate>? {
        if (git("cat-file", "-e", "$before^{commit}").first != 0) return null
        Log.info("🔎 Looking for data changes since ${before.take(7)}")
        val (status, output) = git("diff", "--no-renames", "--name-only", before, "HEAD", "--", DATA_DIR)
        check(status == 0) { "Could not inspect changed data files: $output" }
        return output.lineSequence()
            .mapNotNull { changedDataFile.matchEntire(it)?.groupValues?.get(1)?.let(LocalDate::parse) }
            .toSet()
    }

    /** Runs git and returns its exit code with its combined output. */
    private fun git(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder("git", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }
}

//endregion

//region Model

/** A dated release entry, from the Atom feed or the all-channel page. */
data class FeedEntry(
    val id: String,
    val title: String,
    val updated: String,
    val sourceUrl: String,
    val contentHtml: String,
) {
    val canonicalId: String get() = canonicalEntryId(id)
    val releasedOn: LocalDate get() = title.toReleaseDateOrNull() ?: LocalDate.ofInstant(Instant.parse(updated), ZoneOffset.UTC)

    fun componentNotes(): List<ComponentNote> =
        Jsoup.parse(contentHtml, sourceUrl).select("a[href]").mapNotNull { it.toComponentNote() }.distinctBy(ComponentNote::url)
}

/** A component release linked from an entry, with the notes scraped from its release page. */
data class ComponentNote(
    val name: String,
    val version: String,
    val url: String,
    val detailsHtml: String = "",
    val commitsUrl: String? = null,
)

/** A stored component release. */
data class ComponentRecord(
    val id: String,
    val name: String,
    val version: String,
    val url: String,
    val detailsHtml: String,
    val commitsUrl: String?,
) {
    val key: String get() = componentKey(id, version)
}

/** A stored release entry, one JSON file in `data/`. */
data class EntryRecord(
    val id: String,
    val title: String,
    val releasedOn: LocalDate,
    val updated: String,
    val sourceUrl: String,
    val components: List<ComponentRecord>,
    val ignored: Boolean = false,
    val rssUpdated: String? = null,
) {
    val canonicalId: String get() = canonicalEntryId(id)
    val anchor: String get() = entryAnchor(id, title)
}

private val looseDateId = Regex("(#[A-Za-z]+)_0?([1-9]\\d?)_?(\\d{4})$")

// Match padded (october_07_2026) and unseparated (april_302019) IDs without changing the stored source ID or page anchor.
fun canonicalEntryId(id: String): String = id.replace(looseDateId) {
    val (month, day, year) = it.destructured
    "${month}_${day}_$year"
}

fun entryAnchor(id: String, title: String): String = id.substringAfterLast('#', "").ifBlank { title }.slug()

fun componentKey(id: String, version: String): String = "$id/${version.slug()}"

/** Indexes records by [EntryRecord.canonicalId], failing on duplicates. */
fun List<EntryRecord>.associateByCanonicalId(): Map<String, EntryRecord> {
    // The official feed sometimes pads the day in its ID while older archive entries do not.
    val grouped = groupBy(EntryRecord::canonicalId)
    val duplicates = grouped.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "Duplicate cached release entry IDs: ${duplicates.sorted().joinToString()}" }
    return grouped.mapValues { it.value.single() }
}

//endregion

//region Sources

val FEED_URL = "https://developer.android.com/feeds/androidx-release-notes.xml"
val ALL_CHANNEL_URL = "https://developer.android.com/jetpack/androidx/versions/all-channel"
private val USER_AGENT = "androidx-release-notes-viewer/1.0"
private val HEADINGS = "h1, h2, h3, h4, h5, h6"

/** Loads [input] from a URL, or from a local file whose links resolve against [baseUri]. */
fun loadDocument(input: String, baseUri: String, parser: Parser = Parser.htmlParser()): Document =
    if (input.startsWith("https://") || input.startsWith("http://")) fetchDocument(input, parser)
    else Jsoup.parse(Path(input), "UTF-8", baseUri, parser)

fun fetchDocument(url: String, parser: Parser = Parser.htmlParser()): Document {
    Log.info("🌐 Fetching $url")
    return runCatching { Jsoup.connect(url).userAgent(USER_AGENT).timeout(45_000).maxBodySize(0).parser(parser).get() }
        .onFailure { Log.error("Failed to fetch $url: ${it.message}") }
        .getOrThrow()
}

/** Parses an Atom feed loaded with Jsoup's XML parser. */
fun Document.atomEntries(): List<FeedEntry> = select("entry").map { entry ->
    fun text(tag: String) = entry.selectFirst(tag)?.text()
    val title = text("title").orEmpty()
    FeedEntry(
        id = text("id")?.takeIf(String::isNotBlank) ?: error("Atom entry has no id: $title"),
        title = title,
        updated = OffsetDateTime.parse(text("updated") ?: error("Atom entry has no updated: $title")).toInstant().toString(),
        sourceUrl = entry.selectFirst("link[rel=alternate]")?.attr("href").orEmpty(),
        contentHtml = entry.selectFirst("content")?.wholeText().orEmpty(),
    )
}

/** Turns the all-channel page into one entry per dated release group. */
fun Document.allChannelEntries(): List<FeedEntry> {
    class ReleaseGroup(val fragment: String) {
        val notes = mutableListOf<ComponentNote>()
    }

    // A date heading owns the following component links until the next date heading.
    val groups = linkedMapOf<LocalDate, ReleaseGroup>()
    var current: ReleaseGroup? = null
    for (element in body().select("$HEADINGS, a[href]")) {
        if (element.normalName() == "a") element.toComponentNote()?.let { current?.notes?.add(it) }
        else element.ownText().ifBlank { element.text() }.toReleaseDateOrNull()?.let { date ->
            current = groups.getOrPut(date) { ReleaseGroup(element.id().ifBlank { date.anchor }) }
        }
    }
    return groups.mapNotNull { (date, group) ->
        val notes = group.notes.distinctBy(ComponentNote::url).ifEmpty { return@mapNotNull null }
        FeedEntry(
            id = "tag:google.com,2016:androidx-release-notes#${group.fragment}",
            title = date.heading,
            updated = date.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
            sourceUrl = "$ALL_CHANNEL_URL#${group.fragment}",
            contentHtml = Element("ul").apply {
                notes.forEach { appendElement("li").appendElement("a").attr("href", it.url).text("${it.name} Version ${it.version}") }
            }.outerHtml(),
        )
    }
}

private val modernComponentLabel = Regex("^(.+?)\\s+Version\\s+(\\S+)$", RegexOption.IGNORE_CASE)
private val legacyComponentLabel = Regex("^(.+?)\\s+(\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?)$")
private val componentVersion = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$")

/** Reads a "Name Version x.y.z" (or legacy "Name x.y.z") link to an AndroidX release page. */
fun Element.toComponentNote(): ComponentNote? {
    val uri = runCatching { URI(absUrl("href")) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.host == "developer.android.com" && "/jetpack/androidx/releases/" in it.path.orEmpty()
    } ?: return null
    val label = text() // Already whitespace-normalized and trimmed.
    val match = modernComponentLabel.matchEntire(label) ?: legacyComponentLabel.matchEntire(label) ?: return null
    val (name, version) = match.destructured
    return ComponentNote(name = name, version = version, url = uri.toString()).takeIf { componentVersion.matches(version) }
}

//endregion

//region Import

/** Imports feed entries into the [archive], with the release notes scraped from each component page. */
class Importer(private val archive: Archive = Archive(), private val scraper: ComponentScraper = ComponentScraper()) {
    fun import(feed: List<FeedEntry>, fromRss: Boolean) {
        val records = archive.load()
        val known = records.associateByCanonicalId()
        val knownComponents = records.flatMap(EntryRecord::components).associateByTo(mutableMapOf(), ComponentRecord::key)
        // Ignored entries stay ignored; RSS refreshes the others whose `updated` value changed since the last RSS import.
        val selected = feed.filter { entry ->
            known[entry.canonicalId].let { it == null || !it.ignored && fromRss && it.rssUpdated != entry.updated }
        }
        Log.info("📰 ${feed.size} feed entries, ${records.size} cached, ${selected.size} new or updated")
        if (selected.isEmpty()) return Log.info("😴 Nothing to import")
        val updates = selected.sortedBy { Instant.parse(it.updated) }.mapIndexed { index, entry ->
            val previous = known[entry.canonicalId]
            Log.info("⏳ [${index + 1}/${selected.size}] ${if (previous == null) "New" else "Updated"}: ${entry.title} (${entry.id})")
            val links = entry.componentNotes()
            check(links.isNotEmpty()) { "No AndroidX component release links found in '${entry.title}' (${entry.id})" }
            Log.info("🔎 Found ${links.size} component releases")
            val record = EntryRecord(
                id = previous?.id ?: entry.id,
                title = entry.title,
                releasedOn = entry.releasedOn,
                updated = entry.updated,
                sourceUrl = entry.sourceUrl,
                components = merge(links.map(scraper::scrape), previous?.components.orEmpty(), knownComponents),
                rssUpdated = if (fromRss) entry.updated else previous?.rssUpdated,
            )
            record.components.associateByTo(knownComponents, ComponentRecord::key)
            record to previous
        }
        // Write only once every entry was fetched and parsed, so a failure leaves data/ untouched.
        updates.forEach { (record, previous) -> archive.save(record, previous) }
        Log.info("✅ Imported ${updates.size} entries")
    }

    /** Merges scraped [notes] with [cached] details so incomplete source pages cannot erase known content. */
    private fun merge(
        notes: List<ComponentNote>,
        cached: List<ComponentRecord>,
        known: Map<String, ComponentRecord>,
    ): List<ComponentRecord> {
        val components = linkedMapOf<String, ComponentRecord>()
        for (note in notes) {
            val hasNewDetails = note.detailsHtml.hasVisibleDetails()
            val exact = cached.firstOrNull { it.version == note.version && it.url == note.url }
            val samePage = if (exact != null) emptyList()
            else cached.filter { it.version == note.version && it.url.pageUrl == note.url.pageUrl }
            val alias = samePage.singleOrNull()
            if (alias != null && note.url.substringAfter('#') == note.version && alias.url.substringAfter('#') != note.version) {
                // The RSS feed can point to an unrelated older release at #version while all-channel
                // identifies the actual artifact at #artifact-version on the same page.
                components[alias.key] = alias.copy(name = note.name)
                continue
            }
            val richestSamePage = if (hasNewDetails) null
            else samePage.maxByOrNull { if (it.detailsHtml.hasVisibleDetails()) it.detailsHtml.length else 0 }
            val id = (exact ?: richestSamePage)?.id ?: note.name.slug()
            val key = componentKey(id, note.version)
            val previous = components[key] ?: cached.firstOrNull { it.key == key } ?: known[key]
            require(previous == null || previous.version == note.version) {
                "Component release path collision for ${note.name} ${note.version} (stored ${previous?.name} ${previous?.version})"
            }
            val hasPreviousDetails = previous != null && previous.detailsHtml.hasVisibleDetails()
            val keepPrevious = !hasNewDetails && (hasPreviousDetails || previous?.commitsUrl != null)
            when {
                hasNewDetails -> Unit
                keepPrevious -> Log.info("♻️ Keeping cached details for ${note.name} ${note.version}")
                else -> Log.warn("No details found for ${note.name} ${note.version}")
            }
            components[key] = ComponentRecord(
                id = id,
                name = note.name,
                version = note.version,
                url = if (keepPrevious) previous.url else note.url,
                detailsHtml = if (keepPrevious && hasPreviousDetails) previous.detailsHtml else note.detailsHtml,
                commitsUrl = note.commitsUrl ?: previous?.commitsUrl,
            )
        }
        return components.values.toList()
    }
}

//endregion

//region Scraping

/** Scrapes component release notes from the library release pages, fetching each page once. */
class ComponentScraper {
    private val pages = mutableMapOf<String, Document>()
    private val releasePageLink = "a[href*='/jetpack/androidx/releases/']"
    private val detailsSafelist = Safelist.relaxed()
        .addTags("details", "summary", "kbd", "samp", "var", "dl", "dt", "dd", "figure", "figcaption")
    private val githubCommitsPath = Regex("^/[^/]+/[^/]+/(?:commits|compare)/[^/]+/?$")
    private val releasedAnnouncement = Regex("(?i)\\b(?:is|are|has been|have been)\\s+released\\b")
    private val commitAnnouncement = Regex("(?i)\\bcontains?\\s+(?:(?:these|the\\s+following)\\s+)?commits\\b")
    private val linkedReferenceLabel = Regex("(?i)^(?:references?|commits?)\\s*:")
    private val transparentContainers = setOf("article", "blockquote", "div", "section")
    private val nestedBlockElements = transparentContainers + setOf("h1", "h2", "h3", "h4", "h5", "h6", "ol", "p", "small", "ul")

    fun scrape(component: ComponentNote): ComponentNote {
        val fragment = URI(component.url).fragment ?: error("Component URL has no release anchor: ${component.url}")
        val (url, target) = locate(component, fragment)
        val header = target?.let { it.closest(HEADINGS) ?: it } ?: run {
            Log.warn("No release section for ${component.name} ${component.version} at $url; details left empty")
            return component.copy(url = url, detailsHtml = "")
        }
        val level = header.headingLevel ?: 3
        val html = header.nextElementSiblings()
            .takeWhile { (it.headingLevel ?: Int.MAX_VALUE) > level }
            .joinToString("", transform = Element::outerHtml)
        if (html.isBlank()) {
            Log.warn("Empty release section for ${component.name} ${component.version} at $url")
            return component.copy(url = url, detailsHtml = "")
        }
        val section = Jsoup.parseBodyFragment(html, url)
        val commitsUrl = section.commitLogUrl()
        section.body().stripLeadingReleaseMetadata()
        // Cleaning also resolves relative links against the page URL.
        val detailsHtml = Jsoup.clean(section.body().html(), url, detailsSafelist).takeIf { it.hasVisibleDetails() }.orEmpty()
        return component.copy(url = url, detailsHtml = detailsHtml, commitsUrl = commitsUrl)
    }

    private fun page(url: String): Document = url.pageUrl.let { pages.getOrPut(it) { fetchDocument(it) } }

    /** Finds the release section on the component page, or on the page it was renamed to. */
    private fun locate(component: ComponentNote, fragment: String): Pair<String, Element?> {
        val pageUrl = component.url.pageUrl
        val document = page(pageUrl)
        document.getElementById(fragment)?.let { return component.url to it }
        if (fragment == component.version) {
            // The RSS feed can link #version while the page prefixes the artifact:
            // #wear-1.3.0-rc01, or the only #…-1.0.1 anchor such as #input-motionprediction-1.0.1.
            val prefixed = document.getElementById("${pageUrl.substringAfterLast('/')}-$fragment")
                ?: document.select("[id$=\"-$fragment\"]").singleOrNull()
            prefixed?.let {
                Log.info("🔀 ${component.name} ${component.version} is at #${it.id()}")
                return "$pageUrl#${it.id()}" to it
            }
        }
        Log.warn("#$fragment not found on $pageUrl; looking for a renamed library page")
        val renamed = document.renamedReleaseUrl(component, fragment) ?: return component.url to null
        Log.info("🔀 ${component.name} ${component.version} moved to $renamed")
        return renamed to page(renamed).getElementById(fragment)
    }

    /**
     * Old feed links can redirect to the AndroidX versions index after a library page is renamed.
     * Resolves the same version from the matching library row on that index.
     */
    private fun Document.renamedReleaseUrl(component: ComponentNote, fragment: String): String? {
        val expectedName = component.name.slug()
        val link = select("tr").firstNotNullOfOrNull { row ->
            row.selectFirst(releasePageLink)?.takeIf {
                val libraryName = it.text().slug()
                fragment in row.text() && (libraryName == expectedName || libraryName.startsWith("$expectedName-"))
            }
        } ?: return null
        val base = URI(link.absUrl("href"))
        return URI(base.scheme, base.authority, base.path, base.query, fragment).toString()
    }

    private fun Document.commitLogUrl(): String? = select("a[href]").firstNotNullOfOrNull { link ->
        val url = link.absUrl("href")
        val uri = runCatching { URI(url) }.getOrNull() ?: return@firstNotNullOfOrNull null
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        val isGitilesLog = host.endsWith(".googlesource.com") && path.substringAfter("/+log/", "").isNotBlank()
        val isGitHubCommits = host == "github.com" && githubCommitsPath.matches(path) &&
            commitAnnouncement.containsMatchIn(link.parent()?.text().orEmpty())
        url.takeIf { uri.scheme == "https" && (isGitilesLog || isGitHubCommits) }
    }

    /** Removes the release date, announcement, and commit-range lines that open a release section. */
    private fun Element.stripLeadingReleaseMetadata() {
        for (child in children()) {
            if (child.headingLevel != null) return
            val isMetadata = child.isReleaseMetadata()
            if (child.normalName() in transparentContainers && child.children().isNotEmpty()) {
                if (isMetadata && child.children().none { it.normalName() in nestedBlockElements }) {
                    child.remove()
                    continue
                }
                child.stripLeadingReleaseMetadata()
                if (child.children().isEmpty() && child.text().isBlank()) {
                    child.remove()
                    continue
                }
                return
            }
            if (!isMetadata) return
            child.remove()
        }
    }

    private fun Element.isReleaseMetadata(): Boolean {
        val text = text()
        // select() includes the element itself, so this also matches a <small> element.
        val isLinkedReference = select("a[href]").isNotEmpty() &&
            (select("small").isNotEmpty() || linkedReferenceLabel.containsMatchIn(text))
        return text.toReleaseDateOrNull() != null || releasedAnnouncement.containsMatchIn(text) ||
            commitAnnouncement.containsMatchIn(text) || isLinkedReference
    }
}

private val headingTag = Regex("h([1-6])")

val Element.headingLevel: Int? get() = headingTag.matchEntire(normalName())?.groupValues?.get(1)?.toInt()

private val sectionTitle = Regex(
    "(?i)^(?:API Changes|Bug Fixes|New Features|Behavior Changes|Dependency Changes|" +
        "Important changes since .+|Known Issues|Other Changes|Documentation Changes):?$",
)

/** Whether this HTML shows more than section titles. */
fun String.hasVisibleDetails(): Boolean {
    val body = Jsoup.parseBodyFragment(this).body()
    if (body.text().isBlank()) return false
    fun Element.isSectionTitle(): Boolean {
        val isTitle = headingLevel != null || normalName() == "p" && children().size == 1 && child(0).normalName() == "strong"
        return isTitle && sectionTitle.matches(text())
    }
    return body.children().let { children -> children.isEmpty() || children.any { !it.isSectionTitle() } }
}

//endregion

//region Storage

val DATA_DIR = "data"

/** The JSON entry cache: one `YYYY-MM-DD[-suffix].json` file per entry. */
class Archive(private val directory: Path = Path(DATA_DIR)) {
    private val entryFile = Regex("^\\d{4}-\\d{2}-\\d{2}(?:-[a-z0-9]+(?:-[a-z0-9]+)*)?\\.json$")
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun load(): List<EntryRecord> =
        if (directory.notExists()) emptyList()
        else directory.listDirectoryEntries("*.json").filter { entryFile.matches(it.name) }.sorted().map(::read)

    /** Saves [record], and removes the file of its [previous] version when the file name changed. */
    fun save(record: EntryRecord, previous: EntryRecord? = null) {
        val path = pathOf(record)
        require(path.notExists() || read(path).id == record.id) { "Entry cache path collision: $path" }
        path.writeAtomically(json.encodeToString(JsonObject.serializer(), record.toJson()) + "\n")
        Log.info("💾 Saved ${record.title} (${record.components.size} components) to $path")
        previous?.let(::pathOf)?.takeIf { it != path }?.let {
            Log.info("🗑️ Removed stale ${it.name}")
            it.deleteIfExists()
        }
    }

    /** Marks every entry on [date] as ignored, and returns how many entries that date holds. */
    fun ignore(date: LocalDate): Int {
        val entries = load().filter { it.releasedOn == date }
        require(entries.isNotEmpty()) { "No stored entries found for $date" }
        entries.filterNot(EntryRecord::ignored).forEach {
            Log.info("🙈 Ignoring ${it.title} (${it.id})")
            save(it.copy(ignored = true))
        }
        return entries.size
    }

    private fun read(path: Path): EntryRecord =
        Json.parseToJsonElement(path.readText()).jsonObject.toEntryRecord().also {
            require(path.name == fileNameOf(it)) { "Entry cache path and contents disagree: $path" }
        }

    private fun pathOf(record: EntryRecord): Path = directory.resolve(fileNameOf(record))

    /** Names the file after the release date, with a suffix when the source anchor is not just that date. */
    private fun fileNameOf(record: EntryRecord): String {
        val dateAnchor = record.releasedOn.anchor.slug()
        val anchor = entryAnchor(record.canonicalId, record.title)
        require(anchor.isNotEmpty()) { "Invalid entry anchor for ${record.id}" }
        val suffix = when {
            anchor == dateAnchor -> ""
            anchor.startsWith("$dateAnchor-") -> anchor.removePrefix(dateAnchor)
            else -> "-${MessageDigest.getInstance("SHA-256").digest(record.id.toByteArray()).toHexString().take(12)}"
        }
        return "${record.releasedOn}$suffix.json"
    }

    private fun EntryRecord.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("releasedOn", releasedOn.toString())
        put("updated", updated)
        put("sourceUrl", sourceUrl)
        put("components", JsonArray(components.map { it.toJson() }))
        if (ignored) put("ignored", true)
        rssUpdated?.let { put("rssUpdated", it) }
    }

    private fun ComponentRecord.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("version", version)
        put("url", url)
        put("detailsHtml", detailsHtml)
        put("commitsUrl", commitsUrl)
    }

    private fun JsonObject.toEntryRecord(): EntryRecord = EntryRecord(
        id = string("id"),
        title = string("title"),
        releasedOn = LocalDate.parse(string("releasedOn")),
        updated = string("updated"),
        sourceUrl = string("sourceUrl"),
        components = getValue("components").jsonArray.map { it.jsonObject.toComponentRecord() },
        ignored = boolOrNull("ignored") ?: false,
        rssUpdated = stringOrNull("rssUpdated")?.takeIf(String::isNotBlank),
    )

    private fun JsonObject.toComponentRecord(): ComponentRecord = ComponentRecord(
        id = string("id"),
        name = string("name"),
        version = string("version"),
        url = string("url"),
        detailsHtml = string("detailsHtml"),
        commitsUrl = stringOrNull("commitsUrl"),
    )
}

//endregion

//region Site

val OUTPUT_DIR = "docs"

/** Renders the index and one page per release date into [docs], next to the hand-written `docs/assets/`. */
class SiteRenderer(private val docs: Path, private val embedded: Boolean) {
    private val siteTitle = "AndroidX Release Notes"
    private val siteUrl = "https://simonmarquis.github.io/androidx-release-notes/"
    private val siteDescription = "Browse AndroidX release notes across libraries and dates in one place."
    private val datedPage = Regex("^\\d{4}-\\d{2}-\\d{2}\\.html$")
    private val assets = mutableMapOf<String, String>()
    private val favicon by lazy {
        if (embedded) "data:image/svg+xml;base64," + Base64.encode(asset("favicon.svg").trim().toByteArray())
        else "assets/favicon.svg"
    }

    fun render(records: List<EntryRecord>) {
        val active = records.filterNot(EntryRecord::ignored)
            .sortedWith(compareByDescending<EntryRecord> { it.releasedOn }.thenByDescending { it.updated })
        active.associateByCanonicalId() // Fails on duplicate entry IDs.
        require(active.distinctBy(EntryRecord::anchor).size == active.size) { "Release entry anchors must be unique" }
        val decorated = active.map { entry ->
            entry.copy(components = entry.components.map { it.copy(detailsHtml = ReferenceLinks.decorate(it.detailsHtml)) })
        }

        Log.info("🏗️ Rendering ${active.size} entries (${records.size - active.size} ignored)")
        docs.createDirectories()
        docs.resolve("index.html").writeAtomically(page(decorated))
        val dated = decorated.groupBy(EntryRecord::releasedOn)
        dated.forEach { (date, entries) -> docs.resolve("$date.html").writeAtomically(page(entries, date)) }
        val datedPages = dated.keys.mapTo(mutableSetOf()) { "$it.html" }
        docs.listDirectoryEntries("*.html")
            .filter { datedPage.matches(it.name) && it.name !in datedPages }
            .forEach {
                Log.info("🗑️ Removing stale page ${it.name}")
                it.deleteExisting()
            }
        Log.info("✅ Rendered index.html and ${dated.size} dated pages in $docs")
    }

    //region Assets

    // Pages load the assets from docs/assets/; embedded pages inline them so a single file works on its own.
    private fun asset(name: String): String = assets.getOrPut(name) { docs.resolve("assets/$name").readText() }

    private fun HEAD.stylesheetAsset(name: String) =
        if (embedded) style { unsafe { +asset(name) } } else link(rel = "stylesheet", href = "assets/$name")

    private fun FlowOrMetaDataOrPhrasingContent.scriptAsset(name: String) =
        if (embedded) script { unsafe { +asset(name) } } else script(src = "assets/$name") {}

    //endregion

    //region Pages

    /** Renders the index when [date] is null, or the page of that date. */
    private fun page(records: List<EntryRecord>, date: LocalDate? = null): String =
        "<!DOCTYPE html>\n" + createHTML(prettyPrint = true).html { pageContent(records, date) }

    private fun HTML.pageContent(records: List<EntryRecord>, date: LocalDate?) {
        val index = date == null
        lang = "en"
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            meta(name = "color-scheme", content = "light dark")
            meta(name = "theme-color", content = "#ffffff") { attributes["media"] = "(prefers-color-scheme: light)" }
            meta(name = "theme-color", content = "#1b1e22") { attributes["media"] = "(prefers-color-scheme: dark)" }
            // Links open in a new tab unless they set target="_self"; this keeps the attribute off every note link.
            base { attributes["target"] = "_blank" }
            title(if (date == null) siteTitle else "${date.heading} · $siteTitle")
            meta(name = "description", content = if (date == null) siteDescription else description(date, records))
            link(rel = "canonical", href = if (date == null) siteUrl else "$siteUrl$date.html")
            link(rel = "icon", type = "image/svg+xml", href = favicon)
            stylesheetAsset("site.css")
            scriptAsset("theme.js")
        }
        body {
            if (index) attributes["data-date-navigation"] = "true"
            siteHeader(index = index)
            main("page-content") {
                div("content-inner") {
                    div("release-groups") {
                        records.forEach { dateGroup(it, index) }
                    }
                }
            }
            scriptAsset("site.js")
        }
    }

    private fun description(date: LocalDate, records: List<EntryRecord>): String {
        val prefix = "AndroidX release notes for ${date.heading}: "
        val components = records.flatMap(EntryRecord::components).map { "${it.name} ${it.version}" }
        val full = prefix + components.joinToString(", ") + "."
        if (full.length <= 160) return full
        val kept = components.runningReduce { acc, it -> "$acc, $it" }.lastOrNull { prefix.length + it.length <= 149 }
        return prefix + (kept ?: components.first()) + ", and more."
    }

    private fun FlowContent.siteHeader(index: Boolean) = header("site-header") {
        div("header-inner") {
            a(href = if (index) "#" else "./", classes = "brand") {
                target = "_self"
                span("brand-name") { +"AndroidX" }
                span("brand-subtitle") { +"Release Notes" }
            }
            div("header-filters") {
                attributes("role" to "group", "aria-label" to "Filter by stability")
                listOf("stable" to "Stable", "rc" to "RC", "beta" to "Beta", "alpha" to "Alpha").forEach { (filter, label) ->
                    button(type = ButtonType.button, classes = "filter-btn $filter") {
                        attributes("data-filter" to filter, "aria-pressed" to "false")
                        +label
                    }
                }
            }
            // Shown while the page is loading; site.js ends loading once the page is parsed.
            if (index) span("loader") { attributes["aria-hidden"] = "true" }
            // CSS expands the field while it is focused or filled; the icon label focuses it.
            div("search-control") {
                label("search-icon") {
                    htmlFor = "release-search"
                    attributes["title"] = "Search"
                }
                input(type = InputType.search, name = "release-search") {
                    id = "release-search"
                    placeholder = "Name, version, date…"
                    attributes("autocomplete" to "off", "aria-label" to "Filter components by name, version or date")
                }
            }
            button(type = ButtonType.button, classes = "theme-toggle") {
                id = "theme-toggle"
                attributes["title"] = "Toggle theme"
            }
            a(href = "https://github.com/SimonMarquis/androidx-release-notes", classes = "github-link") {
                attributes["title"] = "View project on GitHub"
            }
        }
    }

    private fun FlowContent.dateGroup(record: EntryRecord, index: Boolean) = section("date-group") {
        val anchor = record.anchor
        id = anchor
        div("date-divider") {
            if (index) div("date-stepper") {
                attributes("role" to "group", "aria-label" to "Navigate release dates")
                // CSS greys out the steps past the first and last visible dates.
                dateStep("previous", "Previous date (newer)", "P")
                dateStep("next", "Next date (older)", "N")
            } else a("./#$anchor", classes = "date-back") {
                // site.js turns a plain click into history.back() when the previous history entry is the index.
                target = "_self"
                attributes["title"] = "All releases"
            }
            h2("date-title") {
                if (index) a("${record.releasedOn}.html", classes = "date-link") {
                    target = "_self"
                    +record.releasedOn.heading
                } else +record.releasedOn.heading
            }
            div("divider-line")
        }
        record.components.forEach { card(record, it, anchor) }
    }

    private fun FlowContent.dateStep(step: String, label: String, key: String) =
        button(type = ButtonType.button, classes = "date-step") {
            attributes(
                "data-date-step" to step,
                "aria-label" to label,
                "title" to "$label · $key",
                "aria-keyshortcuts" to key,
            )
        }

    private fun FlowContent.card(record: EntryRecord, component: ComponentRecord, anchor: String) {
        val version = component.version.lowercase()
        val kind = listOf("alpha", "beta", "rc").firstOrNull { it in version } ?: "stable"
        article("card") {
            attributes(
                "data-release-kind" to kind,
                "data-search" to "${component.name} ${component.version} ${record.releasedOn} ${record.releasedOn.heading}".lowercase(),
            )
            id = "$anchor-${component.id}-${component.version.slug()}"
            div("head") {
                h3("name") { a(component.url.pageUrl) { +component.name } }
                div("actions") {
                    component.commitsUrl?.let { a(href = it, classes = "commits") { attributes["title"] = "Git commits" } }
                    a(href = component.url, classes = "badge $kind") { +component.version }
                }
            }
            if (component.detailsHtml.isNotBlank()) div("notes") { unsafe { +component.detailsHtml } }
        }
    }

    private fun Tag.attributes(vararg pairs: Pair<String, String>) = attributes.putAll(pairs)

    //endregion
}

//endregion

//region Reference links

/** Turns issue, review, GitHub, and commit references into icon links. */
object ReferenceLinks {
    private val githubReferencePath = Regex("^/[^/]+/[^/]+/(?:issues|pull)/[0-9]+/?$")
    private val githubCommitPath = Regex("^/[^/]+/[^/]+/commit/[0-9a-fA-F]{7,40}/?$")
    // Commit SHAs with at least one digit (717406), so words like "added" never match.
    private val commitShaLabel = Regex("^(?=[a-fA-F]*[0-9])[0-9a-fA-F]{5,40}$")
    // Change-Ids (I1a2b3), commit SHAs and aosp/123.
    private val shortGerritLabel = Regex("^(?:I[0-9a-fA-F]{4,40}|(?=[a-fA-F]*[0-9])[0-9a-fA-F]{5,40}|aosp/[0-9]+)$")
    private val gerritChangeNumberPath = Regex("^/[0-9]+/?$")
    private val corpGerritChangePath = Regex("^/q/I[0-9a-fA-F]{40}/?$")
    private val leakedHtmlComment = Regex("%3C!--|<!--", RegexOption.IGNORE_CASE)
    private val internalIssueUrl = Regex(
        "^https?://(?:b|b\\.corp\\.google\\.com|buganizer\\.corp\\.google\\.com)/(?:issues/)?([0-9]+)/?$",
        RegexOption.IGNORE_CASE,
    )
    private val rawIssueReference = Regex("(?<![A-Za-z0-9_/@])b/([0-9]+)\\b")
    private val literalTextTags = setOf("a", "code", "pre", "kbd", "samp", "var", "script", "style")
    private val openingParenthesis = Regex("\\(\\s*$")
    private val closingParenthesis = Regex("^\\s*\\)")
    private val separatingComma = Regex("^\\s*,\\s*$")

    /** Pages open links in a new tab through `<base target="_blank">`. */
    fun decorate(html: String): String = Jsoup.parseBodyFragment(html).apply {
        outputSettings().prettyPrint(false)
        linkifyRawIssueReferences()
        dropBlockWhitespace()
        val links = select("a[href]")
        links.forEach { it.repairHref() }
        links.forEach { link ->
            val kind = link.referenceKind ?: return@forEach
            link.attr("title", link.label.ifEmpty { link.href }).addClass("ref").addClass(kind)
        }
        unwrapReferenceGroups()
    }.body().html()

    //region Classification

    private val Element.href: String get() = attr("href")

    private val Element.label: String get() = text().ifEmpty { attr("title").trim() }

    private val Element.referenceKind: String?
        get() = when {
            hrefStartsWith("issuetracker.google.com/") -> "ref-issue"
            isGitHubReference() -> "ref-github"
            hrefStartsWith("android-review.googlesource.com/") || isShortGerritReference() -> "ref-gerrit"
            else -> null
        }

    private fun Element.hrefStartsWith(hostPrefix: String): Boolean =
        href.startsWith("https://$hostPrefix") || href.startsWith("http://$hostPrefix")

    private fun Element.hrefUri(): URI? = runCatching { URI(href) }.getOrNull()

    private fun Element.isGitHubReference(): Boolean {
        val uri = hrefUri() ?: return false
        val path = uri.path.orEmpty()
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true) &&
            (githubReferencePath.matches(path) || githubCommitPath.matches(path) && commitShaLabel.matches(label))
    }

    private fun Element.isShortGerritReference(): Boolean {
        if (!shortGerritLabel.matches(label)) return false
        val uri = hrefUri() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        return when {
            host.endsWith(".googlesource.com") -> "/+/" in path || "/+log/" in path
            host == "r.android.com" -> gerritChangeNumberPath.matches(path)
            host == "android-review.git.corp.google.com" -> corpGerritChangePath.matches(path)
            else -> false
        }
    }

    //endregion

    //region Rewrites

    private fun issueUrl(id: String) = "https://issuetracker.google.com/issues/$id"

    /** Fixes reference URLs that the source release notes get wrong, before links are classified. */
    private fun Element.repairHref() {
        // Some source pages leak an HTML comment into the href (".../issues/123<!-- This is a private bug... -->"),
        // percent-encoded or not. Everything from the comment on is not part of the URL.
        val href = leakedHtmlComment.find(href)?.let { href.take(it.range.first) } ?: href
        // Internal bug links (http://b/123, b.corp.google.com, buganizer) are unreachable outside Google; use the public tracker.
        attr("href", internalIssueUrl.matchEntire(href)?.let { issueUrl(it.groupValues[1]) } ?: href)
    }

    /** Links plain-text "b/123" references to the public issue tracker. */
    private fun Document.linkifyRawIssueReferences() {
        for (node in body().allElements.flatMap(Element::textNodes)) {
            if (generateSequence(node.parent()) { it.parent() }.any { it.normalName() in literalTextTags }) continue
            val text = node.wholeText
            val matches = rawIssueReference.findAll(text).toList().ifEmpty { continue }
            var offset = 0
            for (match in matches) {
                if (offset < match.range.first) node.before(TextNode(text.substring(offset, match.range.first)))
                node.before(Element("a").attr("href", issueUrl(match.groupValues[1])).text(match.value))
                offset = match.range.last + 1
            }
            if (offset < text.length) node.before(TextNode(text.substring(offset)))
            node.remove()
        }
    }

    /** Removes whitespace-only text between block elements (indentation left by the sanitizer), outside `<pre>`. */
    private fun Document.dropBlockWhitespace() {
        fun Node?.isBlockOrEdge() = this == null || this is Element && isBlock
        body().allElements
            .filter { it.closest("pre") == null }
            .flatMap(Element::textNodes)
            .filter { it.isBlank && it.previousSibling().isBlockOrEdge() && it.nextSibling().isBlockOrEdge() }
            .forEach(TextNode::remove)
    }

    /** Drops the parentheses around "(link, link)" reference groups, since the links render as icons. */
    private fun Document.unwrapReferenceGroups() {
        for (parent in body().allElements) {
            val nodes = parent.childNodes()
            for ((index, node) in nodes.withIndex()) {
                val opening = node as? TextNode ?: continue
                val openingMatch = openingParenthesis.find(opening.wholeText) ?: continue
                val separators = mutableListOf<TextNode>()
                var links = 0
                for (next in nodes.drop(index + 1)) {
                    when {
                        // Reference links carry the "ref" class by now; the sanitized notes have no classes of their own.
                        next is Element && next.normalName() == "a" && next.hasClass("ref") -> links++
                        next is TextNode && links > 0 && separatingComma.matches(next.wholeText) -> separators += next
                        else -> {
                            if (next is TextNode && links > 0 && separators.size == links - 1 &&
                                closingParenthesis.containsMatchIn(next.wholeText)
                            ) {
                                opening.text(opening.wholeText.removeRange(openingMatch.range))
                                next.text(next.wholeText.replaceFirst(closingParenthesis, ""))
                                separators.forEach { it.text(" ") }
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    //endregion
}

//endregion

//region GitHub releases

/** Manages one `androidx-YYYY-MM-DD` release per date through the GitHub REST API. */
class GitHubReleases(env: Map<String, String> = System.getenv()) {
    private val repository = env["GITHUB_REPOSITORY"] ?: error("GITHUB_REPOSITORY is required")
    private val token = env["GITHUB_TOKEN"] ?: error("GITHUB_TOKEN is required for GitHub release operations")
    private val apiRoot = env["GITHUB_API_URL"] ?: "https://api.github.com"
    private val client = HttpClient.newBuilder().connectTimeout(20.seconds.toJavaDuration()).build()
    private val managedTag = Regex("^androidx-(\\d{4}-\\d{2}-\\d{2})$")

    private class Response(val status: Int, val body: String) {
        fun json() = Json.parseToJsonElement(body)
    }

    /** Lists the managed releases, by date. */
    fun list(): Map<LocalDate, JsonObject> = buildList {
        var page = 1
        do {
            val batch = send("GET", "/releases?per_page=100&page=${page++}").json().jsonArray.map { it.jsonObject }
            addAll(batch)
        } while (batch.size == 100)
    }.mapNotNull { release ->
        managedTag.matchEntire(release.stringOrNull("tag_name").orEmpty())?.let { LocalDate.parse(it.groupValues[1]) to release }
    }.toMap()

    fun find(date: LocalDate): JsonObject? =
        send("GET", "/releases/tags/${tagOf(date)}", expected = setOf(200, 404))
            .takeIf { it.status == 200 }
            ?.json()?.jsonObject

    /** Writes the [release] of a cached date, or deletes it when the date has no cached [entries] anymore. */
    fun reconcile(date: LocalDate, entries: List<EntryRecord>?, release: JsonObject?) {
        when {
            entries != null -> write(date, entries, release)
            release != null -> delete(release)
        }
    }

    private fun write(date: LocalDate, entries: List<EntryRecord>, existing: JsonObject?) {
        val tag = tagOf(date)
        val body = body(date, entries)
        val upToDate = existing != null && existing.stringOrNull("name") == "$date" && existing.stringOrNull("body") == body &&
            existing.boolOrNull("draft") != true && existing.boolOrNull("prerelease") != true
        if (upToDate) return Log.info("⏭️ Release $tag is up to date")
        val components = entries.sumOf { it.components.size }
        Log.info("🚀 ${if (existing != null) "Updating" else "Creating"} release $tag ($components components)")
        val payload = buildJsonObject {
            put("tag_name", tag)
            put("name", "$date")
            put("body", body)
            put("draft", false)
            put("prerelease", false)
        }
        if (existing != null) send("PATCH", "/releases/${existing.id}", payload)
        else send("POST", "/releases", payload, expected = setOf(201))
    }

    private fun delete(release: JsonObject) {
        val tag = release.string("tag_name")
        Log.info("🗑️ Deleting orphaned release $tag")
        send("DELETE", "/releases/${release.id}", expected = setOf(204, 404))
        // GitHub answers 422 when the tag does not exist, e.g. for a draft release.
        send("DELETE", "/git/refs/tags/$tag", expected = setOf(204, 404, 422))
    }

    private fun body(date: LocalDate, entries: List<EntryRecord>): String {
        val (owner, name) = repository.split('/', limit = 2)
        val components = entries.flatMap(EntryRecord::components)
            .distinctBy { it.name to it.version }
            .sortedWith(compareBy({ it.name }, { it.version }))
        return buildString {
            appendLine("### 📖 **[Open the complete release notes digest](https://$owner.github.io/$name/$date.html)**")
            appendLine("---")
            components.forEach { appendLine("- [${it.name} `${it.version}`](${it.url})") }
        }
    }

    private fun tagOf(date: LocalDate) = "androidx-$date"

    private val JsonObject.id: Long get() = getValue("id").jsonPrimitive.long

    private fun send(method: String, path: String, payload: JsonObject? = null, expected: Set<Int> = setOf(200)): Response {
        val request = HttpRequest.newBuilder(URI("$apiRoot/repos/$repository$path"))
            .timeout(30.seconds.toJavaDuration())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (payload != null) header("Content-Type", "application/json; charset=utf-8") }
            .method(method, payload?.let { BodyPublishers.ofString(it.toString()) } ?: BodyPublishers.noBody())
            .build()
        val response = runCatching { client.send(request, BodyHandlers.ofString()) }
            .onFailure { Log.error("GitHub API $method $path failed: ${it.message}") }
            .getOrThrow()
        return Response(response.statusCode(), response.body()).also {
            check(it.status in expected) { "GitHub API $method $path returned HTTP ${it.status}: ${it.body.take(500)}" }
        }
    }
}

//endregion

//region Utilities

object Log {
    fun info(message: String) = println(message)

    fun warn(message: String) = System.err.println("⚠️  $message")

    fun error(message: String) = System.err.println("❌ $message")
}

private val nonSlugCharacters = Regex("[^a-z0-9]+")

fun String.slug(): String = lowercase().replace(nonSlugCharacters, "-").trim('-')

/** This URL without its fragment. */
val String.pageUrl: String get() = substringBefore('#')

private val releaseDateFormatter = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern("MMMM d, uuuu")
    .toFormatter(Locale.US)
private val dateAnchorFormatter = DateTimeFormatter.ofPattern("MMMM_d_yyyy", Locale.US)
private val looseDayYear = Regex("(\\d)\\s*,?\\s*(\\d{4})$")

/** Parses a "Month d, yyyy" release heading, tolerating a missing or unspaced comma. */
fun String.toReleaseDateOrNull(): LocalDate? =
    runCatching { LocalDate.parse(trim().replace(looseDayYear, "$1, $2"), releaseDateFormatter) }.getOrNull()

/** The release heading of this date, e.g. "October 7, 2026". */
val LocalDate.heading: String get() = format(releaseDateFormatter)

/** The all-channel anchor of this date, e.g. "october_7_2026". */
val LocalDate.anchor: String get() = format(dateAnchorFormatter).lowercase(Locale.US)

fun JsonObject.string(key: String): String = stringOrNull(key) ?: error("Missing '$key' in JSON object")

fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

/** Writes through a temporary sibling file, so a failure never leaves a partial file behind. */
fun Path.writeAtomically(content: String) {
    parent.createDirectories()
    val temporary = createTempFile(parent, name, ".tmp")
    try {
        temporary.writeText(content)
        temporary.moveTo(this, REPLACE_EXISTING, ATOMIC_MOVE)
    } finally {
        temporary.deleteIfExists()
    }
}

//endregion

ReleaseNotesCommand().main(args)
