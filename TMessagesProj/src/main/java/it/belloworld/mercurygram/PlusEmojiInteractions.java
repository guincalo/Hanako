package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;

import java.util.ArrayList;

/**
 * plus f15: emoji interactions and the "choosing a sticker" status, per account.
 *
 * 1. "Disable emoji interactions" (pref {@code interactions_<acc>}):
 *    - taps on an animated emoji still play locally, but the batched
 *      messages.setTyping(sendMessageEmojiInteraction) is never built
 *      (EmojiAnimationsOverlay.sendCurrentTaps);
 *    - sendMessageEmojiInteractionSeen (MessagesController.sendTyping action 11) is not sent,
 *      so the other side never sees "watching your animation" (a read/online signal);
 *    - interactions received from others are not played (EmojiAnimationsOverlay, onEmojiInteractionsReceived).
 *      Not playing them also means nothing would ever ask to send "seen".
 * 2. "Don't send 'choosing a sticker'" (pref {@code choosing_sticker_<acc>}):
 *    - EmojiView's sticker tab never starts the status (so it never sends the follow-up cancel either);
 *    - MessagesController.sendTyping action 10 is refused as a safety net.
 *
 * Both only ever remove requests; nothing new is sent. Refused sendTyping calls return before
 * any per-dialog typing state is recorded, so later statuses are not blocked.
 * Ghost mode's "Don't send typing status" (PlusGhost OPT_TYPING) already drops all setTyping at
 * the network layer while it is on; these toggles also apply with ghost mode off.
 * Idea from Nagram (disableRemoteEmojiInteractions, disableChoosingSticker); written fresh.
 */
public final class PlusEmojiInteractions {

    /** MessagesController.sendTyping action ids (see the if/else chain there). */
    public static final int TYPING_CHOOSE_STICKER = 10;
    public static final int TYPING_EMOJI_INTERACTION_SEEN = 11;

    // settings row ids (MercurygramSettingsActivity, plus f15 block; range 1500-1599)
    public static final int ID_INTERACTIONS = 1500;
    public static final int ID_CHOOSING_STICKER = 1501;

    private PlusEmojiInteractions() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_f15", Context.MODE_PRIVATE);
    }

    public static boolean isInteractionsDisabled(int account) {
        return PlusUtil.validAccount(account) && prefs().getBoolean("interactions_" + account, false);
    }

    public static void setInteractionsDisabled(int account, boolean value) {
        prefs().edit().putBoolean("interactions_" + account, value).apply();
    }

    public static boolean isChoosingStickerHidden(int account) {
        return PlusUtil.validAccount(account) && prefs().getBoolean("choosing_sticker_" + account, false);
    }

    public static void setChoosingStickerHidden(int account, boolean value) {
        prefs().edit().putBoolean("choosing_sticker_" + account, value).apply();
    }

    // ---- hooks ----

    /** MessagesController.sendTyping: true = refuse this action (nothing is sent, no state is kept). */
    public static boolean blockTyping(int account, int action) {
        if (action == TYPING_CHOOSE_STICKER) {
            return isChoosingStickerHidden(account);
        }
        if (action == TYPING_EMOJI_INTERACTION_SEEN) {
            return isInteractionsDisabled(account);
        }
        return false;
    }

    /** EmojiAnimationsOverlay.sendCurrentTaps: true = don't send our taps. */
    public static boolean blockSendTaps(int account) {
        return isInteractionsDisabled(account);
    }

    /** EmojiAnimationsOverlay, onEmojiInteractionsReceived: true = don't play someone else's taps. */
    public static boolean blockRemoteTaps(int account) {
        return isInteractionsDisabled(account);
    }

    // ---- settings ----

    public static void addSettingsItems(ArrayList<UItem> items, int account) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF15Header)));
        items.add(UItem.asCheck(ID_INTERACTIONS, LocaleController.getString(R.string.PlusF15DisableInteractions))
                .setChecked(isInteractionsDisabled(account)));
        items.add(UItem.asCheck(ID_CHOOSING_STICKER, LocaleController.getString(R.string.PlusF15HideChoosingSticker))
                .setChecked(isChoosingStickerHidden(account)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF15About)));
    }

    /** @return true when the click was ours; {@code refresh} is run after a change. */
    public static boolean onSettingsClick(int account, int id, Runnable refresh) {
        if (id == ID_INTERACTIONS) {
            setInteractionsDisabled(account, !isInteractionsDisabled(account));
        } else if (id == ID_CHOOSING_STICKER) {
            setChoosingStickerHidden(account, !isChoosingStickerHidden(account));
        } else {
            return false;
        }
        if (refresh != null) {
            refresh.run();
        }
        return true;
    }
}
