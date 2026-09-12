# VIDX

**Premium Android video downloader & media utility** — dark neon UI, multi-URL queue,
download manager, smart clipboard, transcripts, and an honest, modular platform-adapter
architecture.

> ⚠️ This project respects platform Terms of Service. It never bypasses DRM, logins,
> paywalls or access controls, and it never fakes a capability. Where a platform exposes
> no public download endpoint, VIDX says so in the UI. See [docs/PLATFORM-NOTES.md](docs/PLATFORM-NOTES.md).

## Status

- Architecture: V1 + V2 + V3 implemented as one modular codebase (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md))
- Testing: full manual matrix + automated JVM suite (see [docs/TESTING.md](docs/TESTING.md))
- Final report: [docs/FINAL_REPORT.md](docs/FINAL_REPORT.md)

## Build

```bash
./gradlew assembleDebug        # standard Android build (needs network on first run)
./gradlew testDebugUnitTest    # unit tests
```

The APK lands in `app/build/outputs/apk/debug/`. A prebuilt CI artifact is committed
to `dist/VIDX-debug.apk` whenever CI passes.

## Tech

- Kotlin, single-module Android app, **zero third-party runtime dependencies**
  (pure Android framework + Kotlin stdlib) — minimal supply chain, small APK.
- minSdk 26 (Android 8.0), targetSdk 34, compileSdk 35.
- Permissions: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE (+dataSync),
  POST_NOTIFICATIONS (runtime), WRITE_EXTERNAL_STORAGE (API ≤ 28 legacy path only).

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — layers, modules, how to add a platform
- [docs/PLATFORM-NOTES.md](docs/PLATFORM-NOTES.md) — per-platform capability matrix & legal notes
- [docs/TESTING.md](docs/TESTING.md) — test matrix & how to run it
- [docs/SECURITY.md](docs/SECURITY.md) — security model & threat notes
- [docs/FINAL_REPORT.md](docs/FINAL_REPORT.md) — development report (features, bugs, limitations)
