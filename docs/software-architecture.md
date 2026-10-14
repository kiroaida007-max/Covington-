# Software architecture and analysis workflow

Implementation on the production candidate, 2026-10-14. The original architecture PDF is
background, not an acceptance certificate. App-corpus testing can continue independently of
desktop work. This document describes actual responsibilities, workflow and remaining gaps.

## Responsibilities

| Layer | Modules / owner | Responsibility |
|---|---|---|
| Shared records | `contracts` | Versioned outcomes, evidence, tool profiles and resource budgets |
| Persistence | `store` | Atomic publication, content-addressed artifacts and journals |
| Input | `archive`, `formats-dex`, `dex-asm` | Bounded inventory, decoding, malformed-input rejection and fixture assembly |
| Reconstruction | `ir`, `lift-dex`, `source-model`, `reconstruct` | Explicit supported semantics, provenance, bounded lifting and rendering |
| Checks | `compiler-runner`, `validate`, `experiments` | Deadline/cancellation-aware processes, compilation, restricted proofs and independent probes |
| Restricted recovery | `orchestrator`, `export` | V0 coordination, pinned installation and publication |
| Whole-app recovery | `app-recovery` | JADX, naming/library evidence, repair gates, contracts, static audit/privacy/diff and emulator observations |
| Interfaces | `cli`, `desktop` | Requests, progress, cancellation and presentation; neither approves model proposals |
| Independent measurement | `benchmarks`, benchmark directories, `scripts/tests` | Hidden originals, seeded mutants, source/mapping truth, fixed partitions and schema/HTTP checks |

The desktop depends on the engine; the engine does not depend on Swing. Availability is separate
from analysis results. `ProjectFiles` is the common desktop filesystem boundary; `ReportReader`
applies byte/JSON/hash budgets; `Project` combines recorded analysis. `ProjectView` owns navigation
and source previews, `AnalysisStatusView` owns report availability/inspection, and `BackendView`
owns contract browsing and mock lifecycle. Background readers have one active and at most one
queued task, with generation guards before publishing on Swing. Keep I/O and tools off Swing.

The broad `app-recovery` module still has many responsibilities. Future extraction should follow
tested request/result interfaces, starting with runtime observation or contracts, rather than
adding empty modules. Complete cross-project cache keys, per-method persistent checkpoints,
large-APK soak and broader held-out recovery measurements remain unfinished.

## Tool roles

| Tool | Purpose | Scope of its evidence |
|---|---|---|
| JADX | Java/resource/manifest decompilation | Decompiled output, not original source or automatic equivalence |
| javac / ECJ, Android platform, D8 | Compilation and source-to-DEX comparison | Recorded supported outcomes; unavailable methods remain unresolved |
| Z3 | Restricted model checks | Proof only for the documented semantic slice and assumptions |
| REA / Wakaru | Optional cross-check and JavaScript recovery | Supported adapters; not the complete upstream tool catalogue |
| Maven / OSV | Library resolution / opt-in desktop vulnerability queries | Matched artifacts / queried advisories, not app certification |
| ADB / aapt2 / rooted emulator | Explicit scenarios and observations | Observed execution with collector/cleanup status, not exhaustive behavior |
| Local GGUF assistant / repair ranker | Optional proposals or ordering | Suggestions; compiler and complete comparison gates still govern acceptance |

Profiles pin versions and hashes. Platform and optional-tool availability are separate. Hash
checks establish integrity against pins, not isolation from hostile inputs. Opening a project
does not automatically download model weights or tools. The ranker remains unpromoted; this
desktop work changes no training data or acceptance criteria. Runtime method hooks and continuous
native tracing are not implemented by the current snapshot engine.

## Analyst workflow

1. Recover into a new folder. Choose external downloads, OSV and optional models explicitly.
   Heavy recovery uses a worker JVM with history, checkpoints, cancellation and resource budgets.
2. Review Summary, then **View → Analysis Status** (Ctrl+I). Loaded means readable, not complete
   or correct. Inspect invalid/changed reports as bounded text, navigate to their analysis or
   regenerate the export. Missing/blocked reports cannot support a negative finding.
3. Inspect source with recorded method outcomes. Paging and background filtering bound the
   display; previews check actual bytes. A matching hash ties them to this export's recorded
   checks. Edited/unreadable files do not inherit a current check. After edits, run
   `apkproject roundtrip` rather than treating historical outcomes as current.
4. Review static UI/security/privacy evidence paths and coverage. Add observations through an
   explicit disposable-emulator session. Static inference and observed execution remain distinct;
   absent flows do not establish privacy.
5. Inspect Backend API and embedded evidence. Desktop mocks regenerate checked examples from
   contract data and the bundled template. Responses remain SYNTHESIZED; unsupported shapes
   remain 501. Embedded adapters are separate exports. Remote business logic absent from the
   APK requires its own source or binaries.
6. Compare versions and retain exports, diagnostics and independent evidence. Keep candidate
   changes reviewable until the relevant native and app-corpus checks complete.

## Trust boundaries

Reads are anchored to the selected physical root and reject traversal, links/reparse targets
below it, replaced roots and special files. On supporting filesystems, `SecureDirectoryStream`
retains directory handles and opens parents/files without following links. Windows falls back
to component/reparse checks and a no-follow final open. Links stay visible but supply no reports
or previews. Runtime output checks existing parents before creating session folders.
This is not a filesystem sandbox: concurrent hostile parent mutation during fallback reads,
enumeration or output creation needs stronger OS isolation. Hard links are not detected as
external content.

Actual report bytes, aggregate loading, JSON nesting/tokens and method indexes have limits.
Invalid method reports discard their entire counter set. Inventories are local integrity
evidence, not publisher signatures: replacing both report and inventory changes that trust.

Double-click/Enter use internal preview. External opening is a separate action restricted to
documents/images; executable/script/shortcut associations are blocked. Table/tree strings are
literal text rather than Swing HTML. Markdown escapes HTML and renders only HTTP/HTTPS links;
browsing rejects other schemes and credential-bearing URLs. Explicit external actions still
use the user's applications and their own security.

Desktop mock startup never compiles project Java. Rendering runs in a bounded 128 MiB child JVM
with a deadline; compilation disables annotation processing, fixes classpath/sourcepath to the
private temporary workspace and uses a 256 MiB heap. The server uses a 128 MiB heap, loopback
binding, bounded HTTP handling/logs/startup and cancellation. Only the installed renderer,
serialization libraries, JDK and bundled template execute. Exported Java is for review or
deliberate execution in a separate environment; this is not a general recovered-code sandbox.

APK workers and emulator sessions retain the limits in [recovery reliability](recovery-reliability.md)
and [runtime behavior](runtime-behavior.md). Native Windows GUI/reparse/MSI checks need actual
Windows validation; Linux screenshots and PowerShell parsing cannot establish them.
