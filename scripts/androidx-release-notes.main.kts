#!/usr/bin/env kotlin

@file:CompilerOptions("-jvm-target", "25", "-opt-in=kotlin.io.path.ExperimentalPathApi")
@file:DependsOn(
    "com.github.ajalt.clikt:clikt-jvm:5.2.1",
    "org.jsoup:jsoup:1.24.1",
    "org.jspecify:jspecify:1.0.1", // jsoup nullability annotations
    "org.jetbrains.kotlinx:kotlinx-html-jvm:0.12.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.13.0",
)

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
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
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

//region Commands

class ReleaseNotesCommand : NoOpCliktCommand(__FILE__.name) {
    override fun help(context: Context) = "Build and maintain the AndroidX release-notes archive."

    init {
        subcommands(SyncCommand(), BuildCommand(), IgnoreCommand(), ReleasesCommand())
    }
}

class SyncCommand : NoOpCliktCommand(name = "sync") {
    override fun help(context: Context) = "Add or update releases from the release pages selected by a source."

    init {
        subcommands(
            ListingSyncCommand("rss", Source.Rss, "Sync the release pages linked by the new or updated RSS feed releases."),
            ListingSyncCommand("all-channel", Source.AllChannel, "Sync the release pages linked by the all-channel page."),
            GroupsSyncCommand(),
        )
    }
}

/** A `sync` source, with the options shared by every source. */
abstract class SyncSourceCommand(name: String) : CliktCommand(name) {
    private val mirror by option(help = "Directory caching fetched documents: read when present, written otherwise.")
    private val allowDestructive by option(help = "Allow removing stale entries, and blanking stored notes or commits.").flag()
    private val strict by option(help = "Fail, without writing, when the sources disagree.").flag()

    protected fun sync(source: Source, input: String? = null) =
        Sync(source, input, Fetcher(mirror?.let { Mirror(Path(it)) }), allowDestructive, strict).run()
}

/** Syncs the release pages linked by a document listing releases. */
class ListingSyncCommand(name: String, private val source: Source, private val description: String) : SyncSourceCommand(name) {
    override fun help(context: Context) = description
    private val input by option(help = "Local file or URL replacing the default `$name` document.")

    override fun run() = sync(source, input)
}

class GroupsSyncCommand : SyncSourceCommand("groups") {
    override fun help(context: Context) = "Sync every version of release pages, whatever the RSS feed or the all-channel page list."
    private val names by argument(name = "GROUP", help = "Group names, comma or space separated (e.g. `core,camera`).").multiple(required = true)

    override fun run() = sync(Source.Groups(names.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter(String::isNotEmpty).distinct()))
}

class BuildCommand : CliktCommand(name = "build") {
    override fun help(context: Context) = "Rebuild all static pages from the stored releases."
    private val embedded by option(help = "Inline CSS, JS, and favicon so each page works on its own.").flag()
    private val minified by option(help = "Use minified CSS and JS (generate the minified assets first).").flag()
    private val output by option(help = "Directory receiving the site.").default(OUTPUT_DIR)

    override fun run() {
        val releases = Archive().load()
        Log.info("📂 Loaded ${releases.size} releases")
        SiteRenderer(Path(STATIC_DIR), Path(output), embedded, minified).render(releases)
    }
}

class IgnoreCommand : CliktCommand(name = "ignore") {
    override fun help(context: Context) = "Hide the stored release of an ISO date and prevent subsequent syncs."
    private val date by argument(help = "ISO date (YYYY-MM-DD).").convert { value ->
        runCatching { LocalDate.parse(value) }.getOrElse { fail("Expected an ISO date (YYYY-MM-DD): $value") }
    }

    override fun run() {
        val release = Archive().ignore(date) ?: throw UsageError("No stored release for $date")
        echo("🙈 Ignored the release of $date (${release.entries.size} entries).")
    }
}

class ReleasesCommand : CliktCommand(name = "releases") {
    override fun help(context: Context) = "Reconcile dated GitHub releases with the stored releases."
    private val commitSha = Regex("^[0-9a-f]{40}$")
    private val changedDataFile = Regex("^$DATA_DIR/(\\d{4}-\\d{2}-\\d{2})\\.json$")
    private val changedFrom by option(help = "Reconcile only JSON dates changed since this commit.")
        .check("expected a full 40-character commit SHA") { commitSha.matches(it) }
    private val dryRun by option(help = "Only read GitHub releases, and log what would be created, updated, or deleted.").flag()

