# Image Resizer

Extremely simple, fast, small, offline-capable, memory-efficient image resizer for iOS and Android.

**Contract:** user picks photos + one number (max file size, e.g. 5 MB) → app returns photos that are **always ≤ target**, with quality and resolution preserved as much as possible. No backend, no upload, local processing only.

## Stack

- **iOS:** Swift + SwiftUI, ImageIO/CoreGraphics codecs. Core algorithm lives in a SwiftPM package (`ios/ImageResizerCore`) buildable and testable on macOS.
- **Android:** Kotlin + Jetpack Compose (minSdk 28), BitmapFactory + `Bitmap.compress`. Core algorithm mirrors the Swift implementation (`android/core/`), the app lives in `android/app/`.
- No third-party image libraries. The only production dependencies are Google Mobile Ads (iOS/Android) and Play Billing (Android).

## Repository layout

```
docs/          ALGORITHM.md = single source of truth for the resize contract
scripts/       make_fixtures.py — generates the golden test images in testdata/
testdata/      generated fixtures (git-ignored; regenerate any time)
ios/           ImageResizerCore (SwiftPM: algorithm + ImageIO codec + tests), app target later
android/       Gradle project: core = pure Kotlin algorithm (unit-tested),
               app = Compose UI + Android codec + AdMob/UMP + Play Billing seams
```

## Running tests

```bash
# fixtures (once, or whenever missing)
python3 scripts/make_fixtures.py

# iOS/macOS core — algorithm + real ImageIO end-to-end tests
# (custom harness: XCTest discovery needs full Xcode, CLT-only machines can't `swift test`)
cd ios && swift run ImageResizerCoreTests

# Android core — pure Kotlin/JVM (needs only a JDK; the wrapper fetches Gradle)
cd android && ./gradlew :core:test

# Android app (debug APK)
cd android && ./gradlew :app:assembleDebug
```

## Key rules

1. Preserve resolution → optimize quality → reduce resolution → **verify size on disk**.
2. Files already ≤ target pass through **without re-encoding** (GPS removed by byte-level strip) and do **not** consume a usage credit.
3. A credit is consumed only when an image was re-encoded **and** successfully saved.
4. Output format is automatic (see `docs/ALGORITHM.md`); there are no format/resolution options.
