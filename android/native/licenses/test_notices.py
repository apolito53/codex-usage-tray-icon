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
            {"path": "reviewed-upstream/Apache-2.0.txt", "counts_as_source_provided_license_text": False},
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

    def test_reviewed_official_terms_require_exact_evidence_without_hiding_raw_gap(self):
        # Fixture terms are deliberately not third-party license texts.
        source = b"Fixture explicitly offers Apache-2.0\n"
        notice = b"Fixture official terms\n"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "terms.txt").write_bytes(notice)
            spec = {
                "basis": "explicit-published-apache-alternative", "finding": "Fixture only",
                "source_archive_sha256": "a" * 64, "selected_license": "Apache-2.0",
                "source_matches": [{"source_path": "Cargo.toml", "sha256": collector.sha256(source), "required_reference": "Apache-2.0"}],
                "notice_files": [{"local_path": "terms.txt", "upstream_path": "LICENSE-APACHE", "url": "https://example.invalid/fixture", "sha256": collector.sha256(notice), "content_kind": "official-license-text"}],
            }
            record = {"name": "review-fixture", "version": "1.0", "source": "registry+fixture", "directory": "packages/fixture", "files": [], "coverage_gaps": [collector.MISSING_LICENSE_TEXT]}
            with patch.object(collector, "reviewed_upstream_specs", return_value={(record["name"], record["version"]): spec}), patch.object(collector, "__file__", str(root / "collect_notices.py")):
                with self.assertRaisesRegex(ValueError, "source hash mismatch"):
                    collector.add_reviewed_upstream_notices(root, record, lambda _: source + b"changed")
                self.assertEqual(2, collector.add_reviewed_upstream_notices(root, record, lambda _: source))
                self.assertEqual(0, collector.add_reviewed_upstream_notices(root, record, lambda _: source))
                self.assertFalse(collector.has_license_text(record))
                self.assertEqual([collector.MISSING_LICENSE_TEXT], record["coverage_gaps"])
                self.assertEqual("Apache-2.0", record["distribution_review"]["selected_license"])
                (root / "terms.txt").write_bytes(notice + b"changed")
                with self.assertRaisesRegex(ValueError, "notice hash mismatch"):
                    collector.add_reviewed_upstream_notices(root, record, lambda _: source)

    def test_unreviewed_new_version_remains_a_distribution_gap(self):
        index = {"packages": [{"name": "unknown-version-fixture", "version": "2.0", "coverage_gaps": [collector.MISSING_LICENSE_TEXT]}]}
        collector.summarize_distribution_review(index)
        self.assertEqual(1, index["unresolved_distribution_count"])
        self.assertEqual(0, index["reviewed_distribution_count"])
        self.assertEqual("unknown-version-fixture 2.0", index["unresolved_distribution"][0]["package"])

    def test_vendored_reviewed_notices_retain_their_original_bytes(self):
        root = Path(collector.__file__).parent
        for spec in collector.reviewed_upstream_specs().values():
            for item in spec["notice_files"]:
                with self.subTest(package=spec["name"], path=item["local_path"]):
                    self.assertEqual(item["sha256"], collector.sha256((root / item["local_path"]).read_bytes()))


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

    def test_inherited_review_status_cannot_clear_an_unreviewed_gap(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original, index = self.make_input(root)
            record = index["packages"][0]
            record["files"] = []
            record["coverage_gaps"] = [collector.MISSING_LICENSE_TEXT]
            record["distribution_review"] = {"status": "reviewed", "finding": "Untrusted inherited fixture claim"}
            with zipfile.ZipFile(original, "w") as bundle:
                bundle.writestr("index.json", json.dumps(index))
            report = supplement.supplement(original, root, root / "output")
            self.assertEqual(1, report["unresolved_distribution_count"])
            self.assertEqual(0, report["reviewed_distribution_count"])

    def test_unsafe_bundle_paths_are_rejected(self):
        for name in ("../escape", "/absolute", "C:/escape", "folder\\escape"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                supplement.safe_relative(name)


if __name__ == "__main__":
    unittest.main()
