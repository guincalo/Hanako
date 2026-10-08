package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * plus f01: per-chat ghost exceptions. Ghost mode stays on everywhere except the chats
 * listed here, where the chosen packets behave like a normal client:
 * - FLAG_READS: read receipts for that chat go out (incl. secret-chat read receipts)
 * - FLAG_TYPING: typing / "choosing sticker" / upload actions go out
 * - FLAG_ONLINE: while that chat is open on screen, the normal online status packets go
 *   out (online is account-wide, so this is scoped to "while the chat is open");
 *   one offline packet is sent when the chat is left.
 *
 * Idea and UI shape from NagramX (GPL-3.0):
 *   com/radolyn/ayugram/utils/AyuGhostPreferences.java (read/typing exclusion per chat)
 *   tw/nekomimi/nekogram/menu/ghostmode/GhostModeExclusionPopupWrapper.java (chat menu toggles)
 * Differences: per account, signed dialog ids (NagramX uses Math.abs(chatId) globally, which
 * mixes a user and a group with the same numeric id), an optional "online while open"
 * flag, and a settings list.
 *
 * Nothing is ever sent just because an exception exists; it only stops PlusGhost from
 * dropping packets the client sends on its own. With no exceptions everything is as before.
 */
public final class PlusGhostExceptions {

    public static final int FLAG_READS = 1;
    public static final int FLAG_TYPING = 2;
    public static final int FLAG_ONLINE = 4;
    /** Flags a new exception starts with when added from the chat menu. */
    public static final int DEFAULT_FLAGS = FLAG_READS | FLAG_TYPING;

    private static final String PREFS = "plus_ghost_exceptions";

    /** account -> (dialogId -> flags); loaded lazily. */
    @SuppressWarnings("unchecked")
    private static final ConcurrentHashMap<Long, Integer>[] cache = new ConcurrentHashMap[UserConfig.MAX_ACCOUNT_COUNT];
    /** Dialog whose "online while open" exception is in effect right now (0 = none). */
    private static final long[] onlineChat = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private PlusGhostExceptions() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String prefix(int account) {
        return account + "_";
    }

