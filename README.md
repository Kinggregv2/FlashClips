# FlashClips

A client-side Fabric mod for Minecraft 26.3 that adds an always-on **30 second replay buffer** on top of [Flashback](https://modrinth.com/mod/flashback).

- **Manual clip:** press **F7** (rebindable under Controls → FlashClips) to save the last 30 seconds.
- **Kill clip:** 3 seconds after you kill a player, the last 30 seconds are saved automatically. The clip covers about 27 s before the kill and 3 s after it, and the kill moment gets a marker on the timeline.

Clips are saved as Flashback replays (`.zip`). Open one with Flashback (*Open replay* → pick the file) to watch it or export it to video.

## Folder layout

```
<game dir>/clips/
├── manual clips/
│   └── clip_2026-11-21_14-03-22.zip
└── kill clips/
    └── <victim uuid>_<latest username>/
        └── kill_<username>_2026-11-21_14-05-10.zip
```

Kill clips are grouped by the victim's UUID. If the player changes their name, the folder is renamed to the new name the next time you kill them, so every clip of that player stays in one place.

## How it works

Flashback normally splits a recording into 5-minute chunks, and each chunk starts with a full world snapshot. FlashClips shortens those chunks to 10 seconds. When you clip, it closes the current chunk, takes the newest chunks that cover at least 30 seconds, and repacks them with fixed-up metadata as a standalone replay. A clip is 30 to 40 seconds long, because it has to start on a chunk boundary.

When you join a world, FlashClips starts a Flashback recording to act as the buffer. Chunks older than the buffer are deleted, so the buffer doesn't keep growing. When you leave, the buffer is thrown away instead of being saved as a full replay.

Kills are detected from vanilla death messages (`death.*` translations) and from custom kill-message regexes. A kill also counts when a player dies within 5 s of being hit by you, or after standing near a crystal or anchor you set off.

## Config

The config file is `config/flashclips.json`:

| key | default | meaning |
|---|---|---|
| `bufferSeconds` | 30 | minimum clip length |
| `killClipDelaySeconds` | 3 | how long after a kill the clip is saved |
| `chunkSeconds` | 10 | Flashback chunk length. Lower gives tighter clips but more frequent snapshots |
| `autoStartBuffer` | true | start the buffer recording when joining |
| `trimOldChunks` | true | delete chunks older than the buffer |
| `discardBufferOnLeave` | true | discard the buffer on leave instead of saving a full replay |
| `killCreditSeconds` | 5 | how long a hit counts toward a kill |
| `explosionCreditRadius` | 12 | radius around crystals/anchors you pop |
| `detectKillsFromChat` / `detectKillsFromHits` | true | kill detection sources |
| `customKillMessagePatterns` | 2 regexes | for servers with custom kill messages. Needs `(?<victim>…)` and `(?<killer>…)` groups |

## Building

1. Copy `Flashback-0.43.6-for-MC26.3.jar` into `libs/`. It isn't redistributed here.
2. Run `./gradlew build`. The jar ends up in `build/libs/`.

Requires Java 25, Flashback 0.43+ and Fabric API.
