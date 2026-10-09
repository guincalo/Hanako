package it.belloworld.mercurygram;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * plus f05: local "activity log" of contacts.
 *
 * Records, on this device only, when a user goes online / offline / to a hidden status
 * (from updateUserStatus) and when they read your messages in a private chat
 * (from updateReadHistoryOutbox). Nothing is ever sent to the server: the log is fed only
 * by updates the client already receives, so it is safe with ghost mode.
 *
 * Idea from Nagram XF / AyuGram "local last seen" (com.radolyn.ayugram.utils.LastSeenHelper,
 * GPL-3.0): keep what the server already told us. Unlike that helper (one timestamp per user)
 * this keeps a timeline, in a small plain SQLite database (no Room), with a retention cap.
 *
 * Off by default. Per-account switch, per-account scope (all users / contacts / selected) and
 * per-contact include/exclude overrides set from the log screen of each profile.
 */
public final class PlusActivityLog {

    public static final int TYPE_ONLINE = 1;
    public static final int TYPE_OFFLINE = 2;
    /** Status hidden by the user's privacy: recently / last week / last month / empty. extra = -100/-101/-102/0. */
    public static final int TYPE_HIDDEN = 3;
    /** They read your messages. extra = max_id read. */
    public static final int TYPE_READ = 4;

    public static final int SCOPE_ALL = 0;
    public static final int SCOPE_CONTACTS = 1;
    public static final int SCOPE_SELECTED = 2;

    /** ProfileActivity menu item id (ProfileActivity's own ids are < 300). */
    public static final int MENU_ID = 70500;

    public static final int[] RETENTION_DAYS = {7, 30, 90, 365};
    private static final int DEFAULT_RETENTION_DAYS = 30;
    /** Hard caps so the DB can never grow without bound, whatever the retention. */
    private static final int MAX_ROWS_PER_USER = 2000;
    private static final int MAX_ROWS_TOTAL = 100000;
    private static final int PRUNE_EVERY_INSERTS = 500;

    private static final DispatchQueue queue = new DispatchQueue("plusActivityLog");
    private static DbHelper dbHelper;
    private static int insertsSincePrune;
    private static boolean prunedAtStart;

    /** "acc_uid" -> last logged type/extra, to drop repeated identical states (e.g. online->online). */
    private static final ConcurrentHashMap<String, long[]> lastState = new ConcurrentHashMap<>();

    private PlusActivityLog() {
    }

