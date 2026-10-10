# Benchmarks

`./gradlew :benchmarks:run` builds every fixture in `fixtures/` from hidden source into an APK,
runs recovery on the APK alone, exports the project, compares hidden original and recovered
project by executing both (each compiled from source) on the same inputs, and repeats recovery
with seeded wrong candidates to confirm validation rejects them.

- `results/v0-results.md`, `results/v0-results.json`: latest measured results (committed).
- `work/`: scratch output (ignored by git).
