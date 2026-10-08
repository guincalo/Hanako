package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_stories;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * plus: per-account ghost mode. Hooked into ConnectionsManager.sendRequestInternal,
 * so every outgoing request passes through {@link #intercept} and {@link #wrapCompletion}.
 *
 * Dropped requests are completed locally with a fake, empty success result (the same idea
 * AyuGram/NagramX use), so client bookkeeping that waits for the callback (pending
 * readMessageContents tasks, typing state, status timer) still runs and does not pile up.
 *
 * "Stay offline": account.updateStatus is never sent by the normal status timer, neither
 * online nor offline (0009: no offline packet at app start either, because offline=true
 * stamps "last seen = now"; AyuGram Desktop with sendOnlinePackets=false sends nothing too).
 * The only status packets that go out are the ones PlusGhost builds itself (marked in
 * {@link #ownStatusRequests}): one offline=true when Stay offline is switched on, and one
 * after each request that makes the server show us online (sending, forwarding, reacting,
 * voting, editing, calls, ...). Optionally (OPT_FORCE_OFFLINE) also when the server reports
 * our own account online while this device is in use.
 */
public final class PlusGhost {

    public static final int OPT_READS = 0;
    public static final int OPT_TYPING = 1;
    public static final int OPT_ONLINE = 2;
    public static final int OPT_STORIES = 3;
    /** 0009: after sending/reacting/voting in a chat, send a read receipt for that chat (AyuGram "Read on interact"). */
    public static final int OPT_READ_ON_SEND = 4;
    /** 0009: when the server reports this account online while this device is in use, push offline again. */
    public static final int OPT_FORCE_OFFLINE = 5;
    public static final int OPT_COUNT = 6;

    /** How long a manual "mark as read" lets read receipts of that one chat through. */
    public static final long MANUAL_READ_WINDOW_MS = 10000;
    private static final long OFFLINE_DELAY_MS = 1500;

    private static final long FORCE_OFFLINE_MIN_INTERVAL_MS = 15000;
    private static final long RECENT_ACTIVITY_MS = 30000;

    private static final String[] OPT_KEYS = {"reads", "typing", "online", "stories", "read_on_send", "force_offline"};
    private static final boolean[] OPT_DEFAULTS = {true, true, true, true, true, false};

    /** account -> (dialogId -> elapsedRealtime deadline) for manual mark-as-read. */
    @SuppressWarnings("unchecked")
    private static final ConcurrentHashMap<Long, Long>[] allowedReads = new ConcurrentHashMap[UserConfig.MAX_ACCOUNT_COUNT];
    /**
     * updateStatus objects built by PlusGhost itself; only these pass while Stay offline is on.
     * (0008 used a per-account "next packet may pass" flag instead. That flag was raised when the
     * offline was scheduled, so a timer packet sent in the 1.5-6 s gap used it up before the send
     * reached the server, and the real offline was then skipped, leaving us online.)
     */
    private static final Set<TLObject> ownStatusRequests = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Runnable[] offlineRunnables = new Runnable[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] lastActivityAt = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] lastForcedOfflineAt = new long[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            allowedReads[i] = new ConcurrentHashMap<>();
        }
    }

    private PlusGhost() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_ghost", Context.MODE_PRIVATE);
    }

    private static boolean validAccount(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
    }

    // Defaults are intentionally unchanged (everything on for every account); see 0004/0005.
    public static boolean isEnabled(int account) {
        return prefs().getBoolean("on_" + account, true);
    }

    public static void setEnabled(int account, boolean value) {
        prefs().edit().putBoolean("on_" + account, value).apply();
        onSettingsChanged(account);
    }

    /** true = this kind of packet is hidden (not sent) while ghost mode is on. */
    public static boolean isHidden(int account, int opt) {
        return prefs().getBoolean(OPT_KEYS[opt] + "_" + account, OPT_DEFAULTS[opt]);
    }

    public static void setHidden(int account, int opt, boolean value) {
        prefs().edit().putBoolean(OPT_KEYS[opt] + "_" + account, value).apply();
        if (opt == OPT_ONLINE) {
            onSettingsChanged(account);
        }
    }

    private static boolean active(int account, int opt) {
        return isEnabled(account) && isHidden(account, opt);
    }

    /** After a toggle: if "Stay offline" is now in effect, go offline once right away. */
    private static void onSettingsChanged(int account) {
        if (validAccount(account) && active(account, OPT_ONLINE)) {
            scheduleOffline(account, 0);
        }
    }

    // ---- manual read exception (scoped to one dialog) ----

    /** Let read receipts for this one dialog through for {@code ms} (manual "mark as read"). */
    public static void allowReads(int account, long dialogId, long ms) {
        if (validAccount(account) && dialogId != 0) {
            allowedReads[account].put(dialogId, SystemClock.elapsedRealtime() + ms);
        }
    }

    private static boolean readsAllowed(int account, long dialogId) {
        if (!validAccount(account) || dialogId == 0) {
            return false;
        }
        Long until = allowedReads[account].get(dialogId);
        if (until == null) {
            return false;
        }
        if (SystemClock.elapsedRealtime() >= until) {
            allowedReads[account].remove(dialogId);
            return false;
        }
        return true;
    }

    /** Called by SecretChatHelper before it sends decryptedMessageActionReadMessages. */
    public static boolean blockSecretRead(int account, long encryptedDialogId) {
        return active(account, OPT_READS) && !readsAllowed(account, encryptedDialogId);
    }

    private static long peerDialogId(TLRPC.InputPeer peer) {
        if (peer == null) {
            return 0;
        }
        if (peer.channel_id != 0) {
            return -peer.channel_id;
        }
        if (peer.chat_id != 0) {
            return -peer.chat_id;
        }
        return peer.user_id;
    }

    /** Dialog a read request belongs to, or 0 when the request carries no peer. */
    private static long readDialogId(TLObject o) {
        if (o instanceof TLRPC.TL_messages_readHistory) {
            return peerDialogId(((TLRPC.TL_messages_readHistory) o).peer);
        } else if (o instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.InputChannel ch = ((TLRPC.TL_channels_readHistory) o).channel;
            return ch != null ? -ch.channel_id : 0;
        } else if (o instanceof TLRPC.TL_messages_readDiscussion) {
            return peerDialogId(((TLRPC.TL_messages_readDiscussion) o).peer);
        } else if (o instanceof TLRPC.TL_channels_readMessageContents) {
            TLRPC.InputChannel ch = ((TLRPC.TL_channels_readMessageContents) o).channel;
            return ch != null ? -ch.channel_id : 0;
        } else if (o instanceof TLRPC.TL_messages_readEncryptedHistory) {
            TLRPC.TL_inputEncryptedChat p = ((TLRPC.TL_messages_readEncryptedHistory) o).peer;
            return p != null ? DialogObject.makeEncryptedDialogId(p.chat_id) : 0;
        } else if (o instanceof TLRPC.TL_messages_readSavedHistory) {
            // monoforum (channel direct messages) topic; the dialog is the parent channel
            return peerDialogId(((TLRPC.TL_messages_readSavedHistory) o).parent_peer);
        }
        return 0; // messages.readMessageContents has ids only
    }

    private static boolean isReadRequest(TLObject o) {
        return o instanceof TLRPC.TL_messages_readHistory
                || o instanceof TLRPC.TL_channels_readHistory
                || o instanceof TLRPC.TL_messages_readDiscussion
                || o instanceof TLRPC.TL_messages_readMessageContents
                || o instanceof TLRPC.TL_channels_readMessageContents
                || o instanceof TLRPC.TL_messages_readEncryptedHistory
                || o instanceof TLRPC.TL_messages_readSavedHistory;
    }

    /** Requests after which the server shows us online (Stay offline re-sends offline after them). */
    private static boolean isActivityRequest(TLObject o) {
        return o instanceof TLRPC.TL_messages_sendMessage
                || o instanceof TLRPC.TL_messages_sendMedia
                || o instanceof TLRPC.TL_messages_sendMultiMedia
                || o instanceof TLRPC.TL_messages_forwardMessages
                || o instanceof TLRPC.TL_messages_forwardMessage
                || o instanceof TLRPC.TL_messages_sendInlineBotResult
                || o instanceof TLRPC.TL_messages_sendReaction
                || o instanceof TLRPC.TL_messages_sendPaidReaction
                || o instanceof TLRPC.TL_messages_sendVote
                || o instanceof TLRPC.TL_messages_editMessage
                || o instanceof TLRPC.TL_messages_sendScreenshotNotification
                || o instanceof TLRPC.TL_messages_sendBotRequestedPeer
                || o instanceof TLRPC.TL_messages_sendWebViewData
                || o instanceof TLRPC.TL_messages_sendQuickReplyMessages
                || o instanceof TLRPC.TL_messages_startBot
                || o instanceof TLRPC.TL_messages_importChatInvite
                || o instanceof TLRPC.TL_channels_joinChannel
                || o instanceof TLRPC.TL_messages_sendEncrypted
                || o instanceof TLRPC.TL_messages_sendEncryptedFile
                || o instanceof TLRPC.TL_messages_sendEncryptedMultiMedia
                // 0009: sendEncryptedService removed. It is mostly automatic (layer notify, rekeying,
                // resend requests), and every extra offline packet stamps a fresh "last seen".
                || o instanceof TL_phone.requestCall
                || o instanceof TL_phone.acceptCall
                || o instanceof TL_phone.confirmCall
                || o instanceof TL_stories.TL_stories_sendStory
                || o instanceof TL_stories.TL_stories_sendReaction;
    }

    // ---- fake completion for dropped requests ----

    private static TLObject fakeResult(TLObject o) {
        if (o instanceof TLRPC.TL_messages_readHistory || o instanceof TLRPC.TL_messages_readMessageContents) {
            // real return type is messages.affectedMessages; pts=-1 makes processNewDifferenceParams a no-op
            TLRPC.TL_messages_affectedMessages res = new TLRPC.TL_messages_affectedMessages();
            res.pts = -1;
            res.pts_count = 0;
            return res;
        }
        // channels.readHistory, channels.readMessageContents, messages.readDiscussion,
        // messages.readEncryptedHistory, messages.readSavedHistory, setTyping, setEncryptedTyping, incrementStoryViews,
        // account.updateStatus all return Bool. (stories.readStories returns Vector<int>, but its
        // only caller, StoriesController, passes a null callback, so nothing ever sees this.)
        return new TLRPC.TL_boolTrue();
    }

    private static boolean drop(TLObject o, RequestDelegate onComplete) {
        if (onComplete != null) {
            final TLObject res = fakeResult(o);
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

    // ---- hooks ----

    /** @return true when the request must be dropped (its callback is completed with a fake result). */
    public static boolean intercept(int account, TLObject o, RequestDelegate onComplete) {
        if (o == null || !validAccount(account) || !isEnabled(account)) {
            return false;
        }
        if (o instanceof TL_account.updateStatus) {
            if (!isHidden(account, OPT_ONLINE)) {
                return false;
            }
            if (ownStatusRequests.remove(o)) {
                ((TL_account.updateStatus) o).offline = true; // one of our own offline packets
                return false;
            }
            return drop(o, onComplete); // no online packets, no periodic last-seen refresh
        }
        if (isHidden(account, OPT_TYPING)
                && (o instanceof TLRPC.TL_messages_setTyping || o instanceof TLRPC.TL_messages_setEncryptedTyping)) {
            return drop(o, onComplete);
        }
        if (isHidden(account, OPT_READS) && isReadRequest(o) && !readsAllowed(account, readDialogId(o))) {
            return drop(o, onComplete);
        }
        if (isHidden(account, OPT_READS) && o instanceof TLRPC.TL_messages_getMessagesViews) {
            // 0009: still fetch view counts, but don't count our view (AyuGram Desktop api_views.cpp does the same)
            ((TLRPC.TL_messages_getMessagesViews) o).increment = false;
            return false;
        }
        if (isHidden(account, OPT_STORIES)
                && (o instanceof TL_stories.TL_stories_readStories || o instanceof TL_stories.TL_stories_incrementStoryViews)) {
            return drop(o, onComplete);
        }
        return false;
    }

    /**
     * Runs after write requests:
     * - Stay offline: send one offline=true (debounced) after the server has processed a request
     *   that shows us online.
     * - Read on send (OPT_READ_ON_SEND): after a successful send/reaction/vote into a chat while
     *   read receipts are hidden, read that chat once, the way a normal client does when you reply.
     *   Replying while your messages stay unread is the easiest ghost-mode tell.
     */
    public static RequestDelegate wrapCompletion(int account, TLObject o, RequestDelegate onComplete) {
        if (o == null || !validAccount(account) || !isEnabled(account)) {
            return onComplete;
        }
        final boolean goOffline = isHidden(account, OPT_ONLINE) && isActivityRequest(o);
        final long readDialog = (isHidden(account, OPT_READS) && isHidden(account, OPT_READ_ON_SEND)) ? interactionDialogId(o) : 0;
        if (!goOffline && readDialog == 0) {
            return onComplete;
        }
        if (goOffline) {
            lastActivityAt[account] = SystemClock.elapsedRealtime();
        }
        if (onComplete == null) {
            // Never wrap a null callback: ConnectionsManager then routes Updates/timestamp
            // callbacks itself. Act once the request has had time to land.
            if (readDialog != 0) {
                AndroidUtilities.runOnUIThread(() -> readNow(account, readDialog), OFFLINE_DELAY_MS);
            }
            if (goOffline) {
                scheduleOffline(account, OFFLINE_DELAY_MS * 2);
            }
            return null;
        }
        if (goOffline) {
            scheduleOffline(account, OFFLINE_DELAY_MS * 4); // fallback if the response never arrives
        }
        return (response, error) -> {
            try {
                onComplete.run(response, error);
            } finally {
                if (readDialog != 0 && error == null) {
                    AndroidUtilities.runOnUIThread(() -> readNow(account, readDialog));
                }
                if (goOffline) {
                    scheduleOffline(account, OFFLINE_DELAY_MS);
                }
            }
        };
    }

    /** Chat a user interaction (send, forward, inline result, reaction, vote) goes to, or 0. */
    private static long interactionDialogId(TLObject o) {
        TLRPC.InputPeer peer = null;
        if (o instanceof TLRPC.TL_messages_sendMessage) {
            peer = ((TLRPC.TL_messages_sendMessage) o).peer;
        } else if (o instanceof TLRPC.TL_messages_sendMedia) {
            peer = ((TLRPC.TL_messages_sendMedia) o).peer;
        } else if (o instanceof TLRPC.TL_messages_sendMultiMedia) {
            peer = ((TLRPC.TL_messages_sendMultiMedia) o).peer;
        } else if (o instanceof TLRPC.TL_messages_sendInlineBotResult) {
            peer = ((TLRPC.TL_messages_sendInlineBotResult) o).peer;
        } else if (o instanceof TLRPC.TL_messages_forwardMessages) {
            peer = ((TLRPC.TL_messages_forwardMessages) o).to_peer;
        } else if (o instanceof TLRPC.TL_messages_sendReaction) {
            peer = ((TLRPC.TL_messages_sendReaction) o).peer;
        } else if (o instanceof TLRPC.TL_messages_sendVote) {
            peer = ((TLRPC.TL_messages_sendVote) o).peer;
        }
        return peerDialogId(peer);
    }

    /**
     * Send a read receipt for one chat now, through the normal read path (UI thread).
     * Skips Saved Messages, secret chats and forums (reading a whole forum after a reply in
     * one topic would read the other topics too).
     */
    public static void readNow(int account, long dialogId) {
        if (!validAccount(account) || dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        try {
            if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
                return;
            }
            MessagesController mc = MessagesController.getInstance(account);
            if (mc.isForum(dialogId) || mc.isMonoForum(dialogId)) {
                return;
            }
            TLRPC.Dialog dialog = mc.dialogs_dict.get(dialogId);
            if (dialog == null || dialog.top_message <= 0) {
                return;
            }
            allowReads(account, dialogId, MANUAL_READ_WINDOW_MS);
            mc.markDialogAsRead(dialogId, dialog.top_message, dialog.top_message, dialog.last_message_date, false, 0, 0, true, 0);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Hook from MessagesController (updateUserStatus for our own user). With OPT_FORCE_OFFLINE,
     * when the server shows this account online while this device is in use (or right after one
     * of our own actions), push offline again. Rate limited. Like AyuGram's "Go offline
     * automatically", this fights any other client of the account that is open at the same time.
     */
    public static void onSelfStatus(int account, TLRPC.UserStatus status) {
        if (!validAccount(account) || !(status instanceof TLRPC.TL_userStatusOnline)
                || !active(account, OPT_ONLINE) || !isHidden(account, OPT_FORCE_OFFLINE)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean inUse = !ApplicationLoader.mainInterfacePaused || now - lastActivityAt[account] < RECENT_ACTIVITY_MS;
        if (!inUse || now - lastForcedOfflineAt[account] < FORCE_OFFLINE_MIN_INTERVAL_MS) {
            return;
        }
        lastForcedOfflineAt[account] = now;
        scheduleOffline(account, OFFLINE_DELAY_MS);
    }

    private static synchronized void scheduleOffline(int account, long delay) {
        if (offlineRunnables[account] != null) {
            Utilities.globalQueue.cancelRunnable(offlineRunnables[account]);
        }
        final Runnable r = () -> {
            synchronized (PlusGhost.class) {
                offlineRunnables[account] = null;
            }
            if (active(account, OPT_ONLINE)) {
                TL_account.updateStatus req = new TL_account.updateStatus();
                req.offline = true;
                ownStatusRequests.add(req); // lets exactly this object through intercept()
                ConnectionsManager.getInstance(account).sendRequest(req, null);
            }
        };
        offlineRunnables[account] = r;
        Utilities.globalQueue.postRunnable(r, delay);
    }
}
