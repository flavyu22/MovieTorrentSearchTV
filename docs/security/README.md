# Security artifacts

`dependency-sbom.cdx.json` is a CycloneDX 1.5 inventory generated from the declared version-catalog dependencies (`gradle/libs.versions.toml`) by `scripts/generate-sbom.py`. It is useful for inventory and external vulnerability scanning, but it is not itself a vulnerability report or proof that every artifact checksum has been verified. Resolved transitive components require a Gradle environment and must be enriched separately after a successful build.

Regenerate it after dependency changes:

```bash
python3 scripts/generate-sbom.py
cp build/reports/sbom.cdx.json docs/security/dependency-sbom.cdx.json
```

Strict Gradle dependency verification still requires reviewed `gradle/verification-metadata.xml` generated in an online environment.
