"""Installer-wrapper tests; synthetic bytes are not an installable/signed APK."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-evaluation.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
SOURCE = "a" * 40
RUN = "https://github.com/TwoWetKnuckles/echoscript/actions/runs/123"


class PublicationWrapperTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="echoscript-publication-")
        self.root = Path(self.temp.name)
        self.folder = self.root / "input"
        self.folder.mkdir()
        contents = {publisher.APK: b"SYNTHETIC WRAPPER VECTOR, NOT AN APK",
                    "BUILD.txt": f"Source commit {SOURCE}\nQuality checks {RUN}\n".encode(),
                    "INSTALL.txt": b"BETA GATE: NOT PASSED\n", "LICENSE.txt": b"Synthetic license vector",
                    "NOTICE.txt": b"Synthetic notice vector"}
        for name, content in contents.items():
            (self.folder / name).write_bytes(content)
        (self.folder / "SHA256SUMS.txt").write_text("".join(
            f"{publisher.digest(self.folder / name)}  {name}\n" for name in contents))

    def tearDown(self):
        self.temp.cleanup()

    def test_repeatable_wrapper_retains_every_original_member(self):
        first = publisher.prepare(self.folder, self.root / "one", SOURCE, RUN)
        second = publisher.prepare(self.folder, self.root / "two", SOURCE, RUN)
        self.assertEqual(first[1].read_bytes(), second[1].read_bytes())
        with zipfile.ZipFile(first[1]) as archive:
            for path in self.folder.iterdir():
                self.assertEqual(archive.read("EchoScript-Android/" + path.name), path.read_bytes())

    def test_changed_apk_fails_before_packaging(self):
        (self.folder / publisher.APK).write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            publisher.prepare(self.folder, self.root / "out", SOURCE, RUN)

    def test_another_source_or_run_is_rejected(self):
        for source, run in [("b" * 40, RUN), (SOURCE, RUN + "4")]:
            with self.assertRaisesRegex(ValueError, "another source or workflow"):
                publisher.prepare(self.folder, self.root / "out", source, run)

    def test_extra_or_symlinked_member_is_rejected(self):
        link = self.folder / "unexpected.txt"
        link.symlink_to(self.folder / "BUILD.txt")
        with self.assertRaisesRegex(ValueError, "Unexpected or missing"):
            publisher.prepare(self.folder, self.root / "out", SOURCE, RUN)

    def test_duplicate_checksum_does_not_pass(self):
        checksums = self.folder / "SHA256SUMS.txt"
        checksums.write_text(checksums.read_text() + checksums.read_text().splitlines()[0] + "\n")
        with self.assertRaisesRegex(ValueError, "Malformed checksum"):
            publisher.prepare(self.folder, self.root / "out", SOURCE, RUN)

    def test_retirement_rejects_changed_or_added_remote_assets(self):
        inventory = {"releaseId": 12, "assets": [{"name": "old.zip", "bytes": 20, "sha256": "a" * 64}]}
        release = {"id": 12, "assets": [{"name": "old.zip", "size": 20, "digest": "sha256:" + "a" * 64}]}
        self.assertTrue(publisher.matches_archive(release, inventory))
        release["assets"][0]["digest"] = "sha256:" + "b" * 64
        self.assertFalse(publisher.matches_archive(release, inventory))
        release["assets"][0]["digest"] = "sha256:" + "a" * 64
        release["assets"].append({"name": "extra.zip", "size": 20, "digest": "sha256:" + "a" * 64})
        self.assertFalse(publisher.matches_archive(release, inventory))


if __name__ == "__main__":
    unittest.main()
