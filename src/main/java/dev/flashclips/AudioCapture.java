package dev.flashclips;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Captures only this process's audio (Minecraft's sound, plus anything else playing inside the game such as
 * Simple Voice Chat) using Windows' WASAPI process loopback, the same mechanism OBS uses for application
 * audio capture. It doesn't change what you hear and adds no delay. Needs Windows 10 2004 or newer.
 *
 * Audio is kept as timestamped 48 kHz stereo 16-bit PCM chunks for the last ~15 seconds.
 */
public final class AudioCapture {
    public static final int RATE = 48000;
    public static final int BYTES_PER_FRAME = 4; // 2 channels * 16 bit

    private record Chunk(long startNanos, byte[] pcm) {}

    private static final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
    private static final long KEEP_NANOS = 15_000_000_000L;

    private static volatile boolean running = false;
    private static volatile boolean failed = false;
    private static Thread thread;
    private static Handler handler; // must stay reachable: Windows calls back into it

    private static final Guid.GUID IID_IUNKNOWN = new Guid.GUID("{00000000-0000-0000-C000-000000000046}");
    private static final Guid.GUID IID_IAGILE_OBJECT = new Guid.GUID("{94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90}");
    private static final Guid.GUID IID_COMPLETION_HANDLER = new Guid.GUID("{41D949AB-9862-444A-80F6-C261334DA5EB}");
    private static final Guid.GUID IID_IAUDIOCLIENT = new Guid.GUID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}");
    private static final Guid.GUID IID_IAUDIOCAPTURECLIENT = new Guid.GUID("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}");

    private AudioCapture() {}

    public static boolean isRunning() {
        return running;
    }

    public static synchronized void start() {
        if (thread != null || failed) return;
        if (!Platform.isWindows()) {
            FlashClips.LOGGER.info("Audio capture is only supported on Windows; clips will have no sound");
            failed = true;
            return;
        }
        thread = new Thread(AudioCapture::run, "FlashClips-Audio");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Interleaved 16-bit stereo PCM covering [startNanos, startNanos + frames / RATE), with silence wherever
     * nothing was captured (the game was quiet).
     */
    public static byte[] extract(long startNanos, int frames) {
        byte[] out = new byte[frames * BYTES_PER_FRAME];
        synchronized (chunks) {
            for (Chunk c : chunks) {
                long offset = Math.round((c.startNanos() - startNanos) * (double) RATE / 1e9);
                int len = c.pcm().length / BYTES_PER_FRAME;
                long from = Math.max(0, offset);
                long to = Math.min(frames, offset + len);
                if (from >= to) continue;
                System.arraycopy(c.pcm(), (int) ((from - offset) * BYTES_PER_FRAME), out,
                        (int) (from * BYTES_PER_FRAME), (int) ((to - from) * BYTES_PER_FRAME));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- capture thread

    private interface Mmdevapi extends Library {
        int ActivateAudioInterfaceAsync(WString deviceInterfacePath, Guid.GUID riid, Pointer activationParams,
                                        Pointer completionHandler, PointerByReference activationOperation);
    }

    private static void run() {
        try {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
            Mmdevapi mmdevapi = Native.load("Mmdevapi", Mmdevapi.class);

            // AUDIOCLIENT_ACTIVATION_PARAMS: process loopback of this process (and its children)
            Memory params = new Memory(12);
            params.setInt(0, 1); // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
            params.setInt(4, Kernel32.INSTANCE.GetCurrentProcessId());
            params.setInt(8, 0); // PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE

            // PROPVARIANT { vt = VT_BLOB, blob = { 12, params } }
            Memory propVariant = new Memory(24);
            propVariant.clear();
            propVariant.setShort(0, (short) 65);
            propVariant.setInt(8, 12);
            propVariant.setPointer(16, params);

            handler = new Handler();
            PointerByReference operation = new PointerByReference();
            check(mmdevapi.ActivateAudioInterfaceAsync(new WString("VAD\\Process_Loopback"), IID_IAUDIOCLIENT,
                    propVariant, handler.object, operation), "ActivateAudioInterfaceAsync");
            if (!handler.done.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("audio activation timed out");

            Pointer op = operation.getValue();
            IntByReference activateResult = new IntByReference();
            PointerByReference unknown = new PointerByReference();
            check(call(op, 3, op, activateResult, unknown), "GetActivateResult");
            check(activateResult.getValue(), "activate audio client");
            Pointer client = unknown.getValue();

            // WAVEFORMATEX: PCM, 2 ch, 48 kHz, 16 bit
            Memory format = new Memory(18);
            format.setShort(0, (short) 1);
            format.setShort(2, (short) 2);
            format.setInt(4, RATE);
            format.setInt(8, RATE * BYTES_PER_FRAME);
            format.setShort(12, (short) BYTES_PER_FRAME);
            format.setShort(14, (short) 16);
            format.setShort(16, (short) 0);

            int streamFlags = 0x00020000 /* LOOPBACK */ | 0x80000000 /* AUTOCONVERTPCM */ | 0x08000000 /* SRC_DEFAULT_QUALITY */;
            check(call(client, 3, client, 0, streamFlags, 2_000_000L, 0L, format, Pointer.NULL), "IAudioClient.Initialize");

            PointerByReference captureRef = new PointerByReference();
            check(call(client, 14, client, IID_IAUDIOCAPTURECLIENT, captureRef), "IAudioClient.GetService");
            Pointer capture = captureRef.getValue();
            check(call(client, 10, client), "IAudioClient.Start");

            running = true;
            FlashClips.LOGGER.info("Capturing game audio (WASAPI process loopback)");

            IntByReference packetFrames = new IntByReference();
            PointerByReference data = new PointerByReference();
            IntByReference frames = new IntByReference();
            IntByReference bufferFlags = new IntByReference();
            LongByReference devicePosition = new LongByReference();
            LongByReference qpcPosition = new LongByReference();

            while (true) {
                Thread.sleep(10);
                check(call(capture, 5, capture, packetFrames), "GetNextPacketSize");
                while (packetFrames.getValue() > 0) {
                    check(call(capture, 3, capture, data, frames, bufferFlags, devicePosition, qpcPosition), "GetBuffer");
                    int n = frames.getValue();
                    byte[] pcm = new byte[n * BYTES_PER_FRAME];
                    if ((bufferFlags.getValue() & 2 /* SILENT */) == 0 && n > 0) {
                        data.getValue().read(0, pcm, 0, pcm.length);
                    }
                    check(call(capture, 4, capture, n), "ReleaseBuffer");
                    store(qpcPosition.getValue(), pcm);
                    check(call(capture, 5, capture, packetFrames), "GetNextPacketSize");
                }
            }
        } catch (Throwable t) {
            FlashClips.LOGGER.error("Game audio capture unavailable; clips will have no sound", t);
            failed = true;
            running = false;
        }
    }

    private static void store(long qpc100ns, byte[] pcm) {
        long now = System.nanoTime();
        long durationNanos = (long) (pcm.length / BYTES_PER_FRAME * 1e9 / RATE);
        // The capture timestamp is QPC-based in 100 ns units, the same clock System.nanoTime uses on Windows.
        long start = qpc100ns * 100L;
        if (qpc100ns == 0 || Math.abs(now - start) > 2_000_000_000L) start = now - durationNanos;
        synchronized (chunks) {
            chunks.addLast(new Chunk(start, pcm));
            while (!chunks.isEmpty() && now - chunks.peekFirst().startNanos() > KEEP_NANOS) chunks.pollFirst();
        }
    }

    private static int call(Pointer comObject, int vtableIndex, Object... args) {
        Pointer vtable = comObject.getPointer(0);
        Function f = Function.getFunction(vtable.getPointer((long) vtableIndex * Native.POINTER_SIZE));
        return f.invokeInt(args);
    }

    private static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(hr));
    }

    /** Minimal COM object implementing IActivateAudioInterfaceCompletionHandler (+ IAgileObject). */
    private static final class Handler {
        final CountDownLatch done = new CountDownLatch(1);
        final Memory object = new Memory(Native.POINTER_SIZE);
        final Memory vtable = new Memory(4L * Native.POINTER_SIZE);

        interface QueryInterface extends Callback {
            int invoke(Pointer self, Pointer riid, Pointer ppv);
        }

        interface RefCount extends Callback {
            int invoke(Pointer self);
        }

        interface ActivateCompleted extends Callback {
            int invoke(Pointer self, Pointer operation);
        }

        final QueryInterface queryInterface = new QueryInterface() {
            @Override
            public int invoke(Pointer self, Pointer riid, Pointer ppv) {
                Guid.GUID iid = new Guid.GUID(riid);
                if (iid.equals(IID_IUNKNOWN) || iid.equals(IID_IAGILE_OBJECT) || iid.equals(IID_COMPLETION_HANDLER)) {
                    ppv.setPointer(0, self);
                    return 0;
                }
                ppv.setPointer(0, Pointer.NULL);
                return 0x80004002; // E_NOINTERFACE
            }
        };
        final RefCount addRef = new RefCount() {
            @Override
            public int invoke(Pointer self) {
                return 1;
            }
        };
        final RefCount release = new RefCount() {
            @Override
            public int invoke(Pointer self) {
                return 1;
            }
        };
        final ActivateCompleted activateCompleted = new ActivateCompleted() {
            @Override
            public int invoke(Pointer self, Pointer operation) {
                done.countDown();
                return 0;
            }
        };

        Handler() {
            vtable.setPointer(0, CallbackReference.getFunctionPointer(queryInterface));
            vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
            vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release));
            vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(activateCompleted));
            object.setPointer(0, vtable);
        }
    }
}
