# Bundled libraries

APK Project Studio is distributed under the repository's MIT license. The package includes
a CycloneDX inventory with versions and SHA-256 hashes of its JVM library artifacts.

- Kotlin standard library, kotlinx.serialization and JetBrains annotations: Apache License 2.0.
- RSyntaxTextArea: BSD 3-Clause license.
- JNA: dual Apache License 2.0 / LGPL 2.1-or-later; this distribution uses the Apache 2.0 option.
- The bundled OpenJDK runtime has its own notices in the runtime's `legal` directory.

Library license/notice files available inside the dependency JARs remain in those JARs.
Downloaded Android SDK, JADX, ECJ, Z3 and optional REA tools are separate dependencies;
their licenses and redistribution terms must be reviewed for any distribution that bundles them.
The application does not acquire redistribution rights to analyzed APKs or recovered code.
