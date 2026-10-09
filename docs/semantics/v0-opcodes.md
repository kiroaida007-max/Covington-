# V0 semantic opcode manifest

Source of truth: `modules/lift-dex/src/main/kotlin/dev/apkproject/lift/V0Semantics.kt`.
The decoder recognises all 224 assigned Dalvik opcodes; only the opcodes below have modelled
meaning. Every other opcode makes its method a capability gap (`opcode.unsupported`).

## Integer model

- Values are 32-bit two's-complement integers; arithmetic wraps.
- Shift distances are masked to 5 bits (`x << 33 == x << 1`, `x << 32 == x`).
- `div`/`rem` truncate toward zero; `MIN_VALUE / -1 == MIN_VALUE`; `MIN_VALUE % -1 == 0`.
- A zero divisor throws `java.lang.ArithmeticException`. The throw is part of the method's
  observable outcome and is never moved onto or off a path by reconstruction.
- No heap, fields, calls, exceptions handlers, `long`, floating point, switches or loops.

## Opcodes with modelled meaning

| Family | Opcodes |
|---|---|
| No-op | `nop` |
| Moves | `move`, `move/from16`, `move/16` |
| Constants | `const/4`, `const/16`, `const`, `const/high16` |
| Return | `return` |
| Jumps | `goto`, `goto/16`, `goto/32` |
| Compare and branch | `if-eq`, `if-ne`, `if-lt`, `if-ge`, `if-gt`, `if-le` |
| Compare with zero | `if-eqz`, `if-nez`, `if-ltz`, `if-gez`, `if-gtz`, `if-lez` |
| Unary | `neg-int`, `not-int`, `int-to-byte`, `int-to-char`, `int-to-short` |
| Binary (23x) | `add-int`, `sub-int`, `mul-int`, `div-int`, `rem-int`, `and-int`, `or-int`, `xor-int`, `shl-int`, `shr-int`, `ushr-int` |
| Binary (2addr) | the same eleven operations with `/2addr` |
| Literal (lit16) | `add-int/lit16`, `rsub-int`, `mul-int/lit16`, `div-int/lit16`, `rem-int/lit16`, `and-int/lit16`, `or-int/lit16`, `xor-int/lit16` |
| Literal (lit8) | `add-int/lit8`, `rsub-int/lit8`, `mul-int/lit8`, `div-int/lit8`, `rem-int/lit8`, `and-int/lit8`, `or-int/lit8`, `xor-int/lit8`, `shl-int/lit8`, `shr-int/lit8`, `ushr-int/lit8` |

## Method eligibility

A method is inside V0 when it is static, its signature is `(I...)I`, it has no try blocks or
payloads, every instruction is in the table above, its control-flow graph is acyclic, and every
register read is defined on every path.

## Three independent semantic models

| Model | Where | Used for |
|---|---|---|
| IR evaluator | `:ir` `IrEvaluator` | runtime comparisons, counterexample replay |
| SMT-LIB QF_BV encoding | `:validate` `SmtEncoder` | equivalence obligations (Z3) |
| Real JVM execution | `:compiler-runner` `JvmHarness` | candidate behaviour; hidden originals in benchmarks |

Tests cross-check each pair: the SMT encoding is evaluated at concrete inputs and compared with
the evaluator, and every validation run compares JVM results with the re-lifted candidate's IR
(`model.consistency`).
