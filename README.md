# AndroidX Release Notes

Browse AndroidX release notes across libraries and dates in one place.

- 🗓️ **Timeline:** Read the entire history of release notes.
- 🎛️ **Stability:** Filter Stable, RC, Beta, or Alpha releases.
- 🔎 **Search:** Find libraries and versions across the full timeline.
- ✨ **Less repetition:** Skip repeated metadata and focus on the changes.
- 🔗 **Compact links:** Open issues, reviews, GitHub references, and commits.

> [!TIP]
> 🔔 Follow releases via **Watch ➞ Custom ➞ Releases**.  
> 📡 Or subscribe to the GitHub releases [Atom feed](https://github.com/SimonMarquis/androidx-release-notes/releases.atom).

<a href="https://simonmarquis.github.io/androidx-release-notes/">
  <picture>
    <!-- Smartphone -->
    <source media="(max-width: 600px) and (prefers-color-scheme: dark)" srcset="art/smartphone-dark.png">
    <source media="(max-width: 600px)" srcset="art/smartphone-light.png">
    <!-- Tablet -->
    <source media="(min-width: 601px) and (max-width: 1023px) and (prefers-color-scheme: dark)" srcset="art/tablet-dark.png">
    <source media="(min-width: 601px) and (max-width: 1023px)" srcset="art/tablet-light.png">
    <!-- Desktop -->
    <source media="(min-width: 1024px) and (prefers-color-scheme: dark)" srcset="art/desktop-dark.png">
    <source media="(min-width: 1024px)" srcset="art/desktop-light.png">
    <img src="art/desktop-light.png" alt="AndroidX release notes">
  </picture>
</a>

---

## Implementation

A single Kotlin script, [`androidx-release-notes.main.kts`](scripts/androidx-release-notes.main.kts), imports official release notes into a JSON cache in [`data`](data/).  
The site in [`docs`](docs/) and the [GitHub releases](https://github.com/SimonMarquis/androidx-release-notes/releases) are built only from that cache.

### Sources

- [Release notes feed](https://developer.android.com/feeds/androidx-release-notes.xml): recent entries.
- [All-channel releases](https://developer.android.com/jetpack/androidx/versions/all-channel): full history.
- [Library release pages](https://developer.android.com/jetpack/androidx/versions): per-version notes and commit ranges.

### Data

Each `data/YYYY-MM-DD.json` holds one release entry and its component releases.  
Set `ignored: true` to hide an entry and prevent re-import.

### Commands

Run `./scripts/androidx-release-notes.main.kts <command>` from the repository root.

| Command | Operation |
| --- | --- |
| `sync-rss` | Import new or updated entries from the feed. |
| `fix-drift` | Crawl every registered component’s full release history and repair changed or missing changelogs. `--dry-run` reports changes without writing JSON. |
| `ignore <YYYY-MM-DD>` | Ignore every entry for a date. |
| `build-site` | Generate HTML pages. `--embedded` inlines assets; `--minified` uses generated minified assets. |
| `sync-releases` | Create, update, or delete one GitHub release per date. `--changed-from SHA` limits it to changed dates. |
| `import-history` | Import new entries from the all-channel page. |

### Workflows

- [![🔃 RSS sync](https://github.com/SimonMarquis/androidx-release-notes/actions/workflows/rss.yml/badge.svg)](https://github.com/SimonMarquis/androidx-release-notes/actions/workflows/rss.yml) runs `sync-rss` periodically and commits new JSON in `data/`.
- [🧭 Drift correction](.github/workflows/drift.yml) runs weekly and manually on `main`, creating or updating one PR from `automation/changelog-drift`. On PRs affecting the script or workflow, it runs the same reconciliation and only logs the produced diff.
- [![📝 Pages sync](https://github.com/SimonMarquis/androidx-release-notes/actions/workflows/pages.yml/badge.svg)](https://github.com/SimonMarquis/androidx-release-notes/actions/workflows/pages.yml) rebuilds `docs/`, refreshes `art/` screenshots, and syncs releases.
- [![🏗️ CI](https://github.com/SimonMarquis/androidx-release-notes/actions/workflows/ci.yml/badge.svg)](https://github.com/SimonMarquis/androidx-release-notes/actions/workflows/ci.yml) builds self-contained preview pages and screenshots on PRs.

Workflows push as a GitHub App (`APP_CLIENT_ID`, `APP_PRIVATE_KEY` secrets).  
GitHub Pages serves [`docs`](docs/) from `main`.

### Drift reconciliation

`fix-drift` uses cached component URLs and version-anchor prefixes as the registry. It fetches each official library page once, resolves existing aliases and renamed pages, enumerates matching version sections, and reuses the RSS changelog parser. Existing entry metadata and component identities are preserved; missing versions are added to the corresponding dated entries. Ignored entries remain unchanged, and ignored dates are not repopulated.

The command validates the full crawl before saving. Missing sections, missing dates, ambiguous identities, or a source response that would erase a cached changelog fail the run without writing data. Unchanged records are not rewritten. After a correction PR is merged, Pages sync rebuilds the site and updates GitHub releases.

Run `./scripts/test-drift.sh` for offline parser and reconciliation fixtures.
