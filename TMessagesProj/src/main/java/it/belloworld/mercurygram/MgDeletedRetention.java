package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * hanako: how long saved deleted / edited messages are kept, modelled on
 * Telegram's Storage usage → "Keep media": one period per chat type (private
 * chats, groups, channels) plus per-chat exceptions. A period can also be
 * "Don't save", which stops archiving for that type / chat altogether.
 *
 * <p>The clock is the time the copy was saved (deletion or edit), not the
 * message date: "1 week" keeps a deleted message for a week after it was
 * deleted. Defaults are Forever everywhere, which is the old behaviour.</p>
 *
 * <p>Per account, like the main toggle. Pruning is local only.</p>
 */
public final class MgDeletedRetention {

    public static final int TYPE_PRIVATE = 0;
    public static final int TYPE_GROUP = 1;
    public static final int TYPE_CHANNEL = 2;
    public static final int TYPE_COUNT = 3;

    /** Keep forever. */
    public static final int KEEP_FOREVER = 0;
    /** Don't save at all. */
    public static final int KEEP_OFF = -1;
    /** Exception value meaning "use the chat type's period" (only used by the chat menu). */
    public static final int KEEP_DEFAULT = -2;
    /** Periods offered, in days (plus FOREVER and OFF). */
    public static final int[] PERIODS = {KEEP_FOREVER, 365, 30, 7, 1};

    private static final String PREFS = "hanako_deleted_retention";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long PRUNE_EVERY_MS = 6L * 60 * 60 * 1000;

    private static final long[] lastPrune = new long[UserConfig.MAX_ACCOUNT_COUNT];

    /** account -> dialogId -> {type, value}; loaded lazily, written through. */
    @SuppressWarnings("unchecked")
    private static final LinkedHashMap<Long, int[]>[] exceptions = new LinkedHashMap[UserConfig.MAX_ACCOUNT_COUNT];

    private MgDeletedRetention() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- chat type ----

