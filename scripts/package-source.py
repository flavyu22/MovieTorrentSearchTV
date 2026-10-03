#!/usr/bin/env python3
"""Create a deterministic, secret-free source archive."""
from __future__ import annotations

import argparse
import os
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_PARTS = {".git", ".gradle", ".idea", ".kotlin", "build", ".artifacts", ".externalNativeBuild", ".cxx", "__pycache__", "java-upgrade"}
EXCLUDED_NAMES = {
    "local.properties",
    "keystore.properties",
    "secrets.properties",
    ".env",
    ".DS_Store",
    # Auto-generated from the local IDE JVM; machine-specific and not part of the source.
    "gradle-daemon-jvm.properties",
    # Audit H4: local build/debug artifacts that must never reach the source archive.
    "$err",
    "$log",
    "MovieTorrentSearchTV-gradle-tests",
    "build_app_out.txt",
    "build_test_out.txt",
    "build-output.txt",
    "build-error.txt",
    "dt_headers.txt",
}


def included(path: Path) -> bool:
    rel = path.relative_to(ROOT)
    return (
        path.is_file()
        and not any(part in EXCLUDED_PARTS for part in rel.parts)
        and path.name not in EXCLUDED_NAMES
        and (path.suffix != ".bat" or path.name == "gradlew.bat")
        and not path.name.endswith(
            (".jks", ".keystore", ".p12", ".pem", ".part", ".lck", ".pyc",
             ".log", ".pid", ".torrent", ".html", ".out")
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", nargs="?", default=str(ROOT.parent / "MovieTorrentSearchTV-2.0.0-professional-repaired-2026-08-07-source.zip"))
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    subprocess.run(["python3", str(ROOT / "scripts/verify-project.py")], cwd=ROOT, check=True)
    files = sorted((p for p in ROOT.rglob("*") if included(p) and p.resolve() != output), key=lambda p: p.relative_to(ROOT).as_posix())
    prefix = "MovieTorrentSearchTV-2.0.0"
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            rel = path.relative_to(ROOT).as_posix()
            info = zipfile.ZipInfo(f"{prefix}/{rel}", date_time=(2026, 8, 7, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if os.access(path, os.X_OK) else 0o644) << 16
            archive.writestr(info, path.read_bytes())
    print(output)


if __name__ == "__main__":
    main()