    override fun run() {
        val changedDates = changedFrom?.let { before ->
            // GitHub sends an all-zero SHA when the pushed ref has no previous commit.
            if (before.all { it == '0' }) return echo("⏭️ No previous commit; skipped GitHub release sync.")
            changedReleaseDates(before).also {
                if (it == null) Log.warn("$before is not in the local history; reconciling all releases")
            }
        }
        if (changedDates?.isEmpty() == true) return echo("⏭️ No JSON dates changed; skipped GitHub release sync.")
        val expected = Archive().load().filterNot(Release::ignored).associateBy(Release::date)
        Log.info("📂 ${expected.size} dated releases expected, ${changedDates?.let { "${it.size} changed" } ?: "reconciling all"}")
        if (dryRun) Log.info("🧪 Dry run: nothing is written to GitHub")
        val releases = GitHubReleases(dryRun)
        val (dates, current) = when (changedDates) {
            null -> releases.list().let { existing -> expected.keys + existing.keys to existing::get }
            else -> changedDates to releases::find
        }
        dates.sorted().forEach { releases.reconcile(it, expected[it], current(it)) }
        echo(
            if (dryRun) "🧪 Compared ${dates.size} dated releases, nothing written; would be ${releases.summary}."
            else "✅ Reconciled ${dates.size} dated releases: ${releases.summary}.",
        )
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

sealed interface Source {
    data object Rss : Source
    data object AllChannel : Source
    data class Groups(val names: List<String>) : Source
}

/** A release-notes page, e.g. `core` for https://developer.android.com/jetpack/androidx/releases/core. */
data class Group(val id: String, val name: String, val url: String)

/** A section of a [Group] page releasing one or more maven artifacts together, e.g. `core-performance`. */
data class Artifact(val id: String, val name: String, val anchor: String?)

/** A version section of an [Artifact], with its release date (null when the page omits it) and notes. */
data class VersionSection(
    val group: Group,
    val artifact: Artifact,
    val version: String,
    val date: LocalDate?,
    val heading: String,
    val headingId: String,
    val anchor: String?,
    val html: String,
    val commits: String?,
) {
    val key: EntryKey get() = EntryKey(group.id, artifact.id, version)

    fun toEntry() = Entry(group.id, artifact.id, artifact.name, version, group.url + anchor?.let { "#$it" }.orEmpty(), html, commits)

    override fun toString() = "$key (${date ?: "undated"})"
}

/** A parsed [Group] page. */
data class GroupPage(val group: Group, val sections: List<VersionSection>)

/** A release date listed by the RSS feed or the all-channel page, with its release links. */
data class ListedRelease(val date: LocalDate, val id: String, val updated: Instant?, val links: List<ReleaseLink>) {
    val fragment: String get() = id.fragment
}

/** A "Name Version x.y.z" link to a version section of a release page. */
data class ReleaseLink(val url: String, val name: String, val version: String) {
    val pageUrl: String get() = url.pageUrl
    val fragment: String get() = url.fragment

    /** An entry made of this link alone, when the page has no matching version section. */
    fun toEntry(group: String = pageUrl.substringAfter(RELEASES_URL)) = Entry(group, name.slug(), name, version, url, "", null)
}

/** One artifact version of a [Release]. */
data class Entry(
    val group: String,
    val id: String,
    val name: String,
    val version: String,
    val url: String,
    val html: String,
    val commits: String?,
) {
    val key: EntryKey get() = EntryKey(group, id, version)
}

data class EntryKey(val group: String, val id: String, val version: String) : Comparable<EntryKey> {
    private val sortedVersion = Version(version)

    override fun compareTo(other: EntryKey) = compareValuesBy(this, other, EntryKey::group, EntryKey::id, EntryKey::sortedVersion)

    override fun toString() = "$group/$id $version"
}

/** A dated release note: one `data/YYYY-MM-DD.json` file. */
data class Release(
    val date: LocalDate,
    val id: String,
    val updated: Instant,
    val entries: List<Entry>,
    val ignored: Boolean = false,
    val rssUpdated: Instant? = null,
) {
    val fragment: String get() = id.fragment
    val link: String get() = "$ALL_CHANNEL_URL#$fragment"
    val anchor: String get() = fragment.slug()
}

private val RELEASE_ID_PREFIX = "tag:google.com,2016:androidx-release-notes#"

/** The part after `#` of an id or URL, if any. */
val String.fragment: String get() = substringAfter('#', "")

/** The date an id fragment names, e.g. `november_09_2026` or `april_302019`. */
val String.fragmentDate: LocalDate? get() = fragment.replace('_', ' ').toDateOrNull()

/** The id of a release missing from the sources, following the current all-channel anchors (e.g. `october_07_2026`). */
val LocalDate.releaseId: String get() = RELEASE_ID_PREFIX + anchor

/**
 * A version, ordered like `1.9.0` < `1.10.0-alpha01` < `1.10.0-beta01` < `1.10.0-rc01` < `1.10.0`; unreadable ones last, by text.
 * Qualifier spellings are equivalent: `1.1.0-b1` is `1.1.0-beta01`.
 */
class Version(val text: String) : Comparable<Version> {
    private val parts: Parts? = versionParts.matchEntire(text.lowercase())?.destructured?.let { (release, qualifier, number, rest) ->
        Parts(release.split('.').map(String::toInt), qualifierAliases.getOrDefault(qualifier, qualifier), number.toIntOrNull() ?: 0, rest)
    }

    /** A spelling shared by equivalent versions, e.g. `1.1.0-beta1`. */
    val canonical: String = parts?.toString() ?: text.lowercase()

    override fun compareTo(other: Version) = compareValuesBy(this, other, { it.parts == null }, { it.parts }, Version::text)

    private data class Parts(val release: List<Int>, val qualifier: String, val number: Int, val rest: String) : Comparable<Parts> {
        override fun compareTo(other: Parts) = release.zip(other.release).firstNotNullOfOrNull { (a, b) -> a.compareTo(b).takeIf { it != 0 } }
            ?: compareValuesBy(this, other, { it.release.size }, { qualifiers.indexOf(it.qualifier) }, Parts::number, Parts::rest)

        override fun toString() = release.joinToString(".") + if (qualifier.isEmpty()) rest else "-$qualifier$number$rest"
    }

    private companion object {
        val versionParts = Regex("^(\\d+(?:\\.\\d+)*)(?:-([a-z]*)-?0*(\\d*)(\\S*))?$")
        val qualifierAliases = mapOf("a" to "alpha", "b" to "beta")
        // Unknown qualifiers sort first, and no qualifier (a stable release) last.
        val qualifiers = listOf("dev", "alpha", "beta", "rc", "")
    }
}

/** Whether two versions only differ in their qualifier spelling, e.g. `1.1.0-b1` and `1.1.0-beta01`. */
fun String.sameVersionAs(other: String): Boolean = Version(this).canonical == Version(other).canonical

//endregion

//region Sources

val FEED_URL = "https://developer.android.com/feeds/androidx-release-notes.xml"
val ALL_CHANNEL_URL = "https://developer.android.com/jetpack/androidx/versions/all-channel"
val RELEASES_URL = "https://developer.android.com/jetpack/androidx/releases/"
private val USER_AGENT = "androidx-release-notes-viewer/1.0"
private val HEADINGS = "h1, h2, h3, h4, h5, h6"

/** A directory of fetched documents with their final URLs, to sync again without downloading them. */
class Mirror(private val directory: Path) {
    private val unsafeFileCharacters = Regex("[^A-Za-z0-9._-]+")

    /** The final URL and body of [url], when mirrored. */
    fun read(url: String): Pair<String, String>? = files(url).takeIf { (body, location) -> body.exists() && location.exists() }
        ?.let { (body, location) -> location.readText() to body.readText() }
        ?.also { Log.info("🗄️ Mirrored $url") }

    fun write(url: String, finalUrl: String, body: String) = files(url).let { (bodyFile, locationFile) ->
        bodyFile.writeAtomically(body)
        locationFile.writeAtomically(finalUrl)
    }

    private fun files(url: String): Pair<Path, Path> =
        directory.resolve(url.substringAfter("://").replace(unsafeFileCharacters, "_")).let { it to it.resolveSibling("${it.name}.location") }
}

/** Fetches documents following redirects, optionally through a [mirror]. */
class Fetcher(private val mirror: Mirror?) {
    /** Loads [input], a URL or a local file whose links resolve against [baseUri]. */
    fun load(input: String, baseUri: String, parser: Parser = Parser.htmlParser()): Document =
        if (input.startsWith("https://") || input.startsWith("http://")) fetch(input, parser) ?: error("$input does not exist")
        else Jsoup.parse(Path(input), "UTF-8", baseUri, parser).also { Log.info("📂 Reading $input") }

    /** Returns the document at its final URL ([Document.location]), or null when the page does not exist. */
    fun fetch(url: String, parser: Parser = Parser.htmlParser()): Document? {
        val (finalUrl, body) = mirror?.read(url)
            ?: download(url)?.also { (finalUrl, body) -> mirror?.write(url, finalUrl, body) }
            ?: return null
        if (finalUrl.pageUrl != url.pageUrl) Log.info("↪️ $url redirects to $finalUrl")
        return Jsoup.parse(body, finalUrl, parser)
    }

    private fun download(url: String): Pair<String, String>? {
        Log.info("🌐 Fetching $url")
        for (attempt in 1..3) {
            try {
                val response = Jsoup.connect(url).userAgent(USER_AGENT).timeout(45_000).maxBodySize(0).ignoreContentType(true).execute()
                return response.url().toString() to response.body()
            } catch (e: HttpStatusException) {
                if (e.statusCode in setOf(404, 410)) return null.also { Log.warn("$url does not exist (HTTP ${e.statusCode})") }
                if (attempt == 3) throw e
            } catch (e: IOException) {
                if (attempt == 3) throw e
            }
            Log.warn("Retrying $url ($attempt/3)")
            Thread.sleep(attempt * 2_000L)
        }
        error("Unreachable")
    }
}

/** Parses an Atom feed loaded with Jsoup's XML parser. */
fun Document.rssReleases(): List<ListedRelease> = select("entry").map { entry ->
    fun text(tag: String) = entry.selectFirst(tag)?.text()?.takeIf(String::isNotBlank) ?: error("RSS entry without <$tag>: ${entry.text().take(200)}")
    val title = text("title")
    val id = text("id")
    val date = title.toDateOrNull() ?: error("Unreadable RSS entry date '$title' ($id)")
    val fragment = id.fragment
    id.fragmentDate?.takeIf { it != date }?.let { Log.issue(Issue.Link, "RSS '$title' has the id #$fragment of $it") }
    val link = entry.selectFirst("link[rel=alternate]")?.attr("href").orEmpty()
    if (link.fragment != fragment) Log.issue(Issue.Link, "RSS '$title' (#$fragment) links to $link")
    val content = Jsoup.parseBodyFragment(entry.selectFirst("content")?.wholeText().orEmpty(), FEED_URL)
    ListedRelease(date, id, OffsetDateTime.parse(text("updated")).toInstant(), content.releaseLinks("RSS '$title'"))
}

/** Turns the all-channel page into one listed release per date heading. */
fun Document.allChannelReleases(): List<ListedRelease> {
    val section = selectFirst("section.releases") ?: error("No releases section on ${location()}")
    val dateAnchor = Regex("^[a-z]+_\\d")
    val releases = mutableListOf<ListedRelease>()
    var current: ListedRelease? = null
    // A date heading owns the following release links until the next heading.
    for (element in section.select("$HEADINGS, a[href]")) {
        if (element.headingLevel == null) {
            current = current?.let { it.copy(links = it.links + element.releaseLinks("all-channel #${it.fragment}")) }
            continue
        }
        current?.let(releases::add)
        val date = element.text().toDateOrNull()
        check(date != null || !dateAnchor.containsMatchIn(element.id())) { "Unreadable all-channel date '${element.text()}' (#${element.id()})" }
        val id = RELEASE_ID_PREFIX + element.id()
        id.fragmentDate?.takeIf { date != null && it != date }?.let { Log.issue(Issue.Link, "all-channel '${element.text()}' has the anchor #${id.fragment} of $it") }
        current = date?.let { ListedRelease(it, id.takeIf { element.id().isNotBlank() } ?: error("No anchor for '${element.text()}'"), null, emptyList()) }
    }
    current?.let(releases::add)
    return releases
}

private val releaseLabel = Regex("^(.+?)\\s+(?:Version\\s+)?v?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)$", RegexOption.IGNORE_CASE)

/** Reads the "Name Version x.y.z" (or legacy "Name x.y.z") links to release pages. */
fun Element.releaseLinks(context: String): List<ReleaseLink> = select("a[href]").mapNotNull { link ->
    val url = link.absUrl("href").takeIf { it.isReleasePageUrl } ?: return@mapNotNull null
    val match = releaseLabel.matchEntire(link.text())
        ?: return@mapNotNull null.also { Log.issue(Issue.Label, "$context: unreadable release link '${link.text()}' to $url") }
    val (name, version) = match.destructured
    ReleaseLink(url, name, version)
}

val String.isReleasePageUrl: Boolean get() = startsWith(RELEASES_URL) && pageUrl.length > RELEASES_URL.length

/** Merges the releases listed twice for a day (e.g. `february_25_2026` and `february_25_2026_2`). */
fun List<ListedRelease>.sameDaysMerged(): List<ListedRelease> = groupBy(ListedRelease::date).map { (date, listed) ->
    if (listed.size > 1) Log.info("🧬 Merging ${listed.joinToString { "#${it.fragment}" }} into $date")
    listed.minBy { it.fragment.length }.copy(
        updated = listed.mapNotNull(ListedRelease::updated).maxOrNull(),
        links = listed.flatMap(ListedRelease::links).distinct(),
    )
}

//endregion

//region Group pages

/** Parses every artifact and version section of a release page. */
class GroupPageParser {
    private val versionHeading = Regex("(?:^|\\s)v?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?)$")
    private val notesHeading = Regex("(?i)\\bsince\\b")
    private val versionSuffix = Regex("[\\s,]*(?:\\bVersion\\s+)?v?\\d+(?:\\.\\d+)+(?:-[0-9A-Za-z.-]+)?\\.?$", RegexOption.IGNORE_CASE)
    private val notesSafelist = Safelist.relaxed()
        .addTags("details", "summary", "kbd", "samp", "var", "dl", "dt", "dd", "figure", "figcaption")
    private val githubCommitsPath = Regex("^/[^/]+/[^/]+/(?:commits|compare)/[^/]+/?$")
    private val releasedAnnouncement = Regex("(?i)\\b(?:is|are|has been|have been)\\s+released\\b")
    private val commitAnnouncement = Regex("(?i)\\bcontains?\\s+(?:(?:these|the\\s+following)\\s+)?commits\\b")
    private val linkedReferenceLabel = Regex("(?i)^(?:references?|commits?)\\s*:")
    private val transparentContainers = setOf("article", "blockquote", "div", "section")
    private val nestedBlockElements = transparentContainers + setOf("h1", "h2", "h3", "h4", "h5", "h6", "ol", "p", "small", "ul")

    /** Returns null when [document] is not a release page, e.g. a removed page redirecting to the versions index. */
    fun parse(document: Document): GroupPage? {
        val url = document.location().pageUrl
        val id = url.substringAfter(RELEASES_URL, "").ifEmpty { return null }
        fun fail(message: String): Nothing = error("$url: $message")
        val body = document.selectFirst(".devsite-article-body") ?: fail("no article body")
        val name = document.selectFirst("h1.devsite-page-title")?.text()?.ifBlank { null } ?: fail("no page title")
        val group = Group(id, name, url)
        val page = Page(group, body)
        val sections = mutableListOf<VersionSection>()
        val enclosing = ArrayDeque<Heading>()
        var current: Heading? = null
        for ((index, heading) in page.headings.withIndex()) {
            // Undated headings inside a version section ("androidx.recyclerview 1.1.0-alpha01") are part of its notes.
            if (heading.date == null && current != null && (heading.level > current.level || heading.version == current.version)) continue
            current = null
            // Release notes follow the "Feedback" section: no heading before it names an artifact ("Declaring dependencies"…).
            if (heading.text == "Feedback") {
                enclosing.clear()
                continue
            }
            while (enclosing.lastOrNull()?.let { it.level >= heading.level } == true) enclosing.removeLast()
            // A version heading followed by deeper version headings groups them, e.g. "Version 1.19".
            val groupsVersions = page.headings.getOrNull(index + 1)?.let { it.level > heading.level && it.version != null } == true
            when {
                heading.version == null || heading.date == null && groupsVersions -> enclosing.addLast(heading)
                else -> {
                    if (heading.date == null) Log.issue(Issue.Undated, "$id#${heading.id} '${heading.text}' has no release date")
                    current = heading
                    sections += page.section(heading, artifactOf(group, enclosing))
                }
            }
        }
        return GroupPage(group, sections.deduplicated(group)).also { it.log() }
    }

    /** A heading, with its text, version, and release date read once. */
    private inner class Heading(val element: Element) {
        val level: Int = element.headingLevel ?: error("Not a heading: ${element.normalName()}")
        val text: String = element.text()
        val id: String = element.id()
        val version: String? = text.takeUnless { notesHeading.containsMatchIn(it) }?.let { versionHeading.find(it)?.groupValues?.get(1) }

        /** The date of the paragraph right after a version heading, e.g. "July 2, 2025". */
        val date: LocalDate? = version?.let { element.nextElementSibling()?.takeIf { it.normalName() == "p" }?.text()?.leadingDateOrNull() }
    }

    /** The headings and ids of a page, indexed once. */
    private inner class Page(val group: Group, val body: Element) {
        val headings: List<Heading> = body.select(HEADINGS).map(::Heading)
        private val dated: Set<Element> = headings.filter { it.date != null }.mapTo(mutableSetOf(), Heading::element)
        private val ids: Map<String, Int> = body.select("[id]").groupingBy(Element::id).eachCount()

        fun section(heading: Heading, artifact: Artifact): VersionSection {
            val notes = heading.element.nextElementSiblings()
                .takeWhile { next -> next.headingLevel.let { it == null || it > heading.level && next !in dated } }
            val (html, commits) = notes(notes)
            return VersionSection(group, artifact, heading.version!!, heading.date, heading.text, heading.id, anchor(heading, artifact), html, commits)
        }

        /** The heading id, or the artifact heading id when the heading has none, or a previous heading already uses it. */
        private fun anchor(heading: Heading, artifact: Artifact): String? {
            val id = heading.id.ifEmpty { null }
            if (id != null && (ids[id] == 1 || body.getElementById(id) === heading.element)) return id
            val fallback = artifact.anchor?.takeIf { ids[it] == 1 } ?: return id
            Log.info("⚓ ${group.id}: ${artifact.name} ${heading.version} links to #$fallback, ${id?.let { "#$it is not unique" } ?: "its heading has no id"}")
            return fallback
        }

        private fun notes(elements: List<Element>): Pair<String, String?> {
            val section = Jsoup.parseBodyFragment(elements.joinToString("", transform = Element::outerHtml), group.url)
            val commits = section.commitLogUrl()
            section.body().stripLeadingReleaseMetadata()
            // Cleaning also resolves relative links against the page URL.
            val html = Jsoup.clean(section.body().html(), group.url, notesSafelist).takeIf { it.hasVisibleDetails() }.orEmpty()
            return html to commits
        }
    }

    /** The innermost enclosing heading naming an artifact, or the group itself. */
    private fun artifactOf(group: Group, enclosing: List<Heading>): Artifact = enclosing.asReversed().firstNotNullOfOrNull { heading ->
        heading.text.replace(versionSuffix, "").trim()
            .takeIf { heading.level > 1 && it.isNotEmpty() }
            ?.let { Artifact(it.slug(), it, heading.id.ifEmpty { null }) }
    } ?: Artifact(group.id, group.name, null)

    /**
     * Splits the versions of an artifact that only their headings tell apart (e.g. "Camera-Core Version 1.0.0-beta11"),
     * then keeps one section per artifact version and date, the one with the richest notes.
     */
    private fun List<VersionSection>.deduplicated(group: Group): List<VersionSection> {
        val split = mutableListOf<VersionSection>()
        val sections = groupBy(VersionSection::key).flatMap { (_, same) ->
            val names = same.map { it.ownName }
            if (names.distinct().size == 1) same else same.zip(names) { section, name -> section.splitAs(name)?.also(split::add) ?: section }
        }
        if (split.isNotEmpty()) Log.info("✂️ ${group.id}: ${split.size} versions split by their heading (${split.map { it.artifact.name }.distinct().joinToString()})")
        return sections.groupBy { it.key to it.date }.map { (key, same) ->
            if (same.size > 1) Log.issue(Issue.Duplicate, "${group.id}: ${key.first} is listed ${same.size} times on ${key.second ?: "no date"}")
            same.maxBy { it.html.length }
        }.also { deduplicated ->
            deduplicated.filter { it.date != null }.groupBy(VersionSection::key).filterValues { it.size > 1 }.forEach { (key, same) ->
                Log.issue(Issue.Duplicate, "$key is released on ${same.joinToString { "${it.date}" }}")
            }
        }
    }

    /** The artifact name in the version heading ("Camera-Core Version 1.0.0") or anchor (`#camera-core-1.0.0`), if any. */
    private val VersionSection.ownName: String
        get() = heading.replace(versionSuffix, "").trim()
            .ifEmpty { headingId.takeIf { it.endsWith(version) }?.removeSuffix(version)?.trimEnd('-', '_').orEmpty() }

    /** This section as its own artifact [name], or null when it already is. */
    private fun VersionSection.splitAs(name: String): VersionSection? {
        val id = name.slug().let { if (it.isEmpty() || it.startsWith(group.id)) it else "${group.id}-$it" }
        if (id.isEmpty() || id == artifact.id || name.slug() == artifact.id) return null
        return copy(artifact = artifact.copy(id = id, name = name))
    }

    private fun GroupPage.log() {
        val artifacts = sections.map(VersionSection::artifact).distinctBy(Artifact::id)
        if (sections.isEmpty()) return Log.info("📄 ${group.id} · ${group.name}: no version sections")
        Log.info("📄 ${group.id} · ${group.name}: ${sections.size} versions in ${artifacts.size} artifacts (${artifacts.joinToString { it.name }})")
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
        return text.leadingDateOrNull() != null || releasedAnnouncement.containsMatchIn(text) ||
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

//region Sync

/**
 * One sync run, adding or updating the stored releases from the release pages.
 * The pages are the source of truth; the RSS feed and the all-channel page select the pages to sync entirely, and are cross-checked.
 */
class Sync(
    private val source: Source,
    private val input: String?,
    private val fetcher: Fetcher,
    private val allowDestructive: Boolean,
    private val strict: Boolean,
    private val archive: Archive = Archive(),
) {
    private val pages = Pages(fetcher)
    private val observed = sortedMapOf<LocalDate, MutableMap<EntryKey, Entry>>()
    private val stored = archive.load().associateBy(Release::date).also { Log.info("📂 Loaded ${it.size} releases") }
    private val storedVersions by lazy { stored.values.flatMap(Release::entries).mapTo(mutableSetOf()) { it.group to Version(it.version).canonical } }

    fun run() {
        val listed = select()
        if (listed?.isEmpty() == true) {
            Log.info("😴 Nothing to sync")
            return write(emptyList())
        }
        val pages = fetch(listed)
        collect(pages)
        listed?.forEach(::crossCheck)
        write(merge(pages, listed.orEmpty()))
    }

    //region Steps

    /** The listed releases to sync, or null to sync whole groups. */
    private fun select(): List<ListedRelease>? = when (source) {
        Source.Rss -> fetcher.load(input ?: FEED_URL, FEED_URL, Parser.xmlParser()).rssReleases()
        Source.AllChannel -> fetcher.load(input ?: ALL_CHANNEL_URL, ALL_CHANNEL_URL).allChannelReleases()
        is Source.Groups -> null
    }?.sameDaysMerged()?.selected()

    private fun fetch(listed: List<ListedRelease>?): List<GroupPage> = when (source) {
        is Source.Groups -> source.names.map { pages[RELEASES_URL + it] ?: throw CliktError("Unknown group '$it'") }
        else -> listed.orEmpty().flatMap(ListedRelease::links).map(ReleaseLink::pageUrl).distinct().mapNotNull(pages::get)
    }.distinctBy { it.group.id }

    /** Observes every dated version of the [pages], so unlisted artifacts and versions are discovered too. */
    private fun collect(pages: List<GroupPage>) {
        Log.info("🔎 Collecting every version of ${pages.size} release pages")
        pages.flatMap(GroupPage::sections).forEach { section -> section.date?.let { observe(it, section.toEntry()) } }
    }

    private fun crossCheck(release: ListedRelease) {
        release.links.forEach { resolve(release, it) }
        reportUnlisted(release)
    }

    /** Merges the observed entries into the stored releases; returns the changed ones. */
    private fun merge(pages: List<GroupPage>, listed: List<ListedRelease>): List<Release> {
        val scope = Scope(pages)
        val listedByDate = listed.associateBy(ListedRelease::date)
        // Stored releases holding entries of the fetched pages are merged too, to find their stale entries.
        val dates = observed.keys + listedByDate.keys + stored.filterValues { !it.ignored && it.entries.any(scope::isExpected) }.keys
        return dates.sorted().mapNotNull { merge(it, stored[it], listedByDate[it], scope) }
            .also { Log.info("🧮 Merged ${dates.size} releases: ${it.size} changed") }
    }

    private fun write(changes: List<Release>) {
        val issues = Log.summary()
        if (strict && issues > 0) throw CliktError("$issues source inconsistencies with --strict; nothing written")
        changes.forEach { if (it.entries.isEmpty()) archive.delete(it.date) else archive.save(it) }
        Log.info("✅ ${changes.size} releases written")
    }

    //endregion

    //region Listings

    /** Keeps the listed releases that are not ignored, nor up to date with the RSS feed. */
    private fun List<ListedRelease>.selected(): List<ListedRelease> {
        val (ignored, active) = partition { stored[it.date]?.ignored == true }
        val (upToDate, selected) = active.partition { it.isUpToDate() }
        ignored.forEach { Log.info("🙈 ${it.date} is ignored") }
        Log.info("📰 $size listed releases: ${selected.size} to sync, ${upToDate.size} up to date, ${ignored.size} ignored")
        selected.filter { it.updated != null }.forEach { Log.info("⏳ ${it.date} (#${it.fragment}, ${it.links.size} links, updated ${it.updated})") }
        return selected
    }

    /**
     * Whether the stored release has the same RSS `updated` value.
     * A listed release without a file of its own (its versions are dated otherwise on their pages) is up to date once all its versions are stored.
     */
    private fun ListedRelease.isUpToDate(): Boolean = updated != null && when (val release = stored[date]) {
        null -> links.all { it.isStored() }
        else -> release.rssUpdated == updated
    }

    private fun ReleaseLink.isStored(): Boolean {
        val version = Version(version).canonical
        // Fetches the page only when needed, to resolve aliases like `camerax` for `camera`.
        return (pageUrl.substringAfter(RELEASES_URL) to version) in storedVersions ||
            pages[pageUrl]?.let { (it.group.id to version) in storedVersions } == true
    }

    /**
     * Finds the version section behind a listed link, whatever its anchor; the page date wins over the listed one.
     * Looks for the same version: on the listed date, on a more specific page that day, at the linked anchor, a few days apart, or undated.
     */
    private fun resolve(release: ListedRelease, link: ReleaseLink) {
        val label = "${release.date} ${link.name} ${link.version}"
        val page = pages[link.pageUrl] ?: return observe(release.date, link.toEntry()).also {
            Log.issue(Issue.Gone, "$label: ${link.pageUrl} has no release notes; keeping the link only")
        }
        val sameVersion = page.sections.filter { it.version.sameVersionAs(link.version) }
        sameVersion.filter { it.date == release.date }.ifEmpty { null }?.let { sameDay ->
            if (sameDay.none { it.headingId == link.fragment }) Log.info("🔀 $label: #${link.fragment} is ${sameDay.joinToString { "#${it.anchor}" }}")
            return
        }
        // Sections can move to a more specific page, e.g. Wear Compose Material3 from wear-compose to wear-compose-m3.
        pages.parsed.filter { it.group.id.startsWith("${page.group.id}-") }
            .flatMap(GroupPage::sections).filter { it.date == release.date && it.version.sameVersionAs(link.version) }
            .ifEmpty { null }?.let { moved ->
                Log.info("🔀 $label: #${link.fragment} is ${moved.joinToString { "${it.group.id}#${it.anchor}" }}")
                // The page may only have been parsed to check the feed, without its versions being collected.
                return moved.forEach { observe(release.date, it.toEntry()) }
            }
        val section = sameVersion.firstOrNull { it.headingId == link.fragment }
            ?: sameVersion.filter { it.date != null && abs(ChronoUnit.DAYS.between(it.date, release.date)) <= 31 }
                .minByOrNull { abs(ChronoUnit.DAYS.between(it.date, release.date)) }
            ?: sameVersion.firstOrNull { it.date == null }
        when {
            section?.date != null -> {
                Log.issue(Issue.Date, "$label is dated ${section.date} on ${page.group.id} (#${section.anchor})")
                observe(section.date, section.toEntry())
            }
            section != null -> {
                Log.issue(Issue.Undated, "$label: ${page.group.id}#${section.headingId} has no release date; using the listed one")
                observe(release.date, section.toEntry())
            }
            else -> {
                Log.issue(Issue.Missing, "$label is missing from ${page.group.url}; keeping the link only")
                observe(release.date, link.toEntry(page.group.id))
            }
        }
    }

    /** Logs the versions released on a listed date by a listed page, without their own link. */
    private fun reportUnlisted(release: ListedRelease) = release.links.groupBy({ pages[it.pageUrl] }, ReleaseLink::version).forEach { (page, versions) ->
        page?.sections.orEmpty()
            .filter { section -> section.date == release.date && versions.none { section.version.sameVersionAs(it) } }
            .forEach { Log.info("🧩 ${release.date}: ${it.key} is not listed") }
    }

    //endregion

    //region Merge

    /** Which stored entries the fetched pages should show again. */
    private class Scope(pages: List<GroupPage>) {
        private val groups = pages.mapTo(mutableSetOf()) { it.group.id }
        private val sections = pages.flatMap(GroupPage::sections)
        private val datedUrls = sections.filter { it.date != null }.mapTo(mutableSetOf()) { it.toEntry().url }
        private val undatedUrls = sections.filter { it.date == null }.mapTo(mutableSetOf()) { it.toEntry().url }

        /** An entry made of a listed link alone, as its fetched page shows no such section. */
        fun isLinkOnly(entry: Entry) = entry.group in groups && entry.html.isBlank() && entry.commits == null &&
            entry.url !in datedUrls && entry.url !in undatedUrls

        /** An entry dated by its listing: a link alone, or an undated version section. */
        fun isListed(entry: Entry) = isLinkOnly(entry) || entry.url in undatedUrls

        fun isExpected(entry: Entry) = entry.group in groups && !isListed(entry)
    }

    /** Merges the observed entries of [date] into its [previous] release; returns null when nothing changed. */
    private fun merge(date: LocalDate, previous: Release?, listed: ListedRelease?, scope: Scope): Release? {
        if (previous?.ignored == true) return null.also { Log.info("🙈 Leaving the ignored release $date untouched") }
        val found = observed[date].orEmpty()
        if (previous == null && found.isEmpty()) return null.also { Log.info("📭 $date has no version section") }
        val entries = previous?.entries.orEmpty().associateByTo(linkedMapOf(), Entry::key)
        found.values.forEach { entry ->
            entries[entry.key] = entries[entry.key]?.let { merged(date, it, entry) } ?: entry.also {
                if (previous != null) Log.info("➕ $date ${it.key}")
            }
        }
        entries.replaceLinkOnly(date, previous, scope)
        entries.dropStale(date, found.keys, scope)
        val id = idOf(date, previous, listed)
        val sortedEntries = entries.values.sortedBy(Entry::key)
        val changed = previous == null || previous.id != id || previous.entries != sortedEntries
        val release = Release(
            date = date,
            id = id,
            updated = if (changed) Instant.now() else previous!!.updated,
            entries = sortedEntries,
            rssUpdated = listed?.updated ?: previous?.rssUpdated,
        )
        if (release == previous) return null
        Log.info("${if (previous == null) "🆕" else "✏️"} $date: ${release.entries.size} entries")
        return release
    }

    /** Removes the links alone whose version section their page (or a more specific one) now shows. */
    private fun MutableMap<EntryKey, Entry>.replaceLinkOnly(date: LocalDate, previous: Release?, scope: Scope) {
        values.filter { linkOnly -> scope.isLinkOnly(linkOnly) && values.any { it.replaces(linkOnly, scope) } }.forEach {
            remove(it.key)
            if (previous?.entries?.contains(it) == true) Log.info("🔁 $date ${it.key} is now on its page")
        }
    }

    /** Whether this version section replaces a [linkOnly] entry: same version, on its page or a more specific one. */
    private fun Entry.replaces(linkOnly: Entry, scope: Scope) = this != linkOnly && !scope.isListed(this) &&
        (group == linkOnly.group || group.startsWith("${linkOnly.group}-")) && version.sameVersionAs(linkOnly.version)

    /** Reports, or removes with [allowDestructive], the entries the fetched pages no longer show. */
    private fun MutableMap<EntryKey, Entry>.dropStale(date: LocalDate, found: Set<EntryKey>, scope: Scope) {
        values.filter { it.key !in found && scope.isExpected(it) }.forEach {
            if (!allowDestructive) return@forEach Log.issue(Issue.Stale, "$date ${it.key} was not found again; keeping it (--allow-destructive removes it)")
            remove(it.key)
            Log.info("➖ $date ${it.key}")
        }
    }

    /**
     * Keeps the stored id, so the feed and the all-channel page cannot take turns; a listed id replaces a synthesized one.
     * Ignores the listed ids naming another date, so release anchors stay unique.
     */
    private fun idOf(date: LocalDate, previous: Release?, listed: ListedRelease?): String {
        val listedId = listed?.id?.takeIf { it.fragmentDate.let { named -> named == null || named == date } }
        val storedId = previous?.id?.takeUnless { it == date.releaseId }
        if (storedId != null && listedId != null && storedId != listedId) {
            Log.issue(Issue.Link, "$date is listed as #${listedId.fragment}; keeping #${storedId.fragment}")
        }
        return storedId ?: listedId ?: date.releaseId
    }

    /** Updates [old] with [new], keeping the stored notes and commits that the page no longer shows. */
    private fun merged(date: LocalDate, old: Entry, new: Entry): Entry {
        val keepHtml = !allowDestructive && new.html.isBlank() && old.html.isNotBlank()
        val keepCommits = !allowDestructive && new.commits == null && old.commits != null
        if (keepHtml || keepCommits) {
            val kept = listOfNotNull("notes".takeIf { keepHtml }, "commits".takeIf { keepCommits }).joinToString(" and ")
            Log.issue(Issue.Blank, "$date ${new.key}: no $kept found; keeping the stored ones (--allow-destructive blanks them)")
        }
        val entry = new.copy(html = if (keepHtml) old.html else new.html, commits = if (keepCommits) old.commits else new.commits)
        val changed = mapOf<String, (Entry) -> Any?>("name" to Entry::name, "url" to Entry::url, "html" to Entry::html, "commits" to Entry::commits)
            .filterValues { it(old) != it(entry) }.keys
        if (changed.isNotEmpty()) Log.info("🔄 $date ${entry.key}: ${changed.joinToString()}")
        return entry
    }

    //endregion

    private fun observe(date: LocalDate, entry: Entry) {
        observed.getOrPut(date, ::linkedMapOf).putIfAbsent(entry.key, entry)
    }
}

/** Parses each release page once, whatever URL alias leads to it. */
class Pages(private val fetcher: Fetcher) {
    private val parser = GroupPageParser()
    private val byUrl = mutableMapOf<String, GroupPage?>()
    private val byLocation = mutableMapOf<String, GroupPage?>()

    /** Every page parsed so far. */
    val parsed: List<GroupPage> get() = byLocation.values.filterNotNull()

    operator fun get(url: String): GroupPage? = url.pageUrl.let { key -> if (key in byUrl) byUrl[key] else load(key).also { byUrl[key] = it } }

    private fun load(url: String): GroupPage? {
        val document = fetcher.fetch(url) ?: return null
        val location = document.location().pageUrl
        return if (location in byLocation) byLocation[location] else parser.parse(document).also { byLocation[location] = it }
    }
}

//endregion

//region Storage

val DATA_DIR = "data"

/** The JSON release store: one `YYYY-MM-DD.json` file per release. */
class Archive(private val directory: Path = Path(DATA_DIR)) {
    private val releaseFile = Regex("^\\d{4}-\\d{2}-\\d{2}\\.json$")
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun load(): List<Release> = if (directory.notExists()) emptyList() else directory.listDirectoryEntries("*.json").sorted().map {
        require(releaseFile.matches(it.name)) { "Unexpected data file: $it" }
        read(it)
    }

    fun save(release: Release) {
        val path = pathOf(release.date)
        path.writeAtomically(json.encodeToString(JsonObject.serializer(), release.toJson()) + "\n")
        Log.info("💾 Saved ${release.entries.size} entries to $path")
    }

    fun delete(date: LocalDate) {
        pathOf(date).deleteIfExists()
        Log.info("🗑️ Removed the empty release $date")
    }

    /** Marks the release of [date] as ignored; returns null when there is none. */
    fun ignore(date: LocalDate): Release? =
        pathOf(date).takeIf { it.exists() }?.let(::read)?.also { if (!it.ignored) save(it.copy(ignored = true)) }

    private fun pathOf(date: LocalDate): Path = directory.resolve("$date.json")

    private fun read(path: Path): Release = Json.parseToJsonElement(path.readText()).jsonObject.toRelease().also {
        require(path.name == "${it.date}.json") { "Data file name and date disagree: $path" }
    }

    private fun Release.toJson(): JsonObject = buildJsonObject {
        put("date", date.toString())
        put("id", id)
        put("link", link)
        put("updated", updated.toString())
        rssUpdated?.let { put("rssUpdated", it.toString()) }
        if (ignored) put("ignored", true)
        put("entries", JsonArray(entries.sortedBy(Entry::key).map { it.toJson() }))
    }

    private fun Entry.toJson(): JsonObject = buildJsonObject {
        put("group", group)
        put("id", id)
        put("name", name)
        put("version", version)
        put("url", url)
        put("html", html)
        put("commits", commits)
    }

    private fun JsonObject.toRelease(): Release = Release(
        date = LocalDate.parse(string("date")),
        id = string("id"),
        updated = Instant.parse(string("updated")),
        rssUpdated = stringOrNull("rssUpdated")?.let(Instant::parse),
        entries = getValue("entries").jsonArray.map { it.jsonObject.toEntry() },
        ignored = boolOrNull("ignored") ?: false,
    )

    private fun JsonObject.toEntry(): Entry = Entry(
        group = string("group"),
        id = string("id"),
        name = string("name"),
        version = string("version"),
        url = string("url"),
        html = string("html"),
        commits = stringOrNull("commits"),
    )
}

//endregion

//region Site

val STATIC_DIR = "static"
val OUTPUT_DIR = "site"

/** Renders the index and one page per release date into [output], along with a copy of the hand-written [static] files. */
class SiteRenderer(private val static: Path, private val output: Path, private val embedded: Boolean, private val minified: Boolean) {
    private val siteTitle = "AndroidX Release Notes"
    private val siteUrl = "https://simonmarquis.github.io/androidx-release-notes/"
    private val siteDescription = "Browse AndroidX release notes across libraries and dates in one place."
    private val datedPage = Regex("^\\d{4}-\\d{2}-\\d{2}\\.html$")
    private val assets = mutableMapOf<String, String>()
    private val favicon by lazy {
        if (embedded) "data:image/svg+xml;base64," + Base64.encode(asset("favicon.svg").trim().toByteArray())
        else "assets/favicon.svg"
    }

    fun render(releases: List<Release>) {
        val active = releases.filterNot(Release::ignored)
            .sortedWith(compareByDescending<Release> { it.date }.thenByDescending { it.updated })
        require(active.distinctBy(Release::anchor).size == active.size) { "Release anchors must be unique" }
        // Artifacts are listed by name, ignoring the case; the stable sort keeps the stored order of equal names.
        val decorated = active.map { release ->
            val entries = release.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Entry::name))
            release.copy(entries = entries.map { it.copy(html = ReferenceLinks.decorate(it.html)) })
        }

        Log.info("🏗️ Rendering ${active.size} releases (${releases.size - active.size} ignored)")
        output.createDirectories()
        static.copyToRecursively(output, followLinks = false, overwrite = true)
        output.resolve("index.html").writeAtomically(page(decorated))
        val dated = decorated.groupBy(Release::date)
        dated.forEach { (date, releases) -> output.resolve("$date.html").writeAtomically(page(releases, date)) }
        val datedPages = dated.keys.mapTo(mutableSetOf()) { "$it.html" }
        output.listDirectoryEntries("*.html")
            .filter { datedPage.matches(it.name) && it.name !in datedPages }
            .forEach {
                Log.info("🗑️ Removing stale page ${it.name}")
                it.deleteExisting()
            }
        output.resolve("sitemap.xml").writeAtomically(sitemap(dated))
        Log.info("✅ Rendered index.html, sitemap.xml and ${dated.size} dated pages in $output")
    }

    //region Assets

    // Pages load the assets copied to assets/; embedded pages inline them so a single file works on its own.
    private fun asset(name: String): String = assets.getOrPut(name) { static.resolve("assets/$name").readText() }

    private fun assetName(name: String, extension: String): String =
        if (minified) "${name.removeSuffix(".$extension")}.min.$extension" else name

    private fun embeddedAsset(name: String, extension: String): String {
        if (!minified) return asset(name)
        val minifiedName = assetName(name, extension)
        // The standalone HTML has no adjacent source map.
        return asset(minifiedName).lineSequence()
            .filterNot { it.contains("sourceMappingURL=$minifiedName.map") }
            .joinToString("\n")
    }

    private fun HEAD.stylesheetAsset(name: String) =
        if (embedded) style { unsafe { +embeddedAsset(name, "css") } } else link(rel = "stylesheet", href = "assets/${assetName(name, "css")}")

    private fun FlowOrMetaDataOrPhrasingContent.scriptAsset(name: String) =
        if (embedded) script { unsafe { +embeddedAsset(name, "js") } } else script(src = "assets/${assetName(name, "js")}") {}

    //endregion

    //region Pages

    /**
     * Lists the index and dated pages. The index follows the weekly release cadence; dated pages rarely change once published.
     */
    private fun sitemap(dated: Map<LocalDate, List<Release>>): String {
        val dates = dated.mapValues { (_, releases) -> releases.maxOf(Release::updated).atOffset(UTC).toLocalDate() }
        return """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
        |  <url><loc>$siteUrl</loc>${dates.values.maxOrNull()?.let { "<lastmod>$it</lastmod>" } ?: ""}<changefreq>weekly</changefreq></url>
        |${dates.entries.joinToString("\n") { (date, updated) -> "  <url><loc>$siteUrl$date.html</loc><lastmod>$updated</lastmod></url>" }}
        |</urlset>
        |""".trimMargin()
    }

    /** Renders the index when [date] is null, or the page of that date. */
    private fun page(releases: List<Release>, date: LocalDate? = null): String =
        "<!DOCTYPE html>\n" + createHTML(prettyPrint = true).html { pageContent(releases, date) }

    private fun HTML.pageContent(releases: List<Release>, date: LocalDate?) {
        val index = date == null
        lang = "en"
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            meta(name = "color-scheme", content = "light dark")
            meta(name = "theme-color", content = "#ffffff") { attributes("media" to "(prefers-color-scheme: light)", "data-theme-color" to "light") }
            meta(name = "theme-color", content = "#1b1e22") { attributes("media" to "(prefers-color-scheme: dark)", "data-theme-color" to "dark") }
            // Links open in a new tab unless they set target="_self"; this keeps the attribute off every note link.
            base { attributes["target"] = "_blank" }
            title(if (date == null) siteTitle else "$siteTitle · ${date.heading}")
            meta(name = "description", content = if (date == null) siteDescription else description(date, releases))
            link(rel = "canonical", href = if (date == null) siteUrl else "$siteUrl$date.html")
            link(rel = "icon", type = "image/svg+xml", href = favicon)
            // Apply the saved theme before the stylesheet or page content can paint.
            script { unsafe { +asset("theme-init.js") } }
            stylesheetAsset("site.css")
        }
        body {
            if (index) attributes["data-date-navigation"] = "true"
            siteHeader(index = index)
            main("page-content") {
                div("content-inner") {
                    div("release-groups") {
                        releases.forEach { dateGroup(it, index) }
                    }
                }
            }
            scriptAsset("site.js")
        }
    }

    private fun description(date: LocalDate, releases: List<Release>): String {
        val prefix = "AndroidX release notes for ${date.heading}: "
        val entries = releases.flatMap(Release::entries).map { "${it.name} ${it.version}" }
        val full = prefix + entries.joinToString(", ") + "."
        if (full.length <= 160) return full
        val kept = entries.runningReduce { acc, it -> "$acc, $it" }.lastOrNull { prefix.length + it.length <= 149 }
        return prefix + (kept ?: entries.first()) + ", and more."
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
            // Grouped in one grid column so the filters stay centered until the expanded search needs the room.
            div("header-actions") {
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
                a(href = "https://developer.android.com/jetpack/androidx/versions", classes = "android-link") {
                    attributes["title"] = "View official AndroidX releases"
                }
                a(href = "https://github.com/SimonMarquis/androidx-release-notes", classes = "github-link") {
                    attributes["title"] = "View project on GitHub"
                }
            }
        }
    }

    private fun FlowContent.dateGroup(release: Release, index: Boolean) = section("date-group") {
        val anchor = release.anchor
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
                if (index) a("${release.date}.html", classes = "date-link") {
                    target = "_self"
                    +release.date.heading
                } else +release.date.heading
            }
            div("divider-line")
        }
        div("card") { release.entries.forEach { entry(release, it, anchor) } }
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

    private fun FlowContent.entry(release: Release, entry: Entry, anchor: String) {
        val version = entry.version.lowercase()
        val kind = listOf("alpha", "beta", "rc").firstOrNull { it in version } ?: "stable"
        article("component") {
            attributes(
                "data-release-kind" to kind,
                "data-search" to "${entry.name} ${entry.version} ${release.date} ${release.date.heading}".lowercase(),
            )
            id = "$anchor-${listOf(entry.group, entry.id).distinct().joinToString("-")}-${entry.version.slug()}"
            div("head") {
                h3("name") { a(entry.url.pageUrl) { +entry.name } }
                div("actions") {
                    entry.commits?.let { a(href = it, classes = "commits") { attributes["title"] = "Git commits" } }
                    a(href = entry.url, classes = "badge $kind") { +entry.version }
                }
            }
            if (entry.html.isNotBlank()) div("notes") { unsafe { +entry.html } }
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
        select("a[href]").forEach { link ->
            link.repairHref()
            val kind = link.referenceKind ?: return@forEach
            link.attr("title", link.label.ifEmpty { link.href }).addClass("ref").addClass(kind)
        }
        select("img").forEach { img ->
            img.attr("loading", "lazy").attr("decoding", "async")
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

/**
 * Manages one `androidx-YYYY-MM-DD` release per date through the GitHub REST API.
 * A [dryRun] only reads, and logs the requests it would send with the changes they would make.
 */
class GitHubReleases(private val dryRun: Boolean = false, env: Map<String, String> = System.getenv()) {
    private val repository = env["GITHUB_REPOSITORY"] ?: error("GITHUB_REPOSITORY is required")
    private val token = env["GITHUB_TOKEN"]
        ?: if (dryRun) null.also { Log.warn("No GITHUB_TOKEN: reading releases anonymously, with a lower rate limit") }
        else error("GITHUB_TOKEN is required for GitHub release operations")
    private val apiRoot = env["GITHUB_API_URL"] ?: "https://api.github.com"
    private val client = HttpClient.newBuilder().connectTimeout(20.seconds.toJavaDuration()).build()
    private val managedTag = Regex("^androidx-(\\d{4}-\\d{2}-\\d{2})$")

    private val outcomes = mutableMapOf<String, Int>()

    /** The reconciled release counts, e.g. `2 created, 1 up to date`. */
    val summary: String get() = outcomes.entries.joinToString { (outcome, count) -> "$count $outcome" }.ifEmpty { "none" }

    /** The newest managed release date: only releases at least as new become the latest one. */
    private var newest: LocalDate? = null
    private val newestListed by lazy { list().keys.maxOrNull() }

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

    /** Writes the [release] of a stored date, or deletes it when the date has no [stored] release anymore. */
    fun reconcile(date: LocalDate, stored: Release?, release: JsonObject?) {
        when {
            stored != null -> write(date, stored, release)
            release != null -> delete(release)
        }
    }

    private fun write(date: LocalDate, stored: Release, existing: JsonObject?) {
        val tag = tagOf(date)
        val body = body(date, stored)
        val upToDate = existing != null && existing.stringOrNull("name") == "$date" && existing.stringOrNull("body") == body &&
            existing.boolOrNull("draft") != true && existing.boolOrNull("prerelease") != true
        outcomes.merge(if (upToDate) "up to date" else if (existing != null) "updated" else "created", 1, Int::plus)
        if (upToDate) return Log.info("⏭️ Release $tag is up to date")
        Log.info(
            if (dryRun) "🧪 Would ${if (existing != null) "update" else "create"} release $tag (${stored.entries.size} entries)"
            else "🚀 ${if (existing != null) "Updating" else "Creating"} release $tag (${stored.entries.size} entries)",
        )
        val payload = buildJsonObject {
            put("tag_name", tag)
            put("name", "$date")
            put("body", body)
            put("draft", false)
            put("prerelease", false)
            // GitHub marks every new release as the latest one by default, even when backfilling an older date.
            put("make_latest", isLatest(date).toString())
        }
        if (dryRun) logChanges(existing, payload)
        if (existing != null) send("PATCH", "/releases/${existing.id}", payload)
        else send("POST", "/releases", payload, expected = setOf(201))
    }

    private fun isLatest(date: LocalDate): Boolean {
        val current = newest ?: newestListed
        return (current == null || date >= current).also { if (it) newest = date }
    }

    private fun delete(release: JsonObject) {
        val tag = release.string("tag_name")
        outcomes.merge("deleted", 1, Int::plus)
        Log.info(if (dryRun) "🧪 Would delete orphaned release $tag" else "🗑️ Deleting orphaned release $tag")
        send("DELETE", "/releases/${release.id}", expected = setOf(204, 404))
        // GitHub answers 422 when the tag does not exist, e.g. for a draft release.
        send("DELETE", "/git/refs/tags/$tag", expected = setOf(204, 404, 422))
    }

    /** Logs the fields the [payload] sets, from their [existing] value, and the body lines it adds or removes. */
    private fun logChanges(existing: JsonObject?, payload: JsonObject) {
        payload.filterKeys { it != "body" }.forEach { (key, value) ->
            val before = existing?.get(key)
            when {
                before == null -> Log.info("   $key: $value")
                before != value -> Log.info("   $key: $before → $value")
            }
        }
        val body = existing?.stringOrNull("body")?.replace("\r\n", "\n").orEmpty()
        if (body == payload.string("body")) return Log.info("   body: unchanged")
        val before = body.lines().dropLastWhile(String::isEmpty)
        val after = payload.string("body").lines().dropLastWhile(String::isEmpty)
        val removed = before.filterNot(after.toSet()::contains)
        val added = after.filterNot(before.toSet()::contains)
        if (removed.isEmpty() && added.isEmpty()) return Log.info("   body: same lines, reordered or with other whitespace")
        Log.info("   body: ${added.size} lines added, ${removed.size} removed")
        removed.forEach { Log.info("   - $it") }
        added.forEach { Log.info("   + $it") }
    }

    private fun body(date: LocalDate, stored: Release): String {
        val (owner, name) = repository.split('/', limit = 2)
        val entries = stored.entries
            .distinctBy { it.name to it.version }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Entry::name).thenBy { Version(it.version) })
        return buildString {
            appendLine("### 📖 **[Open the release notes digest](https://$owner.github.io/$name/$date.html)**")
            appendLine("---")
            entries.forEach { appendLine("- [${it.name} `${it.version}`](${it.url})") }
        }
    }

    private fun tagOf(date: LocalDate) = "androidx-$date"

    private val JsonObject.id: Long get() = getValue("id").jsonPrimitive.long

    private fun send(method: String, path: String, payload: JsonObject? = null, expected: Set<Int> = setOf(200)): Response {
        if (dryRun && method != "GET") return Response(expected.first(), "").also { Log.info("   🧪 Skipped $method $path") }
        val request = HttpRequest.newBuilder(URI("$apiRoot/repos/$repository$path"))
            .timeout(30.seconds.toJavaDuration())
            .apply { if (token != null) header("Authorization", "Bearer $token") }
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

/** A disagreement between the sources, or with the stored releases. */
enum class Issue(val emoji: String, val label: String) {
    Link("🔗", "inconsistent RSS ids or links"),
    Label("🏷️", "unreadable release links"),
    Gone("🪦", "listed pages without release notes"),
    Missing("❓", "listed versions missing from their page"),
    Date("📅", "listed dates disagreeing with the page"),
    Undated("🕳️", "version sections without a date"),
    Duplicate("👯", "duplicated version sections"),
    Blank("♻️", "stored notes or commits kept"),
    Stale("🧹", "stored entries not found again"),
}

object Log {
    private val issues = mutableListOf<Issue>()

    fun info(message: String) = println(message)

    fun warn(message: String) = System.err.println("⚠️  $message")

    fun error(message: String) = System.err.println("❌ $message")

    /** Warns about an [issue], counted in the [summary]. */
    fun issue(issue: Issue, message: String) {
        issues += issue
        warn("${issue.emoji} $message")
    }

    /** Logs the issue counts, and returns their total. */
    fun summary(): Int {
        if (issues.isEmpty()) return 0.also { info("📊 No inconsistencies") }
        info("📊 ${issues.size} inconsistencies:")
        issues.groupingBy { it }.eachCount().toSortedMap().forEach { (issue, count) -> info("   ${issue.emoji} $count ${issue.label}") }
        return issues.size
    }
}

private val nonSlugCharacters = Regex("[^a-z0-9]+")

fun String.slug(): String = lowercase().replace(nonSlugCharacters, "-").trim('-')

/** This URL without its fragment and query. */
val String.pageUrl: String get() = substringBefore('#').substringBefore('?')

private val looseDate = Regex("^([A-Za-z]{3,9})\\.?\\s*(\\d{1,2})(?:st|nd|rd|th)?\\s*,?\\s*(\\d{4})(?!\\d)")

/** Parses a date that starts this text: "October 21, 2026", "Oct. 21, 2026", "Sept 3 2025", "April 30,2019"… */
fun String.leadingDateOrNull(): LocalDate? = looseDate.find(trim())?.toDateOrNull()

/** Parses a text that is only a date, like [leadingDateOrNull]. */
fun String.toDateOrNull(): LocalDate? = looseDate.matchEntire(trim().removeSuffix("."))?.toDateOrNull()

private fun MatchResult.toDateOrNull(): LocalDate? {
    val (monthName, day, year) = destructured
    val month = Month.entries.firstOrNull { it.name.startsWith(monthName.uppercase()) } ?: return null
    return runCatching { LocalDate.of(year.toInt(), month, day.toInt()) }.getOrNull()
}

private val headingFormatter = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US)
private val anchorFormatter = DateTimeFormatter.ofPattern("MMMM_dd_uuuu", Locale.US)

/** The release heading of this date, e.g. "October 7, 2026". */
val LocalDate.heading: String get() = format(headingFormatter)

/** The current all-channel anchor of this date, e.g. "october_07_2026". */
val LocalDate.anchor: String get() = format(anchorFormatter).lowercase(Locale.US)

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
