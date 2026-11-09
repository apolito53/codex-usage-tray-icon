#!/usr/bin/env python3
"""Collect source-provided notices for the locked Android native dependency graph.

This is a provenance/coverage tool, not a license compatibility decision engine.
It intentionally includes build dependencies and code that the linker may discard.
"""

from __future__ import annotations

import argparse
import concurrent.futures
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile


NOTICE_NAME = re.compile(r"(?:^|[._ -])(licen[cs]e|copying|copyright|notice|authors)(?:$|[._ -])", re.I)
LICENSE_NAME = re.compile(r"(?:^|[._ -])(licen[cs]e|copying)(?:$|[._ -])", re.I)
SKIP_DIRS = {".git", ".upstream", ".gradle", ".kotlin", "target", "node_modules", "__pycache__"}
MAX_NOTICE_BYTES = 8 * 1024 * 1024
MISSING_LICENSE_TEXT = "No source-provided license text found; SPDX declaration alone is not a substitute"
LICENSE_DIRECTORY_TEXTS = {"mit.txt", "apache-2.0.txt"}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def has_license_text(record: dict) -> bool:
    for item in record["files"]:
        # An official license supplied for an explicitly reviewed election is
        # useful distribution material, but does not restore a missing upstream
        # file. Keep that raw source-coverage metric honest.
        if item.get("counts_as_source_provided_license_text") is False:
            continue
        path = PurePosixPath(item["path"])
        if LICENSE_NAME.search(path.name) or item.get("content_kind") == "full-license-text":
            return True
        # These authentic upstream filenames were already collected, but the
        # basename-only check missed them. A README inside LICENSES is not text.
        if path.name.lower() in LICENSE_DIRECTORY_TEXTS and any(p.lower() in {"licenses", "licences"} for p in path.parts[:-1]):
            return True
    return False


@lru_cache(maxsize=1)
def reviewed_source_specs() -> dict:
    source = Path(__file__).with_name("reviewed-source-notices.json")
    data = json.loads(source.read_text(encoding="utf-8"))
    return {(item["name"], item["version"]): item for item in data["packages"]}


@lru_cache(maxsize=1)
def reviewed_upstream_specs() -> dict:
    source = Path(__file__).with_name("reviewed-upstream-notices.json")
    data = json.loads(source.read_text(encoding="utf-8"))
    return {(item["name"], item["version"]): item for item in data["packages"]}


