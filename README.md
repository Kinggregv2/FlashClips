# Clips

A client-side Fabric mod for Minecraft 26.3 (shown as **Clips** in Mod Menu) that keeps an always-on **2 second screen recording** and saves short videos for montages.

- **Totem pop or kill:** 1 second after you pop someone's totem or kill someone, the last 2 seconds (1 s before, 1 s after) are saved as an `.mp4`.
- **Clip key:** press **F7** (rebindable under Controls → Clips) to save the last 2 seconds right away.
- **Game audio included.** Only Minecraft's own sound is recorded (plus in-game voice chat such as Simple Voice Chat). Discord, music players and other apps are not.
- **No chat in the videos.** Chat still shows on your screen as normal. Everything else on the HUD (hotbar, hearts, crosshair…) is recorded.
- **Not sorted by target.** All clips go into one folder, ready to drop into an editor.

```
<game dir>/montage clips/
├── 2026-11-21_14-05-09_pop.mp4
├── 2026-11-21_14-05-10_kill.mp4
└── 2026-11-21_14-06-30_manual.mp4
```

A pop and a kill close together are merged into one longer video (up to 8 s), named e.g. `…_pop+kill.mp4`.

Each clip stores where its pops/kills happen in the mp4's `comment` tag, e.g. `{"clips":1,"events":[{"type":"pop","t":1.032}]}` (`t` = seconds from the start of the video).

## Clips Editor (montages)

A very simple montage maker that ships next to the mod:

- **Start it:** double-click **`Clips Editor.bat`** in the Minecraft instance folder. It opens in your browser.
- **Pick your clips:** it lists everything in `montage clips/`. Untick the ones you don't want and reorder them with ↑ ↓.
- **Shorten them:** set how much to keep **before** and **after** each pop/kill, per clip or for all at once. *Play cut* previews one clip's cut.
- **Add music:** pick an **mp3**, then set where in the song to start and the music / game-audio volumes. *Preview montage* plays the whole thing in the browser.
- **Export** writes `montage clips/montages/montage_<time>.mp4`. Video is re-encoded on your GPU (NVENC/AMF/QSV, else CPU). The music fades out over the last second.

It uses the FFmpeg inside Flashback's jar (from `mods/`), so nothing extra needs installing. The window/page can be closed any time; the editor quits by itself a couple of minutes after the page is gone. For clips saved before event times were stored, it guesses the pop/kill time from your *After* setting and marks it "estimated".

Install: put `editor/build/libs/clips-editor.jar` in `<instance>/clips-editor/` and `editor/Clips Editor.bat` in `<instance>/`.

## How it works

1. Each frame, the world image is copied into a separate capture texture.
2. The HUD is drawn into that texture a second time, without the chat layer. Chat is put in its own GUI layer so it can be skipped.
3. The frame is read back from the GPU in the background.
4. It's encoded to H.264 using NVIDIA NVENC, AMD AMF or Intel QSV if available, otherwise the CPU.
5. Game audio is captured with Windows' per-process audio loopback (WASAPI, like OBS's "application audio capture"). It doesn't change what you hear and adds no delay. Needs Windows 10 2004 or newer.
6. The last few seconds of video and audio are kept in memory. Saving copies the video packets into an `.mp4` and encodes the matching audio to AAC.

[Flashback](https://modrinth.com/mod/flashback) must be installed. Clips doesn't use Flashback's replay recording. It borrows Flashback's bundled FFmpeg and its GPU frame-readback code.

**Detection.**
- Kills come from vanilla death messages (`death.*` translations) and custom kill-message regexes. A kill also counts when a player dies within 5 s of being hit by you, or after standing near a crystal or anchor you set off.
- Totem pops count when the player was hit by you within those same 5 seconds.

## Settings

In **Mod Menu → Clips → ⚙** (needs [Cloth Config](https://modrinth.com/mod/cloth-config)) you can turn the recording buffer on/off, set how many seconds before and after a pop/kill are kept (0–10 s each), and toggle game audio.

Everything is also in `config/clips.json`:

| key | default | meaning |
|---|---|---|
| `screenBufferEnabled` | true | screen recording on/off |
| `screenSecondsBefore` / `screenSecondsAfter` | 1 / 1 | video kept before / recorded after the pop or kill |
| `screenMaxClipSeconds` | 8 | max length when several events are merged |
| `screenFps` | 60 | recording frame rate |
| `screenHeight` | 0 | output height in pixels. 0 = window resolution. Lower it if you lose FPS |
| `screenAudio` | true | record game sound |
| `screenContainer` | mp4 | `mp4` or `mkv` |
| `screenEncoder` | auto | or force one, e.g. `h264_nvenc`, `libx264` |
| `screenQuality` | 20 | constant quality. Lower is better and gives bigger files |
| `killCreditSeconds` | 5 | how long a hit counts toward a kill or pop |
| `explosionCreditRadius` | 12 | radius around crystals/anchors you pop |
| `detectKillsFromChat` / `detectKillsFromHits` | true | kill detection sources |
| `customKillMessagePatterns` | 2 regexes | for servers with custom kill messages. Needs `(?<victim>…)` and `(?<killer>…)` groups |

## Building

1. Copy `Flashback-0.43.6-for-MC26.3.jar`, `modmenu-21.0.0.jar` and `cloth-config-fabric-26.3.159.jar` into `libs/` (compile-only, not redistributed here).
2. Run `./gradlew build`. The mod ends up in `build/libs/clips-1.0.0.jar`, the editor in `editor/build/libs/clips-editor.jar`.

Requires Java 25, Flashback 0.43+ and Fabric API.
