# FireDrop

**Fast transfer of files and entire folders over a local Wi-Fi network: computer to phone, phone to computer, and phone to phone.** No internet, no cloud, no accounts.

Russian documentation: [README.md](README.md)

Source code: https://github.com/ArhonTs/FireDrop — licensed under the GNU GPL v3.
The app UI and the web page come in Russian and English. They follow the system language by default, and the settings let you pick it explicitly: **System**, **Russian** or **English**.

## Features

- Files and **entire folders** with the nested directory structure preserved; tens of MB/s — limited only by Wi-Fi.
- Connections via **QR code** (address + token) or a **six-digit PIN** with limited attempts.
- Device discovery over UDP; receiving happens in a foreground service with a persistent notification.
- Incoming files are never overwritten: `name (1).ext`, `name (2).ext`…
- Phone ↔ phone and phone ↔ computer, all direct over Wi-Fi.

## What ships in a release

| File | Purpose |
| --- | --- |
| `FireDrop.exe` | Windows 10 (22H2)/11 x64, self-contained — nothing to install |
| `FireDrop-<version>.apk` | Android 8.0+ (about 300 KB, no Google Play Services) |
| `SHA256SUMS.txt` | Checksums |

## Phone → computer

1. Run `FireDrop.exe`. Allow access **for private networks** when Windows asks.
2. Connect the phone to the same Wi-Fi network.
3. Scan the QR code on the computer screen with the phone camera (opens the FireDrop page), or open **Send** in the Android app.
4. Pick files or a folder and wait for the transfer to finish. Files land in the save folder (default `C:\Users\<name>\Downloads\FireDrop`).

The web page can pick individual files and **folders** (desktop Chrome/Edge/Opera; no folder picker in mobile Safari). The Android app is more reliable on phones: it can pick folders on every supported Android version.

## Phone → phone

1. On the receiving phone: **Receive** → **Turn receiving on**, allow notifications.
2. On the sending phone: **Send** → **Find nearby** and pick the device, or **QR** and scan the receiver's QR code.
3. Enter the six-digit code (auto-filled when scanning the QR), pick files or a folder, and send.

Received files go to `Download/FireDrop`, folder structure is preserved. On Android 10+ the system does not allow creating empty directories through MediaStore, so empty folders are skipped; files create their own folders.

## Computer → phone

1. Turn receiving on on the phone (see above).
2. On the computer, click the paper-plane button — the **Send to phone** panel opens.
3. Choose the recipient: **Find nearby**, a QR text (`firedrop://send?…`), or address and code typed manually.
4. **Files…** or **Folder…** → **Send**.

## Requirements

- Windows 10 (22H2) or Windows 11, 64-bit (`FireDrop.exe` is self-contained).
- Android 8.0+ for the app; Chrome/Edge/Firefox/Safari/Samsung Internet for the web page.
- Devices on the same Wi-Fi network, client isolation (AP isolation) disabled on the router, network not guest.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Computer unreachable from the phone | Same Wi-Fi network; correct address selected in the FireDrop window (button next to the address at the bottom); firewall; VPN off; router isolation |
| "Find nearby" finds nothing | Receiver/server actually running; UDP port 47822 allowed; same subnet; VPN off |
| QR does not scan | 10–30 cm, camera in focus, screen brightness, "Capture frame" button; worst case — enter address and code manually |
| Phone does not receive files | Notifications allowed (Android 13+), battery optimization disabled, free space; Android ≤9 — grant storage access |
| Transfer breaks/is slow | Closer to the router, 5 GHz, don't lock the screen, VPN/power saving off |

## Settings

**Windows** (slider button): computer name, save folder, port (default `47820`), language, autostart, minimize to tray. Stored in `%APPDATA%\FireDrop\settings.json`.

**Android**: device name, receive port, receive folder (`Download/FireDrop`, fixed), language.

**Firewall**: rules are bound to the exact `FireDrop.exe` path — after updating into a new folder press **Allow FireDrop on the network** in the settings (recreates both TCP 47820 and UDP 47822 for the current path and port). Manual, PowerShell as administrator:

