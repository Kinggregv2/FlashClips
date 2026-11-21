# FlashClips

A client-side Fabric mod for Minecraft 26.3, built on [Flashback](https://modrinth.com/mod/flashback). It does two things:

1. **Replay clips.** An always-on 30 second Flashback replay buffer.
   - **Manual clip:** press **F7** (rebindable under Controls → FlashClips) to save the last 30 seconds.
   - **Kill clip:** 3 seconds after you kill a player, the last 30 seconds are saved automatically. The kill moment gets a marker on the timeline.
   - These are Flashback replays, and they show up in Flashback's replay list under a `clips` folder.
2. **Montage clips.** An always-on 2 second screen recording.
   - 1 second after you **pop someone's totem** or **kill someone**, the last 2 seconds (1 s before, 1 s after) are saved as an `.mp4`.
   - Chat is never in these videos. It still shows on your screen as normal.
   - These clips aren't sorted by who you hit. They all go into one folder, ready to drop into an editor.

## Folder layout

```
<game dir>/flashback/replays/clips/          ← shows up in Flashback's replay list
├── manual clips/
│   └── clip_2026-11-21_14-03-22.zip
└── kill clips/
    └── <victim uuid>_<latest username>/
        └── kill_<username>_2026-11-21_14-05-10.zip

<game dir>/montage clips/                    ← plain videos, not Flashback files
├── 2026-11-21_14-05-09_pop.mp4
└── 2026-11-21_14-05-10_kill.mp4
```

Kill replay clips are grouped by the victim's UUID. If the player changes their name, the folder is renamed to the new name the next time you kill them.

## How it works

**Replay clips.** Flashback normally splits a recording into 5-minute chunks, each starting with a full world snapshot. FlashClips shortens those chunks to 10 seconds. When you clip, it closes the current chunk, takes the newest chunks that cover at least 30 seconds, and repacks them with fixed-up metadata as a standalone replay. A clip is 30 to 40 seconds long, because it has to start on a chunk boundary.

When you join a world, FlashClips starts a Flashback recording to act as the buffer. Old chunks are deleted as it goes, and the buffer is thrown away when you leave.

**Montage clips.** Each frame, the world image is copied into a separate capture texture. The HUD is then drawn into that texture a second time, without the chat layer. The frame is read back from the GPU in the background and encoded to H.264 by the FFmpeg that ships inside Flashback. It uses NVIDIA NVENC, AMD AMF or Intel QSV if available, otherwise the CPU. The last few seconds of encoded video are kept in memory, so saving just copies packets into an `.mp4` without re-encoding. A pop and a kill close together are merged into one video, up to 8 seconds long.

**Detection.**
- Kills come from vanilla death messages (`death.*` translations) and custom kill-message regexes. A kill also counts when a player dies within 5 s of being hit by you, or after standing near a crystal or anchor you set off.
- Totem pops come from the totem animation event. They count when the player was hit by you within those same 5 seconds.

## Config

The config file is `config/flashclips.json`:

| key | default | meaning |
|---|---|---|
| `bufferSeconds` | 30 | minimum replay clip length |
| `killClipDelaySeconds` | 3 | how long after a kill the replay clip is saved |
| `chunkSeconds` | 10 | Flashback chunk length. Lower gives tighter clips but more frequent snapshots |
| `autoStartBuffer` | true | start the replay buffer when joining |
| `trimOldChunks` | true | delete chunks older than the buffer |
| `discardBufferOnLeave` | true | discard the buffer on leave instead of saving a full replay |
| `killCreditSeconds` | 5 | how long a hit counts toward a kill or pop |
| `explosionCreditRadius` | 12 | radius around crystals/anchors you pop |
| `detectKillsFromChat` / `detectKillsFromHits` | true | kill detection sources |
| `customKillMessagePatterns` | 2 regexes | for servers with custom kill messages. Needs `(?<victim>…)` and `(?<killer>…)` groups |
| `screenBufferEnabled` | true | montage screen recording on/off |
| `screenSecondsBefore` / `screenSecondsAfter` | 1 / 1 | video kept before / recorded after the pop or kill |
| `screenMaxClipSeconds` | 8 | max length when several events are merged |
| `screenFps` | 60 | recording frame rate |
| `screenHeight` | 0 | output height in pixels. 0 = window resolution |
| `screenContainer` | mp4 | `mp4` or `mkv` |
| `screenEncoder` | auto | or force one, e.g. `h264_nvenc`, `libx264` |
| `screenQuality` | 20 | constant quality. Lower is better and gives bigger files |

## Building

1. Copy `Flashback-0.43.6-for-MC26.3.jar` into `libs/`. It isn't redistributed here.
2. Run `./gradlew build`. The jar ends up in `build/libs/`.

Requires Java 25, Flashback 0.43+ and Fabric API.
