package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * plus f03: ghost mode "send as scheduled".
 *
 * While ghost mode + "Stay offline" are on and this option is enabled, outgoing messages are
 * sent as scheduled messages a few seconds ahead (12 s for text, more for media while it
 * uploads). The server then posts them itself, so sending does not put the account online
 * and Stay offline does not need its "offline after send" packet (which stamps a fresh
 * "last seen"). Approach and timing from AyuGram (GPLv3):
 * - AyuGram Desktop {@code applyGhostScheduling} (ayu/utils/telegram_helpers.cpp), hooked into
 *   every send path, and history_widget.cpp which skips the jump to the scheduled section.
 * - AyuGram docs (docs.ayugram.one/shared/ghost): text 12 s, media
 *   {@code Math.max(6, (int) Math.ceil(fileSize / 1024f / 1024f * 4.5f))}.
 * - NagramX AyuMessageUtils.getScheduleTime / reExtera SendMessage hook (both GPLv3): the same
 *   formula on Android, applied to SendMessagesHelper.sendMessage(SendMessageParams), with
 *   ChatActivity.openScheduledMessages suppressed for the send.
 *
 * Differences from those ports:
 * - schedule_date is re-stamped when the request actually goes out (after uploads), so a slow
 *   upload never leaves a date in the past and a fast one does not wait for the size estimate.
 * - The delivered message carries from_scheduled, which normally makes it unread for us and
 *   raises a "scheduled message sent" notification. That notification is suppressed and the
 *   chat is read (for real with "Read the chat when I reply", otherwise only locally).
 *
 * Off by default. Recipients' clients can see from_scheduled on these messages (official apps
 * show nothing for it, but some third-party clients mark them as scheduled).
 */
public final class PlusScheduledSend {

    /** Lead time the server gets for a text message, as in AyuGram. */
    public static final int BASE_DELAY_S = 12;
    /** schedule_date is re-stamped to now + this when the request leaves (uploads are done by then). */
    private static final int REQUEST_LEAD_S = 12;
    private static final long DIALOG_WINDOW_MS = 10 * 60 * 1000L;
    private static final long DATE_WINDOW_S = 30 * 60;
    private static final long READ_DELAY_MS = 800;
    private static final long BULLETIN_MIN_INTERVAL_MS = 2500;

