// Dalvik assembler: text (.dasm) -> DEX. A test kit for decoder, lifter and
// malformed-input fixtures; it is never part of the recovery path.
dependencies {
    api(project(":formats-dex"))
    testImplementation(project(":compiler-runner"))
}
