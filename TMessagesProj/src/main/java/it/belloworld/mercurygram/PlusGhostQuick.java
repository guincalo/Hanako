package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import it.belloworld.mercurygram.ui.MercurygramSettingsActivity;

/**
 * plus f02: quick ghost-mode controls.
 *
 * - Chat header (three dots) "Send read receipt": reads the open chat (or topic / comment thread /
 *   secret chat) once on the server while ghost mode hides read receipts. Uses
 *   {@link PlusGhost#readNow} for plain chats and the same allowReads + markDialogAsRead(readNow)
 *   path for threads and secret chats. Nothing is sent unless the user taps it.
 *   (AyuGram: "Read until" / AyuGhostUtils.markReadOnServer; NagramX: "Mark as read" in chat menu.)
 * - Chat list three-dots menu: "Ghost mode" check item that switches ghost mode for this account
 *   (long-press opens Mercurygram settings).
 * - Chat list action bar: a ghost icon shown while ghost mode is on for this account. Tapping it
 *   opens a small menu (turn off / settings), so a stray tap never turns ghost mode off.
 *   (AyuGram: drawer "Enable/Disable ghost mode" item; icon ported from AyuGram ayu_ghost.xml, GPL-3.)
 *
 * The indicator follows the "plus_ghost" preferences through a change listener, so toggling ghost
 * mode in Mercurygram settings updates it too, without extra hooks.
 */
public final class PlusGhostQuick {

    /** Chat header menu item id (ChatActivity sub item ids are small ints; keep clear of them). */
    public static final int MENU_SEND_READ = 7602;
    /** Chat list action bar item id (never routed through onItemClick, we set our own listener). */
    private static final int INDICATOR_ID = 7603;

    private static final class Indicator {
        final ActionBarMenuItem item;
        final int account;
        float factor = 1f;

        Indicator(ActionBarMenuItem item, int account) {
            this.item = item;
            this.account = account;
        }
    }

    private static final Map<BaseFragment, Indicator> indicators = new WeakHashMap<>();
    /** Strong ref: SharedPreferences only keeps weak references to listeners. */
    private static SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    private PlusGhostQuick() {
    }

    private static boolean readsHidden(int account) {
        return PlusGhost.isEnabled(account) && PlusGhost.isHidden(account, PlusGhost.OPT_READS);
    }

    // ---- chat: send read receipt ----

