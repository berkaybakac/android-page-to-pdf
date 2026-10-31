# Boran PDF

**An offline Android app that turns visible pages into a single PDF.**

Built to solve a real study workflow: saving dozens of pages by hand, keeping them in order, and importing the result into Samsung Notes. Choose a page count, open the starting page, and let the app capture and advance. Everyday use needs no computer, terminal, or ADB.

## What it does

- Captures visible pages and on-screen annotations into a lossless PDF.
- Advances with a next-page button or a swipe; İşler has automatic button detection and page-number checks.
- Waits for the image to settle before saving, and saves before advancing.
- Stops on uncertain transitions, repeated pages, or app changes. Partial results are clearly marked.
- Saves locally and opens the result in Samsung Notes. No account, server, or internet permission.

## Engineering decisions

I took this from an ADB prototype to a standalone Android app, prioritizing a usable workflow over a large feature set. The main decisions were:

- **Native Java and Android APIs:** accessibility-based capture and gestures, with explicit user permission; no third-party Android libraries.
- **Bounded image memory:** a small PDF writer appends one image at a time instead of retaining the whole document.
- **Conservative automation:** one page-turn action per saved page; uncertain actions are not blindly retried.
- **Focused scope:** no OCR, cloud service, account system, or automatic resume.

## Validation

Real-device checks used a Samsung Galaxy Tab S10 FE running Android 16:

| Version | Verified result |
| --- | --- |
| Android app | Three-page capture and Samsung Notes import; standalone operation with the ADB server off |
| Android app | 42 consecutive pages, followed by a requested stop and a valid, clearly marked partial PDF |
| Python CLI | 100 pages in 14 min 19 sec; embedded PDF images matched the source PNGs pixel for pixel |

The user also reported successful everyday use. A complete 100–200-page Android run has not been verified. Other apps may work with button selection or swipes, but device validation has focused on İşler. Screen comparisons alone cannot guarantee that no page was skipped; page-number checks are specific to İşler. Protected windows are not captured or bypassed.

## Run or build

The Android app requires **Android 14+** and user-enabled accessibility access. Its interface is in Turkish.

```sh
./android/build.sh
# APK: android/build/Boran-PDF.apk
```

Build prerequisites: Android SDK Platform 36, Build Tools 36.0.0, and a Java 17+ JDK. The script defaults to the macOS SDK and Android Studio JDK paths; override with `ANDROID_SDK_ROOT` and `BORAN_JDK` when needed. No Gradle installation is required.

The original macOS CLI is included:

```sh
./setup
./capture --pages 3
```

See [usage and setup](docs/USAGE.md) for permissions, ADB pairing, profiles, and limitations.

## Repository privacy

Source builds use a neutral document icon. Personal portraits, device identifiers, signing keys, PDFs, screenshots, and local configuration are excluded from version control. Optional personal resources stay in ignored local folders. No third-party book pages are included.
