package org.telegram.messenger;

public class LanguageDetector {
    public interface StringCallback {
        void run(String str);
    }
    public interface ExceptionCallback {
        void run(Exception e);
    }

    // Hanako: MLKit stays removed; detection is backed by the pure-Java
    // OfflineLanguageDetector (ported from Forkgram, GPL-2.0).
    public static boolean hasSupport() {
        return true;
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail) {
        detectLanguage(text, onSuccess, onFail, false);
    }

    // Hanako: callbacks run synchronously on the calling thread (the detector is
    // cheap, no model to load). TranslateController.detectStoryLanguage /
    // detectPhotoLanguage and others rely on a synchronous callback.
    // When the detector is not confident this reports UNKNOWN_LANGUAGE ("und")
    // through onSuccess, exactly like MLKit's identifyLanguage did, so every
    // caller keeps Translate visible for unknown text instead of hiding it.
    // onFail is only called if detection itself throws.
    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail, boolean initializeFirst) {
        final String detected;
        try {
            String sample = text;
            if (sample != null && sample.length() > MAX_SAMPLE) {
                sample = sample.substring(0, MAX_SAMPLE);
            }
            detected = OfflineLanguageDetector.detect(sample);
        } catch (Exception e) {
            if (onFail != null) {
                onFail.run(e);
            }
            return;
        }
        if (onSuccess != null) {
            onSuccess.run(detected != null ? detected : TranslateController.UNKNOWN_LANGUAGE);
        }
    }

    private static final int MAX_SAMPLE = 2000;
}
