# Collecting native dependency notices

Run `collect_notices.py` after preparing the exact upstream checkout and fetching
the locked Cargo packages. It collects all reachable normal and build dependencies
for both Android targets, including code the linker may discard. It does not
resolve license compatibility or choose between alternate licenses automatically.
It preserves the exact-version license elections documented in the review below.

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

That source-excerpt-only bundle reported **13 full-text gaps** (769/782
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

## Public distribution disposition review

The subsequent 2026-11-08 review supplies distribution materials for all 13
packages above. It preserves **five missing source-provided full-text files**
while reporting **zero unresolved distribution dispositions**. These are
different measures: a reviewed choice of an explicitly offered license does not
make a missing upstream file exist.

`reviewed-upstream-notices.json` binds each reviewed version to its Cargo.lock
archive checksum and source-file SHA-256 hashes. The original notice bytes are
checked into `reviewed-texts/`; `.gitattributes` prevents line-ending conversion
from changing them. The collector verifies every listed source and notice hash
before accepting a disposition. Future package versions do not inherit it.

Eight Meta packages now have authentic repository-root texts from immutable
snapshots whose published Rust source, `.src` fixtures and original package
manifest all match byte for byte:

| Published packages | Matching repository revision | Matched files |
| --- | --- | --- |
| allocative 0.3.6; allocative_derive 0.3.6 | [starlark-rust c778ec4f](https://github.com/facebook/starlark-rust/tree/c778ec4fc7d250a8158f9a163ff450a112a59b3c) | 62; 4 |
| pagable_derive 0.4.1 | [starlark-rust 8978f3cd](https://github.com/facebook/starlark-rust/tree/8978f3cd91b2908e1e61f6b58f84293097abf8f1) | 6 |
| display_container 0.9.0 | [gazebo 13efb6d4](https://github.com/facebookincubator/gazebo/tree/13efb6d415c4729178dfccc36fa9c87491f0813d) | 2 |
| lock_free_hashtable 0.1.4 | [buck2 48ae5c61](https://github.com/facebook/buck2/tree/48ae5c61c93c808e0831b27104d3bc7262f06bd4) | 7 |
| static_interner 0.1.2; strong_hash 0.1.0; strong_hash_derive 0.1.0 | [buck2 bb911429](https://github.com/facebook/buck2/tree/bb9114292d98db0a2919d37d673bb27f930e6aa1) | 2; 5; 2 |

Cargo-generated `Cargo.toml` and `Cargo.lock` are excluded from that repository
comparison. `Cargo.toml.orig` is included. The archive checksum still identifies
all published bytes, including the generated files. The Meta copyright headers
remain in the original reviewed source excerpts, and repository notices are
copied verbatim rather than reconstructed from those headers.

For **cesu8 1.1.0**, **fxhash 0.2.1**, **eventsource-stream 0.2.3** and
**io_tee 0.1.1**, this distribution elects the exact versions' explicitly offered
**Apache-2.0** alternative. The first two source headers link the ASF terms
directly; the other two have matching manifest and README declarations. The
original attribution or declaration is retained alongside the
[official ASF text](https://www.apache.org/licenses/LICENSE-2.0.txt), copied
verbatim with SHA-256
`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`.
These official copies do not count as recovered upstream license files.

For **debugserver-types 0.5.0**, the exact published manifest's author and MIT
declaration are preserved independently of the Microsoft schema attribution.
The complete `src/schema.json` matches
[Microsoft's protocol snapshot 4102c794](https://github.com/microsoft/debug-adapter-protocol/blob/4102c794ea44a36ec7153dfa3cc1609f1a0e792d/debugAdapterProtocol.json)
after removing carriage-return bytes; parsed JSON is equal. `src/lib.rs` shows
that `schemafy` uses the schema to generate Rust bindings. The bundle retains:

- The [historical Microsoft copyright and terms](https://github.com/microsoft/debug-adapter-protocol/blob/4102c794ea44a36ec7153dfa3cc1609f1a0e792d/License.txt), which specify MIT for code and CC-BY-3.0-US for documentation.
- Microsoft's [authentic full MIT text](https://github.com/microsoft/debug-adapter-protocol/blob/36fbbbb136becbe7f1fa0350f7bf2dcfe989cb2e/License-code.txt), added in its [license-text clarification](https://github.com/microsoft/debug-adapter-protocol/pull/431).
- The [official CC-BY-3.0-US legal code](https://creativecommons.org/licenses/by/3.0/us/legalcode), source credit, use to generate bindings, and the byte-formatting difference. Both categories' attribution and terms are retained conservatively.

No package-author copyright holder or year is invented. Microsoft's later
combined `License.txt` appends CC-BY-4.0 terms despite its 3.0-US heading, so that
later documentation text is not substituted for the historical 3.0-US grant.

The bundle contains `DISTRIBUTION_REVIEW.md` with these dispositions and
`SOURCE_CODE.md` with recipient-facing source locations for the three MPL-2.0
components. `nucleo 0.5.0` and `nucleo-matcher 0.3.1` use the exact Git source
[4253de9f](https://github.com/helix-editor/nucleo/tree/4253de9faabb4e5c6d81d946a5e35a90f87347ee),
including the matcher subdirectory; a registry crate with the same version is
not substituted. `option-ext 0.2.0` uses its checksum-verified published archive.
The source-preparation script does not patch these MPL packages. Their source
and license notices remain available under MPL-2.0, as required when distributing
executables compiled from them. See [Mozilla's distribution guidance](https://www.mozilla.org/en-US/MPL/2.0/FAQ/).
`self_cell 1.2.2` also offers Apache-2.0 as an alternative to GPL-2.0-only; its
declaration does not establish a GPL-only dependency.

Reports retain `coverage_gap_count` and `coverage_gaps` for the raw source-file
inventory. Release checks can separately require `unresolved_distribution_count`
to equal zero and inspect `unresolved_distribution`. `reviewed_distribution_count`
and `distribution_review_manifest_sha256` tie that result to the packaged review.
Exit code **2** still reports missing source-provided full texts, even when all
such packages have reviewed distribution materials. A zero disposition count is
the outcome of this bounded review, not a legal compatibility certification for
the entire conservative graph.

Focused offline checks:

```bash
python3 -m unittest discover -s licenses -p 'test_*.py'
```
