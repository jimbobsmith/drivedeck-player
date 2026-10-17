# DriveDeck Player agent guidelines

Read `docs/foundation/README.md`, `docs/foundation/RESULTS.md` when present, and the linked GitHub issues before changing scope. The parent design is jimbobsmith/drivedeckg#244; hosted sync belongs to jimbobsmith/drivedeck-platform#27.

- GitHub issues are the queue. Fetch current `origin/main`, use a bounded `codex/issue-<number>-<name>` branch, and deliver code, meaningful tests, evidence and documentation through a pull request. Keep runtime/field gates open. `main` is the integrated fork line; upstream `dev` is a reference, not a second product trunk.
- Merging, release signing/publication, licence changes and installation on personal devices require explicit maintainer authority. A local Test build is not a release or an update to PipePipe/DriveDeck.
- Use `D:\tmp\dd` for scratch files, logs, captures, downloaded toolchains and test media. Keep those out of Git. Do not use `C:\tmp`.
- Preserve PipePipe/NewPipe attribution and component licences. Keep the extractor source submodule pinned. Record upstream pins and binary hashes when changing dependencies. Do not silently track a floating upstream branch.
- Standalone Android identity is `com.drivedeck.player`; debug is `.test` and visibly labelled Test. Internal upstream namespaces may remain. Never reuse DriveDeck or PipePipe release signing credentials or redirect the app to upstream APK updates.
- Google-free, offline-first, no analytics or automatic crash uploads. No Play Services, Firebase, Crashlytics or Play runtime dependency. Existing open-source ExoPlayer/Material/protobuf coordinates do not imply a Google account or Play Services.
- Personal-library sync is optional and must not start playback, transfer provider credentials, or become DD's live vehicle control channel. Keep provider cookies, resolved/signed stream URLs, OS settings and private device paths out of portable records.
- DD keeps its source/interruption/phone-audio authority. Publish only truthful media-session state and an exact public content identity. A command acknowledgement is not evidence of Playing. Vehicle video remains explicit parked/passenger behavior.
- Use the pinned JDK 25 / Android SDK 37 toolchain unless an evidence-backed migration is in scope. Run proportional compile, unit, dependency/manifest and runtime checks. Use a named dedicated emulator, never an unqualified connected-device test task with personal devices attached.
- Desktop decoding, YouTube extraction/delivery, local JavaScript decoding, visual rendering, media keys and packaging are separate evidence gates. Do not claim YouTube desktop playback solely because a synthetic decoder test passes.
