package it.belloworld.mercurygram;

import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Mercurygram saved-history media: copies the cached file of a message that is
 * being archived (deleted, or replaced by an edit, or a self-destructing media
 * that expires) into app-private storage ({@code files/mg_history_media}), so it
 * survives a Telegram cache clear and keep-media cleanup.
 *
 * <p>Destination paths are decided up front ({@link #plan}) on the storage
 * queue from the message alone (no disk access) and stored in the history DB;
 * the copy itself runs later on a dedicated serial queue ({@link #copyAsync}).
 * File deletions for trimmed / cleared entries go through the same queue, so a
 * delete never overtakes the copy of the same file.</p>
 *
 * <p>When an entry is read back, {@link #apply} points the message's media at
 * the saved copy: photo sizes / document thumbs get a {@link MgFileLocation},
 * documents get {@code localPath} and {@code attachPath}. {@link #redirect} is
 * the {@code FileLoader.getPathToAttach} hook that resolves those.</p>
 */
public final class MgHistoryMedia {

    public static final String DIR_NAME = "mg_history_media";

    private static volatile File dir;
    private static volatile String dirPrefix;
    private static volatile DispatchQueue queue;

    private MgHistoryMedia() {
    }

    /** One planned copy: the message as archived plus its destination path(s). */
    public static final class Plan {
        public final int account;
        public final TLRPC.Message message;
        public final String mediaPath;
        public final String thumbPath;

        Plan(int account, TLRPC.Message message, String mediaPath, String thumbPath) {
            this.account = account;
            this.message = message;
            this.mediaPath = mediaPath;
            this.thumbPath = thumbPath;
        }
    }

    public static File getDir() {
        File d = dir;
        if (d == null) {
            d = new File(ApplicationLoader.getFilesDirFixed(), DIR_NAME);
            dirPrefix = d.getAbsolutePath() + File.separator;
            dir = d;
        }
        return d;
    }

    private static String getDirPrefix() {
        getDir();
        return dirPrefix;
    }

    private static DispatchQueue getQueue() {
        DispatchQueue q = queue;
        if (q == null) {
            synchronized (MgHistoryMedia.class) {
                q = queue;
                if (q == null) {
                    q = queue = new DispatchQueue("mgHistoryMedia");
                }
            }
        }
        return q;
    }

    private static TLRPC.MessageMedia mediaOf(TLRPC.Message m) {
        if (m == null || m.media == null) {
            return null;
        }
        return MessageObject.getMedia(m);
    }

    private static TLRPC.Photo photoOf(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null
                && !(media.photo instanceof TLRPC.TL_photoEmpty) && !media.photo.sizes.isEmpty()) {
            return media.photo;
        }
        return null;
    }

    private static TLRPC.Document documentOf(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null
                && !(media.document instanceof TLRPC.TL_documentEmpty)) {
            return media.document;
        }
        return null;
    }

    /** Photo, video, voice, round video, GIF, sticker or file media that can be copied. */
    public static boolean hasSavableMedia(TLRPC.Message m) {
        TLRPC.MessageMedia media = mediaOf(m);
        return photoOf(media) != null || documentOf(media) != null;
    }

    /** True when both messages carry the same photo / document (or neither has one). */
    public static boolean sameMedia(TLRPC.Message a, TLRPC.Message b) {
        TLRPC.MessageMedia ma = mediaOf(a);
        TLRPC.MessageMedia mb = mediaOf(b);
        TLRPC.Photo pa = photoOf(ma);
        TLRPC.Photo pb = photoOf(mb);
        if (pa != null || pb != null) {
            return pa != null && pb != null && pa.id == pb.id;
        }
        TLRPC.Document da = documentOf(ma);
        TLRPC.Document db = documentOf(mb);
        if (da != null || db != null) {
            return da != null && db != null && da.id == db.id;
        }
        return true;
    }

    private static boolean isFileSize(TLRPC.PhotoSize s) {
        return s != null && s.location != null
                && !(s instanceof TLRPC.TL_photoStrippedSize)
                && !(s instanceof TLRPC.TL_photoPathSize);
    }

    /** Largest real (downloadable) thumbnail of a document, or null. */
    private static TLRPC.PhotoSize pickThumb(TLRPC.Document document) {
        if (document == null || document.thumbs == null) {
            return null;
        }
        TLRPC.PhotoSize best = null;
        for (int i = 0; i < document.thumbs.size(); i++) {
            TLRPC.PhotoSize s = document.thumbs.get(i);
            if (!isFileSize(s) || s instanceof TLRPC.TL_photoCachedSize) {
                continue;
            }
            if (best == null || s.w * s.h > best.w * best.h) {
                best = s;
            }
        }
        return best;
    }

    private static String extOf(TLRPC.Document document) {
        String name = FileLoader.getAttachFileName(document);
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 8) {
            return "";
        }
        return name.substring(dot).replace(File.separatorChar, '_');
    }

    /**
     * Decide where the media of {@code m} will be saved. No disk access, safe on
     * the storage queue. Returns null when the message has nothing to copy.
     */
    public static Plan plan(int account, long dialogId, TLRPC.Message m, String tag) {
        TLRPC.MessageMedia media = mediaOf(m);
        TLRPC.Photo photo = photoOf(media);
        TLRPC.Document document = documentOf(media);
        if (photo == null && document == null) {
            return null;
        }
        String base = "a" + account + "_" + dialogId + "_" + m.id + "_" + tag;
        File d = getDir();
        String mediaPath;
        String thumbPath = null;
        if (document != null) {
            mediaPath = new File(d, base + extOf(document)).getAbsolutePath();
            if (pickThumb(document) != null) {
                thumbPath = new File(d, base + "_thumb.jpg").getAbsolutePath();
            }
        } else {
            mediaPath = new File(d, base + ".jpg").getAbsolutePath();
        }
        return new Plan(account, m, mediaPath, thumbPath);
    }

    public static void copyAsync(Collection<Plan> plans) {
        if (plans == null || plans.isEmpty()) {
            return;
        }
        final ArrayList<Plan> copy = new ArrayList<>(plans);
        getQueue().postRunnable(() -> {
            for (int i = 0; i < copy.size(); i++) {
                runCopy(copy.get(i));
            }
        });
    }

    /** Copies the planned file(s) on the calling thread. */
    public static void runCopy(Plan p) {
        if (p == null) {
            return;
        }
        try {
            TLRPC.MessageMedia media = mediaOf(p.message);
            TLRPC.Photo photo = photoOf(media);
            TLRPC.Document document = documentOf(media);
            FileLoader fl = FileLoader.getInstance(p.account);
            ArrayList<File> candidates = new ArrayList<>();
            candidates.add(fl.getPathToMessage(p.message));
            candidates.add(fl.getPathToMessage(p.message, true, true));
            if (photo != null) {
                for (int i = photo.sizes.size() - 1; i >= 0; i--) {
                    TLRPC.PhotoSize s = photo.sizes.get(i);
                    if (isFileSize(s)) {
                        candidates.add(fl.getPathToAttach(s, null, false, true));
                        candidates.add(fl.getPathToAttach(s, null, true, true));
                    }
                }
            } else if (document != null) {
                candidates.add(fl.getPathToAttach(document, null, false, true));
                candidates.add(fl.getPathToAttach(document, null, true, true));
            }
            if (p.mediaPath != null) {
                copyFirst(candidates, new File(p.mediaPath));
            }
            TLRPC.PhotoSize thumb = pickThumb(document);
            if (p.thumbPath != null && thumb != null) {
                ArrayList<File> thumbCandidates = new ArrayList<>();
                thumbCandidates.add(fl.getPathToAttach(thumb, null, true, true));
                thumbCandidates.add(fl.getPathToAttach(thumb, null, false, true));
                copyFirst(thumbCandidates, new File(p.thumbPath));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean copyFirst(ArrayList<File> candidates, File dest) {
        if (dest.exists()) {
            return true;
        }
        for (int i = 0; i < candidates.size(); i++) {
            File src = candidates.get(i);
            if (src == null || src.getPath().length() == 0) {
                continue;
            }
            if (src.isFile() && src.length() > 0) {
                if (copyStream(src, null, dest)) {
                    return true;
                }
                continue;
            }
            // Self-destructing media is cached encrypted: <name>.enc + <name>.enc.key.
            File enc = new File(src.getAbsolutePath() + ".enc");
            File key = new File(FileLoader.getInternalCacheDir(), enc.getName() + ".key");
            if (enc.isFile() && key.isFile()) {
                if (copyStream(enc, key, dest)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean copyStream(File src, File keyFile, File dest) {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        InputStream in = null;
        FileOutputStream out = null;
        boolean ok = false;
        try {
            in = keyFile != null ? new EncryptedFileInputStream(src, keyFile) : new FileInputStream(src);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf, 0, buf.length)) > 0) {
                out.write(buf, 0, read);
            }
            out.getFD().sync();
            ok = true;
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignore) {
            }
        }
        if (ok) {
            ok = tmp.renameTo(dest);
        }
        if (!ok) {
            tmp.delete();
        }
        return ok;
    }

    /** Deletes saved files (paths from trimmed / forgotten entries) after any pending copy. */
    public static void deleteAsync(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        final ArrayList<String> copy = new ArrayList<>(paths);
        getQueue().postRunnable(() -> {
            String prefix = getDirPrefix();
            for (int i = 0; i < copy.size(); i++) {
                String path = copy.get(i);
                // Never touch anything outside our own directory.
                if (path == null || !path.startsWith(prefix)) {
                    continue;
                }
                try {
                    new File(path).delete();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    /** Deletes every saved file (Clear saved history). */
    public static void deleteAllAsync() {
        getQueue().postRunnable(() -> {
            try {
                File[] files = getDir().listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static boolean exists(String path) {
        return !TextUtils.isEmpty(path) && new File(path).isFile();
    }

    /**
     * Points a message rebuilt from the history DB at its saved media copies,
     * and strips self-destruct state so the kept copy shows like a normal
     * message. Call off the UI thread (checks the files exist).
     */
    public static void apply(TLRPC.Message m, String mediaPath, String thumbPath) {
        if (m == null) {
            return;
        }
        m.ttl = 0;
        m.destroyTime = 0;
        m.destroyTimeMillis = 0;
        TLRPC.MessageMedia media = mediaOf(m);
        if (media == null) {
            return;
        }
        TLRPC.Photo photo = photoOf(media);
        TLRPC.Document document = documentOf(media);
        if (exists(mediaPath)) {
            if (photo != null) {
                for (int i = 0; i < photo.sizes.size(); i++) {
                    TLRPC.PhotoSize s = photo.sizes.get(i);
                    if (isFileSize(s) && !(s.location instanceof MgFileLocation)) {
                        s.location = new MgFileLocation(s.location, mediaPath);
                    }
                }
            } else if (document != null) {
                document.localPath = mediaPath;
                m.attachPath = mediaPath;
            }
            // A saved view-once / timed media is shown as a normal one.
            media.ttl_seconds = 0;
        }
        if (document != null && exists(thumbPath)) {
            for (int i = 0; i < document.thumbs.size(); i++) {
                TLRPC.PhotoSize s = document.thumbs.get(i);
                if (isFileSize(s) && !(s instanceof TLRPC.TL_photoCachedSize) && !(s.location instanceof MgFileLocation)) {
                    s.location = new MgFileLocation(s.location, thumbPath);
                }
            }
        }
    }

    /**
     * FileLoader.getPathToAttach hook: resolves objects that {@link #apply}
     * pointed at a saved copy. Returns null (fall through to the normal path)
     * for everything else, or when the saved copy is gone.
     */
    public static File redirect(TLObject attach) {
        String path = null;
        if (attach instanceof TLRPC.PhotoSize) {
            TLRPC.FileLocation loc = ((TLRPC.PhotoSize) attach).location;
            if (loc instanceof MgFileLocation) {
                path = ((MgFileLocation) loc).path;
            }
        } else if (attach instanceof MgFileLocation) {
            path = ((MgFileLocation) attach).path;
        } else if (attach instanceof TLRPC.Document) {
            String lp = ((TLRPC.Document) attach).localPath;
            if (lp != null && lp.startsWith(getDirPrefix())) {
                path = lp;
            }
        } else if (attach instanceof TLRPC.Photo) {
            ArrayList<TLRPC.PhotoSize> sizes = ((TLRPC.Photo) attach).sizes;
            if (sizes != null) {
                for (int i = 0; i < sizes.size(); i++) {
                    TLRPC.PhotoSize s = sizes.get(i);
                    if (s != null && s.location instanceof MgFileLocation) {
                        path = ((MgFileLocation) s.location).path;
                        break;
                    }
                }
            }
        }
        if (path == null) {
            return null;
        }
        File f = new File(path);
        return f.exists() ? f : null;
    }
}
