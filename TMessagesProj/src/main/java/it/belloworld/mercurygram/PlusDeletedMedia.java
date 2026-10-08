package it.belloworld.mercurygram;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.webkit.MimeTypeMap;

import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * plus f20: browser for the media kept by the 0007 anti-recall store
 * ({@link MgMessageHistory} rows + {@link MgHistoryMedia} copies).
 *
 * <p>Reads only the local history DB and the app-private copies; never touches
 * the network. Viewer objects handed to PhotoViewer are registered here so the
 * viewer hides every action that would talk to the server (delete, forward,
 * reply, show in chat).</p>
 */
public final class PlusDeletedMedia {

    /** ChatActivity header-menu id (f20 range 2000-2099). */
    public static final int MENU_ID = 2050;

    public static final int CAT_PHOTO = 1;
    public static final int CAT_VIDEO = 2;
    public static final int CAT_FILE = 3;

    /** How the saved copy came to be (from the MgHistoryMedia file tag). */
    public static final int ORIGIN_DELETED = 0;
    public static final int ORIGIN_VIEW_ONCE = 1;
    public static final int ORIGIN_EDITED = 2;

    private static final String PREFS = "plus_f20";
    private static final String KEY_CHAT_MENU = "chat_menu";
    private static final String KEY_INCLUDE_EDITS = "include_edits";
    private static final int LOAD_LIMIT = 3000;
    private static final long SHARE_TTL_MS = 60 * 60 * 1000L;

    // a<account>_<dialog>_<mid>_<tag><ms> as written by MgHistoryMedia.plan
    private static final Pattern TAG = Pattern.compile("^a\\d+_-?\\d+_-?\\d+_([dex])\\d+");

    private static final Set<MessageObject> viewerObjects =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final LruCache<String, Bitmap> videoThumbs = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private PlusDeletedMedia() {
    }

    /** One saved media copy. */
    public static final class Item {
        public final int account;
        public final MgMessageHistory.Entry entry;
        public final int category;
        public final int origin;
        public final File file;
        public final File thumb;
        public final String displayName;
        public final String mime;
        /** Built off the UI thread; null when the viewer cannot show this item. */
        public MessageObject viewerObject;

        Item(int account, MgMessageHistory.Entry entry, int category, int origin, File file, File thumb,
             String displayName, String mime) {
            this.account = account;
            this.entry = entry;
            this.category = category;
            this.origin = origin;
            this.file = file;
            this.thumb = thumb;
            this.displayName = displayName;
            this.mime = mime;
        }

        public TLRPC.Document document() {
            TLRPC.MessageMedia media = MessageObject.getMedia(entry.message);
            return media instanceof TLRPC.TL_messageMediaDocument ? media.document : null;
        }

        /** Image file usable as a grid preview, or null. */
        public String previewPath() {
            if (category == CAT_PHOTO) {
                return file.getAbsolutePath();
            }
            if (thumb != null) {
                return thumb.getAbsolutePath();
            }
            TLRPC.Document d = document();
            if (d != null && MessageObject.isStickerDocument(d) && !MessageObject.isAnimatedStickerDocument(d, true)
                    && !MessageObject.isVideoStickerDocument(d)) {
                return file.getAbsolutePath();
            }
            return null;
        }

        public boolean opensInViewer() {
            return viewerObject != null;
        }
    }

    // ---- prefs ----

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isChatMenuEnabled() {
        return prefs().getBoolean(KEY_CHAT_MENU, true);
    }

