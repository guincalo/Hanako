package it.belloworld.mercurygram.transcribe;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * [Hanako] Parakeet TDT 0.6B v3 through sherpa-onnx's {@link OfflineRecognizer}
 * (NeMo transducer, greedy search) with sherpa's Silero VAD cutting the message
 * into speech segments, each decoded on its own so long voice messages stay
 * cheap and the UI gets a live partial per segment.
 *
 * Threading: every method except {@link #isSupported()} must run on
 * {@link MgWhisperTranscriber}'s single worker thread (the native objects are
 * not thread-safe). Memory: the recognizer holds ~0.7 GB of native heap, so it
 * is loaded lazily and released after {@link MgParakeetSpec#IDLE_RELEASE_MS}
 * idle (scheduled by the transcriber), on delete, on disable, and before the
 * whisper engine loads.
 */
public final class MgParakeetEngine {

    private static final int SAMPLE_RATE = 16000;
    private static final int VAD_WINDOW = 512;
    // Parakeet occasionally drops the last word when a segment ends right at
    // speech offset; a short tail of silence avoids that.
    private static final int TAIL_PAD = SAMPLE_RATE / 4;
    // Without VAD, cut long messages into 20 s pieces.
    private static final int FIXED_CHUNK = SAMPLE_RATE * 20;

    private static volatile Boolean supported;

    // Worker-thread state.
    private static OfflineRecognizer recognizer;
    private static long loadedStamp;
    private static Vad vad;

    private MgParakeetEngine() {
    }

    /**
     * True when this build ships the sherpa-onnx native lib for the running ABI.
     * Only arm64-v8a is packaged (the Hanako APK is arm64-only; the other
     * F-Droid flavors don't carry the lib), so everything else keeps whisper.
     */
    public static boolean isSupported() {
        Boolean v = supported;
        if (v != null) {
            return v;
        }
        boolean ok = false;
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            String primary = (abis != null && abis.length > 0) ? abis[0] : "";
            if ("arm64-v8a".equals(primary)) {
                ApplicationInfo ai = ApplicationLoader.applicationContext.getApplicationInfo();
                // The app module packages with useLegacyPackaging, so native
                // libs are extracted here; a flavor without the lib keeps whisper.
                ok = new File(ai.nativeLibraryDir, "libsherpa-onnx-jni.so").exists();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        supported = ok;
        return ok;
    }

    private static long bundleStamp() {
        File enc = MgParakeetModel.file(MgParakeetSpec.ENCODER);
        return enc.lastModified() * 31 + enc.length();
    }

    /**
     * Writes the onnxruntime session config sherpa-onnx reads from the
     * {@code cpu:<path>} provider string: no CPU memory arena (it keeps the peak
     * activation size resident between calls) and initializers allocated with
     * the plain device allocator, so freeing the session actually returns the
     * memory. Returns the provider string, or plain {@code "cpu"} if the file
     * can't be written (sherpa exits the process on an unreadable config path).
     */
    private static String providerWithConfig() {
        try {
            File cfg = new File(MgParakeetModel.dir(), "ort-session.cfg");
            String body = "EnableCpuMemArena=0\n"
                    + "SessionConfig.session.use_device_allocator_for_initializers=1\n";
            try (FileOutputStream fos = new FileOutputStream(cfg)) {
                fos.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (cfg.canRead() && cfg.length() > 0) {
                return "cpu:" + cfg.getAbsolutePath();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return "cpu";
    }

    static boolean ensureLoaded() {
        long stamp = bundleStamp();
        if (recognizer != null && stamp == loadedStamp) {
            return true;
        }
        release();
        if (!isSupported() || !MgParakeetModel.isInstalled()) {
            return false;
        }
        try {
            OfflineTransducerModelConfig t = new OfflineTransducerModelConfig();
            t.setEncoder(MgParakeetModel.file(MgParakeetSpec.ENCODER).getAbsolutePath());
            t.setDecoder(MgParakeetModel.file(MgParakeetSpec.DECODER).getAbsolutePath());
            t.setJoiner(MgParakeetModel.file(MgParakeetSpec.JOINER).getAbsolutePath());

            OfflineModelConfig mc = new OfflineModelConfig();
            mc.setTransducer(t);
            mc.setTokens(MgParakeetModel.file(MgParakeetSpec.TOKENS).getAbsolutePath());
            mc.setModelType(MgParakeetSpec.MODEL_TYPE);
            mc.setNumThreads(MgParakeetSpec.NUM_THREADS);
            mc.setProvider(providerWithConfig());
            mc.setDebug(false);

            OfflineRecognizerConfig rc = new OfflineRecognizerConfig();
            rc.setModelConfig(mc);
            rc.setDecodingMethod("greedy_search");

            // Loads from file paths (newFromFile): onnxruntime reads the .onnx
            // itself, no extra full-size Java/native copy of the 650 MB encoder.
            recognizer = new OfflineRecognizer(null, rc);
            loadedStamp = stamp;
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            release();
            return false;
        }
    }

    private static Vad ensureVad() {
        if (vad != null) {
            vad.reset();
            return vad;
        }
        File f = MgParakeetModel.file(MgParakeetSpec.VAD);
        if (!MgParakeetSpec.fileLooksComplete(MgParakeetSpec.VAD, f.exists(), f.length())) {
            return null;
        }
        try {
            SileroVadModelConfig s = new SileroVadModelConfig();
            s.setModel(f.getAbsolutePath());
            s.setThreshold(0.5f);
            s.setMinSilenceDuration(0.5f);
            s.setMinSpeechDuration(0.25f);
            s.setWindowSize(VAD_WINDOW);
            s.setMaxSpeechDuration(20f);
            VadModelConfig vc = new VadModelConfig();
            vc.setSileroVadModelConfig(s);
            vc.setSampleRate(SAMPLE_RATE);
            vc.setNumThreads(1);
            vc.setProvider("cpu");
            vad = new Vad(null, vc);
            return vad;
        } catch (Throwable e) {
            FileLog.e(e);
            vad = null;
            return null;
        }
    }

    static boolean isLoaded() {
        return recognizer != null;
    }

    /** Frees the recognizer and VAD. Worker thread only. Safe when nothing is loaded. */
    static void release() {
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Throwable t) {
                FileLog.e(t);
            }
            recognizer = null;
            loadedStamp = 0;
        }
        if (vad != null) {
            try {
                vad.release();
            } catch (Throwable t) {
                FileLog.e(t);
            }
            vad = null;
        }
    }

    private static String decodeSegment(float[] samples, int from, int to) {
        int n = to - from;
        if (n <= 0) {
            return "";
        }
        float[] buf = new float[n + TAIL_PAD];
        System.arraycopy(samples, from, buf, 0, n);
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(buf, SAMPLE_RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult r = recognizer.getResult(stream);
            return r != null && r.getText() != null ? r.getText().trim() : "";
        } finally {
            stream.release();
        }
    }

    /**
     * Transcribes 16 kHz mono PCM. Returns the text (possibly empty when VAD
     * found no speech) or {@code null} when the engine can't run.
     */
    static String transcribe(float[] pcm, boolean useVad, MgWhisperNative.SegmentCallback partial) {
        if (!ensureLoaded()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        Vad v = useVad ? ensureVad() : null;
        if (v != null) {
            float[] window = new float[VAD_WINDOW];
            for (int off = 0; off < pcm.length; off += VAD_WINDOW) {
                int n = Math.min(VAD_WINDOW, pcm.length - off);
                if (n == VAD_WINDOW) {
                    System.arraycopy(pcm, off, window, 0, VAD_WINDOW);
                    v.acceptWaveform(window);
                } else {
                    float[] last = new float[VAD_WINDOW]; // zero-padded final window
                    System.arraycopy(pcm, off, last, 0, n);
                    v.acceptWaveform(last);
                }
                drain(v, out, partial);
            }
            v.flush();
            drain(v, out, partial);
            v.reset();
        } else {
            int[] chunks = MgParakeetSpec.fixedChunks(pcm.length, FIXED_CHUNK);
            for (int i = 0; i < chunks.length; i += 2) {
                String text = decodeSegment(pcm, chunks[i], chunks[i + 1]);
                append(out, text, partial);
            }
        }
        return out.toString();
    }

    private static void drain(Vad v, StringBuilder out, MgWhisperNative.SegmentCallback partial) {
        while (!v.empty()) {
            SpeechSegment seg = v.front();
            float[] s = seg.getSamples();
            v.pop();
            if (s != null && s.length > 0) {
                append(out, decodeSegment(s, 0, s.length), partial);
            }
        }
    }

    private static void append(StringBuilder out, String text, MgWhisperNative.SegmentCallback partial) {
        String joined = MgParakeetSpec.joinSegments(out, text);
        if (joined.length() != out.length()) {
            out.setLength(0);
            out.append(joined);
            if (partial != null) {
                partial.onSegment(joined);
            }
        }
    }
}