    /** account -> schedule dates (server seconds) we picked -> expiry (server seconds). */
    @SuppressWarnings("unchecked")
    private static final ConcurrentHashMap<Integer, Long>[] ghostDates = new ConcurrentHashMap[UserConfig.MAX_ACCOUNT_COUNT];
    /** account -> dialog -> elapsedRealtime until which a delivered from_scheduled message is ours. */
    @SuppressWarnings("unchecked")
    private static final ConcurrentHashMap<Long, Long>[] ghostDialogs = new ConcurrentHashMap[UserConfig.MAX_ACCOUNT_COUNT];
    /** account -> album groupId -> schedule date, so every part of an album gets the same date. */
    @SuppressWarnings("unchecked")
    private static final ConcurrentHashMap<Long, Integer>[] albumDates = new ConcurrentHashMap[UserConfig.MAX_ACCOUNT_COUNT];
    @SuppressWarnings("unchecked")
    private static final Set<Long>[] pendingReads = new Set[UserConfig.MAX_ACCOUNT_COUNT];
    /** Send requests recognised as ours when they left; PlusGhost.wrapCompletion skips them. */
    private static final Set<TLObject> ghostRequests = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static long lastBulletinAt;

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            ghostDates[i] = new ConcurrentHashMap<>();
            ghostDialogs[i] = new ConcurrentHashMap<>();
            albumDates[i] = new ConcurrentHashMap<>();
            pendingReads[i] = ConcurrentHashMap.newKeySet();
        }
    }

    private PlusScheduledSend() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_f03", Context.MODE_PRIVATE);
    }

    // ---- settings ----

    /** Row id in MercurygramSettingsActivity (outside the ranges used there). */
    public static final int SETTINGS_ROW_ID = 9030;

    /** The per-account switch (default off). */
    public static boolean isEnabled(int account) {
        return prefs().getBoolean("sched_" + account, false);
    }

    public static void setEnabled(int account, boolean value) {
        prefs().edit().putBoolean("sched_" + account, value).apply();
    }

    /** Only in effect while ghost mode and its "Stay offline" option are on (AyuGram: "full ghost mode"). */
    public static boolean isActive(int account) {
        return PlusUtil.validAccount(account) && isEnabled(account)
                && PlusGhost.isEnabled(account) && PlusGhost.isHidden(account, PlusGhost.OPT_ONLINE);
    }

    // ---- send hooks ----

    /**
     * Hook at the top of SendMessagesHelper.sendMessage(SendMessageParams): turns a plain send
     * into a scheduled one by filling {@code p.scheduleDate}. Covers text, stickers, GIFs, voice,
     * single media and every part of an album (prepareSendingMedia sends each part through here).
     */
    public static void apply(int account, SendMessagesHelper.SendMessageParams p) {
        try {
            if (p == null || p.scheduleDate != 0 || p.retryMessageObject != null || !isActive(account)) {
                return;
            }
            if (p.sendingStory != null || p.replyToStoryItem != null || p.game != null || p.invoice != null
                    || p.richMessage != null || p.stars > 0 || p.payStars > 0 || p.dice_stake != 0
                    || p.monoForumPeer != 0 || p.suggestionParams != null || p.ephemeralReceiverBotId != 0
                    || p.quick_reply_shortcut != null || p.quick_reply_shortcut_id != 0) {
                return;
            }
            SendMessageChatArguments args = p.sendMessageChatArguments;
            if (args != null && (args.welcomeMessageChatId != 0 || args.quickReplyShortcut != null || args.quickReplyShortcutId != 0)) {
                return;
            }
            if (!canSchedule(account, p.peer, p.replyToTopMsg)) {
                return;
            }
            MessagesController mc = MessagesController.getInstance(account);
            if (p.message != null && p.message.length() < 30 && mc.diceEmojies != null
                    && mc.diceEmojies.contains(p.message.replace("\ufe0f", ""))) {
                return; // a scheduled dice is sent as plain emoji text
            }
            HanakoTelemetry.count(HanakoTelemetry.SCHEDULED_SEND); // hanako: usage statistics (off by default)

            long groupId = 0;
            if (p.params != null) {
                String g = p.params.get("groupId");
                if (g != null) {
                    try {
                        groupId = Long.parseLong(g);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            int now = ConnectionsManager.getInstance(account).getCurrentTime();
            int date;
            Integer albumDate = groupId != 0 ? albumDates[account].get(groupId) : null;
            if (albumDate != null && albumDate > now) {
                date = albumDate;
            } else {
                date = now + delaySeconds(p.photo, p.document);
                if (groupId != 0) {
                    albumDates[account].put(groupId, date);
                }
            }
            p.scheduleDate = date;
            p.scheduleRepeatPeriod = 0;
            remember(account, p.peer, date, now);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Hook for SendMessagesHelper.sendMessage(ArrayList&lt;MessageObject&gt;, ...) (forwards).
     * @return the schedule date to forward with ({@code scheduleDate} unchanged when not applied).
     */
    public static int forwardDate(int account, long peer, int scheduleDate, MessageObject replyToTopMsg, long payStars, long monoForumPeerId, Object suggestionParams) {
        try {
            if (scheduleDate != 0 || payStars > 0 || monoForumPeerId != 0 || suggestionParams != null || !isActive(account)) {
                return scheduleDate;
            }
            if (!canSchedule(account, peer, replyToTopMsg)) {
                return scheduleDate;
            }
            int now = ConnectionsManager.getInstance(account).getCurrentTime();
            int date = now + BASE_DELAY_S;
            remember(account, peer, date, now);
            return date;
        } catch (Exception e) {
            FileLog.e(e);
            return scheduleDate;
        }
    }

    /** The same limits the official client puts on the "Schedule message" menu (ChatActivity.canScheduleMessage). */
    private static boolean canSchedule(int account, long peer, MessageObject replyToTopMsg) {
        if (peer == 0 || DialogObject.isEncryptedDialog(peer)) {
            return false;
        }
        if (peer == UserConfig.getInstance(account).getClientUserId()) {
            return false; // Saved Messages: nobody to hide from, and scheduled sends there become reminders
        }
        if (PlusGhostExceptions.onlineExceptedNow(account, peer)) {
            return false; // f01 "online while open" is showing us online in this chat: send right away
        }
        MessagesController mc = MessagesController.getInstance(account);
        if (mc.isMonoForum(peer) || mc.getSendPaidMessagesStars(peer) > 0) {
            return false;
        }
        if (replyToTopMsg != null && !mc.isForum(peer)) {
            return false; // comment / reply threads can't hold scheduled messages; forum topics can
        }
        return true;
    }

    /** AyuGram's delay: 12 s, plus max(6, ceil(MB * 4.5)) s for media that still has to upload. */
    public static int delaySeconds(TLRPC.TL_photo photo, TLRPC.TL_document document) {
        if (document != null && document.access_hash != 0) {
            // already on the server (sticker, saved GIF, re-sent file): nothing to upload
            return BASE_DELAY_S;
        }
        long size = 0;
        if (photo != null && photo.sizes != null) {
            TLRPC.PhotoSize ps = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
            if (ps != null) {
                size = ps.size;
            }
        }
        if (size == 0 && document != null) {
            size = document.size;
        }
        if (size <= 0) {
            return BASE_DELAY_S;
        }
        return BASE_DELAY_S + Math.max(6, (int) Math.ceil(size / 1024.0f / 1024.0f * 4.5f));
    }

    private static void remember(int account, long peer, int date, int now) {
        ghostDates[account].put(date, (long) now + DATE_WINDOW_S);
        ghostDialogs[account].put(peer, SystemClock.elapsedRealtime() + (date - now) * 1000L + DIALOG_WINDOW_MS);
        prune(account, now);
    }

    private static void prune(int account, int now) {
        for (Iterator<Map.Entry<Integer, Long>> it = ghostDates[account].entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<Long, Integer>> it = albumDates[account].entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() + DATE_WINDOW_S < now) {
                it.remove();
            }
        }
        long el = SystemClock.elapsedRealtime();
        for (Iterator<Map.Entry<Long, Long>> it = ghostDialogs[account].entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < el) {
                it.remove();
            }
        }
    }

    private static boolean isGhostDate(int account, int date) {
        return PlusUtil.validAccount(account) && date != 0 && ghostDates[account].containsKey(date);
    }

    // ---- request hooks (PlusGhost, i.e. ConnectionsManager.sendRequestInternal) ----

    /**
     * Called for every outgoing request. A send request whose schedule_date is one we picked is
     * re-stamped to now + REQUEST_LEAD_S: media is uploaded before the send request goes out, so
     * the size estimate is no longer needed, and the date can never be in the past.
     */
    public static void onRequest(int account, TLObject o) {
        if (o == null || !PlusUtil.validAccount(account)) {
            return;
        }
        try {
            int date;
            if (o instanceof TLRPC.TL_messages_sendMessage) {
                date = ((TLRPC.TL_messages_sendMessage) o).schedule_date;
            } else if (o instanceof TLRPC.TL_messages_sendMedia) {
                date = ((TLRPC.TL_messages_sendMedia) o).schedule_date;
            } else if (o instanceof TLRPC.TL_messages_sendMultiMedia) {
                date = ((TLRPC.TL_messages_sendMultiMedia) o).schedule_date;
            } else if (o instanceof TLRPC.TL_messages_forwardMessages) {
                date = ((TLRPC.TL_messages_forwardMessages) o).schedule_date;
            } else if (o instanceof TLRPC.TL_messages_sendInlineBotResult) {
                date = ((TLRPC.TL_messages_sendInlineBotResult) o).schedule_date;
            } else {
                return;
            }
            if (!isGhostDate(account, date)) {
                return;
            }
            int now = ConnectionsManager.getInstance(account).getCurrentTime();
            int newDate = now + REQUEST_LEAD_S;
            if (newDate != date) {
                ghostDates[account].put(newDate, (long) now + DATE_WINDOW_S);
                if (o instanceof TLRPC.TL_messages_sendMessage) {
                    ((TLRPC.TL_messages_sendMessage) o).schedule_date = newDate;
                } else if (o instanceof TLRPC.TL_messages_sendMedia) {
                    ((TLRPC.TL_messages_sendMedia) o).schedule_date = newDate;
                } else if (o instanceof TLRPC.TL_messages_sendMultiMedia) {
                    ((TLRPC.TL_messages_sendMultiMedia) o).schedule_date = newDate;
                } else if (o instanceof TLRPC.TL_messages_forwardMessages) {
                    ((TLRPC.TL_messages_forwardMessages) o).schedule_date = newDate;
                } else {
                    ((TLRPC.TL_messages_sendInlineBotResult) o).schedule_date = newDate;
                }
            }
            ghostRequests.add(o);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * True for a send request this feature scheduled. PlusGhost.wrapCompletion then skips its
     * "offline after send" packet (the send never showed us online, and an offline packet would
     * stamp a fresh last seen) and its immediate read-on-send (done when the message is posted).
     */
    public static boolean isGhostRequest(TLObject o) {
        return o != null && ghostRequests.contains(o);
    }

    // ---- UI hooks ----

    /**
     * ChatActivity (didReceiveNewMessages, normal chat): a local scheduled message just appeared.
     * @return true when it is one of ours, so the chat stays open instead of jumping to the
     * scheduled list; a short bulletin says when it goes out.
     */
    public static boolean keepInChat(BaseFragment fragment, int account, MessageObject mo) {
        if (mo == null || mo.messageOwner == null || !isGhostDate(account, mo.messageOwner.date)) {
            return false;
        }
        try {
            long el = SystemClock.elapsedRealtime();
            if (fragment != null && el - lastBulletinAt > BULLETIN_MIN_INTERVAL_MS) {
                lastBulletinAt = el;
                int secs = Math.max(1, mo.messageOwner.date - ConnectionsManager.getInstance(account).getCurrentTime());
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.timer_toast,
                        LocaleController.formatString(R.string.PlusF03ScheduledSendBulletin, secs)).show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    /**
     * NotificationsController.processNewMessages: a message of ours that the server just posted
     * from the schedule (from_scheduled). Returns true to drop its "scheduled message sent"
     * notification, and reads the chat so it does not stay unread.
     */
    public static boolean onPostedOwnMessage(int account, MessageObject mo) {
        if (!PlusUtil.validAccount(account) || mo == null || mo.messageOwner == null
                || !mo.messageOwner.from_scheduled || !mo.messageOwner.out) {
            return false;
        }
        long dialogId = mo.getDialogId();
        Long until = ghostDialogs[account].get(dialogId);
        if (until == null || SystemClock.elapsedRealtime() > until) {
            return false; // a message the user scheduled themselves: leave it alone
        }
        if (pendingReads[account].add(dialogId)) {
            AndroidUtilities.runOnUIThread(() -> {
                pendingReads[account].remove(dialogId);
                readAfterPost(account, dialogId);
            }, READ_DELAY_MS);
        }
        return true;
    }

    /**
     * After our scheduled message is posted: with "Read the chat when I reply" (or with read
     * receipts not hidden) send a real read, as PlusGhost does right after a normal send.
     * Otherwise read it locally only (ghost mode drops the readHistory request).
     */
    private static void readAfterPost(int account, long dialogId) {
        try {
            boolean readsHidden = PlusGhost.isEnabled(account) && PlusGhost.isHidden(account, PlusGhost.OPT_READS);
            if (!readsHidden || PlusGhost.isHidden(account, PlusGhost.OPT_READ_ON_SEND)) {
                PlusGhost.readNow(account, dialogId);
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
            mc.markDialogAsRead(dialogId, dialog.top_message, dialog.top_message, dialog.last_message_date, false, 0, 0, true, 0);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
