# Record contracts

The versioned records exchanged between modules and written to disk are defined as
`@Serializable` Kotlin classes in `modules/contracts/src/main/kotlin/dev/apkproject/contracts/`.
They are the single source of truth; this page lists them with their schema version.

| Record | Version | Written to |
|---|---|---|
| `ArtifactRef`, `MethodKey`, `ByteRange` | 1 | embedded in every other record |
| `JobSpec` | 1 | `runs/<job>/state.json` |
| `WorkerEnvelope` | 1 | `runs/<job>/journal.ndjson`, one line per event |
| `CapabilityGap` | 1 | `reports/capabilities.json`, `reports/recovery.json` |
| `ObligationRecord`, `CheckRecord`, `ValidationReport` | 1 | `reports/recovery.json`, `reports/validation.json` |
| `MethodOutcome`, `ExportManifest` | 1 | `reports/recovery.json`, exported `recovery/report.json` |
| `SourceMapEntry` | 1 | `reports/source-map.json`, exported `recovery/source-map.json` |
| `ToolchainProfile` | 1 | `profiles/*.json` |

Encoding rules (dossier section 10):

- 64-bit integers that JSON consumers could truncate (`byteSize`, offsets, `sequence`,
  `elapsedMs`) are serialised as strings.
- IDs refer to immutable content (SHA-256).
- Unknown keys are rejected on read; a breaking change increments `schemaVersion`.
