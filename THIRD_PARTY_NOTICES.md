# Third-party notices

FastDownloader — Copyright © 2026 Janardan/TSE — includes or uses the following open-source software.
Their licences require these notices to be distributed with the app; they do not change who owns FastDownloader.

| Component | Used for | Licence | Notice shipped as |
|---|---|---|---|
| [Electron](https://www.electronjs.org/) (includes Chromium and Node.js) | App framework | MIT (Chromium: BSD and others) | `LICENSE.electron.txt`, `LICENSES.chromium.html` next to the app |
| [WebTorrent](https://webtorrent.io/) and its dependencies | Torrent and magnet downloads | MIT | inside `resources/app.asar` (`node_modules/*/LICENSE`) |
| [FFmpeg](https://ffmpeg.org/) via [ffmpeg-static](https://github.com/eugeneware/ffmpeg-static) (Windows, macOS) and [BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds) (Linux) | Joining and converting video streams | GPL v3 (FFmpeg builds), MIT/GPL (package) | next to the binary in `resources/app.asar.unpacked/node_modules/ffmpeg-static/` (`ffmpeg*.LICENSE`) |
| [Space Grotesk](https://github.com/floriankarsten/space-grotesk), [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), [Inter](https://github.com/rsms/inter) | App and website typefaces | SIL Open Font License 1.1 | `fonts/*-LICENSE.txt` inside the app |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | YouTube and other video sites | The Unlicense (public domain) | downloaded on first use; not bundled |

The Android app has its own licence (GNU GPL v3, because it includes the GPL-3.0 library youtubedl-android) and notices: see [android/NOTICE.md](android/NOTICE.md).

The app icon is an original drawing made by `scripts/make-icon.js`.

## Source code for the bundled FFmpeg (GPL)

FFmpeg is included unmodified as a separate program. Its source code is available from:

- FFmpeg itself: <https://ffmpeg.org/download.html> (all release sources)
- Windows build (gyan.dev, via ffmpeg-static): <https://www.gyan.dev/ffmpeg/builds/>
- macOS builds (via ffmpeg-static): <https://github.com/eugeneware/ffmpeg-static> (links to each build's origin)
- Linux build: <https://github.com/BtbN/FFmpeg-Builds> (build scripts and exact source versions for every release)

If any of these links stop working, open an issue in this repository and the matching source will be provided.
