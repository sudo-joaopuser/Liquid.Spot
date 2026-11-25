# Credits and third-party licenses

LiquidSpot is distributed under the **GNU General Public License v3.0**. The full text is in [LICENSE](LICENSE).

Parts of LiquidSpot were adapted from the projects below.

## BitChord, GPL-3.0
https://github.com/kushagrasinghx/BitChord

Copyright © kushagrasinghx.

BitChord is the source of the look and parameters of the glass and the floating bar:
- 8 dp blur (16 dp in frosted mode).
- 1.5 saturation.
- 24 dp lens.
- 40% tint.
- 0.5 dp rim.
- 19 dp and 6 dp margins.
- The selection pill spring.
- The Liquid, Frosted and Solid modes.
- The lens that lifts when dragging the bar, instrumental breaks, duet sides, background vocals, the tap that brings the controls back and batched lyrics translation.

The code was ported from Jetpack Compose to Android Views.

## Echo-Music, GPL-3.0
According to BitChord's headers, the origin of the `GlassNavBar` used as a reference.

## Kyant0/backdrop, Apache-2.0
https://github.com/Kyant0/backdrop

Copyright 2025 Kyant.

The AGSL shaders for the refraction lens and the rim highlight (`xposed/ui/glass/GlassShaders.kt`) were ported from backdrop v2.0.0, in the version bundled with BitChord. The license is at http://www.apache.org/licenses/LICENSE-2.0.

## liquid_glass_easy, MIT
BitChord took the selection pill motion constants from this library, according to the header of `GlassSelectionPill.kt`.

## SpotifyPlus, MIT
https://github.com/sudo-joaopuser/SpotifyPlus

Copyright (c) 2025 Devon Shoutz.

SpotifyPlus was the skeleton and the inspiration for LiquidSpot. Files that carry SpotifyPlus code keep its MIT license notice in their header.

## Lyrics sources (services)
Spotify's own lyrics come from the SpotifyPlus API (`spotifyplus-api.devon-shoutz.workers.dev`). Sources queried: lrc.red, BiniLyrics, BetterLyrics, PaxSenix, LyricsPlus, Unison, KuGou, LRCLIB, Musixmatch and Genius. A search sends only the song's title, artist, album and duration (plus the track id for Spotify's lyrics).

## LRCLIB (service)
https://lrclib.net

Synced lyrics in the player come from the public LRCLIB API. A search sends only the song's title, artist, album and duration, with the User-Agent `LiquidSpot/<version>`. Lyrics belong to their authors.

## wav2vec2-base-960h, Apache-2.0
https://huggingface.co/facebook/wav2vec2-base-960h

Speech recognition model by Meta AI. AI syllable sync uses the ONNX conversion published at https://huggingface.co/Xenova/wav2vec2-base-960h (`onnx/model_quantized.onnx` and `vocab.json`). The model is not bundled in the APK: the app only downloads it when you tap Download, or uses the file you import. Syllable alignment runs on the device and the song's audio never leaves it.

## ONNX Runtime, MIT
https://github.com/microsoft/onnxruntime

Copyright (c) Microsoft Corporation. The APK ships only the Java classes of `onnxruntime-android`. The native libraries are downloaded from the official AAR on Maven Central together with the model, only when you ask for it.

## Google Translate (service)
The Translate button sends the lyric lines to `translate.googleapis.com` and gets them back in the device language. LiquidSpot is not affiliated with Google.

## open.spotify.com (service)
To open the artist or album page from the player, LiquidSpot reads the header of the song's public page (`https://open.spotify.com/track/<id>`), only when you tap the name. The request sends only the track id, with the User-Agent `LiquidSpot/<version>`.

## Build libraries
- **libxposed API and Service**: Apache-2.0.
- **HiddenApiBypass** (LSPosed): Apache-2.0. Used only to unlock `AccessibilityNodeInfo.getChildId` inside Spotify.
- **AndroidX and Jetpack Compose**: Apache-2.0.
- **Kotlin**: Apache-2.0.
- **ONNX Runtime** (Java classes only): MIT.

## Inspiration
**spoti.pw**, by skopevoj (PolyForm Strict). It served only as inspiration for the features, and its screenshots were the visual reference for the player and the sheets. LiquidSpot contains none of its code.
