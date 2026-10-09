package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.WeakHashMap;

/**
 * plus f12: peek a chat without opening it (Telegram X style).
 *
 * Upstream already has a chat preview (long-press the avatar in the chat list, ChatActivity in
 * preview mode), and its main read path skips preview mode. It is not airtight though: unread
 * reactions and poll votes are cleared, channel views are counted (getMessagesViews increment)
 * and a few side paths can still read. While the guard is on, a peeked chat is left exactly as it
 * was, whether or not ghost mode is on:
 * - ChatActivity skips the local reaction / poll-vote "seen" checks while in preview mode, so the
 *   badges stay (hook in ChatActivity, see {@link #frozen}).
 * - Network safety net ({@link #intercept}, hooked into ConnectionsManager.sendRequestInternal
 *   before ghost mode): read-type requests for a peeked dialog are completed locally with a fake
 *   result and never sent; getMessagesViews for it goes out with increment=false. This stays in
 *   force for {@link #LINGER_MS} after the peek closes, because the views queue is flushed on a
 *   5 s timer.
 * - An explicit "Mark as read" from the peek menu still works (it opens PlusGhost's manual-read
 *   window for that chat, which this guard honours).
 * Expanding the peek into the full chat (tap it) ends the peek right away: from then on it is a
 * normal open and the usual (ghost) rules apply.
 *
 * Nothing extra is ever sent: the guard only drops or downgrades requests the client was about
 * to send anyway.
 *
 * Optional: "Long-press anywhere on a chat to peek" swaps the long-press targets in the chat
 * list (row = peek, avatar = select).
 */
public final class PlusPeek {

    /** Settings row ids (MercurygramSettingsActivity, plus f12 block): 1200..1299. */
    public static final int ID_GUARD = 1200;
    public static final int ID_LONG_PRESS = 1201;

    /** Longer than MessagesController's 5 s views flush interval. */
    private static final long LINGER_MS = 6500;

    /** live peek fragment -> {account, dialogId} */
    private static final WeakHashMap<Object, long[]> active = new WeakHashMap<>();
    /** recently closed peeks: {account, dialogId, elapsedRealtime deadline} */
    private static final ArrayList<long[]> lingering = new ArrayList<>();

