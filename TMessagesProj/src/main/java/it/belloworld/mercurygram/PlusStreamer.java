package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.Window;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * plus f10: streamer mode. One global switch that hides, on this device's screen only,
 * other people's names, chat titles, avatars, phone numbers and notification content,
 * so the app can be shown on a stream or a screen share.
 *
 * Everything here is local display logic: nothing is sent to the server, nothing stored
 * by the app (database, drafts, contacts) is changed, so it is ghost-safe and fully
 * reversible. Names are replaced by a stable pseudonym ("User 3F2A", "Chat 9B1C") built
 * from the peer id and a random per-install salt, so the streamer can still tell chats
 * apart while viewers cannot work out who they are.
 *
 * Optional FLAG_SECURE blocks screenshots/recording/casting of the app window entirely
 * (the window shows black in captures), for when the stream should never show Telegram.
 *
 * Hooks (all marked "plus f10"): UserObject, ContactsController.formatName, AvatarDrawable,
 * ImageReceiver.setForUserOrChat, ImageLocation.getForUser/getForChat, DialogCell,
 * ChatActivity, ProfileActivity, SettingsActivity, UserInfoActivity, ProfileSearchCell,
 * ChatMessageCell, NotificationsController, LaunchActivity, DialogsActivity menu.
 */
public final class PlusStreamer {

    private static final String PREFS = "plus_f10";

    public static final int OPT_NAMES = 0;
    public static final int OPT_CHAT_TITLES = 1;
    public static final int OPT_AVATARS = 2;
    public static final int OPT_PHONES = 3;
    public static final int OPT_NOTIFICATIONS = 4;
    public static final int OPT_FLAG_SECURE = 5;
    public static final int OPT_COUNT = 6;

    private static final String[] OPT_KEYS = {"names", "chat_titles", "avatars", "phones", "notifications", "flag_secure"};
    private static final boolean[] OPT_DEFAULTS = {true, true, true, true, true, false};

    private static final String MASKED_USERNAME = "\u2022\u2022\u2022\u2022\u2022\u2022";
    private static final String MASKED_PHONE = "+\u2022\u2022 \u2022\u2022\u2022 \u2022\u2022\u2022 \u2022\u2022\u2022\u2022";

    /** Cached state: these are read on hot drawing paths, so no SharedPreferences lookup there. */
    private static volatile boolean loaded;
    private static volatile boolean enabled;
    private static final boolean[] opts = new boolean[OPT_COUNT];
    private static long salt;

    private static FlagSecureReason flagSecure;
    private static Window flagSecureWindow;