    /** Type of a dialog, from memory; unknown chats count as groups. */
    public static int typeOf(int account, long dialogId) {
        if (DialogObject.isUserDialog(dialogId) || DialogObject.isEncryptedDialog(dialogId)) {
            return TYPE_PRIVATE;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return ChatObject.isChannelAndNotMegaGroup(chat) ? TYPE_CHANNEL : TYPE_GROUP;
    }

    /** True when the chat type cannot be told from memory (chat not loaded). */
    private static boolean typeUnknown(int account, long dialogId) {
        return DialogObject.isChatDialog(dialogId) && MessagesController.getInstance(account).getChat(-dialogId) == null;
    }

    // ---- per-type periods ----

    public static int getTypePeriod(int account, int type) {
        return prefs().getInt("t" + account + "_" + type, KEEP_FOREVER);
    }

    public static void setTypePeriod(int account, int type, int period) {
        prefs().edit().putInt("t" + account + "_" + type, period).apply();
        requestPrune(account, true);
    }

    // ---- exceptions ----

    private static LinkedHashMap<Long, int[]> exc(int account) {
        synchronized (exceptions) {
            LinkedHashMap<Long, int[]> m = exceptions[account];
            if (m == null) {
                m = new LinkedHashMap<>();
                String raw = prefs().getString("x" + account, "");
                if (raw != null && !raw.isEmpty()) {
                    for (String part : raw.split(";")) {
                        String[] f = part.split(":");
                        if (f.length != 3) {
                            continue;
                        }
                        try {
                            m.put(Long.parseLong(f[0]), new int[]{Integer.parseInt(f[1]), Integer.parseInt(f[2])});
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
                exceptions[account] = m;
            }
            return m;
        }
    }

    private static void saveExc(int account) {
        StringBuilder sb = new StringBuilder();
        synchronized (exceptions) {
            for (Map.Entry<Long, int[]> e : exc(account).entrySet()) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(e.getKey()).append(':').append(e.getValue()[0]).append(':').append(e.getValue()[1]);
            }
        }
        prefs().edit().putString("x" + account, sb.toString()).apply();
    }

    /** The chat's own period, or {@link #KEEP_DEFAULT} when it has no exception. */
    public static int getException(int account, long dialogId) {
        synchronized (exceptions) {
            int[] v = exc(account).get(dialogId);
            return v != null ? v[1] : KEEP_DEFAULT;
        }
    }

    /** Sets (or with {@link #KEEP_DEFAULT} removes) a chat's exception. */
    public static void setException(int account, long dialogId, int period) {
        synchronized (exceptions) {
            if (period == KEEP_DEFAULT) {
                exc(account).remove(dialogId);
            } else {
                exc(account).put(dialogId, new int[]{typeOf(account, dialogId), period});
            }
        }
        saveExc(account);
        requestPrune(account, true);
    }

    /** Dialog ids with an exception of the given type, in the order they were added. */
    public static ArrayList<Long> exceptionsOf(int account, int type) {
        ArrayList<Long> out = new ArrayList<>();
        synchronized (exceptions) {
            for (Map.Entry<Long, int[]> e : exc(account).entrySet()) {
                if (e.getValue()[0] == type) {
                    out.add(e.getKey());
                }
            }
        }
        Collections.reverse(out); // newest first, like Telegram's list
        return out;
    }

    public static void clearExceptions(int account, int type) {
        synchronized (exceptions) {
            exc(account).entrySet().removeIf(e -> e.getValue()[0] == type);
        }
        saveExc(account);
    }

    // ---- effective rule ----

    /** Effective period for a chat: its exception, else its type's period. */
    public static int periodFor(int account, long dialogId) {
        int e = getException(account, dialogId);
        if (e != KEEP_DEFAULT) {
            return e;
        }
        return getTypePeriod(account, typeOf(account, dialogId));
    }

    /** Whether new deletions / edits in this chat are saved at all. */
    public static boolean shouldSave(int account, long dialogId) {
        return periodFor(account, dialogId) != KEEP_OFF;
    }

    /**
     * Saved copies older than this (ms since epoch, by save time) are dropped;
     * 0 keeps everything. "Don't save" only stops new copies: what is already
     * saved stays until the user clears it (never delete data on a toggle).
     * Returns 0 when the chat type is unknown and it has no exception, so
     * nothing is deleted on a guess.
     */
    public static long cutoffFor(int account, long dialogId, long now) {
        int e = getException(account, dialogId);
        int period;
        if (e != KEEP_DEFAULT) {
            period = e;
        } else {
            if (typeUnknown(account, dialogId)) {
                return 0;
            }
            period = getTypePeriod(account, typeOf(account, dialogId));
        }
        if (period == KEEP_FOREVER || period == KEEP_OFF) {
            return 0;
        }
        return now - period * DAY_MS;
    }

    private static boolean isTimed(int period) {
        return period > 0;
    }

    /** True when no rule of the account has a time limit (no pruning ever needed). */
    public static boolean keepsEverything(int account) {
        for (int t = 0; t < TYPE_COUNT; t++) {
            if (isTimed(getTypePeriod(account, t))) {
                return false;
            }
        }
        synchronized (exceptions) {
            for (int[] v : exc(account).values()) {
                if (isTimed(v[1])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Drops copies past their period, at most every 6 hours unless forced.
     * Runs on a background queue.
     */
    public static void requestPrune(int account, boolean force) {
        if (account < 0 || account >= lastPrune.length) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (lastPrune) {
            if (!force && now - lastPrune[account] < PRUNE_EVERY_MS) {
                return;
            }
            lastPrune[account] = now;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                MgMessageHistory.getInstance().dropThrowawayCopiesOnce(); // hanako 2.01
                MgMessageHistory.getInstance().enforceMediaCap(false);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
        if (keepsEverything(account)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                MgMessageHistory.getInstance().pruneByRetention(account);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    // ---- media of deleted / edited messages (hanako 2.01) ----
    //
    // Few rules, in this order:
    //  1. Never: stickers, GIFs, custom/animated emoji (Telegram can always show them again).
    //  2. Never from bots, and never bigger than the size limit.
    //  3. Channels and groups over BIG_GROUP members: text only, unless the chat has its own
    //     exception under "Keep for" (an exception means "I care about this chat").
    //  4. Only files that are already downloaded are copied; nothing is fetched again.
    //  5. Over the storage limit the oldest copies are dropped (the messages stay).

    /** Size limit choices for one copied file, bytes; 0 = don't copy media, -1 = any size. */
    public static final long[] MEDIA_MAX = {0, 5L << 20, 20L << 20, 100L << 20, -1};
    /** Storage limit choices for all copies together, bytes; 0 = no limit. */
    public static final long[] MEDIA_CAP = {250L << 20, 1L << 30, 5L << 30, 0};
    public static final int BIG_GROUP = 200;

    public static long getMediaMax() {
        return prefs().getLong("media_max", 20L << 20);
    }

    public static void setMediaMax(long v) {
        prefs().edit().putLong("media_max", v).apply();
    }

    public static long getMediaCap() {
        return prefs().getLong("media_cap", 1L << 30);
    }

    public static void setMediaCap(long v) {
        prefs().edit().putLong("media_cap", v).apply();
        Utilities.globalQueue.postRunnable(() -> MgMessageHistory.getInstance().enforceMediaCap(true));
    }

    public static String mediaMaxLabel(long v) {
        if (v == 0) {
            return LocaleController.getString(R.string.HanakoDeletedMediaNone);
        }
        if (v < 0) {
            return LocaleController.getString(R.string.HanakoDeletedMediaAny);
        }
        return LocaleController.formatString(R.string.HanakoDeletedMediaUpTo, org.telegram.messenger.AndroidUtilities.formatFileSize(v));
    }

    public static String mediaCapLabel(long v) {
        return v <= 0 ? LocaleController.getString(R.string.HanakoDeletedMediaNoCap)
                : org.telegram.messenger.AndroidUtilities.formatFileSize(v);
    }

    private static boolean fromBot(int account, TLRPC.Message m) {
        if (m == null || !(m.from_id instanceof TLRPC.TL_peerUser)) {
            return false;
        }
        TLRPC.User u = MessagesController.getInstance(account).getUser(m.from_id.user_id);
        return u != null && u.bot;
    }

    private static int memberCount(int account, long chatId) {
        MessagesController mc = MessagesController.getInstance(account);
        int n = 0;
        TLRPC.Chat chat = mc.getChat(chatId);
        if (chat != null) {
            n = chat.participants_count;
        }
        TLRPC.ChatFull full = mc.getChatFull(chatId);
        if (full != null) {
            n = Math.max(n, full.participants_count);
        }
        return n;
    }

    /**
     * Messages not worth keeping at all: service messages ("X joined", pins...) and bot
     * messages in groups and channels (welcome / captcha bots delete their own posts).
     */
    public static boolean skipMessage(int account, long dialogId, TLRPC.Message m) {
        if (m == null || m instanceof TLRPC.TL_messageService) {
            return true;
        }
        return !DialogObject.isUserDialog(dialogId) && fromBot(account, m);
    }

    private static boolean isThrowawayDocument(TLRPC.Document d) {
        return MessageObject.isStickerDocument(d) || MessageObject.isAnimatedStickerDocument(d, true)
                || MessageObject.isVideoStickerDocument(d) || MessageObject.isGifDocument(d)
                || MessageObject.isAnimatedEmoji(d);
    }

    /** Whether the media of this message should be copied when it is deleted / edited away. */
    public static boolean shouldCopyMedia(int account, long dialogId, TLRPC.Message m) {
        long max = getMediaMax();
        if (max == 0 || m == null || m.media == null) {
            return false;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(m);
        long size = 0;
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            if (isThrowawayDocument(media.document)) {
                return false;
            }
            size = media.document.size;
        } else if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            for (int i = 0; i < media.photo.sizes.size(); i++) {
                size = Math.max(size, media.photo.sizes.get(i).size);
            }
        }
        if (max > 0 && size > max) {
            return false;
        }
        if (fromBot(account, m)) {
            return false;
        }
        if (getException(account, dialogId) != KEEP_DEFAULT) {
            return true;
        }
        int type = typeOf(account, dialogId);
        if (type == TYPE_CHANNEL) {
            return false;
        }
        return type != TYPE_GROUP || memberCount(account, -dialogId) <= BIG_GROUP;
    }

    /** True for a sticker / GIF / emoji document, whose old copies are dropped once (see MgMessageHistory). */
    public static boolean isThrowaway(TLRPC.Message m) {
        if (m == null || m.media == null) {
            return false;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(m);
        return media instanceof TLRPC.TL_messageMediaDocument && media.document != null
                && isThrowawayDocument(media.document);
    }

    // ---- labels ----

    public static String periodLabel(int period) {
        switch (period) {
            case KEEP_FOREVER:
                return LocaleController.getString(R.string.KeepMediaForever);
            case KEEP_OFF:
                return LocaleController.getString(R.string.HanakoKeepDeletedOff);
            case KEEP_DEFAULT:
                return LocaleController.getString(R.string.HanakoKeepDeletedDefault);
            case 365:
                return LocaleController.formatPluralString("Years", 1);
            case 30:
                return LocaleController.formatPluralString("Months", 1);
            case 7:
                return LocaleController.formatPluralString("Weeks", 1);
            default:
                return LocaleController.formatPluralString("Days", period);
        }
    }

    public static String typeLabel(int type) {
        switch (type) {
            case TYPE_PRIVATE:
                return LocaleController.getString(R.string.PrivateChats);
            case TYPE_GROUP:
                return LocaleController.getString(R.string.GroupChats);
            default:
                return LocaleController.getString(R.string.CacheChannels);
        }
    }
}