def add_reviewed_upstream_notices(output: Path, record: dict, read_source) -> int:
    """Apply only individually reviewed dispositions to exact published bytes.

    Repository recoveries and official terms for a declared alternative are
    deliberately separate. No current branch or generic SPDX fallback is used.
    """
    spec = reviewed_upstream_specs().get((record["name"], record["version"]))
    if spec is None or not (record.get("source") or "").startswith("registry+"):
        return 0
    sources = {}
    for source in spec["source_matches"]:
        data = read_source(source["source_path"])
        if sha256(data) != source["sha256"]:
            raise ValueError(f"Reviewed upstream source hash mismatch: {record['name']} {record['version']} {source['source_path']}")
        reference = source.get("required_reference")
        if reference and reference.encode("utf-8") not in data:
            raise ValueError(f"Reviewed license reference missing: {record['name']} {source['source_path']}")
        sources[source["source_path"]] = data

    # Verify all local originals before adding or accepting a previous supplement.
    texts = []
    for item in spec["notice_files"]:
        relative = PurePosixPath(item["local_path"])
        if relative.is_absolute() or ".." in relative.parts or "\\" in item["local_path"]:
            raise ValueError(f"Unsafe reviewed notice path: {relative}")
        data = Path(__file__).parent.joinpath(*relative.parts).read_bytes()
        if sha256(data) != item["sha256"]:
            raise ValueError(f"Reviewed notice hash mismatch: {record['name']} {relative}")
        texts.append((item, data))
    recovered = spec["basis"] == "byte-identical-published-source"
    review = {
        "status": "reviewed",
        "basis": spec["basis"],
        "finding": spec["finding"],
        "source_archive_sha256": spec["source_archive_sha256"],
        "source_matches": spec["source_matches"],
        "source_provided_text_recovered": recovered,
    }
    for key in ("repository", "revision", "package_path", "selected_license", "schema_provenance"):
        if key in spec:
            review[key] = spec[key]
    record["distribution_review"] = review
    added = 0

    def preserve(relative, data, provenance):
        nonlocal added
        previous = next((item for item in record["files"] if item["path"] == relative), None)
        if previous is not None:
            if previous["sha256"] != sha256(data):
                raise ValueError(f"Conflicting reviewed notice: {record['name']} {relative}")
            if sha256((output / record["directory"] / relative).read_bytes()) != previous["sha256"]:
                raise ValueError(f"Existing reviewed notice hash mismatch: {record['name']} {relative}")
            return
        add_file(output, record, None, relative, provenance, data)
        added += 1

    for item, data in texts:
        preserve(f"reviewed-upstream/{item['upstream_path']}", data, {
            "origin": "reviewed-matching-repository" if recovered else "reviewed-distribution-license-material",
            "content_kind": item["content_kind"],
            "counts_as_source_provided_license_text": recovered,
            "url": item["url"],
            "source_archive_sha256": spec["source_archive_sha256"],
            **({"linked_from": item["linked_from"]} if "linked_from" in item else {}),
            **({"repository_revision": spec["revision"]} if "revision" in spec else {}),
        })
    # Preserve package-authored evidence separately from external license terms.
    for path in spec.get("preserve_sources", ["Cargo.toml"] if not recovered else []):
        preserve(f"reviewed-attribution/{path}", sources[path], {
            "origin": "verbatim-reviewed-published-source",
            "content_kind": "package-license-declaration-and-attribution",
            "counts_as_source_provided_license_text": False,
            "source_path": path,
            "source_file_sha256": sha256(sources[path]),
            "source_archive_sha256": spec["source_archive_sha256"],
        })
    return added


def summarize_distribution_review(index: dict) -> None:
    """Count unresolved raw gaps, allowing only exact-source reviewed dispositions."""
    unresolved = []
    for record in index["packages"]:
        if record["coverage_gaps"] and record.get("distribution_review", {}).get("status") != "reviewed":
            unresolved.append({"package": f"{record['name']} {record['version']}", "gaps": record["coverage_gaps"]})
    index["distribution_review_scope"] = "Disposition of package-level source-text gaps only; not a graph-wide legal compatibility certification."
    index["reviewed_distribution_count"] = sum(r.get("distribution_review", {}).get("status") == "reviewed" for r in index["packages"])
    index["unresolved_distribution_count"] = len(unresolved)
    index["unresolved_distribution"] = unresolved
    manifest = Path(__file__).with_name("reviewed-upstream-notices.json")
    index["distribution_review_manifest_sha256"] = sha256(manifest.read_bytes())


