package it.belloworld.mercurygram.transcribe;

import java.util.Locale;

/**
 * [Hanako] Pure (Android-free) description of the NVIDIA Parakeet TDT 0.6B v3
 * engine: the pinned model bundle, the languages it speaks, and the rule that
 * decides which engine actually transcribes a message. Kept free of Android
 * and native dependencies so it can be unit-tested on its own.
 *
 * Model: sherpa-onnx export of nvidia/parakeet-tdt-0.6b-v3 (CC-BY-4.0), int8,
 * from huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8,
 * pinned to an exact repository revision so the pinned SHA-256s can't drift.
 * The Silero VAD is the ONNX build sherpa-onnx ships (the whisper.cpp engine
 * uses its own ggml VAD, which sherpa can't read).
 */
public final class MgParakeetSpec {

    /** Stable model id, stored in {@code SharedConfig.mg_transcribeModel}. */
    public static final String MODEL_ID = "parakeet-tdt-0.6b-v3-int8";

    /** Directory (under the app's files dir) holding the bundle. */
    public static final String DIR_NAME = "parakeet-tdt-0.6b-v3-int8";

    /** sherpa-onnx model type for NeMo TDT transducers. */
    public static final String MODEL_TYPE = "nemo_transducer";

    /** Inference threads: Tensor G3 has 1×X3 + 4×A715 big/mid cores. */
    public static final int NUM_THREADS = 4;

    /** Free the ~0.7 GB model after this long without a transcription. */
    public static final long IDLE_RELEASE_MS = 120_000L;

    public static final String HF_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78";
    private static final String HF_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/"
            + HF_REVISION + "/";

    /** One file of the bundle, with its pinned size and SHA-256. */
    public static final class FileSpec {
        public final String name;
        public final String url;
        public final long size;
        public final String sha256;

        FileSpec(String name, String url, long size, String sha256) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public static final FileSpec ENCODER = new FileSpec("encoder.int8.onnx", HF_BASE + "encoder.int8.onnx",
            652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247");
    public static final FileSpec DECODER = new FileSpec("decoder.int8.onnx", HF_BASE + "decoder.int8.onnx",
            11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e");
    public static final FileSpec JOINER = new FileSpec("joiner.int8.onnx", HF_BASE + "joiner.int8.onnx",
            6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3");
    public static final FileSpec TOKENS = new FileSpec("tokens.txt", HF_BASE + "tokens.txt",
            93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d");
    public static final FileSpec VAD = new FileSpec("silero_vad.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            643_854L, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6");

    /** Download order: small files first so a broken network fails fast, the big encoder last. */
    public static final FileSpec[] FILES = {TOKENS, VAD, JOINER, DECODER, ENCODER};

    /** The 25 languages Parakeet TDT 0.6B v3 transcribes (ISO-639-1). */
    public static final String[] LANGUAGES = {
            "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de",
            "el", "hu", "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk",
            "sl", "es", "sv", "ru", "uk",
    };

    private MgParakeetSpec() {
    }

    /** Sum of all pinned file sizes (~671 MB). */
    public static long totalSize() {
        long t = 0;
        for (FileSpec f : FILES) {
            t += f.size;
        }
        return t;
    }

    /** True when {@code code} is one of the 25 languages (case-insensitive; region suffixes ignored). */
    public static boolean isLanguageSupported(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        String c = code.toLowerCase(Locale.ROOT);
        int dash = c.indexOf('-');
        if (dash < 0) {
            dash = c.indexOf('_');
        }
        if (dash > 0) {
            c = c.substring(0, dash);
        }
        for (String l : LANGUAGES) {
            if (l.equals(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a file on disk matches its spec closely enough to count as
     * installed. The SHA-256 is verified once while downloading; afterwards the
     * exact pinned size is the cheap check (no 650 MB re-hash per cell bind).
     */
    public static boolean fileLooksComplete(FileSpec spec, boolean exists, long length) {
        return exists && length == spec.size;
    }

    /** Result of {@link #route}. */
    public enum Route {
        /** Transcribe with Parakeet. */
        PARAKEET,
        /** Transcribe with the whisper.cpp model named by the caller's fallback. */
        WHISPER,
        /** Parakeet can't speak this language and no whisper model is installed. */
        UNSUPPORTED_LANGUAGE,
    }

    /**
     * Picks the engine for one transcription.
     *
     * @param parakeetSelected   the user picked Parakeet as the model
     * @param parakeetSupported  this device can run the sherpa-onnx engine
     * @param effectiveLang      the resolved spoken-language hint ({@code "auto"} or an ISO code)
     * @param whisperAvailable   at least one whisper.cpp model is installed and runnable
     */
    public static Route route(boolean parakeetSelected, boolean parakeetSupported,
                              String effectiveLang, boolean whisperAvailable) {
        if (!parakeetSelected) {
            return Route.WHISPER;
        }
        if (!parakeetSupported) {
            return whisperAvailable ? Route.WHISPER : Route.UNSUPPORTED_LANGUAGE;
        }
        if (effectiveLang == null || effectiveLang.isEmpty() || "auto".equals(effectiveLang)
                || isLanguageSupported(effectiveLang)) {
            return Route.PARAKEET;
        }
        return whisperAvailable ? Route.WHISPER : Route.UNSUPPORTED_LANGUAGE;
    }

    /**
     * Fixed-size chunk boundaries for when VAD is off: {@code [start0, end0, start1, end1, ...]}
     * in samples. Parakeet's encoder attention grows with input length, so long
     * messages are cut into {@code chunk}-sample pieces instead of one pass.
     */
    public static int[] fixedChunks(int totalSamples, int chunk) {
        if (totalSamples <= 0 || chunk <= 0) {
            return new int[0];
        }
        int n = (totalSamples + chunk - 1) / chunk;
        int[] out = new int[n * 2];
        for (int i = 0; i < n; i++) {
            out[2 * i] = i * chunk;
            out[2 * i + 1] = Math.min(totalSamples, (i + 1) * chunk);
        }
        return out;
    }

    /** Joins per-segment texts with single spaces, dropping empty pieces. */
    public static String joinSegments(CharSequence soFar, String next) {
        String n = next == null ? "" : next.trim();
        if (n.isEmpty()) {
            return soFar == null ? "" : soFar.toString();
        }
        if (soFar == null || soFar.length() == 0) {
            return n;
        }
        return soFar + " " + n;
    }
}
