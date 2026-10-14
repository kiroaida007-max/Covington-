# Analysis workbench and project trust boundaries

Continue on `codex/production-candidate`, draft PR #11, parent `1f561b5`. The user parked CI
activation and asked for architecture, tool/workflow organization, native UI/UX and security
while Claude tests apps independently. No other branch/worktree or Claude app-test artifact was
changed. Do not resume account/runner activation as a prerequisite for this software work.
Engine/ML details remain in the October 12 handoffs; no dataset, model promotion or repair gate
changed here.

## Concrete changes

- Project browsing previously could follow source/report directory links outside the export.
  `ProjectFiles` anchors the physical root, checks root identity and components/reparse targets,
  rejects links and special files, and uses secure directory handles where supported. Report
  readers, streaming method checks, previews and runtime session locations share that boundary.
  Links remain visible but do not supply content. Availability distinguishes BLOCKED from
  missing/invalid/changed reports. Windows fallback and concurrent-mutation limits are documented.
- Mock Start previously compiled the project-supplied `BackendSkeleton.java`. It now serializes
  the loaded contract and renders the bundled template in a 128 MiB/deadline-bounded child JVM.
  javac uses a private classpath/sourcepath, disables processors and has a 256 MiB heap; the
  loopback server has a 128 MiB heap. Startup/cancel/output/cleanup checks remain. The UI tracks
  unexpected exits; edited exports are never executable inputs to this screen. Response audits
  are recomputed; unsupported shapes still return 501 and responses remain SYNTHESIZED.
- `BackendView` is separate from navigation/source browsing. `AnalysisStatusView` supplies a
  sortable availability table, unavailable-only filter, complete detail/next steps, navigation,
  refresh and a bounded 512 KiB plain-text report preview. Ctrl+I opens it; scrollable native
  tabs preserve page space. Project menu actions disable when no project is open.
- Unavailable privacy/security/components/names/report pages show actual report state instead
  of suggesting a clean result. Legacy embedded frontend fallback is allowed only when the
  standalone audit is absent, not invalid/blocked/changed. Source preview failure clears the
  current-byte assertion; successful source checks are explicitly historical/hash-relative.
- Double-click/Enter preview internally. External file opening blocks executable/script/
  shortcut associations, and browsing permits only credential-free HTTP/HTTPS addresses.
  Report tables/tree/status values and raw previews cannot activate Swing HTML images.
  Markdown escapes HTML and removes nonweb links; report-provided advisory links use the same
  URL boundary. Job-history project loading now uses the existing background reader.
- `docs/software-architecture.md` describes real module ownership, tool evidence, workflows and
  trust limits. The roadmap no longer says the existing desktop/runtime/export is not started.
  Native capture includes Analysis Status (18 images); both Windows capture guards require 18.

## Verification scope

Full local Gradle suite: **324 cases, zero failures/errors, one optional external library benchmark
skip**. Python: **65 passed**. Desktop checks were rerun after the final boundary/presentation
changes. New regressions include report/source symlinks, internal links/traversal/nonregular
files, replaced roots and redirected runtime output, sorted/filtered report navigation,
background raw preview, unavailable privacy state, literal HTML display, unsafe URL/file types,
and actual UI mock startup with a malicious exported Java marker that must never execute.
The mock test verifies checked 200 and unresolved 501 responses and evidence/contract headers.

Packaged Linux capture checks navigation, source filtering, worker separation, actual mock
startup/cleanup, late-start close and recovery cancellation. Developer tour captures 26 screens,
including risk/privacy/data/screen paths. These are Linux/Metal renders, not Windows screenshots.
Independent OpenAPI/completeness/hash HTTP checks on the packaged renderer's regenerated Tusky
contract: **109 routes, 119 probes, 115 served/valid examples**; original server behavior remains
unverified. Product BOM schema and exact **21 packaged JAR hashes** pass. Windows workflow passes
actionlint; native execution is still pending.

The 200,000-method/10,000-file/2,000-folder workload remains inside the shipped 512 MiB test heap.
One full-suite sample measured 703 ms load, 106,128,928 allocated bytes on the loading thread and
7 ms first Swing view. These are host/cache/JVM-dependent observations; added boundary checks
have a cost, and this change claims neither faster decompilation nor a universal latency bound.

Local evidence: `/workspace/scratch/workbench-full-tests.log`, `workbench-python-tests.log` and
`/workspace/scratch/workbench-review/` (final regressions, packaged captures, HTTP and BOM checks).
Do not claim native Windows reparse/GUI/installer validation from Linux. Hard links, hostile
concurrent parent mutation in fallback/enumeration/output, comprehensive OS sandboxing, broad
held-out app accuracy and prolonged heavy-input soak remain open. Keep the candidate draft;
no merge, release, billing or visibility change is authorized by this handoff.
