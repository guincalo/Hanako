package it.belloworld.mercurygram;

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

    /**
     * Display name of a dialog: the user's name (also for a secret chat), or the chat title.
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
            return chat != null && !TextUtils.isEmpty(chat.title) ? chat.title : fallback;
        }
        return user != null ? UserObject.getUserName(user) : fallback;
    }
}