    public static void setChatMenuEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_CHAT_MENU, value).apply();
    }

    public static boolean isIncludeEdits() {
        return prefs().getBoolean(KEY_INCLUDE_EDITS, false);
    }

    public static void setIncludeEdits(boolean value) {
        prefs().edit().putBoolean(KEY_INCLUDE_EDITS, value).apply();
    }

    /** ChatActivity hook: whether to offer "Deleted media" in this chat's header menu. */
    public static boolean showChatMenu(int account, long dialogId) {
        return dialogId != 0 && !DialogObject.isEncryptedDialog(dialogId) && isChatMenuEnabled();
    }

    // ---- viewer guard ----

    /** PhotoViewer hook: true for objects opened from the browser (read-only, local). */
    public static boolean isBrowserObject(MessageObject mo) {
        return mo != null && viewerObjects.contains(mo);
    }

    // ---- loading ----

    /** Loads the saved media of one chat (or all chats when dialogId == 0). Call off the UI thread. */
    public static ArrayList<Item> load(int account, long dialogId) {
        ArrayList<Item> out = new ArrayList<>();
        boolean includeEdits = isIncludeEdits();
        // The edited table also holds expired view-once media, which always belongs here.
        List<MgMessageHistory.Entry> entries =
                MgMessageHistory.getInstance().getMediaEntries(account, dialogId, true, LOAD_LIMIT);
        for (int i = 0; i < entries.size(); i++) {
            MgMessageHistory.Entry e = entries.get(i);
            int origin = originOf(e);
            if (origin == ORIGIN_EDITED && !includeEdits) {
                continue;
            }
            Item item = makeItem(account, e, origin);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    static int originOf(MgMessageHistory.Entry e) {
        if (e.kind == MgMessageHistory.KIND_DELETED) {
            return ORIGIN_DELETED;
        }
        String name = e.mediaPath != null ? new File(e.mediaPath).getName() : "";
        Matcher m = TAG.matcher(name);
        if (m.find() && "x".equals(m.group(1))) {
            return ORIGIN_VIEW_ONCE;
        }
        return ORIGIN_EDITED;
    }

    private static Item makeItem(int account, MgMessageHistory.Entry e, int origin) {
        if (e.message == null || TextUtils.isEmpty(e.mediaPath)) {
            return null;
        }
        File file = new File(e.mediaPath);
        if (!file.isFile() || file.length() == 0) {
            return null;
        }
        File thumb = !TextUtils.isEmpty(e.thumbPath) && new File(e.thumbPath).isFile() ? new File(e.thumbPath) : null;
        TLRPC.MessageMedia media = MessageObject.getMedia(e.message);
        int category;
        String name;
        String mime;
        String ext = extOf(file.getName());
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            category = CAT_PHOTO;
            mime = "image/jpeg";
            name = "deleted_photo_" + e.mid + ".jpg";
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            TLRPC.Document d = media.document;
            boolean video = MessageObject.isVideoDocument(d) || MessageObject.isRoundVideoDocument(d)
                    || MessageObject.isGifDocument(d);
            category = video ? CAT_VIDEO : CAT_FILE;
            mime = !TextUtils.isEmpty(d.mime_type) ? d.mime_type : mimeOf(ext);
            name = FileLoader.getDocumentFileName(d);
            if (TextUtils.isEmpty(name)) {
                name = (video ? "deleted_video_" : "deleted_file_") + e.mid + ext;
            }
        } else {
            return null;
        }
        name = sanitize(name);
        Item item = new Item(account, e, category, origin, file, thumb, name, mime);
        if (category != CAT_FILE) {
            item.viewerObject = buildViewerObject(account, e.message);
        }
        return item;
    }

    private static MessageObject buildViewerObject(int account, TLRPC.Message m) {
        try {
            // Local copy only: never let the viewer think the media is unread / timed.
            m.media_unread = false;
            m.unread = false;
            MessageObject mo = new MessageObject(account, m, true, true);
            if (mo.type < 0 || !(mo.isPhoto() || mo.isVideo() || mo.isGif() || mo.type == MessageObject.TYPE_PHOTO)) {
                return null;
            }
            viewerObjects.add(mo);
            return mo;
        } catch (Exception ex) {
            FileLog.e(ex);
            return null;
        }
    }

    private static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || fileName.length() - dot > 8) {
            return "";
        }
        return fileName.substring(dot);
    }

    private static String mimeOf(String ext) {
        if (TextUtils.isEmpty(ext)) {
            return "application/octet-stream";
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.substring(1).toLowerCase());
        return mime != null ? mime : "application/octet-stream";
    }

    private static String sanitize(String name) {
        String s = name.replace('/', '_').replace('\\', '_').replace(':', '_').trim();
        if (s.startsWith(".")) {
            s = "_" + s;
        }
        return s.isEmpty() ? "file" : s;
    }

    // ---- labels ----

    /** Chat title from memory only (no network, no storage read). */
    public static String chatTitle(int account, long dialogId) {
        return PlusUtil.dialogTitle(account, dialogId, "#" + dialogId);
    }

    // ---- video thumbnails (fallback when no document thumb was saved) ----

    public static Bitmap cachedVideoThumb(String path) {
        return videoThumbs.get(path);
    }

    /** Builds a frame thumbnail from the saved file off the UI thread; callback on the UI thread. */
    public static void loadVideoThumb(String path, Utilities.Callback<Bitmap> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bmp = null;
            try {
                bmp = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (bmp != null) {
                videoThumbs.put(path, bmp);
            }
            final Bitmap result = bmp;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    // ---- export / share / open ----

    /**
     * Below Android 10 a public-folder write needs the storage permission;
     * returns false (and asks for it) when it is missing.
     */
    public static boolean checkStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= 29 || activity == null) {
            return true;
        }
        if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        try {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    /**
     * Copies the saved file to the gallery (photos, videos) or Downloads (files).
     * Upstream {@code MediaController.saveFile} refuses app-private sources, so
     * this writes through MediaStore itself. Callback on the UI thread.
     */
    public static void export(Item item, Utilities.Callback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try {
                ok = Build.VERSION.SDK_INT >= 29 ? exportMediaStore(item) : exportLegacy(item);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final boolean result = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null) {
                    callback.run(result);
                }
            });
        });
    }

    @RequiresApi(29)
    private static boolean exportMediaStore(Item item) throws Exception {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        Uri collection;
        String relative;
        if (item.category == CAT_PHOTO) {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relative = Environment.DIRECTORY_PICTURES + File.separator + "Telegram";
        } else if (item.category == CAT_VIDEO && item.mime != null && item.mime.startsWith("video/")) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relative = Environment.DIRECTORY_MOVIES + File.separator + "Telegram";
        } else {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relative = Environment.DIRECTORY_DOWNLOADS + File.separator + "Telegram";
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, item.mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relative + File.separator);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, cv);
        if (uri == null) {
            return false;
        }
        boolean ok = false;
        try (InputStream in = new FileInputStream(item.file); OutputStream out = resolver.openOutputStream(uri)) {
            if (out != null) {
                copy(in, out);
                ok = true;
            }
        } finally {
            if (ok) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else {
                resolver.delete(uri, null, null);
            }
        }
        return ok;
    }

    private static boolean exportLegacy(Item item) throws Exception {
        String type = item.category == CAT_PHOTO ? Environment.DIRECTORY_PICTURES
                : item.category == CAT_VIDEO ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
        File dir = new File(Environment.getExternalStoragePublicDirectory(type), "Telegram");
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File dest = uniqueFile(dir, item.displayName);
        try (InputStream in = new FileInputStream(item.file); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        if (item.category != CAT_FILE) {
            AndroidUtilities.addMediaToGallery(dest);
        }
        return true;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        for (int i = 1; f.exists() && i < 100; i++) {
            f = new File(dir, dot > 0 ? name.substring(0, dot) + "(" + i + ")" + name.substring(dot) : name + "(" + i + ")");
        }
        return f;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int read;
        while ((read = in.read(buf)) > 0) {
            out.write(buf, 0, read);
        }
        out.flush();
    }

    /**
     * The FileProvider paths do not cover files/mg_history_media, so share and
     * open-with go through a short-lived copy under files/cache (covered by the
     * "cache" files-path) carrying the original file name.
     */
    private static File stage(Item item) throws Exception {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "cache" + File.separator + "plus_f20_share");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File[] old = dir.listFiles();
        if (old != null) {
            long now = System.currentTimeMillis();
            for (File f : old) {
                if (now - f.lastModified() > SHARE_TTL_MS) {
                    f.delete();
                }
            }
        }
        File dest = new File(dir, item.displayName);
        try (InputStream in = new FileInputStream(item.file); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        return dest;
    }

    /** Share sheet for the saved file (no Telegram request is involved). */
    public static void share(Activity activity, Item item) {
        if (activity == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File staged = null;
            try {
                staged = stage(item);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final File f = staged;
            AndroidUtilities.runOnUIThread(() -> {
                if (f == null) {
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f);
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType(item.mime != null ? item.mime : "application/octet-stream");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(intent, null));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        });
    }

    /** Opens a non-media file (or anything the viewer cannot show) in another app. */
    public static void openExternal(Activity activity, Item item) {
        if (activity == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File staged = null;
            try {
                staged = stage(item);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final File f = staged;
            AndroidUtilities.runOnUIThread(() -> {
                if (f == null) {
                    return;
                }
                try {
                    AndroidUtilities.openForView(f, item.displayName, item.mime, activity, null, false);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        });
    }

    /** Removes the saved entry (message + media copy); callback on the UI thread. */
    public static void delete(Item item, Runnable onDone) {
        MgMessageHistory.Entry e = item.entry;
        if (item.viewerObject != null) {
            viewerObjects.remove(item.viewerObject);
        }
        MgMessageHistory.getInstance().forgetMediaEntry(item.account, e.kind, e.dialogId, e.mid, e.mediaPath,
                () -> AndroidUtilities.runOnUIThread(() -> {
                    if (onDone != null) {
                        onDone.run();
                    }
                }));
    }
}
