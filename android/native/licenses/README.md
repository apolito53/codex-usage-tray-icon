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
package's notice. Exit code 0 only means the filename-based coverage check found
texts; it does not establish legal completeness. Source-embedded notices and
unusual file names can require further review before external distribution.

Keep generated bundles out of Git. An externally delivered native diagnostic APK
should include the reviewed bundle in its assets and retain its index alongside
the build record. This source tool does not modify Cargo manifests, upstream
source, or the Android app.

## Current offline inventory

At upstream commit `8f195c93d7e7acfef95acf273f0e49cce917e291`, the initial
offline collection covered 782 unique packages across both Android graphs
(781 ARM64, 782 x86_64, including build dependencies). It collected 1,319
package notice files and 17 Rust/NDK notice files. The source-provided license
text coverage check reported 61 package gaps, listed in `coverage-gaps.json`.
The report intentionally does not treat a package's SPDX declaration alone
as a bundled license text.

This inventory is incomplete for external native APK distribution. The internal
runtime-test APK is not a deliverable for this milestone. The existing notification
preview does not contain these native dependencies. Missing-text retrieval is
available as a bounded follow-up; the initial inventory did not use that network
option or claim the remaining source-embedded notices had all been reviewed.
