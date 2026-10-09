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
 * When the device has neither, Telegram's own passcode is asked instead if one is set. With no
 * way to verify anything it fails open, the same rule Chat lock follows (the person holding the
 * phone could set a screen lock themselves). A success is remembered for two minutes so one
 * flow (for example Export a chat, then a locked chat in the picker) asks only once.
 */
public final class HanakoAuthGate {

    private static final long RECENT_MS = 120_000L;
    private static long lastVerified; // SystemClock.elapsedRealtime(), 0 = never

    private HanakoAuthGate() {
    }

    public static boolean recentlyVerified() {
        return lastVerified != 0 && SystemClock.elapsedRealtime() - lastVerified < RECENT_MS;
    }

    private static void markVerified() {
        lastVerified = SystemClock.elapsedRealtime();
    }

    /** Runs {@code onSuccess} on the UI thread once the user is confirmed; does nothing on cancel. */
    public static void require(BaseFragment fragment, CharSequence subtitle, Runnable onSuccess) {
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
        onSuccess.run();
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
            String code = field.getText() != null ? field.getText().toString() : "";
            if (!code.isEmpty() && SharedConfig.checkPasscode(code)) {
                field.setText("");
                d.dismiss();
                markVerified();
                onSuccess.run();
            } else {
                field.setText("");
                error.setText(LocaleController.getString(R.string.HanakoAuthPasscodeWrong));
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
