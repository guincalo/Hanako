package it.belloworld.mercurygram.transcribe

import it.belloworld.mercurygram.transcribe.MgParakeetSpec.Route
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Hanako] Parakeet TDT 0.6B v3 bundle spec, language set, engine routing and
 * chunking. Pure logic (MgParakeetSpec has no Android / native deps), so no
 * model file or sherpa-onnx lib is needed.
 */
class MgParakeetSpecTest {

    @Test
    fun bundleIsPinned() {
        assertEquals(5, MgParakeetSpec.FILES.size)
        assertEquals(671_122_626L, MgParakeetSpec.totalSize())
        val names = MgParakeetSpec.FILES.map { it.name }.toSet()
        assertEquals(
            setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "silero_vad.onnx"),
            names
        )
        for (f in MgParakeetSpec.FILES) {
            assertEquals(64, f.sha256.length)
            assertTrue(f.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(f.url.startsWith("https://"))
            assertTrue(f.size > 0)
        }
        // HF files come from one pinned revision, never "main".
        for (f in listOf(MgParakeetSpec.ENCODER, MgParakeetSpec.DECODER, MgParakeetSpec.JOINER, MgParakeetSpec.TOKENS)) {
            assertTrue(f.url.contains("/resolve/" + MgParakeetSpec.HF_REVISION + "/"))
        }
        // Encoder downloads last so small files fail fast on a bad network.
        assertSame(MgParakeetSpec.ENCODER, MgParakeetSpec.FILES.last())
    }

    @Test
    fun fileValidationUsesExactSize() {
        val enc = MgParakeetSpec.ENCODER
        assertTrue(MgParakeetSpec.fileLooksComplete(enc, true, enc.size))
        assertFalse(MgParakeetSpec.fileLooksComplete(enc, true, enc.size - 1))
        assertFalse(MgParakeetSpec.fileLooksComplete(enc, true, enc.size + 1))
        assertFalse(MgParakeetSpec.fileLooksComplete(enc, false, enc.size))
    }

    @Test
    fun languages() {
        assertEquals(25, MgParakeetSpec.LANGUAGES.size)
        for (code in listOf("ru", "uk", "en", "de", "fr", "es", "it", "pl", "EN", "ru-RU", "pt_BR")) {
            assertTrue(code, MgParakeetSpec.isLanguageSupported(code))
        }
        for (code in listOf("ja", "zh", "ar", "tr", "kk", "", null)) {
            assertFalse(code.toString(), MgParakeetSpec.isLanguageSupported(code))
        }
    }

    @Test
    fun routing() {
        // Whisper selected: always whisper.
        assertSame(Route.WHISPER, MgParakeetSpec.route(false, true, "ja", false))
        // Parakeet: auto-detect and its 25 languages stay on Parakeet.
        assertSame(Route.PARAKEET, MgParakeetSpec.route(true, true, "auto", false))
        assertSame(Route.PARAKEET, MgParakeetSpec.route(true, true, "", false))
        assertSame(Route.PARAKEET, MgParakeetSpec.route(true, true, "ru", false))
        // Other languages fall back to whisper, or fail clearly without one.
        assertSame(Route.WHISPER, MgParakeetSpec.route(true, true, "ja", true))
        assertSame(Route.UNSUPPORTED_LANGUAGE, MgParakeetSpec.route(true, true, "ja", false))
        // Device without the sherpa-onnx lib.
        assertSame(Route.WHISPER, MgParakeetSpec.route(true, false, "ru", true))
    }

    @Test
    fun fixedChunks() {
        val sr = 16000
        assertArrayEquals(intArrayOf(0, 20 * sr, 20 * sr, 40 * sr, 40 * sr, 45 * sr),
            MgParakeetSpec.fixedChunks(45 * sr, 20 * sr))
        assertArrayEquals(intArrayOf(0, 5), MgParakeetSpec.fixedChunks(5, 20 * sr))
        assertEquals(0, MgParakeetSpec.fixedChunks(0, 20 * sr).size)
    }

    @Test
    fun joinSegments() {
        assertEquals("Hi", MgParakeetSpec.joinSegments("", "  Hi "))
        assertEquals("Hi there", MgParakeetSpec.joinSegments("Hi", "there"))
        assertEquals("Hi", MgParakeetSpec.joinSegments("Hi", "   "))
        assertEquals("", MgParakeetSpec.joinSegments(null, null))
    }

    @Test
    fun parakeetIsFirstAndAWhisperModelEntry() {
        assertSame(MgWhisperModel.Model.PARAKEET, MgWhisperModel.Model.values()[0])
        assertSame(MgWhisperModel.Model.PARAKEET, MgWhisperModel.Model.byId(MgParakeetSpec.MODEL_ID))
        assertTrue(MgWhisperModel.Model.PARAKEET.isParakeet())
        assertEquals(MgWhisperModel.Engine.PARAKEET, MgWhisperModel.Model.PARAKEET.engine)
        for (m in MgWhisperModel.Model.WHISPER_BY_QUALITY) {
            assertEquals(MgWhisperModel.Engine.WHISPER, m.engine)
        }
        assertEquals(MgParakeetSpec.totalSize(), MgWhisperModel.Model.PARAKEET.approxSize)
    }
}