    private PlusPeek() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_f12", Context.MODE_PRIVATE);
    }

    /** Default on: a peek never reads anything. */
    public static boolean isGuardOn() {
        return prefs().getBoolean("guard", true);
    }

    /** Default off: upstream behaviour (long-press the avatar to peek, the row to select). */
    public static boolean isLongPressAnywhere() {
        return prefs().getBoolean("long_press_anywhere", false);
    }

    // ---- hooks ----

    /** DialogsActivity.onItemLongClick: does this long-press open a peek? */
    public static boolean wantsPeek(boolean onAvatar) {
        return isLongPressAnywhere() != onAvatar;
    }

    /** ChatActivity: true while this preview must not change any read state locally. */
    public static boolean frozen(boolean inPreviewMode) {
        return inPreviewMode && isGuardOn();
    }

    /** ChatActivity.setInPreviewMode (after super). */
    public static void onPreviewMode(Object fragment, int account, long dialogId, boolean preview) {
        if (preview) HanakoTelemetry.count(HanakoTelemetry.PEEK); // hanako: usage statistics (off by default)
        if (fragment == null) {
            return;
        }
        synchronized (PlusPeek.class) {
            if (preview && dialogId != 0 && PlusUtil.validAccount(account)) {
                active.put(fragment, new long[]{account, dialogId});
            } else {
                // expanded into the full chat (or never a peek): a real open, no linger
                active.remove(fragment);
            }
        }
    }

    /** ChatActivity.onFragmentDestroy: the peek closed without being opened. */
    public static void onDestroy(Object fragment) {
        synchronized (PlusPeek.class) {
            long[] p = active.remove(fragment);
            if (p != null) {
                lingering.add(new long[]{p[0], p[1], SystemClock.elapsedRealtime() + LINGER_MS});
            }
        }
    }

    /** dialogId == 0: is any peek of this account active or lingering? */
    private static boolean isPeeked(int account, long dialogId) {
        synchronized (PlusPeek.class) {
            for (long[] p : active.values()) {
                if (p[0] == account && (dialogId == 0 || p[1] == dialogId)) {
                    return true;
                }
            }
            if (lingering.isEmpty()) {
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            boolean hit = false;
            for (Iterator<long[]> it = lingering.iterator(); it.hasNext(); ) {
                long[] p = it.next();
                if (now >= p[2]) {
                    it.remove();
                } else if (p[0] == account && (dialogId == 0 || p[1] == dialogId)) {
                    hit = true;
                }
            }
            return hit;
        }
    }

    private static boolean anyPeek() {
        synchronized (PlusPeek.class) {
            return !active.isEmpty() || !lingering.isEmpty();
        }
    }


    /**
     * Dialog of a read-type request, 0 when it is not one, -1 for messages.readMessageContents
     * (which carries message ids only).
     */
    private static long readDialogId(TLObject o) {
        if (o instanceof TLRPC.TL_messages_readHistory) {
            return PlusGhost.peerDialogId(((TLRPC.TL_messages_readHistory) o).peer);
        } else if (o instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.InputChannel ch = ((TLRPC.TL_channels_readHistory) o).channel;
            return ch != null ? -ch.channel_id : 0;
        } else if (o instanceof TLRPC.TL_messages_readDiscussion) {
            return PlusGhost.peerDialogId(((TLRPC.TL_messages_readDiscussion) o).peer);
        } else if (o instanceof TLRPC.TL_channels_readMessageContents) {
            TLRPC.InputChannel ch = ((TLRPC.TL_channels_readMessageContents) o).channel;
            return ch != null ? -ch.channel_id : 0;
        } else if (o instanceof TLRPC.TL_messages_readSavedHistory) {
            return PlusGhost.peerDialogId(((TLRPC.TL_messages_readSavedHistory) o).parent_peer);
        } else if (o instanceof TLRPC.TL_messages_readMentions) {
            return PlusGhost.peerDialogId(((TLRPC.TL_messages_readMentions) o).peer);
        } else if (o instanceof TLRPC.TL_messages_readReactions) {
            return PlusGhost.peerDialogId(((TLRPC.TL_messages_readReactions) o).peer);
        } else if (o instanceof TLRPC.TL_messages_readPollVotes) {
            return PlusGhost.peerDialogId(((TLRPC.TL_messages_readPollVotes) o).peer);
        } else if (o instanceof TLRPC.TL_messages_readMessageContents) {
            return -1;
        }
        return 0;
    }

    /**
     * Hook from ConnectionsManager.sendRequestInternal, before ghost mode.
     * @return true when the request must not be sent (its callback gets a fake empty result).
     */
    public static boolean intercept(int account, TLObject o, RequestDelegate onComplete) {
        if (o == null || !PlusUtil.validAccount(account) || !anyPeek() || !isGuardOn()) {
            return false;
        }
        if (o instanceof TLRPC.TL_messages_getMessagesViews) {
            TLRPC.TL_messages_getMessagesViews req = (TLRPC.TL_messages_getMessagesViews) o;
            if (req.increment && isPeeked(account, PlusGhost.peerDialogId(req.peer))) {
                req.increment = false; // still fetch the counts, just don't count us
            }
            return false;
        }
        long dialogId = readDialogId(o);
        if (dialogId == 0) {
            return false;
        }
        if (dialogId == -1) {
            // ids only; while a peek is up it is the only thing that can be reading
            if (!isPeeked(account, 0)) {
                return false;
            }
        } else if (!isPeeked(account, dialogId) || PlusGhost.isReadAllowed(account, dialogId)) {
            return false; // not peeked, or an explicit "Mark as read"
        }
        if (onComplete != null) {
            final TLObject res = PlusGhost.fakeResult(o);
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    onComplete.run(res, null);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        }
        return true;
    }


    // ---- settings (MercurygramSettingsActivity) ----

    public static void addSettingsItems(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF12Header)));
        items.add(UItem.asCheck(ID_GUARD, LocaleController.getString(R.string.PlusF12Guard)).setChecked(isGuardOn()));
        items.add(UItem.asCheck(ID_LONG_PRESS, LocaleController.getString(R.string.PlusF12LongPressAnywhere)).setChecked(isLongPressAnywhere()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF12About)));
    }

    /** @return true when the click was a plus f12 row. */
    public static boolean onSettingsClick(int id, Runnable refresh) {
        if (id == ID_GUARD) {
            prefs().edit().putBoolean("guard", !isGuardOn()).apply();
        } else if (id == ID_LONG_PRESS) {
            prefs().edit().putBoolean("long_press_anywhere", !isLongPressAnywhere()).apply();
        } else {
            return false;
        }
        if (refresh != null) {
            refresh.run();
        }
        return true;
    }
}
