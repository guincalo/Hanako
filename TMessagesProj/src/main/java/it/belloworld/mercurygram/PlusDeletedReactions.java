package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;

/**
 * plus f07: reactions on deleted messages kept by saved message history (0007).
 *
 * <p>The archived blob of a deleted message already carries the reactions it
 * had when the server deleted it (MessagesStorage keeps {@code message.reactions}
 * current in {@code messages_v2}, and MgMessageHistory copies that row). This
 * class makes them stay as they were:</p>
 * <ul>
 *   <li><b>frozen</b>: reaction updates for a ghost cell are ignored, so a later
 *       reactions poll or update cannot wipe or alter what was saved;</li>
 *   <li><b>read-only</b>: tapping, long-pressing or adding a reaction on a ghost
 *       does nothing (no request is sent for a message that no longer exists);</li>
 *   <li>with the setting off, ghosts are shown without reactions instead.</li>
 * </ul>
 * Purely local, no network requests.
 */
public final class PlusDeletedReactions {

    private static final String PREFS = "plus_f07";
    private static final String KEY_KEEP = "keep_reactions";

    private PlusDeletedReactions() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isKeepEnabled() {
        return prefs().getBoolean(KEY_KEEP, true);
    }

    public static void setKeepEnabled(boolean keep) {
        prefs().edit().putBoolean(KEY_KEEP, keep).apply();
    }

    /** True when reaction updates must not touch this message (a kept-after-delete ghost). */
    public static boolean isFrozen(MessageObject mo) {
        return mo != null && mo.mgDeletedGhost;
    }

    /**
     * Gate for every reaction interaction on a message. Returns true (and shows
     * a short hint) when the message is a ghost, so the caller must stop.
     */
    public static boolean blockInteraction(Context context, MessageObject mo) {
        if (!isFrozen(mo)) {
            return false;
        }
        if (context != null) {
            try {
                Toast.makeText(context, LocaleController.getString(R.string.PlusF07ReactionsFrozen), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignore) {
            }
        }
        return true;
    }

    /**
     * Called wherever a message becomes a ghost (re-injected from the archive,
     * flagged on load, or diverted from a live delete). With the setting off the
     * saved reactions are dropped from the displayed copy (the archive keeps them).
     */
    public static void onGhost(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || isKeepEnabled()) {
            return;
        }
        if (mo.messageOwner.reactions != null) {
            mo.messageOwner.reactions = null;
            mo.messageOwner.flags &= ~TLObject.FLAG_20; // keep serialization consistent
            mo.reactionsChanged = true;
            mo.forceUpdate = true;
        }
    }
}
