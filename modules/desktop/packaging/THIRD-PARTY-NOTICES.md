# Bundled libraries

APK Project Studio is distributed under the repository's MIT license. The package includes
a CycloneDX inventory with versions and SHA-256 hashes of its JVM library artifacts.

- Kotlin standard library, kotlinx.serialization and JetBrains annotations: Apache License 2.0.
- RSyntaxTextArea: BSD 3-Clause license.
- JNA: dual Apache License 2.0 / LGPL 2.1-or-later; this distribution uses the Apache 2.0 option.
- The bundled OpenJDK runtime has its own notices in the runtime's `legal` directory.
- Built-in repair-retrieval excerpts derive from GPUImage (Apache-2.0), jsoup (MIT) and Jackson
  (Apache-2.0). Their pinned source metadata is in `repair-knowledge.json`; copyright notices,
  license texts and Jackson third-party notices are in
  `dev/apkproject/app/repair-knowledge-notices.txt` inside the app-recovery JAR.
- Optional downloaded Qwen2.5-Coder GGUF weights (Apache-2.0) and llama.cpp CPU binaries (MIT)
  are separate from this package. The setup helper installs pinned license files and artifact
  provenance alongside them; see `docs/local-repair-assistant.md`.
- Experimental privacy API model weights are trained on SuSi and FlowDroid API labels
  (secure-software-engineering, documented LGPL 2.1) and Android SDK/engine tables. Their
  source URLs, exact input hashes, evaluation and limitations are recorded in
  `scripts/ml/privacy-api-model.md`, the trainer and bundled `privacy-api-model.json`.
  Preserve that attribution and assess derived-weight redistribution terms before release.

Library license/notice files available inside the dependency JARs remain in those JARs.
Downloaded Android SDK, JADX, ECJ, Z3 and optional REA tools are separate dependencies;
their licenses and redistribution terms must be reviewed for any distribution that bundles them.
The application does not acquire redistribution rights to analyzed APKs or recovered code.
