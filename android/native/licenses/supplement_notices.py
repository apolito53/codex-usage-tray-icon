#!/usr/bin/env python3
"""Add reviewed exact-version source attribution to an existing native notices ZIP.

No Rust installation, dependency resolution, mutable branch fetch, or alteration
of the original bundle is involved. Full-text gaps remain explicit.
"""

from __future__ import annotations

import argparse
import io
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile
import zipfile

from collect_notices import (
    MAX_NOTICE_BYTES,
    MISSING_LICENSE_TEXT,
    add_reviewed_source_notices,
    has_license_text,
    reviewed_source_specs,
    sha256,
    write_bundle,
)


def safe_relative(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise ValueError(f"Unsafe bundle path: {name}")
    return path


def supplement(input_zip: Path, crate_archives: Path, output: Path) -> dict:
    output = output.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output directory is not empty; use a new path")
    if Path(str(output) + ".zip").exists():
        raise ValueError("Output ZIP already exists; use a new path")
    output.mkdir(parents=True, exist_ok=True)
    original_bytes = input_zip.read_bytes()
    with zipfile.ZipFile(io.BytesIO(original_bytes)) as bundle:
        if sum(item.file_size for item in bundle.infolist()) > 256 * 1024 * 1024:
            raise ValueError("Input notices bundle exceeds size limit")
        for item in bundle.infolist():
            path = safe_relative(item.filename)
            if item.is_dir():
                continue
            if item.file_size > MAX_NOTICE_BYTES:
                raise ValueError(f"Input notice exceeds size limit: {item.filename}")
            destination = output.joinpath(*path.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(bundle.read(item))
    index = json.loads((output / "index.json").read_text(encoding="utf-8"))
    old_gap_count = index["coverage_gap_count"]
    added = 0
    for record in index["packages"]:
        safe_relative(record["directory"])
        # Verify all retained originals against the downloaded bundle's index.
        for item in record["files"]:
            relative = safe_relative(item["path"])
            if sha256((output / record["directory"]).joinpath(*relative.parts).read_bytes()) != item["sha256"]:
                raise ValueError(f"Original notice hash mismatch: {record['name']} {item['path']}")
        spec = reviewed_source_specs().get((record["name"], record["version"]))
        if spec is not None:
            prefix = f"{record['name']}-{record['version']}"
            archive_bytes = (crate_archives / f"{prefix}.crate").read_bytes()
            if sha256(archive_bytes) != spec["source_archive_sha256"]:
                raise ValueError(f"Published crate archive hash mismatch: {prefix}")
            with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode="r:gz") as archive:
                def read_source(relative: str) -> bytes:
                    safe_relative(relative)
                    member = archive.getmember(f"{prefix}/{relative}")
                    if not member.isfile() or member.size > MAX_NOTICE_BYTES:
                        raise ValueError(f"Invalid source member: {prefix}/{relative}")
                    with archive.extractfile(member) as source:
                        return source.read()
                added += add_reviewed_source_notices(output, record, read_source)
        if has_license_text(record):
            record["coverage_gaps"] = [gap for gap in record["coverage_gaps"] if gap != MISSING_LICENSE_TEXT]
    gaps = [{"package": f"{record['name']} {record['version']}", "gaps": record["coverage_gaps"]} for record in index["packages"] if record["coverage_gaps"]]
    index["coverage_gap_count"] = len(gaps)
    index["coverage_gaps"] = gaps
    manifest = Path(__file__).with_name("reviewed-source-notices.json")
    index.setdefault("supplements", []).append({
        "origin": "reviewed-exact-version-source-notices",
        "input_archive_sha256": sha256(original_bytes),
        "review_manifest_sha256": sha256(manifest.read_bytes()),
        "input_coverage_gap_count": old_gap_count,
        "reviewed_source_excerpts_added": added,
        "coverage_gap_count": len(gaps),
        "limitation": "Source copyright and license references are preserved but do not substitute for missing complete license texts.",
    })
    return write_bundle(output, index)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Existing native-notices.zip; never modified")
    parser.add_argument("--crate-archives", required=True, type=Path, help="Directory of the exact NAME-VERSION.crate archives")
    parser.add_argument("--output", required=True, type=Path, help="New or empty output directory")
    args = parser.parse_args()
    report = supplement(args.input, args.crate_archives, args.output)
    print(json.dumps(report, indent=2))
    return 2 if report["coverage_gap_count"] else 0


if __name__ == "__main__":
    sys.exit(main())
