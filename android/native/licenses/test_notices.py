"""Focused, offline provenance/coverage regressions for the notice collector."""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import collect_notices as collector
import supplement_notices as supplement


class CoverageTests(unittest.TestCase):
    def test_known_license_directory_texts_count_but_readmes_and_references_do_not(self):
        for path in ("crate/LICENSES/MIT.txt", "upstream-revision/LICENSES/Apache-2.0.txt", "crate/LICENSE"):
            self.assertTrue(collector.has_license_text({"files": [{"path": path}]}))
        for item in (
            {"path": "crate/LICENSES/README.md"},
            {"path": "reviewed-source/lib.rs.lines-1-9.txt", "content_kind": "copyright-and-license-reference"},
            {"path": "reviewed-source/README.md.lines-22-22.txt", "content_kind": "license-reference"},
        ):
            self.assertFalse(collector.has_license_text({"files": [item]}))
        self.assertTrue(collector.has_license_text({"files": [{"path": "reviewed-source/lib.rs.lines-1-19.txt", "content_kind": "full-license-text"}]}))

    def test_reviewed_source_requires_exact_file_and_span_hashes(self):
        source = b"// Fixture attribution line\n// Fixture reference line\nfn fixture() {}\n"
        selected = b"".join(source.splitlines(keepends=True)[:2])
        spec = {
            "finding": "Test fixture, not third-party attribution",
            "source_archive_sha256": "a" * 64,
            "source_notices": [{
                "source_path": "src/lib.rs", "source_lines": [1, 2],
                "source_file_sha256": collector.sha256(source),
                "notice_sha256": collector.sha256(selected),
                "content_kind": "copyright-and-license-reference",
            }],
        }
        record = {"name": "notice-test-fixture", "version": "1.0", "source": "registry+fixture", "directory": "packages/fixture", "files": []}
        with tempfile.TemporaryDirectory() as temporary, patch.object(collector, "reviewed_source_specs", return_value={(record["name"], record["version"]): spec}):
            with self.assertRaisesRegex(ValueError, "source hash mismatch"):
                collector.add_reviewed_source_notices(Path(temporary), record, lambda _: source + b"changed")
            self.assertEqual(1, collector.add_reviewed_source_notices(Path(temporary), record, lambda _: source))
            self.assertEqual(0, collector.add_reviewed_source_notices(Path(temporary), record, lambda _: source))
            self.assertFalse(collector.has_license_text(record))
            item = record["files"][0]
            self.assertEqual(selected, (Path(temporary) / record["directory"] / item["path"]).read_bytes())
            self.assertEqual(collector.sha256(source), item["source_file_sha256"])
            self.assertEqual([1, 2], item["source_lines"])
            spec["source_notices"][0]["notice_sha256"] = "b" * 64
            with self.assertRaisesRegex(ValueError, "span mismatch"):
                collector.add_reviewed_source_notices(Path(temporary), record, lambda _: source)


class BundleTests(unittest.TestCase):
    def make_input(self, directory: Path, corrupt=False) -> tuple[Path, dict]:
        payload = b"Preserved fixture bytes; not an actual third-party license.\n"
        record = {
            "name": "bundle-test-fixture", "version": "1.0", "source": "registry+fixture",
            "directory": "packages/fixture", "files": [{"path": "crate/LICENSES/MIT.txt", "sha256": collector.sha256(payload)}],
            "coverage_gaps": [collector.MISSING_LICENSE_TEXT, "Manifest license_file is missing"],
        }
        index = {
            "schema_version": 1, "scope": "Offline test fixture", "package_count": 1, "packages": [record],
            "toolchains": [], "coverage_gap_count": 1,
            "coverage_gaps": [{"package": "bundle-test-fixture 1.0", "gaps": record["coverage_gaps"]}],
        }
        path = directory / "input.zip"
        with zipfile.ZipFile(path, "w") as bundle:
            bundle.writestr("index.json", json.dumps(index))
            bundle.writestr("packages/fixture/crate/LICENSES/MIT.txt", payload + (b"tampered" if corrupt else b""))
            bundle.writestr("toolchains/fixture/NOTICE", b"Original toolchain fixture bytes\n")
        return path, index

    def test_supplement_preserves_originals_and_other_gaps_and_is_deterministic(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(supplement, "reviewed_source_specs", return_value={}):
            root = Path(temporary)
            original, _ = self.make_input(root)
            original_hash = collector.sha256(original.read_bytes())
            first = supplement.supplement(original, root, root / "first")
            second = supplement.supplement(original, root, root / "second")
            self.assertEqual(first["archive_sha256"], second["archive_sha256"])
            self.assertEqual(original_hash, collector.sha256(original.read_bytes()))
            self.assertEqual(1, first["coverage_gap_count"])
            index = json.loads((root / "first/index.json").read_text())
            self.assertEqual(["Manifest license_file is missing"], index["packages"][0]["coverage_gaps"])
            self.assertEqual(original_hash, index["supplements"][0]["input_archive_sha256"])
            with zipfile.ZipFile(original) as before, zipfile.ZipFile(first["archive"]) as after:
                for path in ("packages/fixture/crate/LICENSES/MIT.txt", "toolchains/fixture/NOTICE"):
                    self.assertEqual(before.read(path), after.read(path))
            with self.assertRaisesRegex(ValueError, "not empty"):
                supplement.supplement(original, root, root / "first")

    def test_tampered_original_notice_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original, _ = self.make_input(root, corrupt=True)
            with self.assertRaisesRegex(ValueError, "Original notice hash mismatch"):
                supplement.supplement(original, root, root / "output")

    def test_unsafe_bundle_paths_are_rejected(self):
        for name in ("../escape", "/absolute", "C:/escape", "folder\\escape"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                supplement.safe_relative(name)


if __name__ == "__main__":
    unittest.main()
