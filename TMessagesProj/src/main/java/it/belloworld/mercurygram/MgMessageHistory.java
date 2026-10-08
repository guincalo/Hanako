package it.belloworld.mercurygram;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.LongSparseArray;
import android.util.SparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mercurygram "saved message history" — keeps a local copy of messages the
 * server reports deleted, plus pre-edit versions of edited messages, so that
 * e.g. a scammer cannot retract a message before it is seen.
 *
 * <p>Stored in a dedicated SQLite database ({@code mg_message_history.db}),
 * separate from Telegram's {@code cache4.db}, so clearing the Telegram cache
 * does not wipe saved entries.</p>
 *
 * <p>Media of archived messages (photo, video + thumb, voice, round video,
 * GIF, sticker, file) is copied into app-private storage by
 * {@link MgHistoryMedia}; the copy's path is stored with the entry
 * ({@code media_path}, {@code thumb_path}) and applied when the entry is read.</p>
 *
 * <p>Personal build: self-destructing / view-once media is archived too (when
 * deleted, or when it expires after viewing). Secret chats are still never
 * archived. The feature is per-account ({@code UserConfig.mg.savedMessagesHistory}).</p>
 */
public class MgMessageHistory {

    public static final int KIND_DELETED = 0;
    public static final int KIND_EDITED = 1;

    private static final String DB_NAME = "mg_message_history.db";
    private static final int DB_VERSION = 2;
    private static final String TBL_DELETED = "deleted_messages";
    private static final String TBL_EDITED = "edited_messages";
    // Cap rows kept per (account, dialog) so an active deleter cannot grow the DB unbounded.
    private static final int MAX_PER_DIALOG = 2000;

    private static final Object writeLock = new Object();
    private static volatile MgMessageHistory instance;

    private final DbHelper dbHelper;

    public static class Entry {
        public final int kind;
        public final long dialogId;
        public final int mid;
        public final long whenMs;
        public final TLRPC.Message message;
        /** Saved copy of the media / video thumbnail, or null (may no longer exist). */
        public final String mediaPath;
        public final String thumbPath;

        Entry(int kind, long dialogId, int mid, long whenMs, TLRPC.Message message, String mediaPath, String thumbPath) {
            this.kind = kind;
            this.dialogId = dialogId;
            this.mid = mid;
            this.whenMs = whenMs;
            this.message = message;
            this.mediaPath = mediaPath;
            this.thumbPath = thumbPath;
        }
    }

    public static MgMessageHistory getInstance() {
        MgMessageHistory local = instance;
        if (local == null) {
            synchronized (MgMessageHistory.class) {
                local = instance;
                if (local == null) {
                    local = instance = new MgMessageHistory();
                }
            }
        }
        return local;
    }

