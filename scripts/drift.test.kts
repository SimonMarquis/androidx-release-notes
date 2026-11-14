
// Offline fixtures use the production parser, reconciliation, and JSON storage.
val fixturePage = "https://developer.android.com/jetpack/androidx/releases/fixture"
val fixtureDate = LocalDate.parse("2026-01-01")
fun fixtureComponent(
    version: String = "1.0.0",
    prefix: String = "",
    id: String = "fixture",
    details: String = "<p>Old notes</p>",
    page: String = fixturePage,
) = ComponentRecord(id, id, version, "$page#$prefix$version", details, null)

fun fixtureEntry(
    date: LocalDate = fixtureDate,
    components: List<ComponentRecord> = listOf(fixtureComponent()),
    ignored: Boolean = false,
) = EntryRecord(
    "tag:google.com,2016:androidx-release-notes#${date.anchor}", date.heading, date,
    "2026-01-02T12:34:56Z", "$ALL_CHANNEL_URL#${date.anchor}", components, ignored, "2026-01-02T12:34:56Z",
)

fun fixtureSection(version: String, date: String, notes: String, prefix: String = "") = """
    <h3 id="$prefix$version">Version $version</h3>
    <p>$date</p>
    <p>androidx.fixture:fixture:$version is released.</p>
    <p>$notes</p>
""".trimIndent()

fun fixtureScraper(html: String) = ComponentScraper { url -> Jsoup.parse(html, url) }
fun mustFail(label: String, block: () -> Unit) {
    check(runCatching(block).isFailure) { "Expected failure: $label" }
}

// Crawl full histories, preserve metadata, fill existing and missing dates, and fetch a shared page only once.
val fixtureHtml = fixtureSection("1.0.0", "January 1, 2026", "Updated notes") +
    fixtureSection("1.1.0", "January 1, 2026", "Missing version") +
    fixtureSection("1.2.0", "January 3, 2026", "New date") +
    fixtureSection("1.0.0", "January 1, 2026", "Separate artifact", "child-") +
    fixtureSection("1.1.0", "January 3, 2026", "Separate missing version", "child-") +
    fixtureSection("1.0.0", "January 3, 2026", "Unregistered artifact", "other-")
var fixtureFetches = 0
val fixtureFixer = DriftFixer(ComponentScraper { url ->
    fixtureFetches++
    Jsoup.parse(fixtureHtml, url)
})
val fixtureOriginal = fixtureEntry(components = listOf(fixtureComponent(), fixtureComponent(prefix = "child-", id = "child")))
val fixtureResult = fixtureFixer.reconcile(listOf(fixtureOriginal))
check(fixtureFetches == 1)
val fixtureUpdated = fixtureResult.first { it.releasedOn == fixtureDate }
check(fixtureUpdated.copy(components = fixtureOriginal.components) == fixtureOriginal)
check(fixtureUpdated.components.first().detailsHtml == "<p>Updated notes</p>")
check(fixtureUpdated.components.first { it.id == "child" }.detailsHtml == "<p>Separate artifact</p>")
check(fixtureResult.flatMap { it.components }.size == 5)
check(fixtureResult.none { entry -> entry.components.any { "other-" in it.url } })
check(DriftFixer(fixtureScraper(fixtureHtml)).reconcile(fixtureResult) == fixtureResult)

// Ignore markers suppress updates and prevent missing versions from resurrecting an ignored date.
val fixtureIgnoredDate = LocalDate.parse("2026-01-03")
val fixtureIgnored = fixtureEntry(fixtureIgnoredDate, listOf(fixtureComponent("1.2.0")), ignored = true)
val fixtureWithIgnored = DriftFixer(fixtureScraper(fixtureHtml)).reconcile(listOf(fixtureOriginal, fixtureIgnored))
check(fixtureWithIgnored.first { it.ignored } == fixtureIgnored)
check(fixtureWithIgnored.flatMap { it.components }.none { it.id == "child" && it.version == "1.1.0" })

// Old entry IDs are matched canonically and their original IDs remain intact.
val fixturePadded = fixtureOriginal.copy(id = fixtureOriginal.id.replace("january_1_", "january_01_"))
val fixturePaddedResult = DriftFixer(fixtureScraper(fixtureHtml)).reconcile(listOf(fixturePadded))
check(fixturePaddedResult.count { it.releasedOn == fixtureDate } == 1)
check(fixturePaddedResult.first().id == fixturePadded.id)

// Retired anchors retain their cached notes while the remaining family is still crawled.
val fixtureRetiredHtml = fixtureSection("1.1.0", "January 3, 2026", "Still available")
val fixtureRetired = DriftFixer(fixtureScraper(fixtureRetiredHtml)).reconcile(listOf(fixtureEntry()))
check(fixtureRetired.first().components.single() == fixtureComponent())
check(fixtureRetired.flatMap { it.components }.size == 2)