    private static ConcurrentHashMap<Long, Integer> map(int account) {
        ConcurrentHashMap<Long, Integer> m = cache[account];
        if (m == null) {
            synchronized (PlusGhostExceptions.class) {
                m = cache[account];
                if (m == null) {
                    m = new ConcurrentHashMap<>();
                    try {
                        String p = prefix(account);
                        for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                            String k = e.getKey();
                            if (k.startsWith(p) && e.getValue() instanceof Integer) {
                                try {
                                    long did = Long.parseLong(k.substring(p.length()));
                                    int flags = (Integer) e.getValue();
                                    if (did != 0 && flags != 0) {
                                        m.put(did, flags);
                                    }
                                } catch (NumberFormatException ignore) {
                                }
                            }
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    cache[account] = m;
                }
            }
        }
        return m;
    }

    // ---- storage ----

    public static int getFlags(int account, long dialogId) {
        if (!PlusUtil.validAccount(account) || dialogId == 0) {
            return 0;
        }
        Integer f = map(account).get(dialogId);
        return f != null ? f : 0;
    }

    public static boolean has(int account, long dialogId, int flag) {
        return (getFlags(account, dialogId) & flag) != 0;
    }

    public static void setFlags(int account, long dialogId, int flags) {
        if (!PlusUtil.validAccount(account) || dialogId == 0) {
            return;
        }
        boolean hadOnline = has(account, dialogId, FLAG_ONLINE);
        if (flags == 0) {
            map(account).remove(dialogId);
            prefs().edit().remove(prefix(account) + dialogId).apply();
        } else {
            map(account).put(dialogId, flags);
            prefs().edit().putInt(prefix(account) + dialogId, flags).apply();
        }
        boolean hasOnline = (flags & FLAG_ONLINE) != 0;
        if (hadOnline != hasOnline && openChat[account] == dialogId) {
            // the chat is open right now: apply the online change immediately
            if (hasOnline) {
                onChatResumed(account, dialogId);
            } else {
                stopOnline(account);
            }
        }
    }

    /** Exception dialog ids of this account (unordered snapshot). */
    public static ArrayList<Long> list(int account) {
        ArrayList<Long> out = new ArrayList<>();
        if (PlusUtil.validAccount(account)) {
            out.addAll(map(account).keySet());
        }
        return out;
    }

    public static int count(int account) {
        return PlusUtil.validAccount(account) ? map(account).size() : 0;
    }

    // ---- PlusGhost hooks (any thread) ----

    /** Read receipts for this dialog go out even though ghost hides reads. */
    public static boolean readsExcepted(int account, long dialogId) {
        return has(account, dialogId, FLAG_READS);
    }

    /** Typing actions for this dialog go out even though ghost hides typing. */
    public static boolean typingExcepted(int account, long dialogId) {
        return has(account, dialogId, FLAG_TYPING);
    }

    /** Status packets pass because an "online while open" chat is on screen. */
    public static boolean onlineExceptedNow(int account) {
        return PlusUtil.validAccount(account) && onlineChat[account] != 0;
    }

    /** plus f03: this dialog is the "online while open" chat on screen right now. */
    public static boolean onlineExceptedNow(int account, long dialogId) {
        return PlusUtil.validAccount(account) && dialogId != 0 && onlineChat[account] == dialogId;
    }

    // ---- ChatActivity hooks (UI thread) ----

    /** Dialog of the chat currently resumed on screen, per account (0 = none). */
    private static final long[] openChat = new long[UserConfig.MAX_ACCOUNT_COUNT];

    public static void onChatResumed(int account, long dialogId) {
        if (!PlusUtil.validAccount(account) || dialogId == 0) {
            return;
        }
        openChat[account] = dialogId;
        if (!has(account, dialogId, FLAG_ONLINE)
                || !PlusGhost.isEnabled(account) || !PlusGhost.isHidden(account, PlusGhost.OPT_ONLINE)) {
            return;
        }
        boolean wasOnline = onlineChat[account] != 0;
        onlineChat[account] = dialogId;
        if (!wasOnline) {
            // The status timer re-sends online only every 55 s; announce online now, like a
            // normal client coming to the foreground. Passes intercept() because onlineChat is set.
            try {
                TL_account.updateStatus req = new TL_account.updateStatus();
                req.offline = false;
                ConnectionsManager.getInstance(account).sendRequest(req, null);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public static void onChatPaused(int account, long dialogId) {
        if (!PlusUtil.validAccount(account) || dialogId == 0) {
            return;
        }
        if (openChat[account] == dialogId) {
            openChat[account] = 0;
        }
        if (onlineChat[account] == dialogId) {
            stopOnline(account);
        }
    }

    private static void stopOnline(int account) {
        if (onlineChat[account] == 0) {
            return;
        }
        onlineChat[account] = 0;
        PlusGhost.goOfflineNow(account); // one offline=true, only while Stay offline is in effect
    }

    // ---- UI ----

    /** Chat title for lists ("Deleted account"-safe); secret chats get a lock prefix. */
    public static String dialogTitle(int account, long dialogId) {
        String fallback = String.valueOf(dialogId);
        String title = PlusUtil.dialogTitle(account, dialogId, fallback);
        return DialogObject.isEncryptedDialog(dialogId) && !fallback.equals(title) ? "\uD83D\uDD12 " + title : title;
    }

    /** "Read receipts, typing" style summary of an exception. */
    public static String flagsSummary(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & FLAG_READS) != 0) {
            sb.append(LocaleController.getString(R.string.PlusGhostExcSummaryReads));
        }
        if ((flags & FLAG_TYPING) != 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(LocaleController.getString(R.string.PlusGhostExcSummaryTyping));
        }
        if ((flags & FLAG_ONLINE) != 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(LocaleController.getString(R.string.PlusGhostExcSummaryOnline));
        }
        return sb.toString();
    }

    /**
     * Per-chat dialog: three check boxes (send reads / send typing / online while open).
     * A chat without an exception starts with {@link #DEFAULT_FLAGS} pre-ticked; nothing is
     * saved until Save. Unticking everything removes the exception.
     */
    public static void showDialog(BaseFragment fragment, long dialogId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final Context context = fragment.getParentActivity();
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final int current = getFlags(account, dialogId);
        final int initial = current != 0 ? current : DEFAULT_FLAGS;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final int[] flagOf = {FLAG_READS, FLAG_TYPING, FLAG_ONLINE};
        final int[] labels = {R.string.PlusGhostExcSendReads, R.string.PlusGhostExcSendTyping, R.string.PlusGhostExcOnlineWhileOpen};
        final CheckBoxCell[] cells = new CheckBoxCell[flagOf.length];
        for (int i = 0; i < flagOf.length; i++) {
            final CheckBoxCell cell = new CheckBoxCell(context, 1, rp);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setText(LocaleController.getString(labels[i]), "", (initial & flagOf[i]) != 0, false);
            cell.setPadding(LocaleController.isRTL ? AndroidUtilities.dp(16) : AndroidUtilities.dp(8), 0, LocaleController.isRTL ? AndroidUtilities.dp(8) : AndroidUtilities.dp(16), 0);
            cell.setOnClickListener(v -> cell.setChecked(!cell.isChecked(), true));
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            cells[i] = cell;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, rp);
        builder.setTitle(LocaleController.getString(R.string.PlusGhostExcDialogTitle));
        builder.setMessage(LocaleController.getString(PlusGhost.isEnabled(account)
                ? R.string.PlusGhostExcDialogInfo : R.string.PlusGhostExcDialogInfoGhostOff));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            int flags = 0;
            for (int i = 0; i < cells.length; i++) {
                if (cells[i].isChecked()) {
                    flags |= flagOf[i];
                }
            }
            setFlags(account, dialogId, flags);
            if (onChanged != null) {
                onChanged.run();
            }
        });
        if (current != 0) {
            builder.setNeutralButton(LocaleController.getString(R.string.PlusGhostExcRemove), (dialog, which) -> {
                setFlags(account, dialogId, 0);
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }
}