def write_distribution_review(output: Path, index: dict) -> None:
    lines = ["# Reviewed native distribution materials", "", index["distribution_review_scope"], "",
             f"Source-provided full-text gaps: {index['coverage_gap_count']}. Unresolved distribution dispositions: {index['unresolved_distribution_count']}.", "",
             "Missing upstream files remain counted even when authentic official terms and an explicit license election supply reviewed distribution materials.", ""]
    for record in index["packages"]:
        review = record.get("distribution_review")
        if not review:
            continue
        lines.extend([f"## {record['name']} {record['version']}", "", review["finding"], ""])
        if review.get("selected_license"):
            lines.extend([f"Distribution license election: **{review['selected_license']}**.", ""])
        lines.extend([f"Published source archive SHA-256: `{review['source_archive_sha256']}`.", ""])
        if review.get("revision"):
            lines.extend([f"Matching repository: {review['repository']}/tree/{review['revision']}/{review['package_path']}", ""])
        schema = review.get("schema_provenance")
        if schema:
            lines.extend(["Debug Adapter Protocol schema: Copyright (c) Microsoft Corporation.", "",
                          f"Original source: {schema['repository']}/blob/{schema['revision']}/{schema['repository_path']}", "",
                          "The schema is used to generate Rust bindings through schemafy. The published copy differs only in carriage-return bytes; no substantive JSON changes were found. Microsoft's original terms provide MIT for code and CC-BY-3.0-US for documentation. Both sets of terms and attribution are retained conservatively. The package author's own MIT declaration is preserved separately; no copyright year is added.", "",
                          "CC-BY-3.0-US: https://creativecommons.org/licenses/by/3.0/us/", ""])
        for item in record["files"]:
            if item["path"].startswith(("reviewed-upstream/", "reviewed-attribution/")):
                path = f"{record['directory']}/{item['path']}"
                lines.append(f"- [{item['path']}]({path})" + (f" - original: {item['url']}" if item.get("url") else ""))
        lines.append("")
    if index["unresolved_distribution"]:
        lines.extend(["## Unresolved dispositions", ""])
        lines.extend(f"- {item['package']}: {'; '.join(item['gaps'])}" for item in index["unresolved_distribution"])
    (output / "DISTRIBUTION_REVIEW.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    source_lines = ["# Source code for native components", "",
                    "The following components are available under the Mozilla Public License 2.0 (MPL-2.0). Their source code is available at the exact locked revisions below. The corresponding MPL license texts and copyright notices are retained in this notice bundle.", ""]
    for record in index["packages"]:
        if record.get("declared_license") != "MPL-2.0":
            continue
        source = record.get("source") or ""
        if source.startswith("git+"):
            repository = source[4:].split("?", 1)[0].removesuffix(".git")
            revision = source.rsplit("#", 1)[-1]
            source_url = f"{repository}/archive/{revision}.tar.gz"
        else:
            source_url = record.get("source_archive")
        if not source_url:
            raise ValueError(f"MPL source location missing: {record['name']} {record['version']}")
        source_lines.append(f"- **{record['name']} {record['version']}** - source: {source_url}; MPL-2.0: `{record['directory']}/crate/`.")
    source_lines.extend(["", "The source archives retain their original license and copyright notices. This native build applies no patches to these MPL packages; the project's source preparation changes are confined to the Codex checkout. Any distributor changes to the MPL-covered files must also be made available under MPL-2.0.", "",
                         "The MPL license permits inclusion in a larger work under other terms. Those terms do not restrict recipients' rights to the MPL-covered source. See the bundled MPL-2.0 text, especially sections 3.1-3.3, and https://www.mozilla.org/en-US/MPL/2.0/FAQ/ .", ""])
    (output / "SOURCE_CODE.md").write_text("\n".join(source_lines), encoding="utf-8")


def add_reviewed_source_notices(output: Path, record: dict, read_source) -> int:
    """Preserve only reviewed, hash-matching spans of exact published versions.

    Copyright/license-reference headers remain separate from full license text.
    read_source accepts a crate-relative path and returns its original bytes.
    """
    spec = reviewed_source_specs().get((record["name"], record["version"]))
    if spec is None or not (record.get("source") or "").startswith("registry+"):
        return 0
    record["source_notice_review"] = {"finding": spec["finding"], "source_archive_sha256": spec["source_archive_sha256"]}
    added = 0
    for notice in spec["source_notices"]:
        path = notice["source_path"]
        data = read_source(path)
        if sha256(data) != notice["source_file_sha256"]:
            raise ValueError(f"Reviewed source hash mismatch: {record['name']} {record['version']} {path}")
        first, last = notice["source_lines"]
        selected = b"".join(data.splitlines(keepends=True)[first - 1:last])
        if sha256(selected) != notice["notice_sha256"]:
            raise ValueError(f"Reviewed source span mismatch: {record['name']} {record['version']} {path}")
        relative = f"reviewed-source/{path}.lines-{first}-{last}.txt"
        previous = next((item for item in record["files"] if item["path"] == relative), None)
        if previous is not None:
            if previous["sha256"] != sha256(selected):
                raise ValueError(f"Conflicting existing source notice: {relative}")
            continue
        provenance = {
            "origin": "verbatim-reviewed-published-source",
            "content_kind": notice["content_kind"],
            "source_path": path,
            "source_lines": [first, last],
            "source_file_sha256": sha256(data),
            "source_archive_sha256": spec["source_archive_sha256"],
            "source_archive": record.get("source_archive"),
            "source_url": f"https://docs.rs/crate/{record['name']}/{record['version']}/source/{urllib.parse.quote(path)}",
        }
        add_file(output, record, None, relative, provenance, selected)
        added += 1
    return added