    private PlusStreamer() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (PlusStreamer.class) {
            if (loaded || ApplicationLoader.applicationContext == null) {
                return;
            }
            SharedPreferences p = prefs();
            for (int i = 0; i < OPT_COUNT; i++) {
                opts[i] = p.getBoolean(OPT_KEYS[i], OPT_DEFAULTS[i]);
            }
            salt = p.getLong("salt", 0);
            if (salt == 0) {
                salt = new SecureRandom().nextLong() | 1L;
                p.edit().putLong("salt", salt).apply();
            }
            enabled = p.getBoolean("enabled", false);
            loaded = true;
        }
    }

    // ---- settings ----

    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    public static boolean getOption(int opt) {
        ensureLoaded();
        return opts[opt];
    }

    public static void setOption(int opt, boolean value) {
        ensureLoaded();
        opts[opt] = value;
        prefs().edit().putBoolean(OPT_KEYS[opt], value).apply();
        if (enabled) {
            applyChange(null);
        }
    }

    /** Turns streamer mode on/off and redraws everything right away. */
    public static void setEnabled(boolean value, BaseFragment from) {
        ensureLoaded();
        enabled = value;
        prefs().edit().putBoolean("enabled", value).apply();
        applyChange(from);
    }

    public static void toggle(BaseFragment from) {
        setEnabled(!isEnabled(), from);
    }

    private static boolean active(int opt) {
        ensureLoaded();
        return enabled && opts[opt];
    }

    public static boolean hideNames() {
        return active(OPT_NAMES);
    }

    public static boolean hideChatTitles() {
        return active(OPT_CHAT_TITLES);
    }

    public static boolean hideAvatars() {
        return active(OPT_AVATARS);
    }

    public static boolean hidePhones() {
        return active(OPT_PHONES);
    }

    /** Used by NotificationsController the same way as "app is locked by passcode". */
    public static boolean hideNotifications() {
        return active(OPT_NOTIFICATIONS);
    }

    /** Block screen capture: the app window, and the PhotoViewer window (its own window). */
    public static boolean wantsFlagSecure() {
        return active(OPT_FLAG_SECURE);
    }

    // ---- masking helpers used by the hooks ----

    private static boolean isSelf(TLRPC.User user) {
        if (user == null) {
            return false;
        }
        if (user.self) {
            return true;
        }
        int acc = UserConfig.selectedAccount;
        return acc >= 0 && acc < UserConfig.MAX_ACCOUNT_COUNT && user.id == UserConfig.getInstance(acc).getClientUserId();
    }

    private static String tag(long id) {
        long h = (id ^ salt) * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        return String.format(Locale.US, "%04X", (int) (h & 0xFFFF));
    }

    private static String maskedUser(TLRPC.User user) {
        return LocaleController.formatString(R.string.PlusF10MaskedUser, tag(user.id));
    }

    /** Masked user name, or null when this user's name should be shown. */
    public static String userName(TLRPC.User user) {
        if (user == null || !hideNames() || Boolean.TRUE.equals(bypass.get()) || isSelf(user) || isServiceUser(user)) {
            return null;
        }
        return maskedUser(user);
    }

    private static final ThreadLocal<Boolean> bypass = new ThreadLocal<>();

    /**
     * Unmasked first name, for text that is sent to the server (mention text inserted in the
     * input field). Masking it would send "User 3F2A" to the chat.
     */
    public static String realFirstName(TLRPC.User user) {
        bypass.set(Boolean.TRUE);
        try {
            return UserObject.getFirstName(user, false);
        } finally {
            bypass.remove();
        }
    }

    /** Unmasked full name, for data that is stored rather than shown (f07 vanished-chat log). */
    public static String realUserName(TLRPC.User user) {
        bypass.set(Boolean.TRUE);
        try {
            return UserObject.getUserName(user);
        } finally {
            bypass.remove();
        }
    }

    /**
     * A stored title (a log entry with no live user/chat object) for display, masked by its
     * dialog id the same way live names and titles are; {@code title} when nothing is hidden.
     */
    public static String dialogTitle(long dialogId, String title) {
        if (!hidesDialog(dialogId)) {
            return title;
        }
        return LocaleController.formatString(dialogId > 0 ? R.string.PlusF10MaskedUser : R.string.PlusF10MaskedChat, tag(dialogId));
    }

    /** Are the name, title and username of this (stored) dialog masked right now? */
    public static boolean hidesDialog(long dialogId) {
        return dialogId > 0 ? hideNames() : dialogId < 0 && hideChatTitles();
    }

    /** "@username" for display: masked when the dialog's name is, otherwise as given. */
    public static String username(long dialogId, String username) {
        return hidesDialog(dialogId) ? MASKED_USERNAME : username;
    }

    /** Name for display: masked when needed, otherwise {@code original}. */
    public static String userName(TLRPC.User user, String original) {
        String masked = userName(user);
        return masked != null ? masked : original;
    }

    /**
     * A person's name given as plain text with no peer behind it (hidden forwarder, channel
     * signature): masked by its text, otherwise as given. Used for f13 message shots.
     */
    public static String freeName(String name) {
        if (TextUtils.isEmpty(name) || !hideNames()) {
            return name;
        }
        return LocaleController.formatString(R.string.PlusF10MaskedUser, tag(name.hashCode()));
    }

    private static boolean isServiceUser(TLRPC.User user) {
        // Telegram service accounts and the replies bot are not people.
        return UserObject.isService(user.id) || UserObject.isReplyUser(user.id);
    }

    /** Chat or channel title for display. */
    public static String chatTitle(TLRPC.Chat chat) {
        if (chat == null) {
            return null;
        }
        if (!hideChatTitles()) {
            return chat.title;
        }
        return LocaleController.formatString(R.string.PlusF10MaskedChat, tag(-chat.id));
    }

    /** Formatted phone number for display. */
    public static String phone(String formatted) {
        if (!hidePhones() || TextUtils.isEmpty(formatted)) {
            return formatted;
        }
        return MASKED_PHONE;
    }

    /** true = do not load this user/chat photo; show a blank placeholder instead. */
    public static boolean hideAvatar(TLObject object) {
        if (!hideAvatars()) {
            return false;
        }
        if (object instanceof TLRPC.User) {
            return !isSelf((TLRPC.User) object);
        }
        return object instanceof TLRPC.Chat;
    }

    // ---- FLAG_SECURE ----

    public static void attachWindow(Window window) {
        if (window == null) {
            return;
        }
        if (flagSecure != null) {
            flagSecure.detach();
        }
        flagSecureWindow = window;
        flagSecure = new FlagSecureReason(window, PlusStreamer::wantsFlagSecure);
        flagSecure.attach();
    }

    public static void detachWindow(Window window) {
        if (flagSecure != null && flagSecureWindow == window) {
            flagSecure.detach();
            flagSecure = null;
            flagSecureWindow = null;
        }
    }

    // ---- redraw ----

    private static void applyChange(BaseFragment from) {
        AndroidUtilities.runOnUIThread(() -> {
            if (flagSecure != null) {
                flagSecure.invalidate();
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
                try {
                    // Re-posts the notifications already in the shade, now with or without content.
                    // Local only: no network request.
                    NotificationsController.getInstance(a).showNotifications();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (from != null) {
                INavigationLayout layout = from.getParentLayout();
                if (layout != null) {
                    layout.rebuildAllFragmentViews(false, false);
                }
            }
        });
    }
}
