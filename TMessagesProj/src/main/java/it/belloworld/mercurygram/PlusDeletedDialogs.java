package it.belloworld.mercurygram;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * plus f07: local log of whole chats that vanished from the chat list without
 * the user deleting them here (kicked/banned, left on another device, channel
 * or group gone, or the other side wiped the whole private history).
 *
 * <p>Only the server-driven removal sites in {@code MessagesController} call
 * {@link #onServerRemoved}; a delete/leave the user starts on this device goes
 * through other paths and is not logged. Everything is local: the snapshot is
 * read from memory and from {@code cache4.db} before the upstream delete runs
 * on the same serial storage queue, and kept in {@code plus_f07_dialogs.db}.
 * No network request is ever made (ghost-safe).</p>
 */
public final class PlusDeletedDialogs {

    // Where the removal was observed (the hook site), classified into a cause below.
    public static final int SITE_CHANNEL_UPDATE = 1; // updateChannel: no longer in a channel / supergroup
    public static final int SITE_CHAT_UPDATE = 2;    // updateChat: basic group forbidden / kicked
    public static final int SITE_HISTORY_GONE = 3;   // checkLastDialogMessage: no message left at all

    public static final int CAUSE_UNKNOWN = 0;
    public static final int CAUSE_KICKED = 1;        // removed by an admin
    public static final int CAUSE_FORBIDDEN = 2;     // banned, or the chat/channel was deleted
    public static final int CAUSE_LEFT = 3;          // left (usually from another device)
    public static final int CAUSE_HISTORY = 4;       // the whole history was deleted by the other side
    public static final int CAUSE_ACCOUNT_GONE = 5;  // the other account was deleted

    public static final int TYPE_USER = 0;
    public static final int TYPE_BOT = 1;
    public static final int TYPE_GROUP = 2;
    public static final int TYPE_CHANNEL = 3;

    private static final String PREFS = "plus_f07";
    private static final String KEY_LOG = "log_dialogs_";
    private static final String DB_NAME = "plus_f07_dialogs.db";
    private static final int DB_VERSION = 1;
    private static final String TBL = "deleted_dialogs";
    private static final int MAX_ROWS_PER_ACCOUNT = 500;
    private static final long DEDUP_MS = 60_000L;

    public static final class Entry {
        public long rowId;
        public int account;
        public long dialogId;
        public String title;
        public String username;
        public int type;
        public int cause;
        public long whenMs;
        public int cachedMessages;
        public String lastMessage;
    }

    private static final Object lock = new Object();
    private static volatile DbHelper helper;
    private static final android.util.LongSparseArray<Long> recent = new android.util.LongSparseArray<>();

    private PlusDeletedDialogs() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(int account) {
        return prefs().getBoolean(KEY_LOG + account, true);
    }

    public static void setEnabled(int account, boolean enabled) {
        prefs().edit().putBoolean(KEY_LOG + account, enabled).apply();
    }

    private static final class DbHelper extends SQLiteOpenHelper {
        DbHelper() {
            super(ApplicationLoader.applicationContext, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TBL + " (" +
                    "account INTEGER, dialog_id INTEGER, title TEXT, username TEXT, type INTEGER, " +
                    "cause INTEGER, when_ms INTEGER, cached INTEGER, last_text TEXT)");
            db.execSQL("CREATE INDEX idx_dd_account ON " + TBL + "(account, when_ms)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    private static SQLiteDatabase db() {
        DbHelper h = helper;
        if (h == null) {
            synchronized (PlusDeletedDialogs.class) {
                h = helper;
                if (h == null) {
                    h = helper = new DbHelper();
                }
            }
        }
        return h.getWritableDatabase();
    }

    /**
     * Called right before a server-driven {@code deleteDialog} (any thread the
     * update path runs on). Takes the name snapshot from memory now, then reads
     * the cached message count / last message on the storage queue, ahead of
     * the upstream delete that is queued after this call.
     */
    public static void onServerRemoved(int account, long dialogId, int site) {
        try {
            if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId) || !isEnabled(account)) {
                return;
            }
            final long now = System.currentTimeMillis();
            final long key = dialogId * 8 + account;
            synchronized (recent) {
                Long last = recent.get(key);
                if (last != null && now - last < DEDUP_MS) {
                    return;
                }
                if (recent.size() > 64) {
                    recent.clear();
                }
                recent.put(key, now);
            }
            final Entry e = new Entry();
            e.account = account;
            e.dialogId = dialogId;
            e.whenMs = now;
            MessagesController mc = MessagesController.getInstance(account);
            if (dialogId > 0) {
                TLRPC.User user = mc.getUser(dialogId);
                e.type = user != null && user.bot ? TYPE_BOT : TYPE_USER;
                if (user != null) {
                    e.title = UserObject.getUserName(user);
                    e.username = UserObject.getPublicUsername(user);
                }
                e.cause = user != null && UserObject.isDeleted(user) ? CAUSE_ACCOUNT_GONE : CAUSE_HISTORY;
            } else {
                TLRPC.Chat chat = mc.getChat(-dialogId);
                e.type = ChatObject.isChannelAndNotMegaGroup(chat) ? TYPE_CHANNEL : TYPE_GROUP;
                if (chat != null) {
                    e.title = chat.title;
                    e.username = ChatObject.getPublicUsername(chat);
                }
                e.cause = classifyChat(chat, site);
            }
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                readCache(storage, e);
                Utilities.globalQueue.postRunnable(() -> insert(e));
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static int classifyChat(TLRPC.Chat chat, int site) {
        if (site == SITE_HISTORY_GONE) {
            return CAUSE_HISTORY;
        }
        if (chat == null) {
            return CAUSE_UNKNOWN;
        }
        if (chat.kicked) {
            return CAUSE_KICKED;
        }
        if (chat instanceof TLRPC.TL_channelForbidden || chat instanceof TLRPC.TL_chatForbidden || chat.deactivated) {
            return CAUSE_FORBIDDEN;
        }
        if (chat.left) {
            return CAUSE_LEFT;
        }
        return CAUSE_UNKNOWN;
    }

    private static void readCache(MessagesStorage storage, Entry e) {
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT COUNT(*) FROM messages_v2 WHERE uid = %d", e.dialogId));
            if (cursor.next()) {
                e.cachedMessages = cursor.intValue(0);
            }
            cursor.dispose();
            cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT data FROM messages_v2 WHERE uid = %d AND mid > 0 ORDER BY mid DESC LIMIT 1", e.dialogId));
            if (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    TLRPC.Message m = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    if (m != null) {
                        e.lastMessage = preview(m);
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private static String preview(TLRPC.Message m) {
        if (!TextUtils.isEmpty(m.message)) {
            String s = m.message.replace('\n', ' ');
            return s.length() > 120 ? s.substring(0, 120) + "…" : s;
        }
        if (m.media != null && !(m.media instanceof TLRPC.TL_messageMediaEmpty)) {
            return "[media]";
        }
        if (m.action != null) {
            return "[service message]";
        }
        return null;
    }

    private static void insert(Entry e) {
        synchronized (lock) {
            try {
                SQLiteDatabase db = db();
                ContentValues cv = new ContentValues();
                cv.put("account", e.account);
                cv.put("dialog_id", e.dialogId);
                cv.put("title", e.title);
                cv.put("username", e.username);
                cv.put("type", e.type);
                cv.put("cause", e.cause);
                cv.put("when_ms", e.whenMs);
                cv.put("cached", e.cachedMessages);
                cv.put("last_text", e.lastMessage);
                db.insert(TBL, null, cv);
                db.execSQL("DELETE FROM " + TBL + " WHERE account=? AND rowid NOT IN (SELECT rowid FROM " + TBL
                                + " WHERE account=? ORDER BY when_ms DESC LIMIT " + MAX_ROWS_PER_ACCOUNT + ")",
                        new Object[]{e.account, e.account});
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    /** Entries of one account, newest first. Call off the UI thread. */
    public static List<Entry> list(int account) {
        ArrayList<Entry> out = new ArrayList<>();
        synchronized (lock) {
            Cursor c = null;
            try {
                c = db().rawQuery("SELECT rowid, dialog_id, title, username, type, cause, when_ms, cached, last_text FROM "
                        + TBL + " WHERE account=? ORDER BY when_ms DESC", new String[]{Integer.toString(account)});
                while (c.moveToNext()) {
                    Entry e = new Entry();
                    e.rowId = c.getLong(0);
                    e.account = account;
                    e.dialogId = c.getLong(1);
                    e.title = c.isNull(2) ? null : c.getString(2);
                    e.username = c.isNull(3) ? null : c.getString(3);
                    e.type = c.getInt(4);
                    e.cause = c.getInt(5);
                    e.whenMs = c.getLong(6);
                    e.cachedMessages = c.getInt(7);
                    e.lastMessage = c.isNull(8) ? null : c.getString(8);
                    out.add(e);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        return out;
    }

    public static int count(int account) {
        synchronized (lock) {
            Cursor c = null;
            try {
                c = db().rawQuery("SELECT COUNT(*) FROM " + TBL + " WHERE account=?", new String[]{Integer.toString(account)});
                return c.moveToNext() ? c.getInt(0) : 0;
            } catch (Throwable t) {
                FileLog.e(t);
                return 0;
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
    }

    public static void remove(long rowId) {
        synchronized (lock) {
            try {
                db().execSQL("DELETE FROM " + TBL + " WHERE rowid=?", new Object[]{rowId});
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    public static void clear(int account) {
        synchronized (lock) {
            try {
                db().execSQL("DELETE FROM " + TBL + " WHERE account=?", new Object[]{account});
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }
}
