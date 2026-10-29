# FastDownloader for Android: licence and third-party notices

FastDownloader for Android is Copyright © 2026 Janardan/TSE and is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License, version 3** ([LICENSE](LICENSE)). It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

The Android app's complete source code is published at <https://github.com/Janardanpal007/FastDownloader-Download/tree/main/android>.

The Android app is licensed under the GPL because it includes youtubedl-android, which is GPL-3.0. The desktop apps (Windows, macOS, Linux) and the browser extension are separate programs and are not covered by this licence.

## Included software

| Component | Used for | Licence |
|---|---|---|
| [youtubedl-android](https://github.com/yausername/youtubedl-android) | Runs yt-dlp on Android | GPL-3.0 |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | YouTube and other video sites | The Unlicense |
| [Python](https://www.python.org/) (bundled by youtubedl-android) | Runs yt-dlp | Python Software Foundation License |
| [FFmpeg](https://ffmpeg.org/) (bundled by youtubedl-android) | Joining video and audio, converting to MP3 | LGPL/GPL; source: <https://ffmpeg.org/download.html> and <https://github.com/yausername/youtubedl-android> |
| [QuickJS](https://bellard.org/quickjs/) (bundled by youtubedl-android) | JavaScript that YouTube's player needs | MIT |
| [libtorrent](https://libtorrent.org/) via [libtorrent4j](https://github.com/aldenml/libtorrent4j) | Torrents and magnet links | BSD-3-Clause / MIT |
| [OkHttp](https://square.github.io/okhttp/) | HTTP downloads and proxy | Apache-2.0 |
| [Kotlin](https://kotlinlang.org/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Language and libraries | Apache-2.0 |
| [AndroidX and Jetpack Compose](https://developer.android.com/jetpack/androidx) | Screens and Android integration | Apache-2.0 |
| [Material Icons](https://github.com/google/material-design-icons) (path data) | Icons | Apache-2.0 |
| [Space Grotesk](https://github.com/floriankarsten/space-grotesk), [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), [Inter](https://github.com/rsms/inter) | Typefaces | SIL Open Font License 1.1 |

The app icon is an original drawing made by `scripts/make-icon.js`. The ad and tracker list is FastDownloader's own (shared with the browser extension).