    // ---------------------------------------------------------------- config

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_activity_log", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(int account) {
        SharedPreferences p = prefs();
        return p.getBoolean(PlusUtil.accountKey(p, "on", account), false);
    }

    public static void setEnabled(int account, boolean value) {
        HanakoTelemetry.count(HanakoTelemetry.ACTIVITY_LOG_TOGGLE); // hanako: usage statistics (off by default)
        SharedPreferences p = prefs();
        p.edit().putBoolean(PlusUtil.accountKey(p, "on", account), value).apply();
    }

    public static int getScope(int account) {
        SharedPreferences p = prefs();
        return p.getInt(PlusUtil.accountKey(p, "scope", account), SCOPE_CONTACTS);
    }

    public static void setScope(int account, int scope) {
        SharedPreferences p = prefs();
        p.edit().putInt(PlusUtil.accountKey(p, "scope", account), scope).apply();
    }

    /** Global (all accounts). */
    public static int getRetentionDays() {
        return prefs().getInt("retention_days", DEFAULT_RETENTION_DAYS);
    }

    public static void setRetentionDays(int days) {
        prefs().edit().putInt("retention_days", days).apply();
        queue.postRunnable(PlusActivityLog::pruneInternal);
    }

    /*
     * "u_<user>" + "_u<self>": the per-person override, keyed by the account's own user id
     * (old "u_<slot>_<user>" keys are moved over on first read, see PlusUtil.accountKey).
     */
    private static String overrideKey(int account, long userId) {
        SharedPreferences p = prefs();
        String old = "u_" + account + "_" + userId;
        long self = PlusUtil.validAccount(account) ? UserConfig.getInstance(account).getClientUserId() : 0;
        if (self == 0) {
            return old;
        }
        String key = "u_" + userId + "_u" + self;
        if (!p.contains(key) && p.contains(old)) {
            p.edit().putInt(key, p.getInt(old, 0)).remove(old).apply();
        }
        return key;
    }

    /** 1 = always log, -1 = never log, 0 = follow the account scope. */
    public static int getUserOverride(int account, long userId) {
        return prefs().getInt(overrideKey(account, userId), 0);
    }

    public static void setUserOverride(int account, long userId, int value) {
        SharedPreferences.Editor e = prefs().edit();
        String key = overrideKey(account, userId);
        if (value == 0) {
            e.remove(key);
        } else {
            e.putInt(key, value);
        }
        e.apply();
    }

    /** Whether events of this user are recorded right now. */
    public static boolean isTracked(int account, long userId) {
        if (userId <= 0 || !isEnabled(account)) {
            return false;
        }
        if (userId == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        int override = getUserOverride(account, userId);
        if (override != 0) {
            return override > 0;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        if (user != null && (user.bot || user.self)) {
            return false;
        }
        switch (getScope(account)) {
            case SCOPE_ALL:
                return true;
            case SCOPE_CONTACTS:
                return ContactsController.getInstance(account).contactsDict.get(userId) != null;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- hooks

    /**
     * Hook: MessagesController.processUpdateArray, main-thread part, TL_updateUserStatus
     * (after the recently/last week/last month expires conversion).
     */
    public static void onUserStatus(int account, long userId, TLRPC.UserStatus status) {
        try {
            if (status == null || !isTracked(account, userId)) {
                return;
            }
            int now = ConnectionsManager.getInstance(account).getCurrentTime();
            int type;
            int ts = now;
            long extra = 0;
            if (status instanceof TLRPC.TL_userStatusOnline) {
                type = TYPE_ONLINE;
            } else if (status instanceof TLRPC.TL_userStatusOffline) {
                type = TYPE_OFFLINE;
                // expires holds was_online for userStatusOffline
                extra = status.expires;
                if (status.expires > 0 && status.expires <= now) {
                    ts = status.expires;
                }
            } else {
                type = TYPE_HIDDEN;
                extra = status.expires;
            }
            String key = account + "_" + userId;
            long[] last = lastState.get(key);
            if (last != null && last[0] == type && (type == TYPE_ONLINE || last[1] == extra)) {
                return;
            }
            lastState.put(key, new long[]{type, extra});
            insert(account, userId, ts, type, extra, false);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Hook: MessagesController.processUpdateArray, TL_updateReadHistoryOutbox for a private chat
     * (stage queue). {@code date} is the update container date; events replayed by getDifference
     * only tell us the read happened at or before that time, so they are flagged approximate.
     */
    public static void onReadOutbox(int account, long userId, int maxId, int date, boolean fromGetDifference) {
        try {
            if (!isTracked(account, userId)) {
                return;
            }
            int now = ConnectionsManager.getInstance(account).getCurrentTime();
            int ts = date > 0 ? date : now;
            boolean approx = fromGetDifference || date <= 0 || Math.abs(now - date) > 30;
            insert(account, userId, ts, TYPE_READ, maxId, approx);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Hook: ProfileActivity action bar item click. Returns true when handled. */
    public static boolean onProfileMenuClick(BaseFragment fragment, int id, long userId) {
        if (id != MENU_ID || userId == 0) {
            return false;
        }
        fragment.presentFragment(new it.belloworld.mercurygram.ui.PlusActivityLogActivity(userId));
        return true;
    }

    /** Whether ProfileActivity should offer the "Activity log" item for this user. */
    public static boolean showInProfile(int account, TLRPC.User user) {
        return user != null && !user.bot && !user.self && isEnabled(account);
    }

    // ---------------------------------------------------------------- storage

    public static final class Entry {
        public int date;
        public int type;
        public long extra;
        public boolean approx;
    }

    public interface Callback<T> {
        void run(T result);
    }

    private static void insert(int account, long userId, int date, int type, long extra, boolean approx) {
        queue.postRunnable(() -> {
            try {
                SQLiteDatabase db = db();
                if (!prunedAtStart) {
                    prunedAtStart = true;
                    pruneInternal();
                }
                ContentValues cv = new ContentValues();
                cv.put("acc", account);
                cv.put("uid", userId);
                cv.put("date", date);
                cv.put("type", type);
                cv.put("extra", extra);
                cv.put("approx", approx ? 1 : 0);
                db.insert("log", null, cv);
                if (++insertsSincePrune >= PRUNE_EVERY_INSERTS) {
                    pruneInternal();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Newest first, at most {@code limit} entries. Callback runs on the UI thread. */
    public static void load(int account, long userId, int limit, Callback<ArrayList<Entry>> callback) {
        queue.postRunnable(() -> {
            ArrayList<Entry> result = new ArrayList<>();
            Cursor c = null;
            try {
                c = db().rawQuery("SELECT date, type, extra, approx FROM log WHERE acc = ? AND uid = ? ORDER BY date DESC, id DESC LIMIT " + limit,
                        new String[]{Integer.toString(account), Long.toString(userId)});
                while (c.moveToNext()) {
                    Entry e = new Entry();
                    e.date = c.getInt(0);
                    e.type = c.getInt(1);
                    e.extra = c.getLong(2);
                    e.approx = c.getInt(3) != 0;
                    result.add(e);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    public static void clearUser(int account, long userId, Runnable done) {
        lastState.remove(account + "_" + userId);
        queue.postRunnable(() -> {
            try {
                db().delete("log", "acc = ? AND uid = ?", new String[]{Integer.toString(account), Long.toString(userId)});
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    public static void clearAll(Runnable done) {
        lastState.clear();
        queue.postRunnable(() -> {
            try {
                db().delete("log", null, null);
                db().execSQL("VACUUM");
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    /** Drops rows older than the retention, then enforces the per-user and total row caps. Queue thread only. */
    private static void pruneInternal() {
        insertsSincePrune = 0;
        try {
            SQLiteDatabase db = db();
            int cutoff = (int) (System.currentTimeMillis() / 1000L) - getRetentionDays() * 86400;
            db.delete("log", "date < ?", new String[]{Integer.toString(cutoff)});
            db.execSQL("DELETE FROM log WHERE id IN (SELECT id FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY acc, uid ORDER BY date DESC, id DESC) AS rn FROM log) WHERE rn > " + MAX_ROWS_PER_USER + ")");
            db.execSQL("DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY date DESC, id DESC LIMIT " + MAX_ROWS_TOTAL + ")");
        } catch (Throwable e) {
            // ROW_NUMBER needs SQLite 3.25 (Android 11+); older devices fall back to the total cap only
            try {
                db().execSQL("DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY date DESC, id DESC LIMIT " + MAX_ROWS_TOTAL + ")");
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
    }

    private static SQLiteDatabase db() {
        if (dbHelper == null) {
            dbHelper = new DbHelper(ApplicationLoader.applicationContext);
        }
        return dbHelper.getWritableDatabase();
    }

    private static final class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context context) {
            super(context, "plus_activity_log.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, acc INTEGER NOT NULL, uid INTEGER NOT NULL, date INTEGER NOT NULL, type INTEGER NOT NULL, extra INTEGER NOT NULL DEFAULT 0, approx INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX log_acc_uid_date ON log (acc, uid, date)");
            db.execSQL("CREATE INDEX log_date ON log (date)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