def file_candidates(root: Path):
    for current, directories, files in os.walk(root):
        directories[:] = sorted(d for d in directories if d not in SKIP_DIRS)
        for name in sorted(files):
            path = Path(current) / name
            relative = path.relative_to(root)
            if NOTICE_NAME.search(name) or any(p.lower() in {"licenses", "licences"} for p in relative.parts[:-1]):
                yield path, relative.as_posix()


def root_notices(root: Path):
    if root.is_dir():
        for path in sorted(root.iterdir()):
            if path.is_file() and NOTICE_NAME.search(path.name):
                yield path, path.name


def repository_revision(package: dict, root: Path):
    info_path = root / ".cargo_vcs_info.json"
    if not info_path.is_file():
        return None
    info = json.loads(info_path.read_text())
    revision = info.get("git", {}).get("sha1", "")
    repository = package.get("repository") or ""
    parsed = urllib.parse.urlparse(repository.removesuffix(".git"))
    parts = parsed.path.strip("/").split("/")
    if parsed.hostname != "github.com" or len(parts) < 2 or not re.fullmatch(r"[a-f0-9]{40}", revision):
        return None
    owner, name = parts[:2]
    if not all(re.fullmatch(r"[A-Za-z0-9_.-]+", p) for p in (owner, name)):
        return None
    vcs_path = PurePosixPath(info.get("path_in_vcs", ""))
    if vcs_path.is_absolute() or ".." in vcs_path.parts:
        return None
    return {"repository": f"https://github.com/{owner}/{name}", "revision": revision, "path_in_vcs": str(vcs_path)}


def request_bytes(url: str, cache: Path) -> bytes:
    key = sha256(url.encode())
    cached = cache / key
    if cached.is_file():
        return cached.read_bytes()
    request = urllib.request.Request(url, headers={"User-Agent": "Android-native-notice-collector/1"})
    with urllib.request.urlopen(request, timeout=25) as response:
        data = response.read(MAX_NOTICE_BYTES + 1)
    if len(data) > MAX_NOTICE_BYTES:
        raise ValueError("response exceeds notice size limit")
    # Different packages may concurrently request the same workspace notice.
    # Atomic replacement prevents another worker from reading a partial cache file.
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temporary:
        temporary.write(data)
        temporary_path = Path(temporary.name)
    temporary_path.replace(cached)
    return data