    /** Whether the chat header menu should offer "Send read receipt" for this chat (checked when the menu is built). */
    public static boolean canSendReadReceipt(ChatActivity f) {
        if (f == null) {
            return false;
        }
        try {
            final int account = f.getCurrentAccount();
            final long dialogId = f.getDialogId();
            if (dialogId == 0 || f.getChatMode() != 0 || !readsHidden(account)) {
                return false;
            }
            if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
                return false; // Saved Messages
            }
            MessagesController mc = MessagesController.getInstance(account);
            if (DialogObject.isEncryptedDialog(dialogId)) {
                TLRPC.EncryptedChat chat = mc.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                return chat instanceof TLRPC.TL_encryptedChat;
            }
            // Reading a whole forum / monoforum would read every topic: only offer it inside a topic.
            if ((mc.isForum(dialogId) || mc.isMonoForum(dialogId)) && f.getThreadId() == 0) {
                return false;
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static CharSequence sendReadReceiptLabel() {
        return LocaleController.getString(R.string.PlusGhostSendReadReceipt);
    }

    /** Header menu click. Sends one read receipt for what is open in {@code f}. */
    public static void sendReadReceipt(ChatActivity f) {
        if (f == null) {
            return;
        }
        boolean sent = false;
        try {
            sent = doSendReadReceipt(f);
        } catch (Exception e) {
            FileLog.e(e);
        }
        BulletinFactory.of(f).createSimpleBulletin(sent ? R.raw.contact_check : R.raw.error,
                LocaleController.getString(sent ? R.string.PlusGhostReadReceiptSent : R.string.PlusGhostReadReceiptNothing)).show();
    }

    private static boolean doSendReadReceipt(ChatActivity f) {
        final int account = f.getCurrentAccount();
        final long dialogId = f.getDialogId();
        final long threadId = f.getThreadId();
        final MessagesController mc = MessagesController.getInstance(account);
        final boolean encrypted = DialogObject.isEncryptedDialog(dialogId);

        // newest loaded message: highest positive id (cloud chats) / lowest negative id (secret chats)
        int maxPositive = 0;
        int minNegative = 0;
        int maxDate = 0;
        ArrayList<MessageObject> messages = f.messages;
        if (messages != null) {
            for (int i = 0, n = messages.size(); i < n; i++) {
                MessageObject m = messages.get(i);
                if (m == null || m.messageOwner == null || m.isSending() || m.isSendError()) {
                    continue;
                }
                int id = m.getId();
                if (id > maxPositive) {
                    maxPositive = id;
                }
                if (id < minNegative) {
                    minNegative = id;
                }
                if (m.messageOwner.date > maxDate) {
                    maxDate = m.messageOwner.date;
                }
            }
        }

        if (!encrypted && threadId == 0) {
            TLRPC.Dialog dialog = mc.dialogs_dict.get(dialogId);
            if (dialog != null && dialog.top_message > 0 && dialog.top_message >= maxPositive) {
                PlusGhost.readNow(account, dialogId); // 0009 path: reads up to the dialog's top message
                return true;
            }
        }

        if (encrypted) {
            if (maxDate <= 0) {
                return false;
            }
            TLRPC.EncryptedChat chat = mc.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (!(chat instanceof TLRPC.TL_encryptedChat)) {
                return false;
            }
            int newest = minNegative != 0 ? minNegative : Integer.MAX_VALUE;
            PlusGhost.allowReads(account, dialogId, PlusGhost.MANUAL_READ_WINDOW_MS);
            // messages.readEncryptedHistory(max_date); intercept() lets it through via readsAllowed()
            mc.markDialogAsRead(dialogId, newest, newest, maxDate, false, 0, 0, true, 0);
            return true;
        }

        if (maxPositive <= 0) {
            return false;
        }
        // Topic / comment thread (messages.readDiscussion), monoforum topic (messages.readSavedHistory),
        // or a plain chat that is not in the loaded dialog list (read history). All carry this dialog's
        // peer, so the scoped allowReads exception lets exactly this request through.
        PlusGhost.allowReads(account, dialogId, PlusGhost.MANUAL_READ_WINDOW_MS);
        mc.markDialogAsRead(dialogId, maxPositive, maxPositive, maxDate, false, threadId, 0, true, 0);
        return true;
    }

    // ---- chat list: ghost toggle in the three-dots menu ----

    /** Adds the "Ghost mode" check item to the chat list's three-dots menu. */
    public static void addMenuToggle(BaseFragment f, ItemOptions io) {
        if (f == null || io == null) {
            return;
        }
        final int account = f.getCurrentAccount();
        final boolean on = PlusGhost.isEnabled(account);
        io.addChecked(on, R.drawable.plus_ghost, LocaleController.getString(R.string.PlusGhostMode),
                () -> setGhost(f, account, !on),
                () -> openSettings(f));
    }

    private static void setGhost(BaseFragment f, int account, boolean on) {
        PlusGhost.setEnabled(account, on); // turning it on goes offline once (PlusGhost.onSettingsChanged)
        refreshIndicators();
        try {
            BulletinFactory.of(f).createSimpleBulletin(R.raw.contact_check,
                    LocaleController.getString(on ? R.string.PlusGhostModeOn : R.string.PlusGhostModeOff)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void openSettings(BaseFragment f) {
        f.presentFragment(new MercurygramSettingsActivity());
    }

    // ---- chat list: indicator ----

    /** Adds the ghost indicator to the chat list action bar (hidden while ghost mode is off). */
    public static void addIndicator(BaseFragment f, ActionBarMenu menu) {
        if (f == null || menu == null) {
            return;
        }
        try {
            final int account = f.getCurrentAccount();
            ActionBarMenuItem item = menu.addItem(INDICATOR_ID, R.drawable.plus_ghost);
            item.setContentDescription(LocaleController.getString(R.string.PlusGhostModeIndicator));
            item.setOnClickListener(v -> showIndicatorMenu(f, v, account));
            item.setOnLongClickListener(v -> {
                openSettings(f);
                return true;
            });
            Indicator ind = new Indicator(item, account);
            synchronized (indicators) {
                indicators.put(f, ind);
            }
            ensurePrefsListener();
            apply(ind);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Called next to the passcode item's visibility update with the same search / action mode /
     * side panel factor, so the indicator hides together with the other action bar icons.
     */
    public static void updateIndicator(BaseFragment f, float factor) {
        Indicator ind;
        synchronized (indicators) {
            ind = indicators.get(f);
        }
        if (ind != null) {
            ind.factor = factor;
            apply(ind);
        }
    }

    private static void apply(Indicator ind) {
        float f = PlusGhost.isEnabled(ind.account) ? ind.factor : 0f;
        FragmentFloatingButton.setAnimatedVisibility(ind.item, f);
    }

    private static void refreshIndicators() {
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<Indicator> list;
            synchronized (indicators) {
                list = new ArrayList<>(indicators.values());
            }
            for (Indicator ind : list) {
                apply(ind);
            }
        });
    }

    private static synchronized void ensurePrefsListener() {
        if (prefsListener != null) {
            return;
        }
        prefsListener = (prefs, key) -> {
            if (key == null || key.startsWith("on_")) {
                refreshIndicators();
            }
        };
        ApplicationLoader.applicationContext.getSharedPreferences("plus_ghost", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private static void showIndicatorMenu(BaseFragment f, View anchor, int account) {
        try {
            ItemOptions io = ItemOptions.makeOptions(f, anchor);
            int color = Theme.getColor(Theme.key_actionBarDefaultTitle, f.getResourceProvider());
            io.setColors(color, color);
            io.addText(LocaleController.getString(R.string.PlusGhostModeIsOn), 13);
            io.addGap();
            io.add(R.drawable.plus_ghost, LocaleController.getString(R.string.PlusGhostTurnOff), () -> setGhost(f, account, false));
            io.add(R.drawable.msg_settings_old, LocaleController.getString(R.string.PlusGhostSettings), () -> openSettings(f));
            io.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
