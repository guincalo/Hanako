package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * plus f09: "Send without sound while ghost mode is on" (per account, default off).
 *
 * When the option is on and this account's ghost mode is on, every outgoing message request
 * gets its silent flag set right before it is serialized, so recipients get the message
 * without a notification sound. Hooked once in ConnectionsManager.sendRequestInternal, which
 * covers text, media, albums, forwards, inline bot results and secret chat messages from every
 * send path (chat input, share sheet, forwards, scheduled messages, resends) without touching
 * the UI code. It only flips a flag on a request that is being sent anyway: no extra requests.
 */
public final class PlusGhostSilent {

    private PlusGhostSilent() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_f09", Context.MODE_PRIVATE);
    }

    /** The setting itself (default off). */
    public static boolean isEnabled(int account) {
        return PlusUtil.validAccount(account) && prefs().getBoolean("silent_in_ghost_" + account, false);
    }

    public static void setEnabled(int account, boolean value) {
        if (PlusUtil.validAccount(account)) {
            prefs().edit().putBoolean("silent_in_ghost_" + account, value).apply();
        }
    }

    /** true when outgoing messages of this account are sent silently right now. */
    public static boolean isActive(int account) {
        return isEnabled(account) && PlusGhost.isEnabled(account);
    }

    /** Hook (ConnectionsManager, before serialization): set the silent flag on message sends. */
    public static void apply(int account, TLObject o) {
        if (o == null || !isActive(account)) {
            return;
        }
        if (o instanceof TLRPC.TL_messages_sendMessage) {
            ((TLRPC.TL_messages_sendMessage) o).silent = true;
        } else if (o instanceof TLRPC.TL_messages_sendMedia) {
            ((TLRPC.TL_messages_sendMedia) o).silent = true;
        } else if (o instanceof TLRPC.TL_messages_sendMultiMedia) {
            ((TLRPC.TL_messages_sendMultiMedia) o).silent = true;
        } else if (o instanceof TLRPC.TL_messages_forwardMessages) {
            ((TLRPC.TL_messages_forwardMessages) o).silent = true;
        } else if (o instanceof TLRPC.TL_messages_sendInlineBotResult) {
            ((TLRPC.TL_messages_sendInlineBotResult) o).silent = true;
        } else if (o instanceof TLRPC.TL_messages_sendEncrypted) {
            ((TLRPC.TL_messages_sendEncrypted) o).silent = true;
        } else if (o instanceof TLRPC.TL_messages_sendEncryptedFile) {
            ((TLRPC.TL_messages_sendEncryptedFile) o).silent = true;
        }
    }
}