```powershell
New-NetFirewallRule -DisplayName "FireDrop TCP" -Direction Inbound -Protocol TCP -LocalPort 47820 -Action Allow
New-NetFirewallRule -DisplayName "FireDrop UDP" -Direction Inbound -Protocol UDP -LocalPort 47822 -Action Allow
```

## Speed

Use 5 GHz / Wi-Fi 6-7 networks, keep the phone near the router, disable VPN and guest networks, do not lock the sender's screen (the app keeps it awake during the transfer). Reference: about **30 MB/s** on a Wi-Fi 5 test network; gigabit Wi-Fi can be faster.

## Security

- The server works only in the local network; access only via the QR link with a token or the PIN (several failed attempts trigger a temporary lockout).
- Traffic is HTTP inside the Wi-Fi network; do not use FireDrop in public networks, do not show the QR/PIN to strangers.

## Installing on Android

1. Copy the APK to the phone and open it; allow installation from "unknown sources" for the file manager/browser.
2. Play Protect: **More details → Install anyway** (the app is signed outside the Play Store).
3. Android 13+: allow notifications; Android ≤9: grant storage access.
4. Xiaomi/Huawei/Samsung phones: "Battery → No restrictions" for FireDrop, otherwise the system may stop receiving.

## Logs and diagnostics

`%APPDATA%\FireDrop\logs\firedrop.log`, incomplete downloads in `%APPDATA%\FireDrop\partial` (cleaned automatically). Android: `adb logcat -s FireDrop`.

SmartScreen may warn about the unsigned `FireDrop.exe` — **More details → Run anyway**, if you downloaded it from a trusted source.

## Building from source

**Windows** (requires the [.NET 10 SDK](https://dotnet.microsoft.com/download)):

```powershell
dotnet publish .\FireDrop\FireDrop.csproj -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
  -p:EnableCompressionInSingleFile=true -o .\dist
```

**Android** (JDK 17, Android SDK Platform 34, Build-Tools 34.0.0; no Gradle):

```powershell
.\Android\build.ps1 -Sdk "C:\Android\sdk" -Jdk "C:\Program Files\Java\jdk-17"
```

On first run the script creates the signing key `Android\keystore\firedrop.jks` — keep it safe, otherwise updates won't install over old versions. Linux uses the same pipeline (for the F-Droid build server): `bash Android/fdroid-build.sh` → `Android/build/FireDrop-unsigned.apk`.

The zxing 3.5.3 library (Apache-2.0) is not stored in the repository — the jar is downloaded from Maven Central and verified by its SHA-256 on first build.

## License and F-Droid

The repository is under the **GNU GPL v3** (see `LICENSE`). The only third-party dependency is ZXing (Apache-2.0).

F-Droid builds the app itself from the open source code and signs it with its own key. Metadata: `metadata/ru.firedrop.app.yml` (copy into the `metadata/` folder of your `fdroiddata` checkout and open a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata)). New versions are picked up by tags `v1.2.4`, `v1.2.5`… Release notes live in `Android/fastlane/metadata/android/*/changelogs/<versionCode>.txt`.

**Note**: the F-Droid APK is signed with their key, the APK from this repository with your `firedrop.jks`; they do not install over each other — delete the app first when switching. Users of the F-Droid catalog get regular updates instead.

## Project structure

- `FireDrop/` — the WPF app, the embedded ASP.NET Core server, the phone web page (`FireDrop/Web/`), UI string tables (`FireDrop/L10n.cs`, `L10nUi.cs`, `L10nCore.cs`).
- `Android/app/java/ru/firedrop/app/` — the Android app; `L10n.java` plus fragments `L10nA/S/R/Q.java` for strings.
- `Android/build.ps1` (Windows), `Android/fdroid-build.sh` (Linux) — APK assembly.
- `metadata/ru.firedrop.app.yml` — F-Droid metadata; `Android/fastlane/metadata/` — catalog descriptions and changelogs.
- `verification/` — smoke tests for both apps.
- `dist/` — the ready Windows release.

---

Version 1.2.4 · FireDrop · 2026
