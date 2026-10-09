package it.belloworld.mercurygram;

import android.app.Activity;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * hanako: "confirm it's you" in front of actions that copy or rewrite private data
 * (full backup, settings import / restore, export of per-chat lists, chat export).
 *
 * <p>Uses the same biometric prompt as Chat lock, but always allows the device screen lock.
 * When the device has neither, Telegram's own passcode is asked instead if one is set, with the
 * same growing wait after wrong tries as Telegram's passcode screen. With no way to verify
 * anything, most actions go ahead after a one-time warning (the person holding the phone could
 * set a screen lock themselves), but a full backup is refused: it copies the login sessions.
 *
 * <p>A success is remembered for two minutes so one flow (for example Create backup, then the
 * file picker) asks only once. It never stands in for Chat lock: a locked chat always asks
 * Chat lock itself, with Chat lock's own biometrics-only setting.
 */
public final class HanakoAuthGate {

    private static final long RECENT_MS = 120_000L;
    private static long lastVerified; // SystemClock.elapsedRealtime(), 0 = never

    private HanakoAuthGate() {
    }

    private static final String NO_LOCK_SEEN_KEY = "hanako_auth_no_lock_seen";

    public static boolean recentlyVerified() {
        return lastVerified != 0 && SystemClock.elapsedRealtime() - lastVerified < RECENT_MS;
    }

    /** Drops the two-minute grace (the backup screen calls this when it closes). */
    public static void forget() {
        lastVerified = 0;
    }

    private static void markVerified() {
        lastVerified = SystemClock.elapsedRealtime();
    }

    /** Runs {@code onSuccess} on the UI thread once the user is confirmed; does nothing on cancel. */
    public static void require(BaseFragment fragment, CharSequence subtitle, Runnable onSuccess) {
        require(fragment, subtitle, false, onSuccess);
    }

    /**
     * @param failClosed refuse when the phone has no screen lock and no Telegram passcode
     *                   (full backup), instead of going ahead after a warning
     */
    public static void require(BaseFragment fragment, CharSequence subtitle, boolean failClosed, Runnable onSuccess) {
        if (onSuccess == null) {
            return;
        }
        if (recentlyVerified()) {
            onSuccess.run();
            return;
        }
        if (PlusChatLock.canAuthenticateStrict()) {
            PlusChatLock.authenticateStrict(LocaleController.getString(R.string.HanakoAuthTitle), subtitle, success -> {
                if (success) {
                    markVerified();
                    onSuccess.run();
                }
            });
            return;
        }
        if (!TextUtils.isEmpty(SharedConfig.passcodeHash) && fragment != null && fragment.getParentActivity() != null) {
            askPasscode(fragment, subtitle, onSuccess);
            return;
        }
        noLock(fragment, failClosed, onSuccess);
    }

    /* Nothing on this phone can confirm the user: refuse, or warn once and go ahead. */
    private static void noLock(BaseFragment fragment, boolean failClosed, Runnable onSuccess) {
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        android.content.SharedPreferences p = org.telegram.messenger.MessagesController.getGlobalMainSettings();
        if (activity == null) {
            if (!failClosed) onSuccess.run();
            return;
        }
        if (!failClosed && p.getBoolean(NO_LOCK_SEEN_KEY, false)) {
            onSuccess.run();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoAuthNoLockTitle));
        if (failClosed) {
            b.setMessage(LocaleController.getString(R.string.HanakoAuthNoLockRefused));
            b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        } else {
            b.setMessage(LocaleController.getString(R.string.HanakoAuthNoLockNotice));
            b.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
                p.edit().putBoolean(NO_LOCK_SEEN_KEY, true).apply();
                onSuccess.run();
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        }
        fragment.showDialog(b.create());
    }

    /* Telegram's passcode back-off (PasscodeView): count down the wait since the last bad try. */
    private static boolean passcodeWaiting() {
        long now = SystemClock.elapsedRealtime();
        if (now > SharedConfig.lastUptimeMillis) {
            SharedConfig.passcodeRetryInMs -= now - SharedConfig.lastUptimeMillis;
            if (SharedConfig.passcodeRetryInMs < 0) SharedConfig.passcodeRetryInMs = 0;
        }
        SharedConfig.lastUptimeMillis = now;
        return SharedConfig.passcodeRetryInMs > 0;
    }

    private static String passcodeWaitText() {
        int seconds = Math.max(1, (int) Math.ceil(SharedConfig.passcodeRetryInMs / 1000.0));
        return LocaleController.formatString(R.string.TooManyTries, LocaleController.formatPluralString("Seconds", seconds));
    }

    private static void askPasscode(BaseFragment fragment, CharSequence subtitle, Runnable onSuccess) {
        Activity activity = fragment.getParentActivity();
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView info = new TextView(activity);
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            info.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
            info.setText(subtitle);
            layout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        }
        final EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        field.setHint(LocaleController.getString(R.string.HanakoAuthPasscodeHint));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, rp));
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        final TextView error = new TextView(activity);
        error.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular, rp));
        error.setVisibility(View.GONE);
        layout.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        AlertDialog.Builder b = new AlertDialog.Builder(activity, rp);
        b.setTitle(LocaleController.getString(R.string.HanakoAuthTitle));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            if (passcodeWaiting()) {
                field.setText("");
                error.setText(passcodeWaitText());
                error.setVisibility(View.VISIBLE);
                return;
            }
            String code = field.getText() != null ? field.getText().toString() : "";
            field.setText("");
            if (!code.isEmpty() && SharedConfig.checkPasscode(code)) {
                SharedConfig.badPasscodeTries = 0;
                SharedConfig.saveConfig();
                d.dismiss();
                markVerified();
                onSuccess.run();
            } else {
                SharedConfig.increaseBadPasscodeTries();
                error.setText(SharedConfig.passcodeRetryInMs > 0 ? passcodeWaitText()
                        : LocaleController.getString(R.string.HanakoAuthPasscodeWrong));
                error.setVisibility(View.VISIBLE);
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            field.setText("");
            d.dismiss();
        });
        AlertDialog dialog = b.create();
        dialog.setDismissDialogByButtons(false);
        fragment.showDialog(dialog);
        dialog.setCanceledOnTouchOutside(false);
        AndroidUtilities.runOnUIThread(() -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }, 200);
    }
}
