# FlashClips

A client-side Fabric mod for Minecraft 26.3 that keeps an always-on **2 second screen recording** and saves short videos for montages.

- **Totem pop or kill:** 1 second after you pop someone's totem or kill someone, the last 2 seconds (1 s before, 1 s after) are saved as an `.mp4`.
- **Clip key:** press **F7** (rebindable under Controls → FlashClips) to save the last 2 seconds right away.
- **No chat in the videos.** Chat still shows on your screen as normal. Everything else on the HUD (hotbar, hearts, crosshair…) is recorded.
- **Not sorted by target.** All clips go into one folder, ready to drop into an editor.

```
<game dir>/montage clips/
├── 2026-11-21_14-05-09_pop.mp4
├── 2026-11-21_14-05-10_kill.mp4
└── 2026-11-21_14-06-30_manual.mp4
```

A pop and a kill close together are merged into one longer video (up to 8 s), named e.g. `…_pop+kill.mp4`.

## How it works

1. Each frame, the world image is copied into a separate capture texture.
2. The HUD is drawn into that texture a second time, without the chat layer. Chat is put in its own GUI layer so it can be skipped.
3. The frame is read back from the GPU in the background.
4. It's encoded to H.264 using NVIDIA NVENC, AMD AMF or Intel QSV if available, otherwise the CPU.
5. The last few seconds of encoded video are kept in memory. Saving just copies those packets into an `.mp4`, without re-encoding.

[Flashback](https://modrinth.com/mod/flashback) must be installed. FlashClips doesn't use Flashback's replay recording. It borrows Flashback's bundled FFmpeg and its GPU frame-readback code.

**Detection.**
- Kills come from vanilla death messages (`death.*` translations) and custom kill-message regexes. A kill also counts when a player dies within 5 s of being hit by you, or after standing near a crystal or anchor you set off.
- Totem pops count when the player was hit by you within those same 5 seconds.

## Config

The config file is `config/flashclips.json`:

| key | default | meaning |
|---|---|---|
| `screenBufferEnabled` | true | screen recording on/off |
| `screenSecondsBefore` / `screenSecondsAfter` | 1 / 1 | video kept before / recorded after the pop or kill |
| `screenMaxClipSeconds` | 8 | max length when several events are merged |
| `screenFps` | 60 | recording frame rate |
| `screenHeight` | 0 | output height in pixels. 0 = window resolution. Lower it if you lose FPS |
| `screenContainer` | mp4 | `mp4` or `mkv` |
| `screenEncoder` | auto | or force one, e.g. `h264_nvenc`, `libx264` |
| `screenQuality` | 20 | constant quality. Lower is better and gives bigger files |
| `killCreditSeconds` | 5 | how long a hit counts toward a kill or pop |
| `explosionCreditRadius` | 12 | radius around crystals/anchors you pop |
| `detectKillsFromChat` / `detectKillsFromHits` | true | kill detection sources |
| `customKillMessagePatterns` | 2 regexes | for servers with custom kill messages. Needs `(?<victim>…)` and `(?<killer>…)` groups |

## Building

1. Copy `Flashback-0.43.6-for-MC26.3.jar` into `libs/`. It isn't redistributed here.
2. Run `./gradlew build`. The jar ends up in `build/libs/`.

Requires Java 25, Flashback 0.43+ and Fabric API. Videos have no audio.
