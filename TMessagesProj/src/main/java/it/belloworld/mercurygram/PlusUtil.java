package it.belloworld.mercurygram;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

/**
 * plus: helpers shared by the Plus* features (added in the integration pass so the
 * features stop carrying private copies of the same code).
 */
public final class PlusUtil {

    private PlusUtil() {
    }

    public static boolean validAccount(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
    }

    // ---- per-account preference keys ----
    //
    // Per-account feature settings (ghost mode, activity log) used to be keyed by the account
    // slot ("on_2"). A slot is not an account: after a restore into another slot, or a logout
    // and a new login, the settings landed on the wrong account. They are now keyed by the
    // Telegram user id ("on_u12345"), and an old slot key is moved over the first time it is
    // read while that slot is signed in.

    /** Pref files whose per-account keys follow {@link #accountKey}. */
    public static boolean isPerAccountFile(String name) {
        return "plus_ghost".equals(name) || "plus_activity_log".equals(name);
    }

    /**
     * Key of a per-account setting: "base_u&lt;userId&gt;", or "base_&lt;slot&gt;" while the slot
     * is not signed in. Moves an old slot key to the user key on first use.
     */
    public static String accountKey(SharedPreferences p, String base, int account) {
        String slotKey = base + "_" + account;
        long uid = validAccount(account) ? UserConfig.getInstance(account).getClientUserId() : 0;
        if (uid == 0) {
            return slotKey;
        }
        String key = base + "_u" + uid;
        if (!p.contains(key) && p.contains(slotKey)) {
            Object v = p.getAll().get(slotKey);
            SharedPreferences.Editor e = p.edit();
            if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
            else if (v instanceof Integer) e.putInt(key, (Integer) v);
            else if (v instanceof Long) e.putLong(key, (Long) v);
            else if (v instanceof String) e.putString(key, (String) v);
            e.remove(slotKey).apply();
        }
        return key;
    }

    /** "…_u&lt;digits&gt;": a key made by {@link #accountKey} for a signed-in account. */
    public static boolean isUserKey(String key) {
        return userOfKey(key) != 0;
    }

    /** The user id at the end of a "…_u&lt;id&gt;" key, or 0. */
    public static long userOfKey(String key) {
        if (key == null) return 0;
        int i = key.lastIndexOf("_u");
        if (i <= 0 || i + 2 >= key.length()) return 0;
        String id = key.substring(i + 2);
        if (!TextUtils.isDigitsOnly(id) || id.length() > 19) return 0;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** An old slot key ("on_3"): ends in "_" and a slot number. */
    public static boolean isSlotKey(String key) {
        return slotOfKey(key) >= 0;
    }

    /** The slot at the end of an old "…_&lt;slot&gt;" key, or -1. */
    public static int slotOfKey(String key) {
        if (key == null) return -1;
        int i = key.lastIndexOf('_');
        if (i <= 0 || i + 1 >= key.length()) return -1;
        String n = key.substring(i + 1);
        if (n.length() > 2 || !TextUtils.isDigitsOnly(n)) return -1;
        int slot = Integer.parseInt(n);
        return validAccount(slot) ? slot : -1;
    }

    /**
     * Display name of a dialog: the user's name (also for a secret chat), or the chat title.
     * Names and titles follow streamer mode (f10).
     * Returns {@code fallback} when the peer is not cached.
     */
    public static String dialogTitle(int account, long dialogId, String fallback) {
        if (!validAccount(account) || dialogId == 0) {
            return fallback;
        }
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.User user;
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat ec = mc.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            user = ec != null ? mc.getUser(ec.user_id) : null;
        } else if (DialogObject.isUserDialog(dialogId)) {
            user = mc.getUser(dialogId);
        } else {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            return chat != null && !TextUtils.isEmpty(chat.title) ? PlusStreamer.chatTitle(chat) : fallback; // plus f10: masked in streamer mode
        }
        return user != null ? UserObject.getUserName(user) : fallback;
    }
}
