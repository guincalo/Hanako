/*
 * plus f08: per-chat biometric lock + hidden chats.
 *
 * Idea and parts of the approach (dialog-id keyed lock list per account, gate
 * in presentFragment, filter in DialogsAdapter, long-press on the chat list
 * title to reveal hidden chats, BiometricPrompt with optional device
 * credential) are ported from OctoGram (it.octogram.android.utils.account.
 * FingerprintUtils, app/fragment/ActionBarOverride, GPLv2+) and Cherrygram
 * (uz.unnarsx.cherrygram.core.CGBiometricPrompt, GPLv2+). Rewritten to live in
 * one class with small hook calls in upstream files.
 *
 * Whole-app lock is NOT reimplemented: upstream Telegram's passcode lock
 * (Settings > Privacy > Passcode) already locks the app with PIN/password and
 * optional fingerprint unlock. This class only adds what upstream lacks:
 * locking individual chats, the archive and secret chats, and hiding locked
 * chats from the chat list / search until revealed.
 *
 * Purely local: nothing here talks to the network.
 */
package it.belloworld.mercurygram;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.TopicsFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;

public final class PlusChatLock {

    /** Row id used by the block in MercurygramSettingsActivity. */
    public static final int SETTINGS_ROW_ID = 9080;

    private static final String PREFS = "plus_f08_chatlock";
    private static final String KEY_LOCKED_PREFIX = "locked_"; // + clientUserId
    public static final String KEY_LOCK_ARCHIVE = "lock_archive";
    public static final String KEY_LOCK_SECRET = "lock_secret";
    public static final String KEY_HIDE_LOCKED = "hide_locked";
    public static final String KEY_MASK_NOTIFICATIONS = "mask_notifications";
    public static final String KEY_ALLOW_DEVICE_CREDENTIAL = "allow_device_credential";

    private static final Object sync = new Object();
    // clientUserId -> locked dialog ids
    private static final HashMap<Long, HashSet<Long>> lockedCache = new HashMap<>();

    // Session state: cleared after RELOCK_GRACE_MS in the background (or process death).
    private static final HashSet<String> unlockedDialogs = new HashSet<>(); // "acc:dialogId"
    private static boolean archiveUnlocked;
    private static final HashSet<Integer> revealedAccounts = new HashSet<>();

    private static BaseFragment bypassFragment;
    private static boolean authInProgress;
    private static BiometricPrompt pendingPrompt;
    private static boolean lifecycleRegistered;
    private static long backgroundSince; // SystemClock.elapsedRealtime() when LaunchActivity stopped, 0 = foreground
    /**
     * How long the app may stay in the background before unlocked chats lock
     * again. Not zero so that picking a photo with the system camera or file
     * picker does not throw you out of the chat you are attaching to.
     */
    private static final long RELOCK_GRACE_MS = 60_000L;

    private static final WeakHashMap<ActionBarMenuItem, ActionBarMenuItem.Item> menuItems = new WeakHashMap<>();

    private PlusChatLock() {
    }

    public interface AuthCallback {
        void onResult(boolean success);
    }

    // ------------------------------------------------------------------ config

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean getBool(String key) {
        boolean def = KEY_MASK_NOTIFICATIONS.equals(key) || KEY_ALLOW_DEVICE_CREDENTIAL.equals(key);
        return prefs().getBoolean(key, def);
    }

    public static void setBool(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        if (KEY_HIDE_LOCKED.equals(key)) {
            synchronized (sync) {
                revealedAccounts.clear();
            }
            reloadAllDialogs();
        }
    }