def fetch_revision_notices(spec: dict, cache: Path) -> dict:
    """Fetch only notice-like files at the package or an enclosing workspace."""
    repository, revision = spec["repository"], spec["revision"]
    repo_path = urllib.parse.urlparse(repository).path.strip("/")
    package_path = PurePosixPath(spec["path_in_vcs"])
    ancestors = {str(package_path), *(str(p) for p in package_path.parents), "."}
    selected = set()
    errors = []
    api_url = f"https://api.github.com/repos/{repo_path}/git/trees/{revision}?recursive=1"
    try:
        tree = json.loads(request_bytes(api_url, cache))
        if tree.get("truncated"):
            errors.append("GitHub tree response was truncated")
        for entry in tree.get("tree", []):
            path = PurePosixPath(entry.get("path", ""))
            if entry.get("type") != "blob":
                continue
            parent = str(path.parent)
            if NOTICE_NAME.search(path.name) and parent in ancestors:
                selected.add(str(path))
            elif path.parent.name.lower() in {"licenses", "licences"} and str(path.parent.parent) in ancestors:
                selected.add(str(path))
    except (OSError, ValueError, urllib.error.URLError) as error:
        errors.append(f"tree lookup failed: {error}")
    if not selected:
        # Exact revisions only. A failed lookup must never fall back to HEAD.
        for ancestor in sorted(ancestors):
            for name in ("LICENSE", "LICENSE-MIT", "LICENSE-APACHE", "LICENSE.md", "LICENSE.txt", "COPYING", "NOTICE"):
                selected.add(str(PurePosixPath(ancestor) / name))
    files = []
    for relative in sorted(selected):
        url = f"https://raw.githubusercontent.com/{repo_path}/{revision}/{urllib.parse.quote(relative)}"
        try:
            data = request_bytes(url, cache)
            files.append({"path": relative, "url": url, "data": data})
        except urllib.error.HTTPError as error:
            if error.code != 404:
                errors.append(f"{relative}: HTTP {error.code}")
        except (OSError, ValueError, urllib.error.URLError) as error:
            errors.append(f"{relative}: {error}")
    return {"files": files, "errors": errors}


