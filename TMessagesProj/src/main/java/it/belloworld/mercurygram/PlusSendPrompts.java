package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/**
 * plus f16: ask before sending a voice message, round video, sticker or GIF, and before starting
 * a voice or video call. Every kind has its own switch (device-wide, default off), like Nagram's
 * confirmAVMessage / askBeforeCall and Octogram's promptBefore*.
 *
 * Purely local: a dialog in front of an action the user started anyway. No network requests.
 *
 * Skipped on purpose: scheduled sends (the date picker already is a confirmation) and paid
 * messages (Telegram's own paid-message dialog already asks).
 */
public final class PlusSendPrompts {

    public static final int KIND_VOICE = 0;
    public static final int KIND_ROUND = 1;
    public static final int KIND_STICKER = 2;
    public static final int KIND_GIF = 3;
    public static final int KIND_VOICE_CALL = 4;
    public static final int KIND_VIDEO_CALL = 5;
    public static final int KIND_COUNT = 6;

    private static final String[] KEYS = {"voice", "round", "sticker", "gif", "voice_call", "video_call"};

    /** Set while a confirmed action re-enters its own entry point, so it does not ask twice. */
    private static boolean bypass;

    private PlusSendPrompts() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_f16", Context.MODE_PRIVATE);
    }

    private static boolean validKind(int kind) {
        return kind >= 0 && kind < KIND_COUNT;
    }

    public static boolean isEnabled(int kind) {
        return validKind(kind) && prefs().getBoolean(KEYS[kind], false);
    }

    public static void setEnabled(int kind, boolean value) {
        if (validKind(kind)) {
            prefs().edit().putBoolean(KEYS[kind], value).apply();
        }
    }

    /** Settings row label for a kind. */
    public static String label(int kind) {
        switch (kind) {
            case KIND_VOICE: return LocaleController.getString(R.string.PlusPromptVoiceRow);
            case KIND_ROUND: return LocaleController.getString(R.string.PlusPromptRoundRow);
            case KIND_STICKER: return LocaleController.getString(R.string.PlusPromptStickerRow);
            case KIND_GIF: return LocaleController.getString(R.string.PlusPromptGifRow);
            case KIND_VOICE_CALL: return LocaleController.getString(R.string.PlusPromptVoiceCallRow);
            case KIND_VIDEO_CALL: return LocaleController.getString(R.string.PlusPromptVideoCallRow);
            default: return "";
        }
    }

    /** Sticker picker documents can be GIFs (secret chats, saved GIFs sent as documents). */
    public static int stickerKind(TLRPC.Document document) {
        return document != null && MessageObject.isGifDocument(document) ? KIND_GIF : KIND_STICKER;
    }

    /**
     * Shows the "Send ...?" dialog for {@code kind} and runs {@code onConfirm} on Send.
     * Cancel or back just closes the dialog. Callers check {@link #isEnabled} first.
     */
    public static void confirm(Context context, int kind, Theme.ResourcesProvider resourcesProvider, Runnable onConfirm) {
        if (context == null) {
            return;
        }
        final String title;
        switch (kind) {
            case KIND_VOICE: title = LocaleController.getString(R.string.PlusPromptVoiceTitle); break;
            case KIND_ROUND: title = LocaleController.getString(R.string.PlusPromptRoundTitle); break;
            case KIND_GIF: title = LocaleController.getString(R.string.PlusPromptGifTitle); break;
            default: title = LocaleController.getString(R.string.PlusPromptStickerTitle); break;
        }
        show(new AlertDialog.Builder(context, resourcesProvider)
                .setTitle(title)
                .setMessage(LocaleController.getString(R.string.PlusPromptSendMessage))
                .setPositiveButton(LocaleController.getString(R.string.Send), (d, w) -> onConfirm.run())
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null));
    }

    /**
     * Hook for sticker / GIF sends that already sit behind the paid-message check: runs
     * {@code send} now, or after the dialog when the kind is switched on.
     * {@code scheduleDate != 0} and {@code payStars > 0} send straight away (already confirmed).
     */
    public static void runSend(Context context, int kind, int scheduleDate, long payStars, Theme.ResourcesProvider resourcesProvider, Runnable send) {
        if (scheduleDate != 0 || payStars > 0 || !isEnabled(kind)) {
            send.run();
            return;
        }
        confirm(context, kind, resourcesProvider, send);
    }

    /**
     * Hook at the top of VoIPHelper.initiateCall (private 1:1 calls only, after the permission
     * check). Returns true when a dialog was shown; {@code retry} re-enters initiateCall on "Call".
     */
    public static boolean interceptCall(Context context, TLRPC.User user, boolean videoCall, Runnable retry) {
        if (bypass || context == null || user == null || !isEnabled(videoCall ? KIND_VIDEO_CALL : KIND_VOICE_CALL)) {
            return false;
        }
        final String name = UserObject.getUserName(user);
        show(new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(videoCall ? R.string.VideoCall : R.string.Call))
                .setMessage(LocaleController.formatString(videoCall ? R.string.PlusPromptVideoCallMessage : R.string.PlusPromptVoiceCallMessage, name))
                .setPositiveButton(LocaleController.getString(videoCall ? R.string.VideoCall : R.string.Call), (d, w) -> {
                    bypass = true;
                    try {
                        retry.run();
                    } finally {
                        bypass = false;
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null));
        return true;
    }

    /** If the window is gone the dialog fails and nothing is sent: an unconfirmed send is what the switch is for. */
    private static void show(AlertDialog.Builder builder) {
        try {
            builder.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
