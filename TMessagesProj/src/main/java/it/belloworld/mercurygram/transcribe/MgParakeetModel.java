package it.belloworld.mercurygram.transcribe;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [Hanako] Storage + download of the Parakeet TDT 0.6B v3 bundle
 * (see {@link MgParakeetSpec}). Mirrors {@link MgWhisperModel}'s UX contract
 * (same {@link MgWhisperModel.ProgressCallback}, callbacks on the UI thread,
 * cancel via generation counter) but the bundle is five files and ~670 MB, so:
 * <ul>
 *   <li>each file streams into {@code <name>.part}, SHA-256 verified inline,
 *       then renamed into place;</li>
 *   <li>a {@code .part} left by a cancel / network error is KEPT and resumed
 *       with an HTTP Range request (its bytes are re-hashed first so the
 *       final checksum still covers the whole file);</li>
 *   <li>free space is checked up front.</li>
 * </ul>
 * Never bundled in the APK.
 */
public final class MgParakeetModel {

    private static final AtomicBoolean isDownloading = new AtomicBoolean(false);
    private static volatile HttpURLConnection currentConn;
    private static volatile int downloadGeneration;
    // Bumped on download / delete so MgWhisperModel's isSelectedInstalled() cache misses.
    static volatile int installEpoch;

    private MgParakeetModel() {
    }

    public static File dir() {
        File d = new File(ApplicationLoader.getFilesDirFixed(), MgParakeetSpec.DIR_NAME);
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    public static File file(MgParakeetSpec.FileSpec spec) {
        return new File(dir(), spec.name);
    }

    /** All five files present with their exact pinned sizes. */
    public static boolean isInstalled() {
        for (MgParakeetSpec.FileSpec f : MgParakeetSpec.FILES) {
            File file = file(f);
            if (!MgParakeetSpec.fileLooksComplete(f, file.exists(), file.length())) {
                return false;
            }
        }
        return true;
    }

    /** Bytes already on disk (finished files + resumable parts), for the progress row. */
    public static long bytesOnDisk() {
        long done = 0;
        for (MgParakeetSpec.FileSpec f : MgParakeetSpec.FILES) {
            File file = file(f);
            if (MgParakeetSpec.fileLooksComplete(f, file.exists(), file.length())) {
                done += f.size;
            } else {
                File part = new File(file.getAbsolutePath() + ".part");
                if (part.exists()) {
                    done += Math.min(part.length(), f.size);
                }
            }
        }
        return done;
    }

    public static boolean isDownloading() {
        return isDownloading.get();
    }

    public static void cancelDownload() {
        downloadGeneration++;
        isDownloading.set(false);
        HttpURLConnection c = currentConn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    /** Deletes the whole bundle (finished files and partial downloads) and frees the engine. */
    public static void delete() {
        cancelDownload();
        File d = dir();
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        installEpoch++;
        MgWhisperTranscriber.releaseEngine();
    }

    public static void download(MgWhisperModel.ProgressCallback callback) {
        if (!isDownloading.compareAndSet(false, true)) {
            AndroidUtilities.runOnUIThread(() -> callback.onError("Already downloading"));
            return;
        }
        final int generation = downloadGeneration;
        Utilities.globalQueue.postRunnable(() -> {
            final long total = MgParakeetSpec.totalSize();
            try {
                long need = total - bytesOnDisk();
                long free = dir().getUsableSpace();
                if (free > 0 && free < need + 50L * 1024 * 1024) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Not enough storage"));
                    return;
                }
                long base = 0; // bytes of files already finished before the current one
                for (MgParakeetSpec.FileSpec spec : MgParakeetSpec.FILES) {
                    File target = file(spec);
                    if (MgParakeetSpec.fileLooksComplete(spec, target.exists(), target.length())) {
                        base += spec.size;
                        continue;
                    }
                    fetchOne(spec, target, base, total, generation, callback);
                    base += spec.size;
                }
                installEpoch++;
                final File d = dir();
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(d));
            } catch (Exception e) {
                boolean cancelled = !isDownloading.get() || generation != downloadGeneration;
                if (!cancelled) {
                    FileLog.e(e);
                }
                final String msg = cancelled ? "cancelled" : e.getMessage();
                AndroidUtilities.runOnUIThread(() -> callback.onError(msg));
            } finally {
                isDownloading.set(false);
                currentConn = null;
            }
        });
    }

    private static void fetchOne(MgParakeetSpec.FileSpec spec, File target, long base, long total,
                                 int generation, MgWhisperModel.ProgressCallback callback) throws Exception {
        File tmp = new File(target.getAbsolutePath() + ".part");
        if (tmp.exists() && tmp.length() > spec.size) {
            tmp.delete();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long have = tmp.exists() ? tmp.length() : 0;
        if (have > 0) {
            // Re-hash what we already have so the final digest covers the whole file.
            try (InputStream is = new BufferedInputStream(new FileInputStream(tmp))) {
                byte[] buf = new byte[1 << 16];
                int len;
                while ((len = is.read(buf)) != -1) {
                    if (!isDownloading.get() || generation != downloadGeneration) {
                        throw new IOException("cancelled");
                    }
                    digest.update(buf, 0, len);
                }
            }
        }
        boolean append = false;
        HttpURLConnection conn = null;
        try {
            if (have < spec.size) {
                conn = (HttpURLConnection) new URL(spec.url).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                if (have > 0) {
                    conn.setRequestProperty("Range", "bytes=" + have + "-");
                }
                currentConn = conn;
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_PARTIAL && have > 0) {
                    append = true;
                } else if (code == HttpURLConnection.HTTP_OK) {
                    // Server ignored the range: start this file over.
                    if (have > 0) {
                        digest.reset();
                        have = 0;
                    }
                } else if (code == 416 && have > 0) {
                    // Range not satisfiable: our part is bogus. Start over next time.
                    tmp.delete();
                    throw new IOException("HTTP 416");
                } else {
                    throw new IOException("HTTP " + code);
                }
                try (InputStream is = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(tmp, append)) {
                    byte[] buf = new byte[1 << 16];
                    long done = have;
                    int len;
                    int lastPermille = -1;
                    while ((len = is.read(buf)) != -1) {
                        if (!isDownloading.get() || generation != downloadGeneration) {
                            throw new IOException("cancelled");
                        }
                        fos.write(buf, 0, len);
                        digest.update(buf, 0, len);
                        done += len;
                        if (done > spec.size) {
                            throw new IOException("file larger than expected");
                        }
                        long overall = base + done;
                        int permille = (int) (overall * 1000 / total);
                        if (permille != lastPermille) {
                            lastPermille = permille;
                            AndroidUtilities.runOnUIThread(() -> callback.onProgress(overall, total));
                        }
                    }
                }
            }
        } finally {
            currentConn = null;
            if (conn != null) {
                conn.disconnect();
            }
        }
        if (tmp.length() != spec.size || !hex(digest.digest()).equalsIgnoreCase(spec.sha256)) {
            tmp.delete();
            throw new IOException("Checksum mismatch: " + spec.name);
        }
        if (target.exists()) {
            target.delete();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Could not save " + spec.name);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format(Locale.US, "%02x", x));
        }
        return sb.toString();
    }
}
