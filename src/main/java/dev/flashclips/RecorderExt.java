package dev.flashclips;

import com.moulberry.flashback.record.FlashbackMeta;

import java.nio.file.Path;

/** Implemented on Flashback's Recorder by RecorderMixin. */
public interface RecorderExt {
    int flashclips$getWrittenTicks();

    FlashbackMeta flashclips$getMetadata();

    Path flashclips$getRecordFolder();
}