    private MgMessageHistory() {
        dbHelper = new DbHelper();
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper() {
            super(ApplicationLoader.applicationContext, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(android.database.sqlite.SQLiteDatabase db) {
            // A message is deleted once → PK dedups. Pre-edit versions accumulate
            // (every retraction must survive), so the edited table has no PK.
            db.execSQL("CREATE TABLE " + TBL_DELETED + " (" +
                    "account INTEGER, dialog_id INTEGER, mid INTEGER, data BLOB, when_ms INTEGER, " +
                    "media_path TEXT, thumb_path TEXT, " +
                    "PRIMARY KEY(account, dialog_id, mid))");
            db.execSQL("CREATE TABLE " + TBL_EDITED + " (" +
                    "account INTEGER, dialog_id INTEGER, mid INTEGER, data BLOB, when_ms INTEGER, " +
                    "media_path TEXT, thumb_path TEXT)");
            db.execSQL("CREATE INDEX idx_deleted_dialog ON " + TBL_DELETED + "(account, dialog_id, when_ms)");
            db.execSQL("CREATE INDEX idx_edited_dialog ON " + TBL_EDITED + "(account, dialog_id, when_ms)");
        }

        @Override
        public void onUpgrade(android.database.sqlite.SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // v2: saved media copies (MgHistoryMedia).
                db.execSQL("ALTER TABLE " + TBL_DELETED + " ADD COLUMN media_path TEXT");
                db.execSQL("ALTER TABLE " + TBL_DELETED + " ADD COLUMN thumb_path TEXT");
                db.execSQL("ALTER TABLE " + TBL_EDITED + " ADD COLUMN media_path TEXT");
                db.execSQL("ALTER TABLE " + TBL_EDITED + " ADD COLUMN thumb_path TEXT");
            }
        }
    }

    /**
     * Messages that are never persisted nor kept as an inline ghost: secret
     * chats. Self-destructing / view-once messages used to be excluded here
     * (api/terms §1.4); this personal build saves them on purpose. Shared by
     * the archive path and the ChatActivity ghost path so the rule cannot drift.
     */
    public static boolean isExcluded(long dialogId, TLRPC.Message message) {
        return DialogObject.isEncryptedDialog(dialogId)
                || message == null;
    }

    private static byte[] serialize(TLRPC.Message message) {
        try {
            SerializedData data = new SerializedData(message.getObjectSize());
            message.serializeToStream(data);
            byte[] bytes = data.toByteArray();
            data.cleanup();
            return bytes;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static TLRPC.Message deserialize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        NativeByteBuffer nbb = null;
        try {
            nbb = new NativeByteBuffer(bytes.length);
            nbb.writeBytes(bytes);
            nbb.buffer.rewind();
            return TLRPC.Message.TLdeserialize(nbb, nbb.readInt32(false), false);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (nbb != null) {
                nbb.reuse();
            }
        }
    }

    /**
     * Archive messages about to be deleted by the server. Must be called
     * <b>before</b> {@code MessagesStorage.markMessagesAsDeleted}; it enqueues
     * its read on the same (serial) storage queue so it observes the rows
     * before the upstream delete runs.
     */
    public void archiveDeleted(int account, long dialogId, ArrayList<Integer> mids) {
        if (!UserConfig.getInstance(account).mg.savedMessagesHistory || mids == null || mids.isEmpty()) {
            return;
        }
        markRemote(dialogId, mids);
        archive(account, dialogId, mids, true, null);
    }

    // Mids the server reported deleted that the storage read has not been consumed for
    // by an open chat yet, keyed like processUpdateArray keys them (0 = any non-channel
    // dialog, -channel_id for a channel). Only these become ghosts in an open chat:
    // a delete the user made locally is not persisted, so it must not ghost either.
    private final LongSparseArray<HashSet<Integer>> remotePending = new LongSparseArray<>();

    public void markRemote(long dialogId, Collection<Integer> mids) {
        synchronized (remotePending) {
            // Unbounded for chats that are never opened, so reset once it grows past 256 dialogs.
            if (remotePending.size() > 256) {
                remotePending.clear();
            }
            HashSet<Integer> set = remotePending.get(dialogId);
            if (set == null) {
                set = new HashSet<>();
                remotePending.put(dialogId, set);
            }
            set.addAll(mids);
        }
    }

    /** Removes and returns the server-deleted mids pending for this key (see {@link #markRemote}). */
    public Set<Integer> takeRemote(long dialogId) {
        synchronized (remotePending) {
            HashSet<Integer> set = remotePending.get(dialogId);
            if (set == null) {
                return Collections.emptySet();
            }
            remotePending.remove(dialogId);
            return set;
        }
    }

    /**
     * Archive the current (pre-edit) version of messages about to be replaced
     * by an edit. Must be called <b>before</b>
     * {@code MessagesStorage.putMessages(..., mode=-2, ...)}.
     */
    public void archiveEditsBefore(int account, long dialogId, ArrayList<TLRPC.Message> newMessages) {
        if (!UserConfig.getInstance(account).mg.savedMessagesHistory || newMessages == null || newMessages.isEmpty()) {
            return;
        }
        ArrayList<Integer> mids = new ArrayList<>(newMessages.size());
        SparseArray<TLRPC.Message> replacements = new SparseArray<>(newMessages.size());
        for (int a = 0; a < newMessages.size(); a++) {
            TLRPC.Message m = newMessages.get(a);
            if (m != null) {
                mids.add(m.id);
                replacements.put(m.id, m);
            }
        }
        archive(account, dialogId, mids, false, replacements);
    }

    /** One row to insert, with the media copy to run once the insert sticks. */
    private static final class Pending {
        final long dialogId;
        final ContentValues cv;
        final MgHistoryMedia.Plan plan;

        Pending(long dialogId, ContentValues cv, MgHistoryMedia.Plan plan) {
            this.dialogId = dialogId;
            this.cv = cv;
            this.plan = plan;
        }
    }

    private static Pending makePending(int account, long uid, TLRPC.Message message, byte[] bytes,
                                       long now, MgHistoryMedia.Plan plan, boolean runPlan) {
        ContentValues cv = new ContentValues();
        cv.put("account", account);
        cv.put("dialog_id", uid);
        cv.put("mid", message.id);
        cv.put("data", bytes);
        cv.put("when_ms", now);
        if (plan != null) {
            cv.put("media_path", plan.mediaPath);
            if (plan.thumbPath != null) {
                cv.put("thumb_path", plan.thumbPath);
            }
        }
        return new Pending(uid, cv, runPlan ? plan : null);
    }

    /**
     * @param replacements for edits, the new versions by mid: the old media is
     *                     only copied when the edit replaces it (otherwise the
     *                     current message still references the same file).
     */
    private void archive(int account, long dialogId, ArrayList<Integer> mids, boolean deleted,
                         SparseArray<TLRPC.Message> replacements) {
        if (!UserConfig.getInstance(account).mg.savedMessagesHistory || mids == null || mids.isEmpty()
                || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        final ArrayList<Integer> midsCopy = new ArrayList<>(mids);
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            ArrayList<Pending> rows = new ArrayList<>();
            try {
                String ids = android.text.TextUtils.join(",", midsCopy);
                String where = dialogId != 0
                        ? String.format(Locale.US, "uid = %d", dialogId)
                        : "is_channel = 0";
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT uid, data FROM messages_v2 WHERE mid IN(%s) AND %s", ids, where));
                long now = System.currentTimeMillis();
                while (cursor.next()) {
                    long uid = cursor.longValue(0);
                    byte[] bytes = cursor.byteArrayValue(1);
                    TLRPC.Message message = deserialize(bytes);
                    if (message == null || isExcluded(uid, message)) {
                        continue;
                    }
                    MgHistoryMedia.Plan plan = null;
                    if (deleted) {
                        plan = MgHistoryMedia.plan(account, uid, message, "d" + now);
                    } else {
                        TLRPC.Message replacement = replacements != null ? replacements.get(message.id) : null;
                        if (replacement == null || !MgHistoryMedia.sameMedia(message, replacement)) {
                            plan = MgHistoryMedia.plan(account, uid, message, "e" + now);
                        }
                    }
                    rows.add(makePending(account, uid, message, bytes, now, plan, true));
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            storeBatch(account, deleted ? TBL_DELETED : TBL_EDITED, rows);
        });
    }

    /**
     * Self-destructing (view-once / timed) media is about to be emptied after it
     * was viewed ({@code MessagesStorage.emptyMessagesMedia}). Runs <b>on the
     * storage queue</b>, before the cached file is queued for deletion: copies
     * the media synchronously (one message) and keeps the pre-expiry version as
     * an edit-history entry of the message (the message itself stays, empty).
     */
    public void archiveExpiringSync(int account, long dialogId, TLRPC.Message message) {
        if (!UserConfig.getInstance(account).mg.savedMessagesHistory || message == null
                || DialogObject.isEncryptedDialog(dialogId) || !MgHistoryMedia.hasSavableMedia(message)) {
            return;
        }
        try {
            byte[] bytes = serialize(message);
            if (bytes == null) {
                return;
            }
            long now = System.currentTimeMillis();
            MgHistoryMedia.Plan plan = MgHistoryMedia.plan(account, dialogId, message, "x" + now);
            MgHistoryMedia.runCopy(plan);
            ArrayList<Pending> rows = new ArrayList<>(1);
            rows.add(makePending(account, dialogId, message, bytes, now, plan, false));
            storeBatch(account, TBL_EDITED, rows);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Inserts all rows of one archive call in a single transaction and prunes
     * each touched dialog once, then hands the media copies (for rows that were
     * actually inserted) and the files of pruned rows to MgHistoryMedia's queue.
     */
    private void storeBatch(int account, String table, ArrayList<Pending> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ArrayList<MgHistoryMedia.Plan> toCopy = new ArrayList<>();
        ArrayList<String> toDelete = new ArrayList<>();
        synchronized (writeLock) {
            try {
                android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    HashSet<Long> dialogs = new HashSet<>();
                    for (int i = 0; i < rows.size(); i++) {
                        Pending p = rows.get(i);
                        long rowId = db.insertWithOnConflict(table, null, p.cv,
                                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
                        if (rowId != -1 && p.plan != null) {
                            toCopy.add(p.plan);
                        }
                        dialogs.add(p.dialogId);
                    }
                    for (Long did : dialogs) {
                        prune(db, table, account, did, toDelete);
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (Exception e) {
                FileLog.e(e);
                toCopy.clear();
                toDelete.clear();
            }
        }
        MgHistoryMedia.copyAsync(toCopy);
        MgHistoryMedia.deleteAsync(toDelete);
    }

    private static final String PRUNE_WHERE = " WHERE account=? AND dialog_id=? AND rowid NOT IN "
            + "(SELECT rowid FROM %1$s WHERE account=? AND dialog_id=? ORDER BY when_ms DESC LIMIT " + MAX_PER_DIALOG + ")";

    private static void prune(android.database.sqlite.SQLiteDatabase db, String table, int account, long dialogId,
                              ArrayList<String> filesOut) {
        String where = String.format(Locale.US, PRUNE_WHERE, table);
        String[] args = new String[]{Integer.toString(account), Long.toString(dialogId),
                Integer.toString(account), Long.toString(dialogId)};
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT media_path, thumb_path FROM " + table + where, args);
            while (c.moveToNext()) {
                if (!c.isNull(0)) {
                    filesOut.add(c.getString(0));
                }
                if (!c.isNull(1)) {
                    filesOut.add(c.getString(1));
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        db.execSQL("DELETE FROM " + table + where, args);
    }

    /** All saved entries (deleted + pre-edit) for a dialog, newest first. */
    public List<Entry> getEntries(int account, long dialogId) {
        return loadEntries(account, dialogId, true, true);
    }

    /**
     * Deleted-only entries with full message blobs, newest first. Drives the
     * cold-restart ghost re-injection in {@code ChatActivity}: the row is gone
     * from {@code messages_v2} once the upstream delete runs, so the only path
     * to bring the cell back is to rebuild a {@code MessageObject} from this blob.
     */
    public List<Entry> getDeletedEntries(int account, long dialogId) {
        return loadEntries(account, dialogId, true, false);
    }

    private List<Entry> loadEntries(int account, long dialogId, boolean includeDeleted, boolean includeEdited) {
        ArrayList<Entry> out = new ArrayList<>();
        synchronized (writeLock) {
            try {
                android.database.sqlite.SQLiteDatabase db = dbHelper.getReadableDatabase();
                if (includeDeleted) {
                    loadKind(db, account, dialogId, TBL_DELETED, KIND_DELETED, out);
                }
                if (includeEdited) {
                    loadKind(db, account, dialogId, TBL_EDITED, KIND_EDITED, out);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        out.sort((a, b) -> Long.compare(b.whenMs, a.whenMs));
        return out;
    }

    private void loadKind(android.database.sqlite.SQLiteDatabase db, int account, long dialogId,
                          String table, int kind, List<Entry> out) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT mid, when_ms, data, media_path, thumb_path FROM " + table
                            + " WHERE account=? AND dialog_id=? ORDER BY when_ms DESC LIMIT " + MAX_PER_DIALOG,
                    new String[]{Integer.toString(account), Long.toString(dialogId)});
            while (c.moveToNext()) {
                TLRPC.Message m = deserialize(c.getBlob(2));
                if (m != null) {
                    String mediaPath = c.isNull(3) ? null : c.getString(3);
                    String thumbPath = c.isNull(4) ? null : c.getString(4);
                    MgHistoryMedia.apply(m, mediaPath, thumbPath);
                    out.add(new Entry(kind, dialogId, c.getInt(0), c.getLong(1), m, mediaPath, thumbPath));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /**
     * Distinct message IDs for a dialog in the given table (deleted or edited).
     * Used to prime per-chat in-memory caches so the long-press menu and the
     * chat-load ghost pass do not hit SQLite per row.
     */
    public Set<Integer> getMidsForDialog(int account, long dialogId, boolean edited) {
        final String table = edited ? TBL_EDITED : TBL_DELETED;
        final String sql = "SELECT DISTINCT mid FROM " + table + " WHERE account=? AND dialog_id=?";
        HashSet<Integer> out = new HashSet<>();
        readCursor(sql, new String[]{Integer.toString(account), Long.toString(dialogId)}, c -> {
            while (c.moveToNext()) {
                out.add(c.getInt(0));
            }
        });
        return out;
    }

    /** Pre-edit versions of a single message, oldest first. */
    public List<Entry> getEditHistoryFor(int account, long dialogId, int mid) {
        ArrayList<Entry> out = new ArrayList<>();
        final String sql = "SELECT when_ms, data, media_path, thumb_path FROM " + TBL_EDITED
                + " WHERE account=? AND dialog_id=? AND mid=? ORDER BY when_ms ASC";
        readCursor(sql,
                new String[]{Integer.toString(account), Long.toString(dialogId), Integer.toString(mid)},
                c -> {
                    while (c.moveToNext()) {
                        TLRPC.Message m = deserialize(c.getBlob(1));
                        if (m != null) {
                            String mediaPath = c.isNull(2) ? null : c.getString(2);
                            String thumbPath = c.isNull(3) ? null : c.getString(3);
                            MgHistoryMedia.apply(m, mediaPath, thumbPath);
                            out.add(new Entry(KIND_EDITED, dialogId, mid, c.getLong(0), m, mediaPath, thumbPath));
                        }
                    }
                });
        return out;
    }

    private interface CursorBody {
        void consume(Cursor c) throws Exception;
    }

    private void readCursor(String sql, String[] args, CursorBody body) {
        synchronized (writeLock) {
            Cursor c = null;
            try {
                c = dbHelper.getReadableDatabase().rawQuery(sql, args);
                body.consume(c);
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /** Drop saved deleted messages the user removed from a chat themselves. */
    public void forgetDeleted(int account, long dialogId, Collection<Integer> mids) {
        if (mids == null || mids.isEmpty()) {
            return;
        }
        final String ids = android.text.TextUtils.join(",", mids);
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<String> files = new ArrayList<>();
            synchronized (writeLock) {
                Cursor c = null;
                try {
                    android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
                    c = db.rawQuery("SELECT media_path, thumb_path FROM " + TBL_DELETED
                                    + " WHERE account=? AND dialog_id=? AND mid IN (" + ids + ")",
                            new String[]{Integer.toString(account), Long.toString(dialogId)});
                    while (c.moveToNext()) {
                        if (!c.isNull(0)) {
                            files.add(c.getString(0));
                        }
                        if (!c.isNull(1)) {
                            files.add(c.getString(1));
                        }
                    }
                    c.close();
                    c = null;
                    db.execSQL("DELETE FROM " + TBL_DELETED
                                    + " WHERE account=? AND dialog_id=? AND mid IN (" + ids + ")",
                            new Object[]{account, dialogId});
                } catch (Exception e) {
                    FileLog.e(e);
                    files.clear();
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
            MgHistoryMedia.deleteAsync(files);
        });
    }

    // plus f20 begin: saved deleted-media browser (PlusDeletedMedia)
    /**
     * Entries that have a saved media copy, newest first: deleted messages and,
     * when {@code includeEdited}, pre-edit / expired view-once versions.
     * {@code dialogId == 0} means every chat of the account. Call off the UI thread.
     */
    public List<Entry> getMediaEntries(int account, long dialogId, boolean includeEdited, int limit) {
        ArrayList<Entry> out = new ArrayList<>();
        loadMediaKind(TBL_DELETED, KIND_DELETED, account, dialogId, limit, out);
        if (includeEdited) {
            loadMediaKind(TBL_EDITED, KIND_EDITED, account, dialogId, limit, out);
        }
        out.sort((a, b) -> Long.compare(b.whenMs, a.whenMs));
        return out;
    }

    private void loadMediaKind(String table, int kind, int account, long dialogId, int limit, List<Entry> out) {
        String sql = "SELECT dialog_id, mid, when_ms, data, media_path, thumb_path FROM " + table
                + " WHERE account=? AND media_path IS NOT NULL" + (dialogId != 0 ? " AND dialog_id=?" : "")
                + " ORDER BY when_ms DESC LIMIT " + Math.max(1, limit);
        String[] args = dialogId != 0
                ? new String[]{Integer.toString(account), Long.toString(dialogId)}
                : new String[]{Integer.toString(account)};
        readCursor(sql, args, c -> {
            while (c.moveToNext()) {
                TLRPC.Message m = deserialize(c.getBlob(3));
                if (m == null) {
                    continue;
                }
                String mediaPath = c.isNull(4) ? null : c.getString(4);
                String thumbPath = c.isNull(5) ? null : c.getString(5);
                MgHistoryMedia.apply(m, mediaPath, thumbPath);
                out.add(new Entry(kind, c.getLong(0), c.getInt(1), c.getLong(2), m, mediaPath, thumbPath));
            }
        });
    }

    /**
     * Drops one saved entry, identified by its media copy, and deletes its files.
     * {@code onDone} runs on a background queue once the row is gone.
     */
    public void forgetMediaEntry(int account, int kind, long dialogId, int mid, String mediaPath, Runnable onDone) {
        if (mediaPath == null) {
            return;
        }
        final String table = kind == KIND_EDITED ? TBL_EDITED : TBL_DELETED;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<String> files = new ArrayList<>();
            String where = " WHERE account=? AND dialog_id=? AND mid=? AND media_path=?";
            String[] args = {Integer.toString(account), Long.toString(dialogId), Integer.toString(mid), mediaPath};
            synchronized (writeLock) {
                Cursor c = null;
                try {
                    android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
                    c = db.rawQuery("SELECT media_path, thumb_path FROM " + table + where, args);
                    while (c.moveToNext()) {
                        if (!c.isNull(0)) {
                            files.add(c.getString(0));
                        }
                        if (!c.isNull(1)) {
                            files.add(c.getString(1));
                        }
                    }
                    c.close();
                    c = null;
                    db.execSQL("DELETE FROM " + table + where, args);
                } catch (Exception e) {
                    FileLog.e(e);
                    files.clear();
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
            MgHistoryMedia.deleteAsync(files);
            if (onDone != null) {
                onDone.run();
            }
        });
    }
    // plus f20 end

    public void clearAll() {
        synchronized (writeLock) {
            try {
                android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.delete(TBL_DELETED, null, null);
                db.delete(TBL_EDITED, null, null);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        MgHistoryMedia.deleteAllAsync();
    }
}
