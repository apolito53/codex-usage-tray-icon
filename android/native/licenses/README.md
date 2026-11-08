# Collecting native dependency notices

Run `collect_notices.py` after preparing the exact upstream checkout and fetching
the locked Cargo packages. It collects all reachable normal and build dependencies
for both Android targets, including code the linker may discard. It does not
resolve license compatibility or choose between alternate licenses.

```bash
python3 licenses/collect_notices.py \
  --upstream-root /absolute/path/to/pinned/codex \
  --rust-doc-root /absolute/path/to/rust/sysroot/share/doc/rust \
  --ndk-root "$ANDROID_NDK_HOME" \
  --fetch-missing \
  --output /absolute/path/to/new/native-notices
```

The ordinary metadata commands use Rust 1.95.0, `--locked`, `--offline`, and
`--filter-platform`. Existing metadata can instead be supplied repeatedly as
`--metadata aarch64-linux-android=/path/metadata.json`. Supply only metadata
produced with the corresponding target filter and the actual build lockfile.

Each package has a path-preserving directory containing its notice-like files.
The JSON index records license declarations, package sources, source archive
links, upstream revisions when available, original relative paths, SHA-256
hashes, and missing-text gaps. The deterministic ZIP has fixed timestamps.
The output directory must be new or empty; an existing bundle is never replaced.

Many published crates declare a license but omit its text. `--fetch-missing`
uses the exact revision and source path in that crate's `.cargo_vcs_info.json`
to obtain notice files from its GitHub repository and enclosing workspaces.
It never substitutes the repository's current default branch. Fetch failures
remain visible in the index. Downloads may be cached with `--cache`.

The collector includes nested vendored notices, such as OpenSSL, AWS-LC, ring,
and zstd. It also preserves SQLite's public-domain source header and can include
the installed Rust standard-library and Android NDK notices. The NDK collection
is a conservative superset, not a claim that every NDK component is in the APK.

Exit code 2 means at least one dependency has no source-provided license text.
Do not treat an SPDX declaration or a generated generic license as that missing
package's notice. Exit code 0 only means the file and reviewed-source coverage
check found texts; it does not establish legal completeness. Source-embedded notices and
unusual file names can require further review before external distribution.

Keep generated bundles out of Git. An externally delivered native diagnostic APK
should include the reviewed bundle in its assets and retain its index alongside
the build record. This source tool does not modify Cargo manifests, upstream
source, or the Android app.

## Initial offline inventory

At upstream commit `8f195c93d7e7acfef95acf273f0e49cce917e291`, the initial
offline collection covered 782 unique packages across both Android graphs
(781 ARM64, 782 x86_64, including build dependencies). It collected 1,319
package notice files and 17 Rust/NDK notice files. The source-provided license
text coverage check reported 61 package gaps, listed in `coverage-gaps.json`.
The report intentionally does not treat a package's SPDX declaration alone
as a bundled license text.

That initial inventory was incomplete. Its internal runtime-test APK was not a
delivery artifact, and the notification preview at that time contained no native
dependencies. The initial run did not fetch missing source texts or claim that
source-embedded notices had all been reviewed. `coverage-gaps.json` remains the
unaltered historical report for that 61-gap run.

## Reviewed source attribution follow-up

The 2026-11-08 account build's exact-revision download found standalone license
texts for 766 of 782 conservative packages. A narrow follow-up verified the 16
remaining published crate archives against the pinned native `Cargo.lock`:

- `cookie-factory 0.3.3` and `crc-catalog 2.4.0` already had authentic bundled
  texts under `LICENSES/MIT.txt` or `LICENSES/Apache-2.0.txt`. The old basename-only
  coverage check missed these filenames; they now count as source-provided text.
- `bech32 0.9.1` embeds its complete Clark Moody MIT notice in `src/lib.rs`,
  lines 1-19. The collector now preserves that exact, hash-checked span.
- Fourteen source excerpts preserve the available copyright and licensing
  statements across 13 packages. Only Bech32's complete grant counts as full
  text. Copyright headers and license references retain their missing-text gaps.

`reviewed-source-notices.json` records exact versions, archive checksums, complete
source-file hashes, selected lines, excerpt hashes, and the reviewed content kind.
Future versions are not automatically matched. Exact source bytes are required;
a changed file or excerpt fails rather than silently substituting another text.
The collector never fills these gaps with generated license text or a repository's
current branch. Versions without a matching published VCS marker or release tag
keep their exact crate archive as the source reference.

The resulting reviewed bundle still reports **13 full-text gaps** (769/782
packages have detected full text). Its index retains the parent archive hash and
review manifest hash. This count concerns the conservative dependency graph,
including build dependencies; it does not identify the final linked contents.

An existing downloaded bundle can be supplemented on a computer without Rust:

```bash
python3 licenses/supplement_notices.py \
  --input /path/to/native-notices.zip \
  --crate-archives /path/to/exact-crate-downloads \
  --output /path/to/new/native-notices-reviewed
```

The archive directory must contain the exact `NAME-VERSION.crate` files listed
in the review manifest. Downloads from
`https://static.crates.io/crates/NAME/NAME-VERSION.crate` are checked against the
recorded lockfile checksums before any excerpt is used. The input bundle and
historical coverage report are never modified; retained notice bytes are checked
against their existing index hashes. A nonzero result of **2** still means the
new bundle has explicit unresolved full-text gaps.

Focused offline checks:

```bash
python3 -m unittest discover -s licenses -p 'test_*.py'
```
