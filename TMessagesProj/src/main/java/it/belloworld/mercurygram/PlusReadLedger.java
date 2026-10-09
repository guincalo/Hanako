package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

/**
 * hanako: remembers how far a chat was read locally while ghost mode hid the read receipt.
 * The server never learns about such a read, so later syncs (channelDifferenceTooLong,
 * resetDialogs, dialog pages, topic loads, mention recounts) bring the old unread counts back.
 * Every server-side dialog/topic passes through {@link #clamp}/{@link #clampTopic} before it
 * reaches memory or the DB. Keyed by Telegram user id, never by account slot. An entry goes
 * away once the server has caught up (read on another device, ghost off, manual mark as read).
 */
public final class PlusReadLedger {

    private PlusReadLedger() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_read_ledger", Context.MODE_PRIVATE);
    }

    private static String key(int account, long dialogId, long topicId) {
        if (!PlusUtil.validAccount(account) || dialogId == 0) {
            return null;
        }
        long uid = UserConfig.getInstance(account).getClientUserId();
        if (uid == 0) {
            return null;
        }
        return "u" + uid + "_" + dialogId + (topicId != 0 ? "_t" + topicId : "");
    }

    /** Called from markDialogAsRead: remember a read the server will not hear about. */
    public static void record(int account, long dialogId, long topicId, int maxId) {
        if (maxId <= 0 || maxId == Integer.MAX_VALUE || DialogObject.isEncryptedDialog(dialogId)
                || !PlusGhost.readsHidden(account, dialogId)) {
            return;
        }
        String k = key(account, dialogId, topicId);
        if (k == null) {
            return;
        }
        SharedPreferences p = prefs();
        if (maxId > p.getInt(k, 0)) {
            p.edit().putInt(k, maxId).apply();
        }
    }

    /** Highest locally read inbox id that the server does not know about, or 0. */
    public static int get(int account, long dialogId, long topicId) {
        String k = key(account, dialogId, topicId);
        return k == null ? 0 : prefs().getInt(k, 0);
    }

    private static void forget(int account, long dialogId, long topicId) {
        String k = key(account, dialogId, topicId);
        if (k != null) {
            prefs().edit().remove(k).apply();
        }
    }

    /** Pull a server dialog up to the local read point. */
    public static void clamp(int account, TLRPC.Dialog d) {
        if (d == null || d.id == 0) {
            return;
        }
        int local = get(account, d.id, 0);
        if (local == 0) {
            return;
        }
        if (d.read_inbox_max_id >= local) {
            forget(account, d.id, 0); // the server caught up, it wins from now on
            return;
        }
        d.read_inbox_max_id = local;
        if (d.top_message <= local) {
            d.unread_count = 0;
            d.unread_mentions_count = 0;
        } else if (DialogObject.isChannel(d)) {
            d.unread_count = Math.min(d.unread_count, d.top_message - local); // channel ids are sequential
        }
    }

    /** Same as {@link #clamp} for a forum topic. dialogId is the forum's dialog id (negative). */
    public static void clampTopic(int account, long dialogId, TLRPC.TL_forumTopic t) {
        if (t == null) {
            return;
        }
        int local = get(account, dialogId, t.id);
        if (local == 0) {
            return;
        }
        if (t.read_inbox_max_id >= local) {
            forget(account, dialogId, t.id);
            return;
        }
        t.read_inbox_max_id = local;
        if (t.top_message <= local) {
            t.unread_count = 0;
            t.unread_mentions_count = 0;
        } else {
            t.unread_count = Math.min(t.unread_count, t.top_message - local);
        }
    }
}
