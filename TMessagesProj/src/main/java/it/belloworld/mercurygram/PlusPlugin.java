package it.belloworld.mercurygram;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;

/**
 * plus f11: the interface a code (DEX) plugin implements.
 *
 * A code plugin is a .dex/.jar/.apk file next to its JSON manifest in the plugin folder; the manifest
 * names the implementing class in "main". The class needs a public no-argument constructor.
 * Compile against the app classes (compileOnly) and convert with d8; see out/f11-plugin-engine.md.
 *
 * Code plugins run inside the app process with the app's full permissions. They are not sandboxed:
 * the user has to switch on "Allow code plugins" and then enable each plugin, and a plugin is
 * disabled again automatically when its code file changes (SHA-256 pin).
 *
 * Hooks are called from several threads (UI thread, stageQueue, message loading threads), so an
 * implementation must be thread-safe and fast. Exceptions are caught; after
 * {@link PlusPlugins#MAX_FAILURES} consecutive failures the plugin is switched off.
 *
 * Naming follows the exteraGram plugin SDK where it maps cleanly: {@link #onSendMessage} is
 * on_send_message_hook, {@link #onRequest} is pre_request_hook (request names use the same
 * "TL_messages_setTyping" / "TL_account_updateStatus" form).
 */
public interface PlusPlugin {

    /** Engine API level. A manifest may declare "api"; manifests asking for a newer level are refused. */
    int API_VERSION = 1;

    /** {@link #onRequest}: let the request go on (possibly modified in place). */
    int PASS = 0;
    /** {@link #onRequest}: do not send; the caller's callback gets a local error PLUS_PLUGIN_BLOCKED. */
    int CANCEL = 1;

    /** Called once after the plugin is instantiated and enabled. */
    default void onLoad(PlusPluginHost host) {
    }

    /** Called when the plugin is disabled, deleted or the plugin list is reloaded. */
    default void onUnload() {
    }

    /**
     * Outgoing message, called at the start of SendMessagesHelper.sendMessage (text messages and
     * media with caption; not for edits, scheduled-message retries or resends).
     * The plugin may change {@code params.message}, {@code params.caption} and {@code params.entities}
     * in place. If you change the text length, fix or clear {@code params.entities} yourself.
     *
     * @return true to cancel the send completely (nothing is sent, the input field is already cleared).
     */
    default boolean onSendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        return false;
    }

    /**
     * Display text of a plain text message (no media), called when the MessageObject builds its text.
     * Display only: the stored message is not changed. When the message has entities (bold, links,
     * custom emoji...) a result with a different length is ignored, because entity offsets would
     * no longer match.
     *
     * @return the text to show; return {@code text} or null to leave it unchanged.
     */
    default CharSequence onMessageText(int account, MessageObject message, CharSequence text) {
        return text;
    }

    /**
     * Every outgoing API request, called on the network stage queue before ghost mode looks at it.
     * The request may be modified in place; ghost mode still filters the result afterwards.
     *
     * @param name request name, e.g. "TL_messages_sendMessage", "TL_account_updateStatus".
     * @return {@link #PASS} or {@link #CANCEL}.
     */
    default int onRequest(int account, String name, TLObject request) {
        return PASS;
    }
}