// A retired artifact family does not prevent other families on its page from being repaired.
val fixtureRetiredFamily = DriftFixer(fixtureScraper(fixtureRetiredHtml)).reconcile(listOf(fixtureOriginal))
check(fixtureRetiredFamily.first().components.first { it.id == "child" } == fixtureOriginal.components.last())

// Bare-version aliases resolve to an artifact-prefixed anchor before family enumeration.
val fixtureAliasHtml = fixtureSection("1.0.0", "January 1, 2026", "Alias resolved", "fixture-") +
    fixtureSection("1.1.0", "January 3, 2026", "Alias history", "fixture-")
val fixtureAliasResult = DriftFixer(fixtureScraper(fixtureAliasHtml)).reconcile(listOf(fixtureEntry()))
check(fixtureAliasResult.first().components.single().url == "$fixturePage#fixture-1.0.0")
check(fixtureAliasResult.flatMap { it.components }.size == 2)

// A renamed page resolves through the versions index, then contributes its full history.
val fixtureRenamedPage = "https://developer.android.com/jetpack/androidx/releases/fixture-new"
val fixtureRenamed = DriftFixer(ComponentScraper { url ->
    when (url) {
        fixturePage -> Jsoup.parse("<table><tr><td><a href='$fixtureRenamedPage'>fixture</a></td><td>1.0.0</td></tr></table>", url)
        fixtureRenamedPage -> Jsoup.parse(fixtureSection("1.0.0", "January 1, 2026", "Renamed") +
            fixtureSection("1.1.0", "January 3, 2026", "Renamed history"), url)
        else -> error("Unexpected page: $url")
    }
}).reconcile(listOf(fixtureEntry()))
check(fixtureRenamed.first().components.single().url == "$fixtureRenamedPage#1.0.0")
check(fixtureRenamed.flatMap { it.components }.size == 2)

// Parse failures, truncated pages, and identity collisions are errors, not deletions or partial repairs.
mustFail("missing cached section") { DriftFixer(fixtureScraper("<h1>No notes</h1>")).reconcile(listOf(fixtureEntry())) }
mustFail("missing release date") {
    DriftFixer(fixtureScraper("<h3 id='1.0.0'>Version 1.0.0</h3><p>Notes without a date</p>")).reconcile(listOf(fixtureEntry()))
}
mustFail("empty changelog") {
    DriftFixer(fixtureScraper("<h3 id='1.0.0'>Version 1.0.0</h3><p>January 1, 2026</p>")).reconcile(listOf(fixtureEntry()))
}
mustFail("ambiguous component identity") {
    val sameId = fixtureOriginal.copy(components = fixtureOriginal.components.map { it.copy(id = "fixture") })
    DriftFixer(fixtureScraper(fixtureHtml)).reconcile(listOf(sameId))
}
mustFail("fetch failure") { DriftFixer(ComponentScraper { error("HTTP failure") }).reconcile(listOf(fixtureEntry())) }

// A real metadata-only release is valid when there is no cached changelog to erase.
val fixtureEmpty = fixtureEntry(components = listOf(fixtureComponent(details = "")))
check(DriftFixer(fixtureScraper("<h3 id='1.0.0'>Version 1.0.0</h3><p>January 1, 2026</p>"))
    .reconcile(listOf(fixtureEmpty)) == listOf(fixtureEmpty))

// Commit ranges use the same scraper as RSS imports.
val fixtureCommits = "https://android.googlesource.com/platform/frameworks/support/+log/old..new/fixture"
val fixtureCommitHtml = "<h3 id='1.0.0'>Version 1.0.0</h3><p>January 1, 2026</p>" +
    "<p>Version 1.0.0 contains <a href='$fixtureCommits'>these commits</a>.</p><p>Updated notes</p>"
check(DriftFixer(fixtureScraper(fixtureCommitHtml)).reconcile(listOf(fixtureEntry())).single().components.single().commitsUrl == fixtureCommits)

// JSON round-trips preserve the result, and reruns after serialization still produce no drift.
val fixtureDirectory = java.nio.file.Files.createTempDirectory("androidx-drift-test")
try {
    val archive = Archive(fixtureDirectory)
    fixtureResult.forEach { archive.save(it) }
    val loaded = archive.load()
    check(loaded.toSet() == fixtureResult.toSet())
    check(DriftFixer(fixtureScraper(fixtureHtml)).reconcile(loaded) == loaded)
} finally {
    fixtureDirectory.toFile().deleteRecursively()
}
println("✅ Drift reconciliation fixtures passed")
