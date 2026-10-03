#!/usr/bin/env python3
"""Generate a deterministic CycloneDX inventory for declared Maven dependencies.

This intentionally reads the version catalogue instead of stale Gradle lock state. A
release environment may enrich this file with resolved transitive components after a
successful Gradle resolution.
"""

from __future__ import annotations

import argparse
import json
import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "gradle" / "libs.versions.toml"
APP_BUILD = ROOT / "app" / "build.gradle.kts"


def app_version_name() -> str:
    """Read the release version from the app build script (single source of truth)."""
    match = re.search(r'val appVersionName\s*=\s*"([^"]+)"', APP_BUILD.read_text(encoding="utf-8"))
    if match is None:
        raise ValueError("appVersionName not found in app/build.gradle.kts")
    return match.group(1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "output",
        nargs="?",
        default=str(ROOT / "build" / "reports" / "sbom.cdx.json"),
        help="Destination CycloneDX JSON path.",
    )
    args = parser.parse_args()

    data = tomllib.loads(CATALOG.read_text(encoding="utf-8"))
    versions: dict[str, str] = data.get("versions", {})
    libraries: dict[str, dict[str, object]] = data.get("libraries", {})
    components: dict[str, dict[str, str]] = {}

    for alias, declaration in sorted(libraries.items()):
        if not isinstance(declaration, dict):
            continue
        group = str(declaration.get("group", "")).strip()
        name = str(declaration.get("name", "")).strip()
        version = declaration.get("version")
        if isinstance(version, dict):
            version = versions.get(str(version.get("ref", "")), "")
        version = str(version or "").strip()

        # BOM-managed entries deliberately have no direct version. They are represented
        # by the BOM component itself and resolved transitively during the Android build.
        if not group or not name or not version:
            continue
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", group):
            raise ValueError(f"Invalid Maven group in version catalogue: {group!r}")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
            raise ValueError(f"Invalid Maven artifact in version catalogue: {name!r}")

        purl = f"pkg:maven/{group}/{name}@{version}"
        components[purl] = {
            "type": "library",
            "group": group,
            "name": name,
            "version": version,
            "purl": purl,
            "bom-ref": purl,
            "properties": [{"name": "catalog.alias", "value": alias}],
        }

    out = Path(args.output).expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            {
                "bomFormat": "CycloneDX",
                "specVersion": "1.5",
                "serialNumber": "urn:uuid:00000000-0000-0000-0000-000000000000",
                "version": 1,
                "metadata": {
                    "component": {
                        "type": "application",
                        "name": "MovieTorrentSearchTV",
                        "version": app_version_name(),
                    },
                    "properties": [
                        {
                            "name": "inventory.scope",
                            "value": "declared version-catalog dependencies; resolved transitives require Gradle",
                        }
                    ],
                },
                "components": sorted(components.values(), key=lambda item: item["purl"]),
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    print(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
