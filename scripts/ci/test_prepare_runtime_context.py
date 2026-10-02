"""Transport tests for the runtime-only CI handoff; no Gradle or registry operations."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("runtime_context", Path(__file__).with_name("prepare-runtime-context.py"))
assert SPEC is not None and SPEC.loader is not None
RUNTIME = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = RUNTIME
SPEC.loader.exec_module(RUNTIME)


class RuntimeContextTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.archive = self.root / "runtime-dist.tar"

    def tar(self, extra: list[tarfile.TarInfo] | None = None, launcher: bool = True,
            root: str = "plainbase-0.0.0-ci", executable: bool = True) -> str:
        with tarfile.open(self.archive, "w") as archive:
            for path, contents, mode in [("lib/server.jar", b"library", 0o644)] + (
                [("bin/plainbase", b"#!/bin/sh\n", 0o755 if executable else 0o644)] if launcher else []
            ):
                member = tarfile.TarInfo(f"{root}/{path}")
                member.size = len(contents)
                member.mode = mode
                archive.addfile(member, io.BytesIO(contents))
            for member in extra or []:
                archive.addfile(member, io.BytesIO(b""))
        return hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def test_smoke_and_docker_paths_preserve_bytes_and_permissions(self) -> None:
        digest = self.tar()
        for docker in (False, True):
            with self.subTest(docker=docker):
                output = self.root / ("docker" if docker else "dist")
                RUNTIME.prepare(self.archive, digest, output, docker)
                installed = output / ("src/server/build/install/plainbase" if docker else "plainbase-0.0.0-ci")
                self.assertEqual((installed / "lib/server.jar").read_bytes(), b"library")
                self.assertTrue((installed / "bin/plainbase").stat().st_mode & 0o111)
                self.assertFalse((installed / "lib/server.jar").stat().st_mode & 0o111)

    def test_checksum_failure_leaves_destination_absent(self) -> None:
        self.tar()
        output = self.root / "dist"
        with self.assertRaisesRegex(ValueError, "checksum"):
            RUNTIME.prepare(self.archive, "0" * 64, output)
        self.assertFalse(output.exists())

    def test_traversal_absolute_duplicate_and_unexpected_paths_fail(self) -> None:
        for path in ("../outside", "/outside", "plainbase-0.0.0-ci/../outside", "plainbase-0.0.0-ci/lib/server.jar",
                     "plainbase-0.0.0-ci/lib/nested/server.jar", "plainbase-0.0.0-ci/config", "other/lib/a.jar"):
            with self.subTest(path=path):
                digest = self.tar([tarfile.TarInfo(path)])
                with self.assertRaises(ValueError):
                    RUNTIME.prepare(self.archive, digest, self.root / "dist")
                self.assertFalse((self.root / "dist").exists())

    def test_links_and_special_files_fail(self) -> None:
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE):
            with self.subTest(kind=kind):
                member = tarfile.TarInfo("plainbase-0.0.0-ci/lib/link.jar")
                member.type = kind
                member.linkname = "outside"
                digest = self.tar([member])
                with self.assertRaisesRegex(ValueError, "links/special"):
                    RUNTIME.prepare(self.archive, digest, self.root / "dist")

    def test_missing_or_nonexecutable_launcher_fails(self) -> None:
        for launcher, executable in ((False, True), (True, False)):
            with self.subTest(launcher=launcher, executable=executable):
                digest = self.tar(launcher=launcher, executable=executable)
                with self.assertRaisesRegex(ValueError, "executable"):
                    RUNTIME.prepare(self.archive, digest, self.root / "dist")

    def test_installed_distribution_must_equal_tar_inventory(self) -> None:
        digest = self.tar()
        output = self.root / "dist"
        RUNTIME.prepare(self.archive, digest, output)
        installed = output / "plainbase-0.0.0-ci"
        RUNTIME.prepare(self.archive, digest, installed=installed)
        library = installed / "lib/server.jar"
        library.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "differs"):
            RUNTIME.prepare(self.archive, digest, installed=installed)
        library.write_bytes(b"library")
        (installed / "lib/extra.jar").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "differs"):
            RUNTIME.prepare(self.archive, digest, installed=installed)

    def test_symlink_and_existing_destinations_fail(self) -> None:
        digest = self.tar()
        real = self.root / "real"
        real.mkdir()
        link = self.root / "link"
        link.symlink_to(real, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            RUNTIME.prepare(self.archive, digest, link / "dist")
        with self.assertRaisesRegex(ValueError, "fresh"):
            RUNTIME.prepare(self.archive, digest, real)


if __name__ == "__main__":
    unittest.main()