    private static long userIdOf(int account) {
        if (!PlusUtil.validAccount(account)) {
            return 0;
        }
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static HashSet<Long> lockedSet(int account) {
        long uid = userIdOf(account);
        if (uid == 0) {
            return null;
        }
        synchronized (sync) {
            HashSet<Long> set = lockedCache.get(uid);
            if (set == null) {
                set = new HashSet<>();
                String raw = prefs().getString(KEY_LOCKED_PREFIX + uid, "");
                if (!TextUtils.isEmpty(raw)) {
                    for (String part : raw.split(",")) {
                        try {
                            if (!part.isEmpty()) {
                                set.add(Long.parseLong(part));
                            }
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
                lockedCache.put(uid, set);
            }
            return set;
        }
    }

    private static void saveLocked(int account, HashSet<Long> set) {
        long uid = userIdOf(account);
        if (uid == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        synchronized (sync) {
            for (Long id : set) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
        }
        if (sb.length() == 0) {
            prefs().edit().remove(KEY_LOCKED_PREFIX + uid).apply();
        } else {
            prefs().edit().putString(KEY_LOCKED_PREFIX + uid, sb.toString()).apply();
        }
    }

    public static boolean isLocked(int account, long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        HashSet<Long> set = lockedSet(account);
        if (set == null) {
            return false;
        }
        synchronized (sync) {
            return set.contains(dialogId);
        }
    }

    public static boolean hasLocked(int account) {
        HashSet<Long> set = lockedSet(account);
        if (set == null) {
            return false;
        }
        synchronized (sync) {
            return !set.isEmpty();
        }
    }

    public static ArrayList<Long> getLocked(int account) {
        HashSet<Long> set = lockedSet(account);
        ArrayList<Long> out = new ArrayList<>();
        if (set != null) {
            synchronized (sync) {
                out.addAll(set);
            }
        }
        return out;
    }

    public static void setLocked(int account, long dialogId, boolean locked) {
        HashSet<Long> set = lockedSet(account);
        if (set == null || dialogId == 0) {
            return;
        }
        boolean changed;
        synchronized (sync) {
            changed = locked ? set.add(dialogId) : set.remove(dialogId);
            if (locked) {
                // You are inside the chat when you lock it: keep it open for this session.
                unlockedDialogs.add(account + ":" + dialogId);
            }
        }
        if (changed) {
            saveLocked(account, set);
            reloadDialogs(account);
        }
    }

    // ------------------------------------------------------------ biometrics

    private static int authenticators() {
        int a = BiometricManager.Authenticators.BIOMETRIC_WEAK;
        if (getBool(KEY_ALLOW_DEVICE_CREDENTIAL)) {
            // WEAK | DEVICE_CREDENTIAL is supported on every API level by androidx.biometric.
            a |= BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        }
        return a;
    }

    /** True when the device can actually verify the user (biometric, or screen lock if allowed). */
    public static boolean canAuthenticate() {
        try {
            return BiometricManager.from(ApplicationLoader.applicationContext).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Ask for biometrics. If the device cannot authenticate at all (no
     * fingerprint/face and no screen lock allowed) the lock fails open, same
     * as OctoGram: removing the device's biometrics or screen lock already
     * requires the device credential, and failing closed would lock the user
     * out of their own chats.
     */
    public static void authenticate(@Nullable CharSequence subtitle, @NonNull AuthCallback callback) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!canAuthenticate()) {
                callback.onResult(true);
                return;
            }
            LaunchActivity activity = LaunchActivity.instance;
            if (activity == null || activity.isFinishing()) {
                callback.onResult(false);
                return;
            }
            if (authInProgress && pendingPrompt != null) {
                // a stale prompt (e.g. its activity was recreated) must not block the lock forever
                try {
                    pendingPrompt.cancelAuthentication();
                } catch (Throwable ignore) {
                }
            }
            authInProgress = true;
            try {
                BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        authInProgress = false;
                        pendingPrompt = null;
                        callback.onResult(true);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        authInProgress = false;
                        pendingPrompt = null;
                        callback.onResult(false);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // a single bad attempt; the system prompt stays open
                    }
                });
                int auth = authenticators();
                BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(LocaleController.getString(R.string.PlusF08UnlockTitle))
                        .setAllowedAuthenticators(auth)
                        .setConfirmationRequired(false);
                if (subtitle != null) {
                    builder.setSubtitle(subtitle);
                }
                if ((auth & BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0) {
                    builder.setNegativeButtonText(LocaleController.getString(R.string.Cancel));
                }
                pendingPrompt = prompt;
                prompt.authenticate(builder.build());
            } catch (Throwable e) {
                FileLog.e(e);
                authInProgress = false;
                pendingPrompt = null;
                callback.onResult(false);
            }
        });
    }

    // ------------------------------------------------- session / lifecycle

    private static void ensureLifecycle() {
        if (lifecycleRegistered) {
            return;
        }
        lifecycleRegistered = true;
        try {
            ((Application) ApplicationLoader.applicationContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(@NonNull Activity activity) {
                    if (!(activity instanceof LaunchActivity)) {
                        return;
                    }
                    long since = backgroundSince;
                    backgroundSince = 0;
                    if (since != 0 && SystemClock.elapsedRealtime() - since >= RELOCK_GRACE_MS) {
                        relock();
                    }
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                }

                @Override
                public void onActivityPaused(@NonNull Activity activity) {
                }

                @Override
                public void onActivityStopped(@NonNull Activity activity) {
                    if (activity instanceof LaunchActivity && !authInProgress && !activity.isChangingConfigurations()) {
                        backgroundSince = SystemClock.elapsedRealtime();
                    }
                }

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Forget every unlock of this session and close open locked screens. */
    public static void relock() {
        boolean hadRevealed;
        synchronized (sync) {
            unlockedDialogs.clear();
            archiveUnlocked = false;
            hadRevealed = !revealedAccounts.isEmpty();
            revealedAccounts.clear();
        }
        LaunchActivity activity = LaunchActivity.instance;
        if (activity != null) {
            closeLockedFragments(activity.getActionBarLayout());
            closeLockedFragments(activity.getRightActionBarLayout());
            closeLockedFragments(activity.getLayersActionBarLayout());
        }
        if (hadRevealed) {
            reloadAllDialogs();
        }
    }

    private static void closeLockedFragments(INavigationLayout layout) {
        if (layout == null) {
            return;
        }
        try {
            List<BaseFragment> stack = layout.getFragmentStack();
            if (stack == null || stack.size() <= 1) {
                return;
            }
            int lowest = -1;
            for (int i = 1; i < stack.size(); i++) {
                if (requiresUnlock(stack.get(i))) {
                    lowest = i;
                    break;
                }
            }
            if (lowest < 0) {
                return;
            }
            // Close everything from the top down to the first locked screen:
            // whatever sits above it (media, profile, ...) belongs to it.
            int guard = stack.size();
            while (stack.size() > lowest && stack.size() > 1 && guard-- > 0) {
                BaseFragment top = stack.get(stack.size() - 1);
                if (!top.finishFragment(false)) {
                    top.removeSelfFromStack(true);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean isSessionUnlocked(int account, long dialogId) {
        synchronized (sync) {
            return unlockedDialogs.contains(account + ":" + dialogId);
        }
    }

    // ------------------------------------------------- presentFragment gate

    private static long dialogIdFromArgs(Bundle args, boolean allowDialogIdKey) {
        if (args == null) {
            return 0;
        }
        int encId = args.getInt("enc_id", 0);
        if (encId != 0) {
            return DialogObject.makeEncryptedDialogId(encId);
        }
        long userId = args.getLong("user_id", 0);
        if (userId != 0) {
            return userId;
        }
        long chatId = args.getLong("chat_id", 0);
        if (chatId != 0) {
            return -chatId;
        }
        if (allowDialogIdKey) {
            return args.getLong("dialog_id", 0);
        }
        return 0;
    }

    private static final int GATE_NONE = 0;
    private static final int GATE_DIALOG = 1;
    private static final int GATE_ARCHIVE = 2;

    private static int gateType(BaseFragment fragment) {
        if (fragment == null) {
            return GATE_NONE;
        }
        Bundle args = fragment.getArguments();
        if (fragment instanceof ChatActivity || fragment instanceof ProfileActivity || fragment instanceof TopicsFragment) {
            long did = dialogIdFromArgs(args, fragment instanceof ProfileActivity);
            if (did == 0 || fragment instanceof ProfileActivity && did == userIdOf(fragment.getCurrentAccount())) {
                // your own profile doubles as the settings screen: never gate it
                return GATE_NONE;
            }
            return GATE_DIALOG;
        }
        if (fragment instanceof DialogsActivity && args != null && args.getInt("folderId", 0) == 1) {
            return GATE_ARCHIVE;
        }
        return GATE_NONE;
    }

    private static boolean requiresUnlock(BaseFragment fragment) {
        int type = gateType(fragment);
        if (type == GATE_NONE) {
            return false;
        }
        int account = fragment.getCurrentAccount();
        if (type == GATE_ARCHIVE) {
            if (!getBool(KEY_LOCK_ARCHIVE)) {
                return false;
            }
            synchronized (sync) {
                return !archiveUnlocked;
            }
        }
        long dialogId = dialogIdFromArgs(fragment.getArguments(), fragment instanceof ProfileActivity);
        if (dialogId == 0) {
            return false;
        }
        return isDialogLockedNow(account, dialogId);
    }

    /**
     * Is this dialog behind the lock right now (locked, or a secret chat with "lock secret
     * chats" on, and not unlocked in this session)? Also used by other features that show
     * a chat's content outside the chat (f20 deleted-media browser).
     */
    public static boolean isDialogLockedNow(int account, long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        boolean locked = isLocked(account, dialogId)
                || (DialogObject.isEncryptedDialog(dialogId) && getBool(KEY_LOCK_SECRET));
        return locked && !isSessionUnlocked(account, dialogId);
    }

    /**
     * Hook at the top of ActionBarLayout.presentFragment(NavigationParams).
     * Returns true when the presentation was taken over (the caller must
     * return false); it is replayed after a successful unlock.
     */
    public static boolean interceptPresent(INavigationLayout layout, INavigationLayout.NavigationParams params) {
        if (params == null || params.fragment == null) {
            return false;
        }
        BaseFragment fragment = params.fragment;
        if (fragment == bypassFragment) {
            bypassFragment = null;
            return false;
        }
        if (!requiresUnlock(fragment)) {
            return false;
        }
        ensureLifecycle();
        if (params.preview) {
            // No peeking into a locked chat with a long-press preview.
            if (gateType(fragment) == GATE_DIALOG) {
                toast(LocaleController.getString(R.string.PlusF12PeekLocked)); // plus f12: say why instead of failing silently
            }
            return true;
        }
        final int account = fragment.getCurrentAccount();
        final int type = gateType(fragment);
        final long dialogId = type == GATE_DIALOG ? dialogIdFromArgs(fragment.getArguments(), fragment instanceof ProfileActivity) : 0;
        authenticate(type == GATE_ARCHIVE ? LocaleController.getString(R.string.ArchivedChats) : null, success -> {
            if (!success) {
                return;
            }
            synchronized (sync) {
                if (type == GATE_ARCHIVE) {
                    archiveUnlocked = true;
                } else {
                    unlockedDialogs.add(account + ":" + dialogId);
                }
            }
            bypassFragment = fragment;
            try {
                layout.presentFragment(params);
            } finally {
                bypassFragment = null;
            }
        });
        return true;
    }

    /**
     * Hook at the top of ActionBarLayout.addFragmentToStack: a locked screen
     * is never added silently (e.g. restored after the process was killed);
     * true = refuse.
     */
    public static boolean blocksAddToStack(BaseFragment fragment) {
        return fragment != null && fragment != bypassFragment && requiresUnlock(fragment);
    }

    // ------------------------------------------------------- hidden chats

    private static boolean hidingActive(int account) {
        if (!getBool(KEY_HIDE_LOCKED) || !hasLocked(account)) {
            return false;
        }
        synchronized (sync) {
            return !revealedAccounts.contains(account);
        }
    }

    public static boolean isDialogHidden(int account, long dialogId) {
        return hidingActive(account) && isLocked(account, dialogId);
    }

    /**
     * Plus lists and logs (f01 exceptions, f07 vanished chats, f05 activity log) leave this
     * dialog out: it is hidden from the chat list and behind the lock right now. Display only,
     * nothing stored is touched.
     */
    public static boolean isHiddenFromLists(int account, long dialogId) {
        return isDialogHidden(account, dialogId) && isDialogLockedNow(account, dialogId);
    }

    /**
     * Hook in DialogsAdapter.updateItemList. Never mutates the passed list
     * (it is usually MessagesController's own dialogs list).
     */
    public static ArrayList<TLRPC.Dialog> filterDialogs(int account, ArrayList<TLRPC.Dialog> array) {
        if (array == null || array.isEmpty() || !hidingActive(account)) {
            return array;
        }
        ArrayList<TLRPC.Dialog> out = null;
        for (int i = 0; i < array.size(); i++) {
            TLRPC.Dialog d = array.get(i);
            boolean hide = d != null && isLocked(account, d.id);
            if (hide && out == null) {
                out = new ArrayList<>(array.size());
                for (int j = 0; j < i; j++) {
                    out.add(array.get(j));
                }
            }
            if (!hide && out != null) {
                out.add(d);
            }
        }
        return out != null ? out : array;
    }

    /** Hook in DialogsSearchAdapter.filter(Object): true = drop the result. */
    public static boolean isHiddenSearchResult(int account, Object obj) {
        if (!hidingActive(account)) {
            return false;
        }
        long dialogId = 0;
        if (obj instanceof TLRPC.User) {
            dialogId = ((TLRPC.User) obj).id;
        } else if (obj instanceof TLRPC.Chat) {
            dialogId = -((TLRPC.Chat) obj).id;
        } else if (obj instanceof TLRPC.EncryptedChat) {
            dialogId = DialogObject.makeEncryptedDialogId(((TLRPC.EncryptedChat) obj).id);
        }
        return dialogId != 0 && isLocked(account, dialogId);
    }

    /**
     * Hook in DialogsActivity's title long-press: toggles the hidden chats
     * between revealed and hidden. Returns false (does nothing visible) when
     * hiding is off or there is nothing hidden, so the gesture stays secret.
     */
    public static boolean onDialogsTitleLongPress(BaseFragment fragment) {
        if (fragment == null || !getBool(KEY_HIDE_LOCKED)) {
            return false;
        }
        final int account = fragment.getCurrentAccount();
        if (!hasLocked(account)) {
            return false;
        }
        ensureLifecycle();
        boolean revealed;
        synchronized (sync) {
            revealed = revealedAccounts.contains(account);
        }
        if (revealed) {
            synchronized (sync) {
                revealedAccounts.remove(account);
            }
            reloadDialogs(account);
            toast(LocaleController.getString(R.string.PlusF08HiddenAgain));
            return true;
        }
        authenticate(LocaleController.getString(R.string.PlusF08RevealSubtitle), success -> {
            if (!success) {
                return;
            }
            synchronized (sync) {
                revealedAccounts.add(account);
            }
            reloadDialogs(account);
            toast(LocaleController.getString(R.string.PlusF08Revealed));
        });
        return true;
    }

    // ------------------------------------------------------- notifications

    /**
     * Hook in NotificationsController.getStringForMessage /
     * getShortStringForMessage: true = show the generic "new message" text
     * instead of the content.
     */
    public static boolean hideNotificationContent(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || !getBool(KEY_MASK_NOTIFICATIONS)) {
            return false;
        }
        long dialogId = messageObject.messageOwner.dialog_id;
        if (dialogId == 0) {
            dialogId = messageObject.getDialogId();
        }
        if (DialogObject.isEncryptedDialog(dialogId) && getBool(KEY_LOCK_SECRET)) {
            return true;
        }
        return isLocked(account, dialogId);
    }

    // ---------------------------------------------------------- chat menu

    /** Hook in ChatActivity's header menu: adds "Lock chat"/"Unlock chat". */
    public static void addChatMenuItem(ActionBarMenuItem headerItem, int id, int account, long dialogId) {
        if (headerItem == null || dialogId == 0) {
            return;
        }
        boolean locked = isLocked(account, dialogId);
        ActionBarMenuItem.Item item = headerItem.lazilyAddSubItem(id, locked ? R.drawable.menu_unlock : R.drawable.msg_secret,
                LocaleController.getString(locked ? R.string.PlusF08UnlockChat : R.string.PlusF08LockChat));
        if (item != null) {
            menuItems.put(headerItem, item);
        }
    }

    /** Hook in ChatActivity's menu click handler. */
    public static void onChatMenuItemClick(ActionBarMenuItem headerItem, int account, long dialogId) {
        if (dialogId == 0) {
            return;
        }
        ensureLifecycle();
        boolean newState = !isLocked(account, dialogId);
        if (newState && !canAuthenticate()) {
            toast(LocaleController.getString(R.string.PlusF08NoBiometrics));
            return;
        }
        setLocked(account, dialogId, newState);
        ActionBarMenuItem.Item item = headerItem != null ? menuItems.get(headerItem) : null;
        if (item != null) {
            item.setText(LocaleController.getString(newState ? R.string.PlusF08UnlockChat : R.string.PlusF08LockChat));
            item.setIcon(newState ? R.drawable.menu_unlock : R.drawable.msg_secret);
        }
        toast(LocaleController.getString(newState
                ? (getBool(KEY_HIDE_LOCKED) ? R.string.PlusF08LockedAndHidden : R.string.PlusF08Locked)
                : R.string.PlusF08Unlocked));
    }

    // ---------------------------------------------------------- settings

    /** Opens the chat lock settings screen, behind an unlock. */
    public static void openSettings(BaseFragment from) {
        if (from == null) {
            return;
        }
        ensureLifecycle();
        authenticate(LocaleController.getString(R.string.PlusF08Title), success -> {
            if (success) {
                from.presentFragment(new it.belloworld.mercurygram.ui.PlusChatLockSettingsActivity());
            }
        });
    }

    public static String settingsSummary(int account) {
        int n = getLocked(account).size();
        if (n == 0 && !getBool(KEY_LOCK_ARCHIVE) && !getBool(KEY_LOCK_SECRET)) {
            return LocaleController.getString(R.string.PasswordOff);
        }
        return Integer.toString(n);
    }

    public static String dialogName(int account, long dialogId) {
        if (dialogId > 0 && dialogId == userIdOf(account)) {
            return LocaleController.getString(R.string.SavedMessages);
        }
        return PlusUtil.dialogTitle(account, dialogId, Long.toString(dialogId));
    }

    // ------------------------------------------------------------ helpers

    private static void reloadDialogs(int account) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload));
    }

    private static void reloadAllDialogs() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.isValidAccount(a)) {
                reloadDialogs(a);
            }
        }
    }

    private static void toast(String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignore) {
            }
        });
    }
}
