#!/usr/bin/env python3
"""Validate a same-run runtime tar before preparing a Docker context or smoke distribution."""

from __future__ import annotations

import argparse
import hashlib
import logging
import re
import shutil
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import BinaryIO

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class RuntimeFile:
    path: str
    sha256: str
    executable: bool


def sha256(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    while block := stream.read(128 * 1024):
        digest.update(block)
    return digest.hexdigest()


def reject_symlinks(path: Path) -> None:
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise ValueError(f"Symlink destination/input: {ancestor}")


def inventory(archive: tarfile.TarFile) -> tuple[str, list[RuntimeFile]]:
    members = archive.getmembers()
    seen: set[str] = set()
    roots: set[str] = set()
    files: list[RuntimeFile] = []
    for member in members:
        name = member.name.removesuffix("/")
        parts = name.split("/")
        if not name or any(part in ("", ".", "..") for part in parts) or "\\" in name or ":" in name:
            raise ValueError(f"Unsafe tar path: {member.name}")
        if PurePosixPath(name).is_absolute() or name in seen:
            raise ValueError(f"Absolute/duplicate tar path: {member.name}")
        seen.add(name)
        root = parts[0]
        if not re.fullmatch(r"plainbase-[A-Za-z0-9.+-]+", root):
            raise ValueError(f"Unexpected distribution root: {root}")
        roots.add(root)
        if member.isdir():
            if len(parts) > 2 or (len(parts) == 2 and parts[1] not in ("bin", "lib")):
                raise ValueError(f"Unexpected tar directory: {name}")
        elif member.isfile():
            relative = "/".join(parts[1:])
            if len(parts) != 3 or not (relative == "bin/plainbase" or (parts[1] == "lib" and parts[2].endswith(".jar"))):
                raise ValueError(f"Unexpected runtime file: {name}")
            if member.mode & 0o7000:
                raise ValueError(f"Special runtime permissions: {name}")
            stream = archive.extractfile(member)
            if stream is None:
                raise ValueError(f"Missing runtime file: {name}")
            with stream:
                files.append(RuntimeFile(relative, sha256(stream), bool(member.mode & 0o111)))
        else:
            raise ValueError(f"Tar links/special files are forbidden: {name}")
    if len(roots) != 1:
        raise ValueError("Expected exactly one distribution root")
    launcher = [file for file in files if file.path == "bin/plainbase"]
    if len(launcher) != 1 or not launcher[0].executable or not any(file.path.startswith("lib/") for file in files):
        raise ValueError("Expected one executable bin/plainbase and runtime lib jars")
    return next(iter(roots)), sorted(files, key=lambda file: file.path)


def installed_inventory(installed: Path) -> list[RuntimeFile]:
    reject_symlinks(installed)
    files: list[RuntimeFile] = []
    for path in installed.rglob("*"):
        reject_symlinks(path)
        if path.is_file():
            with path.open("rb") as stream:
                files.append(RuntimeFile(path.relative_to(installed).as_posix(), sha256(stream), bool(path.stat().st_mode & 0o111)))
        elif not path.is_dir():
            raise ValueError(f"Special installed file: {path}")
    return sorted(files, key=lambda file: file.path)


def prepare(archive_path: Path, expected_sha256: str, output: Path | None = None,
            docker: bool = False, installed: Path | None = None) -> None:
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ValueError("Expected the producer's SHA-256")
    reject_symlinks(archive_path)
    with archive_path.open("rb") as stream:
        if sha256(stream) != expected_sha256:
            raise ValueError("Runtime tar checksum mismatch")
        stream.seek(0)
        with tarfile.open(fileobj=stream, mode="r:") as archive:
            root, files = inventory(archive)
            if installed is not None and installed_inventory(installed) != files:
                raise ValueError("Runtime tar differs from validated installed distribution")
            if output is None:
                if installed is None:
                    raise ValueError("An output or installed distribution is required")
                return
            reject_symlinks(output)
            if output.exists():
                raise ValueError(f"Output must be a fresh directory: {output}")
            output.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix="runtime-staging-", dir=output.parent) as temporary:
                staging = Path(temporary)
                destination = staging / ("src/server/build/install/plainbase" if docker else root)
                destination.mkdir(parents=True)
                for file in files:
                    path = destination / file.path
                    path.parent.mkdir(parents=True, exist_ok=True)
                    source = archive.extractfile(f"{root}/{file.path}")
                    if source is None:
                        raise ValueError(f"Missing runtime file: {file.path}")
                    with source, path.open("xb") as target:
                        shutil.copyfileobj(source, target)
                    path.chmod(0o755 if file.executable else 0o644)
                staging.rename(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--docker", action="store_true")
    parser.add_argument("--installed-dist", type=Path)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    try:
        prepare(args.archive, args.sha256, args.output, args.docker, args.installed_dist)
    except (ValueError, OSError, tarfile.TarError) as error:
        parser.exit(1, f"Runtime validation failed: {error}\n")
    LOGGER.info("Runtime tar validated%s", f" and prepared at {args.output}" if args.output else " against installed distribution")


if __name__ == "__main__":
    main()
