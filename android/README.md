# FastDownloader for Android

The Android version of FastDownloader. It runs on **Android 8.0 (API 26) through Android 17 (API 37)**, and looks and works like the desktop app.

## What it does

- **Fast file downloads**: each file downloads in up to 32 parts at once, using HTTP Range requests. When a part finishes early, its connection takes over half of the largest part still left.
- **Pause and resume**: progress is saved every two seconds, so a download continues after closing the app, restarting the phone or losing the connection. A download that failed because the network dropped starts again by itself when the connection comes back.
- **Queue**: a set number of downloads run at once and the rest wait their turn. Each shows its progress, speed and time left. Downloads keep running in the background, with a notification and a "Pause all" button.
- **Sorted folders**: files are saved in `Download/FastDownloader/`, in ZIP, DOC, Programs, Videos, Music, Torrent, Images and Other folders.
- **Video sites**: YouTube and 1,000+ other sites, through yt-dlp. You pick the quality (up to 8K), MP4 or MKV, or audio only as M4A or MP3. HLS and DASH streams work too.
- **Torrents**: magnet links and .torrent files (libtorrent), with optional extra public trackers.
- **Built-in browser**:
  - finds the videos and audio a page loads;
  - catches download links;
  - blocks ads and trackers, using the same list as the browser extension;
  - signing in to a site there lets you download that site's videos that need an account.
- **Getting links in**:
  - tap **Share** on a link in any app and pick FastDownloader;
  - select text and choose FastDownloader in the text menu;
  - open magnet links or .torrent files;
  - copy a link: FastDownloader offers to download it when you switch to it.
- **Privacy**:
  - an HTTP or SOCKS5 proxy for everything, with DNS through the proxy too and no fallback to your real address;
  - a "Check my IP" button;
  - Do Not Track and Global Privacy Control signals;
  - tracking tags removed from links;
  - a kill switch that runs torrents only while a VPN is on;
  - history options.
- **Other settings**: a speed limit and a Wi-Fi-only mode.

## Installing on a phone

1. Download the APK that fits your phone from the [Releases page](https://github.com/Janardanpal007/FastDownloader-Download/releases/latest):

   | File | For |
   |---|---|
   | `FastDownloader-<version>-android-arm64.apk` | Almost every phone from 2017 on (pick this if unsure) |
   | `FastDownloader-<version>-android-arm.apk` | Older 32-bit phones |
   | `FastDownloader-<version>-android-x86_64.apk` | Chromebooks and emulators |
   | `FastDownloader-<version>-android-universal.apk` | Any device (bigger download) |

2. Open the downloaded file. Android asks to allow installing from your browser or file manager the first time: tap **Settings**, turn on **Allow from this source**, go back and tap **Install**.
3. Open FastDownloader and allow notifications, so you can see downloads in the background.

Android 8 to 10 ask once for storage permission, the first time you download. Android 11 and newer don't need it.

## Building from source

You need JDK 17 or newer, and the Android SDK with platform 37.0 and build-tools 37 (Android Studio installs these for you).

```bash
cd android
./gradlew assembleDebug          # debug build: app/build/outputs/apk/debug/
./gradlew assembleRelease        # release build, signed if keystore.properties exists

```

The app's version is read from the root `package.json`, so every platform shares one version number.

### Release signing

Release APKs must always be signed with the same key, or phones refuse to install an update over the old version. The key is kept **outside** the project, and `android/keystore.properties` (ignored by git) points to it:

```properties
storeFile=C:/Users/you/.fastdownloader/android-release.jks
storePassword=...
keyAlias=fastdownloader
keyPassword=...
```

On GitHub Actions the same values come from the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. **Back up the .jks file and its password.** If they're lost, future versions can't update existing installs.

## How it's built

| Part | Where |
|---|---|
| Download queue, saving and network rules | `app/src/main/java/com/fastdownloader/app/engine/Engine.kt` |
| Segmented HTTP downloads | `engine/HttpTask.kt` |
| Torrents (libtorrent4j) | `engine/TorrentTask.kt` |
| Video sites (youtubedl-android: yt-dlp, Python, FFmpeg, QuickJS) | `engine/VideoTask.kt` |
| HTTP client, proxy, privacy headers | `engine/Net.kt` |
| Categories, link checks, tracking-tag removal | `engine/Links.kt` |
| Background service and notification | `service/DownloadService.kt` |
| Screens (Jetpack Compose) | `ui/` |

## Tested on

- **Android 16** (API 36) emulator:
  - a 756 MB file downloaded in 8 parts, was paused, force-stopped and resumed, and its SHA-256 matched Debian's official value;
  - a YouTube video downloaded (video and audio joined);
  - a magnet link downloaded with libtorrent;
  - the Browser and Settings screens work.
- **Android 8.0** (API 26) emulator:
  - the release build installed and opened;
  - storage permission was asked once;
  - category folders were created;
  - a 64 MB download's SHA-256 matched Debian's official value;
  - YouTube listed qualities up to 4K.
- **Android 17** (API 37): the app is built for it (`targetSdk 37`) and installs. The Android 17 emulator image crashes in its own graphics service on the test PC, so the app hasn't been run on Android 17 yet. Please try it on an Android 17 phone.

## Licence

FastDownloader for Android is free software under the **GNU General Public License v3** ([LICENSE](LICENSE)), because it includes [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0). Third-party components and their licences are listed in [NOTICE.md](NOTICE.md). The desktop apps and the browser extension are separate programs and aren't covered by this licence.
