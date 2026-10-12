# Recovery reliability and release verification

Full APK recovery runs in a supervised JVM. The GUI has a 512 MiB heap, a bounded progress queue,
and bounded logs. The parent cancels cooperatively, then stops the process tree after five seconds.
Workers also stop when their supervising process disappears. JVM exhaustion, crashes and ignored
stdin are exercised with real subprocesses.

## Jobs and resume

```
apkproject decompile app.apk --out app-src --job-dir app-job
apkproject cancel-full app-job
apkproject resume-full app-job
```

The default CLI job folder is `.app-src.job` beside the output. Desktop jobs appear in **Tools >
Recovery Jobs**, which can resume them, open their folders and export diagnostics. Jobs retain an
immutable input copy, work directory, logs and partial project. Publication records a complete
file inventory before moving the project into place. Resume recovers a lost final worker result
and refuses to overwrite a completed project after user edits.

Naming, JADX export and the combined proof/compile/round-trip/repair stage have separate snapshots.
Reuse requires the same input, engine module identities, options, profile and pinned tool hashes,
plus valid hashes for every snapshot file and its metadata. Verification includes library artifact
hashes. Incomplete/corrupt snapshots are recomputed. Caches are per job; interrupted work inside
a stage is repeated. There is no per-method repair checkpoint or cross-project cache yet.

## Resource limits

```
apkproject decompile app.apk --out app-src --memory-mb 4096 --cpu-threads 4 --max-work-mb 8192
```

Automatic planning uses the smaller physical/cgroup memory limit, reserves 35% for the host,
and caps the allocation at 8 GiB. Engine and compiler heaps share that budget; Java/javac heaps
and processor counts are capped centrally. A per-user file lock queues heavy jobs and standalone
compiler tools across GUI/CLI processes. A full job has one external-tool execution slot.

Disk accounting checks owned job/staging folders every five seconds, with an 8 GiB default work
limit and 256 MiB free-space reserve. A burst can temporarily exceed this monitored budget; it
is not an OS quota. The supervisor also attempts OS process-tree quotas before releasing APK
analysis: Windows Job Objects cap aggregate committed memory, CPU rate and process count and
kill the job on handle close; Linux uses cgroup v2 when `APKPROJECT_CGROUP_ROOT` names a writable,
delegated directory with memory/CPU/pids controllers and `cgroup.kill`. Linux also disables swap
for the worker group. The parent process is outside these limits. Neither mechanism isolates
filesystem or network access, and same-user cgroup delegation is not a hostile-code sandbox.

Add `--require-os-containment` to refuse recovery when all required native controls cannot be
installed. Otherwise the fallback is explicit in the job log and `containment.txt`: JVM heap
limits and monitored disk use remain active. The release handshake prevents analysis from
starting before quota attachment. A native direct-memory probe must exceed the quota and fail
before its timeout on Windows; Linux without delegation must reject strict mode before analysis.

APK/AAB inventory uses a fixed 64 KiB file window. Entries are streamed with size/CRC checks;
nested APKs are spooled with combined expansion/entry limits. Bare DEX needs no ZIP copy.
`dexSequence()` supports incremental consumers; full recovery still retains the DEX models needed
for cross-DEX indexes. Limits remain 1 GiB input, 128 MiB expanded entry, 4 GiB combined expansion,
65,535 entries; ZIP64 is unsupported. Local and central flags, sizes and CRC metadata must agree;
signed/unsigned data descriptors are checked and included in overlap bounds. A 96 MiB archive
is tested in a 32 MiB JVM.

Diagnostic ZIPs include status/provenance metadata and at most 64 KiB of each selected log.
APKs, recovered source, profile paths and environment credentials are excluded. Logs can contain
class names and tool diagnostic text. Partial Java retains its evidence label: a checkpoint does
not make all decompiled source verified.

## Accuracy and release gates

Obfuscated API fixtures are split into separate DEX files, AAB modules and APK splits; types and
endpoints must match source expectations. Linux CI gates the pinned Tusky APK/source scores and
independently checks generated OpenAPI and live mock responses. Windows CI runs tool-dependent
tests, completes recovery using the packaged runtime, exercises cancellation, captures seventeen
native screens plus the audit/privacy screen tour, and exercises MSI install/launch/uninstall.
The product BOM inventories exact bundled JVM libraries and the application JAR; schema and
hash checks compare it with the packaged bytes. OpenJDK and downloaded analysis tools retain
their separate notices and are not claimed as part of this JVM-only inventory.
This is regression protection, not a
claim of universal bug-free recovery.

The Android runtime workflow builds a known original APK, strictly recompiles exported Java
without fallback source or missing-class stubs, then runs both separately on a disposable rooted
emulator. Declared visible UI observations must match, and a seeded wrong candidate must be
detected. The product Runtime Behavior engine is also checked for UID-filtered logs, persistent
storage, sampled memory, permission grants and denied network attempts. App-UID IPv4/IPv6 traffic
is denied before launch. This checks one declared scenario;
it does not verify every execution path, remote server behavior or general APK equivalence.

The desktop caps small reports at 8 MiB each/32 MiB combined and streams the method report with
byte, nesting, individual-string, record and retained-path bounds. Invalid, missing, stale and
limited reports stay distinguishable. Exported report hashes are checked before displaying
recorded results; the source preview hashes its actual bytes and warns on edits. This detects
changes relative to the local inventory, not an authenticated publisher. Folder search runs in
the background across at most 100,000 entries, while only 500 rows populate the Swing table.
Sorting applies to the displayed page. Pages and source editors are constructed when opened.

The manual `signed-windows-release.yml` workflow builds/tests with an embedded Ed25519 public key,
signs the launcher and EXE/MSI installers with Authenticode/timestamping, checks the publisher,
then signs a manifest of the final package bytes. It uploads a candidate, without publishing a
GitHub release. Configure the GitHub environment `release-signing`:

| Type | Name | Value |
|---|---|---|
| Variable | `UPDATE_PUBLIC_KEY_B64` | Base64 X.509 DER Ed25519 public key |
| Variable | `WINDOWS_SIGNER_THUMBPRINT` | Expected 40-hex-digit certificate thumbprint |
| Secret | `WINDOWS_SIGNING_PFX_BASE64` | Publisher's code-signing PFX |
| Secret | `WINDOWS_SIGNING_PFX_PASSWORD` | PFX password |
| Secret | `UPDATE_SIGNING_PRIVATE_KEY_PEM` | Matching PKCS#8 Ed25519 private key |

Never commit private keys. Protect the signing environment and its authorized maintainers. The
publisher must supply the signing identity; this implementation does not create or claim one.
EV/HSM services need their own adapter rather than an exported PFX.

**Tools > Verify Update Package** checks the manifest's exact-byte signature against the key
pinned at build time, a newer version, expiry, the highest previously verified release ID, and
the selected package's complete hash/size. It never trusts a key from the update itself. Unconfigured
development builds disable verification. Installation stays with the OS installer: automatic
download/distribution, background installation and key rotation are future work. Manifests expire
after thirty days by default (maximum ninety).

These changes do not recover absent server business logic or establish arbitrary APK compilation.
The original Tusky notification Java type name remains unknown. Its open string wire shape,
14 known values and status-posting client selection are checked against locked upstream source.
The corrected enum benchmark measures 17 genuine enum fields; the old eighteenth was a
same-name collision with a sealed class. Java type recovery remains 377/378.
