# V0 method laboratory: measured results

Generated 2026-10-09 with profile `v0-jvm-pure`. These numbers come from the fixtures in `fixtures/` only;
they are not a claim about arbitrary APKs.

| Fixture | Job state | Accepted / in scope | Recompiles to identical bytecode | Independent checks (mismatches) | Seeded wrong candidates rejected |
|---|---|---|---|---|---|
| v0-mixed | PARTIAL (exit 4) | 3 / 9 | 3 | 462 (0) | 2 / 2 |
| v0-pure | ACCEPTED (exit 0) | 22 / 22 | 22 | 6759 (0) | 19 / 19 |

## v0-mixed

Input APK sha256 `b13559309b9e8f4caf85a43e6224fda24c3ff0f664ee26ffc9d2296688b6cc06` (rebuilt from hidden source; byte-reproducible).

| Method | Status | Evidence | Bytecode match | Independent run | Gap |
|---|---|---|---|---|---|
| `<init>()V` | ACCEPTED | OBSERVED | IDENTICAL | - |  |
| `callsOther(I)I` | UNSUPPORTED | OBSERVED | - | - | opcode.unsupported: invoke-static at 0000 |
| `choose(I)I` | UNSUPPORTED | OBSERVED | - | - | payload.unsupported: switch and array payloads are outside V0 |
| `guarded(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `readsField(I)I` | UNSUPPORTED | OBSERVED | - | - | opcode.unsupported: sget at 0000 |
| `sumTo(I)I` | UNSUPPORTED | OBSERVED | - | - | cfg.loop: back edge b2 -> b1; V0 excludes loops that need invariants |
| `twice(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `widen(I)J` | UNSUPPORTED | OBSERVED | - | - | signature.non-i32: V0 supports (I...)I signatures; found (I)J |
| `instance(I)I` | UNSUPPORTED | OBSERVED | - | - | method.instance: V0 lifts static methods only |

## v0-pure

Input APK sha256 `82df5b611415040d5b36e76a8cf9154b30d6fc87a6dcf6d8d3c3bb0681499b29` (rebuilt from hidden source; byte-reproducible).

| Method | Status | Evidence | Bytecode match | Independent run | Gap |
|---|---|---|---|---|---|
| `<init>()V` | ACCEPTED | OBSERVED | IDENTICAL | - |  |
| `abs(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `answer()I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `average(II)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `bitCount(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `clamp(III)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `divide(II)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `doubled(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `increment(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `isNegative(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `max(II)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `min(II)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `mix(II)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `narrow(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `nestedChoice(III)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `poly(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `remainder(II)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `rotate(II)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `safeDivide(II)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `scramble(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `sign(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
| `twice(I)I` | ACCEPTED | OBSERVED, PROVED, TESTED | IDENTICAL | AGREE |  |
