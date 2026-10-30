<!--
  Ready-to-paste description for the fdroiddata merge request.
  Follows .gitlab/merge_request_templates/Default.md.

  Before submitting:
    - add Android app screenshots (see submission-checklist.md);
    - refresh the version, dates and user numbers below.
-->

## Abstract for new app inclusion

### Functions and usage

FireDrop moves files and whole folders between devices on the same local Wi-Fi
network: phone to computer, computer to phone and phone to phone. Nothing goes
through the internet - the devices exchange data over their local addresses only.

Usage takes two steps:

1. The receiving device shows a QR code and a six-digit code. The Windows app (and
   the Android app) also serve a small web page at `http://<ip>:<port>`.
2. The sending side scans the QR code, opens that link in any browser, or types the
   six-digit code in the app; then it picks files or a folder and sends them.

Incoming files are never overwritten (duplicates get ` (2)` style suffixes), folder
structure is preserved, and the receiver shows live speed, file count and total size.
The six-digit code accepts a limited number of attempts, so a stranger on the same
Wi-Fi cannot guess it.

The Android app is 316 KB, needs Android 8.0+ (minSdk 26), uses 11 normal permissions
(storage access is capped at API 32 / 28, there is no "all files access") and does not
depend on Google Play services. Interface languages: Russian and English, following
the system language, with a manual switch in the settings.

### Screenshots

*Windows receiver (1.2.5):*

![Windows receiver](https://gitlab.com/-/project/88258796/uploads/18d3c08fbcd59307f52dfa4938b80e75/win-1.2.5-main.png)

*The same receiver seen from a phone browser - nothing to install on the sending side:*

![Phone page: pick files](https://gitlab.com/-/project/88258796/uploads/62fd79f535fa4fa922c977db78779279/mobile-final-index.png)

![Phone page: six-digit code](https://gitlab.com/-/project/88258796/uploads/18c0b5a912f2ebb8da57262c94361adb/mobile-final-join.png)

![Phone page: upload in progress](https://gitlab.com/-/project/88258796/uploads/ea8ace5f01f441bcde93690364dbd0b2/mobile-final-upload.png)

<!-- TODO before submitting: add 3-4 screenshots of the Android app itself
     (main screen, QR/pairing, transfer in progress, settings with the language picker). -->

### Why I wrote this app

I move files between my phone and my computer several times a week: photos to edit,
documents back to the phone, whole folders of a project to a colleague in the same
office. Every existing tool I tried asked for one thing too many - an account, a
cloud round-trip, a 40 MB app on both ends, or a cable. FireDrop is the shortest path
I could build: the receiver shows a code, the sender opens it, and the file is there.

### Unique value over similar apps (quality point 6)

The closest app in F-Droid is LocalSend. Both move files over the local network, but
they are built around different trade-offs:

| | FireDrop 1.2.5 | LocalSend 1.18.2 |
|---|---|---|
| Sender needs the app | No - any browser can send; only the receiver runs the app | Yes, the app is needed on both devices |
| APK size | 316 KB | 37-46 MiB per ABI |
| Build | aapt2 + javac + d8, no Gradle | Gradle + Flutter |
| Runtime dependencies | `com.google.zxing:core` only | Flutter engine and plugins |
| Transport | Plain HTTP inside the LAN (stated in the description) | HTTPS between devices |
| Pairing | QR code or six-digit code with limited attempts | QR code / automatic discovery |
| Licence | GPL-3.0-or-later | Apache-2.0 |

The browser-based sender matters in practice: an iPhone, a Linux laptop or a guest's
device can send a file to a FireDrop receiver without installing anything, and the
receiver can be an old phone that would struggle with a Flutter app.

### Development history

Public repository: https://github.com/ArhonTs/FireDrop, first public commit on
26 October 2026. Releases since then:

- 1.2.3 - 26 Oct 2026 - initial public release
- 1.2.4 - 26 Oct 2026 - full English localisation, README rewrite
- 1.2.5 - 29 Oct 2026 - manual language switch on Android and Windows
- 1.2.6 - DATE - CHANGES

By the date of this merge request the project has been developed and released in
public for more than three months, which satisfies quality point 6 without relying on
the exceptions. User interest so far: NN downloads of the Android APK on GitHub,
SS stars, and feedback through Telegram (@LoioshS).

<!-- TODO before submitting: replace DATE, CHANGES, NN and SS with real
     values, or drop the last sentence if the numbers are not meaningful yet. -->

## Checklist

### Policy

* [x] The app complies with the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy). Quality point 6 is covered by the development history above.
* [x] The original app author has been notified - I am the author.
* [x] The upstream repository contains fastlane metadata in `Android/fastlane/metadata/android/` (`en-US` and `ru-RU`: title, short and full description, icons, changelogs 4-8).

### Docs

* [x] [CONTRIBUTING.md](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md) read.
* [x] Metadata follows the [templates](https://gitlab.com/fdroid/fdroiddata/tree/master/templates).
* [x] [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) read; the file is valid YAML with a single `Builds` entry.
* [x] [Quick Start Guide](https://f-droid.org/en/docs/Submitting_to-F-Droid_Quick_Start_Guide/) read.

### Merge Request Setup

* [x] Title follows the "New app: app name" format.
* [x] The fork `wmidxru/fdroiddata` is public and the branch is not protected.
* [x] No rebase is needed: the branch is created from current `master`, there are no conflicts.
* [x] No fdroiddata or RFP issue exists for this app, so nothing is referenced.
* [x] A single app is submitted in this MR.

### Metadata

* [x] Metadata is in `metadata/ru.firedrop.app.yml`.
* [x] The file is valid YAML and uses LF line endings.
* [x] No summary, description, changelog or images are added to the metadata - they live in the upstream fastlane folder, and the diff contains only the metadata file.
* [x] Releases are tagged and auto update is enabled (`AutoUpdateMode: Version v%v`, `UpdateCheckMode: Tags ^v[\d.]+$`).
* [x] Issue tracker and author contact are provided (`IssueTracker`, `AuthorName: LoioshS`, Telegram @LoioshS in the app's About screen).
* [x] `AuthorName` is set.
* [x] No external repositories or submodules are used.
* [ ] Reproducible builds are not enabled. Reason: the release APK is produced by a Windows pipeline (`Android/build.ps1`) and signed with the project key, so it is not bit-identical to a Debian-based build - zip timestamps and JDK packaging differ. I would rather not claim reproducibility that I cannot verify. F-Droid signing is fine for this project; the Android build itself uses only JDK 17 and the Android SDK build-tools, no proprietary toolchain.
* [x] ABI split is not needed: the single APK is 316 KB.
* [x] Only the latest version is kept in the metadata.
* [x] No disabled versions are listed.
* [x] `commit` is the full hash (`c872cf5cdecbcbd107505165320c1125e56511b9`), not a tag or a branch.

### Pipeline

* [ ] Pipelines cannot start on this account: GitLab requires identity verification to run pipelines, and it cannot be completed from the author's region. Per this template's instruction I am leaving a note instead of submitting anything - please trigger the CI on your side.
* [ ] The Reports tab is therefore empty; there are no warnings to explain.

### Build notes

* Build recipe: `bash Android/fdroid-build.sh` (aapt2 -> javac -> d8 -> zipalign, no Gradle).
* The only third-party binary is `com.google.zxing:core:3.5.3` (Apache-2.0), downloaded from Maven Central during the build and verified against the pinned SHA-256 `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`. This is the "trusted Maven repository" case allowed by the inclusion policy; I can move it to `srclibs` or a git submodule if you prefer.
* Output is left unsigned at `Android/build/FireDrop-unsigned.apk`.
