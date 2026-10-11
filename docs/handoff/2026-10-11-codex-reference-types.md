# Source-backed type attribution

Branch `codex/reference-type-attribution`, stacked on draft PR #6 at
`f64245c26c6c3f414edad730f09cd6d2baadf31b`. Earlier correctness/reliability work is recorded in
`2026-10-10-codex-recovery-gaps.md`; native GUI notes remain in the Windows desktop handoff.
Do not merge the base PRs without instruction.

The user asked us to do our best to close the absent notification type name and original-server
logic gaps. The pinned APK has no original identity for `dg.j`. Its known upstream source does
declare `Notification.Type`, so this change supports an explicit source attribution. Source
input is never presented as a name recovered from bytecode or as an original server.

## Implemented

- `ReferenceTypes.kt`: bounded, opt-in reference manifest/source importer. Exact APK and source
  SHA-256s, a property declaration, a same-file named type, and direct bytecode field evidence are
  required. Source names are stored separately in `ModelField.referenceType`; `javaType`, wire
  shapes, endpoints, nullability, examples and dispatch rules remain unchanged.
- Repository/revision metadata are caller assertions; hashes establish exact input bytes and
  declarations, not authenticated publisher identity or build equivalence. OpenAPI and README
  carry the reference name and scope. No code or JVM/JADX name is rewritten.
- CLI: `contract INPUT --out DIR --reference-types MANIFEST --reference-source DIR`. Both optional
  arguments are required together. Existing bytecode-only extraction is unchanged.
- The manifest rejects duplicate properties, conflicting aliases, credential/query/fragment URLs,
  unsupported identifiers, traversal and escaped source symlinks. Limits: 64 KiB manifest, 128
  bindings, 2 MiB per source file, 8 MiB total, 256 nesting levels, 100,000 delimiter pairs and 512
  declarations. Declaration ownership/names are cached to keep nested input work bounded.
- Kotlin reader supports explicit primary-constructor named properties, nullable spelling,
  nested same-file types and import aliases. Generics, body properties, Java source, type aliases,
  interpolated strings and ambiguous lexical/import combinations abstain by rejecting the import.
- Package/import scans use horizontal whitespace at line boundaries. A final performance review
  found quadratic backtracking on blank-line-only input in cross-line whitespace patterns; a
  bounded-time regression covers 100,000 blank lines and CRLF source.
- `tusky-reference-types.json` binds the pinned source declaration and APK. The independent
  `check_reference_types.py` checks source scopes, commit/blob bytes and provenance, and compares
  the entire attributed contract with the raw contract after removing only reference metadata
  and its exact appended note. It rejects invented names, changed facts and dropped warnings.
- Linux CI now runs the raw source/HTTP gates and the separate attributed-source/HTTP checks.
  The raw original Java name score remains 377/378; the additional checked source attribution is
  reported separately as one supplied name.

## Verification

On the pinned Tusky APK/source, the importer records
`com.keylesspalace.tusky.entity.Notification.Type` alongside the unchanged `dg.j`, with one source
attribution. Independent comparison found no other changed contract facts. The existing source
baseline and live HTTP gates pass: 112 endpoints, 255 parameters, 83 models, 378 wire/property/
nullability facts, 377 original Java names, 17 true enum fields, 14 custom values and the observed
status-posting partition. HTTP: 109 routes/119 probes, 115 valid examples and four intended 400s.
See the PR for final exact-head local and CI results.

Tests cover source/input tampering, scoped Type collisions, nested default-value declarations,
unsupported syntax, missing bytecode evidence, duplicate bindings, path escape, symlinks on Unix,
UTF-8/size/nesting limits, serialization, idempotency and the independent checker rejecting
invented source names, rewritten APK facts and missing extraction notes.

## What remains unavailable

Tusky's network module uses a placeholder base domain; a runtime domain header selects actual
servers. Its API includes Mastodon and Pleroma-compatible paths. The APK does not identify one
original deployment or contain its implementation. The user was asked for an original R8 mapping
or server source URL; none has been supplied yet. Do not substitute an upstream server family for
that deployment or label synthesized business logic recovered. Reader inspection also confirmed
known notification names use ROOT-locale lowercase and unknown names preserve their input; no
new arbitrary decoder execution or recovered-reader claim is made in this change.

Publisher signing still needs external keys/certificates; no authenticated CodeRabbit review is
claimed. Other product extension limits remain in the previous handoff. This source import does
not implement them or establish universal bug-free recovery.
