# Project requirements
Android-only Kotlin app, app ID dev.finall.rearcardbridge. UI uses Miuix Compose 0.9.4.
Accept one content URI ZIP containing index.html + JavaScript + optional assets. Show discovered local card resources so the user selects a replacement target. Never hardcode a target productId.
NO application-written hashes/digests/fingerprints or script security analysis (no AST, dangerous API scanning, JS execution/validation sandbox). ZIP/XML format and filesystem path handling are permitted. ZIP format's library-managed CRC is unavoidable and is not a custom digest feature.
NO UI automation, accessibility, input taps/swipes, synthetic clicks, root escalation, or phone installation/testing without separate user authorization.
Shizuku shell may replace writable local resource files and invoke Android Activity APIs; it cannot register a new card or directly apply via the signature-protected SDK. Do not pretend preview-opened means applied.
Source root: app/src/main/java/dev/finall/rearcardbridge. Shared models: model/BridgeModels.kt. IPC contract: app/src/main/aidl/dev/finall/rearcardbridge/ipc/ICardService.aidl. Respect assigned file ownership; main agent owns build/manifest/MainActivity/ViewModel/integration.