def add_file(output: Path, record: dict, source: Path | None, relative: str, provenance: dict, data: bytes | None = None):
    relative_path = PurePosixPath(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts:
        raise ValueError(f"unsafe notice destination: {relative}")
    data = source.read_bytes() if data is None else data
    if len(data) > MAX_NOTICE_BYTES:
        raise ValueError(f"notice exceeds size limit: {relative}")
    destination = output / record["directory"] / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    record["files"].append({"path": relative, "sha256": sha256(data), "bytes": len(data), **provenance})


def collect_metadata(args):
    collected = []
    if args.metadata:
        for item in args.metadata:
            target, separator, file = item.partition("=")
            if not separator:
                raise ValueError("--metadata must be TARGET=/path/to/metadata.json")
            data = Path(file).read_bytes()
            collected.append((target, json.loads(data), sha256(data)))
    else:
        for target in args.target or ["aarch64-linux-android", "x86_64-linux-android"]:
            command = ["cargo", "+1.95.0", "metadata", "--format-version", "1", "--locked", "--offline", "--filter-platform", target, "--manifest-path", str(args.manifest_path)]
            data = subprocess.check_output(command)
            collected.append((target, json.loads(data), sha256(data)))
    return collected


def write_bundle(output: Path, index: dict) -> dict:
    """Write a new deterministic archive without changing any input bundle."""
    archive = Path(str(output) + ".zip")
    if archive.exists():
        raise ValueError(f"Archive already exists: {archive}")
    summarize_distribution_review(index)
    write_distribution_review(output, index)
    gaps = index["coverage_gaps"]
    (output / "index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    lines = ["# Native dependency notices", "", index["scope"], "", "This bundle preserves source-provided license and notice texts, with hashes and provenance in `index.json`. It does not certify legal completeness. License elections are limited to the exact-source dispositions recorded in `DISTRIBUTION_REVIEW.md`.", "", "Reviewed source excerpts distinguish complete license text from copyright or license references. Reference-only excerpts and separately supplied official terms do not clear a missing-source-text gap.", "", f"Unresolved distribution dispositions: {index['unresolved_distribution_count']}. See `DISTRIBUTION_REVIEW.md` for the reviewed materials and `SOURCE_CODE.md` for MPL source availability.", "", "## Missing source-provided license texts", ""]
    lines.extend(f"- {g['package']}: {'; '.join(g['gaps'])}" for g in gaps)
    if not gaps:
        lines.append("No package-level license-text gaps detected by the collector. Embedded notices still require human review.")
    (output / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for path in sorted(output.rglob("*")):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(output).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                bundle.writestr(info, path.read_bytes())
    return {"output": str(output), "archive": str(archive), "archive_sha256": sha256(archive.read_bytes()), "packages": len(index["packages"]), "coverage_gap_count": len(gaps), "coverage_gaps": gaps, "reviewed_distribution_count": index["reviewed_distribution_count"], "unresolved_distribution_count": index["unresolved_distribution_count"], "unresolved_distribution": index["unresolved_distribution"], "distribution_review_manifest_sha256": index["distribution_review_manifest_sha256"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New or empty output directory; never overwrites a prior bundle")
    parser.add_argument("--manifest-path", type=Path, default=Path(__file__).resolve().parents[1] / "Cargo.toml")
    parser.add_argument("--metadata", action="append", help="Reuse TARGET=FILE from cargo metadata --locked --filter-platform TARGET")
    parser.add_argument("--target", action="append")
    parser.add_argument("--upstream-root", required=True, type=Path)
    parser.add_argument("--rust-doc-root", type=Path, help="Rust sysroot share/doc/rust, including COPYRIGHT-library.html")
    parser.add_argument("--ndk-root", type=Path)
    parser.add_argument("--fetch-missing", action="store_true", help="Fetch missing notice text only at .cargo_vcs_info.json exact GitHub revisions")
    parser.add_argument("--cache", type=Path, help="Optional reusable downloaded notice cache")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Output directory is not empty; use a new output path.")
    archive = Path(str(output) + ".zip")
    if archive.exists():
        parser.error(f"Archive already exists: {archive}")
    output.mkdir(parents=True, exist_ok=True)
    cache = (args.cache or output.parent / "notice-download-cache").resolve()
    cache.mkdir(parents=True, exist_ok=True)
    metadata = collect_metadata(args)
    packages, package_targets, roots, metadata_index = {}, {}, set(), []
    for target, graph, digest in metadata:
        available = {p["id"]: p for p in graph["packages"]}
        nodes = {n["id"]: n for n in graph["resolve"]["nodes"]}
        root = graph["resolve"]["root"]
        roots.add(root)
        pending, reached = [root], set()
        while pending:
            current = pending.pop()
            if current in reached:
                continue
            reached.add(current)
            pending.extend(dep["pkg"] for dep in nodes[current]["deps"] if any(k["kind"] != "dev" for k in dep["dep_kinds"]))
        for package_id in reached:
            packages[package_id] = available[package_id]
            package_targets.setdefault(package_id, []).append(target)
        lock = Path(graph["workspace_root"]) / "Cargo.lock"
        metadata_index.append({"target": target, "metadata_sha256": digest, "cargo_lock_sha256": sha256(lock.read_bytes()), "reachable_packages_including_build_dependencies": len(reached)})

    records, missing = [], []
    for package_id, package in sorted(packages.items(), key=lambda item: (item[1]["name"], item[1]["version"], item[1].get("source") or "")):
        root = Path(package["manifest_path"]).parent
        name = f"{package['name']}-{package['version']}"
        if any(r["directory"] == f"packages/{name}" for r in records):
            name += "-" + sha256((package.get("source") or package_id).encode())[:10]
        record = {"name": package["name"], "version": package["version"], "declared_license": package.get("license"), "source": package.get("source"), "repository": package.get("repository"), "authors": package.get("authors", []), "targets": sorted(package_targets[package_id]), "directory": f"packages/{name}", "is_probe_root": package_id in roots, "files": [], "coverage_gaps": []}
        vcs = repository_revision(package, root)
        if vcs:
            record["published_crate_vcs"] = vcs
        if (package.get("source") or "").startswith("registry+"):
            record["source_archive"] = f"https://crates.io/api/v1/crates/{package['name']}/{package['version']}/download"
        for path, relative in file_candidates(root):
            add_file(output, record, path, f"crate/{relative}", {"origin": "published-package" if package.get("source") else "pinned-checkout"})
        declared = package.get("license_file")
        if declared:
            path = root / declared
            if path.is_file():
                add_file(output, record, path, f"declared-license/{path.name}", {"origin": "manifest-license-file"})
            else:
                record["coverage_gaps"].append("Manifest license_file is missing")
        try:
            root.resolve().relative_to(args.upstream_root.resolve())
            in_upstream = True
        except ValueError:
            in_upstream = False
        if in_upstream:
            for path, relative in root_notices(args.upstream_root):
                add_file(output, record, path, f"upstream-workspace/{relative}", {"origin": "codex-workspace-root"})
            record["source_revision"] = subprocess.check_output(["git", "-C", str(args.upstream_root), "rev-parse", "HEAD"], text=True).strip()
        # sqlite3.c carries SQLite's public-domain dedication in its source header.
        if package["name"] == "libsqlite3-sys":
            sqlite = root / "sqlite3/sqlite3.c"
            if sqlite.is_file():
                data = b"".join(sqlite.read_bytes().splitlines(keepends=True)[:45])
                add_file(output, record, None, "source-notices/sqlite3-first-45-lines.txt", {"origin": "verbatim-source-header", "source_path": "sqlite3/sqlite3.c", "source_lines": [1, 45]}, data)
        add_reviewed_source_notices(output, record, lambda relative: (root / relative).read_bytes())
        add_reviewed_upstream_notices(output, record, lambda relative: (root / relative).read_bytes())
        has_license = has_license_text(record)
        if not has_license and not record["is_probe_root"]:
            missing.append((record, vcs))
        records.append(record)

    if args.fetch_missing:
        tasks = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            for record, spec in missing:
                if spec:
                    key = (spec["repository"], spec["revision"], spec["path_in_vcs"])
                    if key not in tasks:
                        tasks[key] = pool.submit(fetch_revision_notices, spec, cache)
            for record, spec in missing:
                if not spec:
                    continue
                result = tasks[(spec["repository"], spec["revision"], spec["path_in_vcs"])].result()
                for item in result["files"]:
                    add_file(output, record, None, f"upstream-revision/{item['path']}", {"origin": "exact-revision-download", "url": item["url"]}, item["data"])
                record["supplement_fetch_notes"] = result["errors"]

    for record, _ in missing:
        if not has_license_text(record):
            record["coverage_gaps"].append(MISSING_LICENSE_TEXT)
    toolchains = []
    if args.rust_doc_root:
        record = {"name": "Rust standard library", "directory": "toolchains/rust", "files": []}
        for path, relative in file_candidates(args.rust_doc_root):
            if relative == "COPYRIGHT-library.html" or relative.startswith("licenses/"):
                add_file(output, record, path, relative, {"origin": "installed-rust-toolchain"})
        toolchains.append(record)
    if args.ndk_root:
        record = {"name": "Android NDK runtime and toolchain notices (conservative superset)", "directory": "toolchains/android-ndk", "files": []}
        for relative in ("NOTICE", "NOTICE.toolchain", "toolchains/llvm/prebuilt/linux-x86_64/NOTICE", "toolchains/llvm/prebuilt/linux-x86_64/sysroot/NOTICE"):
            path = args.ndk_root / relative
            if path.is_file():
                add_file(output, record, path, relative, {"origin": "installed-ndk"})
        toolchains.append(record)
    gaps = [{"package": f"{r['name']} {r['version']}", "gaps": r["coverage_gaps"]} for r in records if r["coverage_gaps"]]
    index = {"schema_version": 1, "scope": "Reachable target-filtered Cargo dependency graph, including build dependencies; conservative superset of linked code.", "limitations": ["Collection of notice-like files is not a legal completeness or license compatibility determination.", "Embedded source comments and custom naming may require further review.", "Some included build dependencies and vendored source components may not be distributed in the APK.", "Source archives and repository revisions identify unmodified third-party sources; no license option is selected automatically."], "inputs": metadata_index, "package_count": len(records), "packages": records, "toolchains": toolchains, "coverage_gap_count": len(gaps), "coverage_gaps": gaps}
    print(json.dumps(write_bundle(output, index), indent=2))
    return 2 if gaps else 0


if __name__ == "__main__":
    sys.exit(main())
