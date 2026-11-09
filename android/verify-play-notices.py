"""Check native inputs and the reviewed distribution evidence actually inside a Play AAB."""

import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(ok, message):
    if not ok:
        raise ValueError(message)


def check(bundle, native):
    with zipfile.ZipFile(bundle) as z:
        wanted = {f"base/lib/{abi}/libcodex_android_native_probe.so" for abi in ("arm64-v8a", "x86_64")}
        require({n for n in z.namelist() if n.startswith("base/lib/") and n.endswith(".so")} == wanted,
                "missing or unexpected native libraries")
        for n in wanted:
            require(digest(z.read(n)) == digest((native / n.removeprefix("base/lib/")).read_bytes()), n)
        for name in ("codex-LICENSE", "codex-NOTICE"):
            require(z.getinfo("base/assets/" + name).file_size > 0, f"missing {name}")
        archive = z.read("base/assets/native-notices.zip")
        report_bytes = z.read("base/assets/notice-report.json")
        report = json.loads(report_bytes)
        require(digest(report_bytes) == digest((native / "notices/notice-report.json").read_bytes()),
                "packaged notice report does not match the reviewed input")
        require(digest(archive) == report["archive_sha256"] == digest((native / "notices/native-notices.zip").read_bytes()),
                "packaged notices do not match the reviewed archive")
        require(report.get("unresolved_distribution_count") == 0 and report.get("unresolved_distribution") == [],
                "unresolved distribution review, or an older notice report without a distribution review")
        manifest = Path(__file__).parent / "native/licenses/reviewed-upstream-notices.json"
        require(digest(manifest.read_bytes()) == report.get("distribution_review_manifest_sha256"),
                "packaged notices use a different distribution review manifest")
        with zipfile.ZipFile(io.BytesIO(archive)) as notices:
            for name in ("DISTRIBUTION_REVIEW.md", "SOURCE_CODE.md"):
                require(notices.getinfo(name).file_size > 0, f"missing packaged {name}")
        print(f"{report['packages']} native packages; {report['coverage_gap_count']} source-text gaps; "
              f"{report['unresolved_distribution_count']} unresolved distribution reviews.")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: verify-play-notices.py app.aab native/out")
    try:
        check(Path(sys.argv[1]), Path(sys.argv[2]))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
