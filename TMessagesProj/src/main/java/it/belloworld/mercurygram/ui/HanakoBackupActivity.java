/*
 * hanako: Settings > Hanako > Backup & export: encrypted backup and restore of the account
 * sessions (HanakoBackup), and the entry point of the per-chat export. Also opened in
 * restore-only mode from the login screen.
 *
 * Creating a backup, restoring one while accounts are signed in, and exporting a chat ask
 * HanakoAuthGate first: biometrics / screen lock, or Telegram's passcode when the phone has
 * neither.
 */
package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.DialogsActivity;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import it.belloworld.mercurygram.HanakoAuthGate;
import it.belloworld.mercurygram.HanakoBackup;
import it.belloworld.mercurygram.HiddenAccountHelper;
import it.belloworld.mercurygram.PlusChatLock;

public class HanakoBackupActivity extends UniversalFragment {

    private static final int ID_CREATE_BACKUP = 3;
    private static final int ID_RESTORE_BACKUP = 4;
    private static final int ID_EXPORT_CHAT = 5;

    private static final int REQ_CREATE_BACKUP = 7303;
    private static final int REQ_RESTORE_BACKUP = 7304;

    /** Password strength levels of {@link #strength(CharSequence)}. */
    private static final int STRENGTH_WEAK = 0;
    private static final int STRENGTH_OK = 1;
    private static final int STRENGTH_STRONG = 2;

    private final boolean restoreOnly;

    /** The new backup's password while the system file picker is open (wiped when it closes). */
    private char[] pendingPassword;

    public HanakoBackupActivity() {
        this(false);
    }

    /** @param restoreOnly opened from the login screen: only Restore makes sense there. */
    public HanakoBackupActivity(boolean restoreOnly) {
        super();
        this.restoreOnly = restoreOnly;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HanakoBackupTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoBackupFullHeader)));
        if (!restoreOnly) {
            items.add(UItem.asButton(ID_CREATE_BACKUP, R.drawable.msg_download, LocaleController.getString(R.string.HanakoBackupCreate)));
        }
        items.add(UItem.asButton(ID_RESTORE_BACKUP, R.drawable.msg_reset, LocaleController.getString(R.string.HanakoBackupRestore)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoBackupFullInfo)));
        if (!restoreOnly) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoBackupChatHeader)));
            items.add(UItem.asButton(ID_EXPORT_CHAT, R.drawable.msg_share, LocaleController.getString(R.string.HanakoBackupExportChat)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoBackupChatInfo)));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_CREATE_BACKUP:
                // copies the logins: refused on a phone with no lock at all
                HanakoAuthGate.require(this, LocaleController.getString(R.string.HanakoAuthBackup), true, this::showBackupWarning);
                break;
            case ID_RESTORE_BACKUP:
                if (UserConfig.getActivatedAccountsCount() > 0) {
                    HanakoAuthGate.require(this, LocaleController.getString(R.string.HanakoAuthRestore), this::openBackupFile);
                } else {
                    openBackupFile(); // login screen: nothing here to protect yet
                }
                break;
            case ID_EXPORT_CHAT:
                HanakoAuthGate.require(this, LocaleController.getString(R.string.HanakoAuthExportChat), this::openChatPicker);
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        wipePending();
        HanakoAuthGate.forget();
    }

    private void wipePending() {
        if (pendingPassword != null) {
            Arrays.fill(pendingPassword, '\0');
            pendingPassword = null;
        }
    }

    // ---------------------------------------------------------------------------------
    // pickers

    private void openBackupFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_RESTORE_BACKUP);
        } catch (Exception e) {
            FileLog.e(e);
            showError(e);
        }
    }

    private void openChatPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowGlobalSearch", false);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        DialogsActivity picker = new DialogsActivity(args);
        final int account = currentAccount;
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            final long did = dids.get(0).dialogId;
            if (PlusChatLock.isDialogLockedNow(account, did)) {
                // A locked chat always asks Chat lock itself (its biometrics-only setting
                // included); "Confirm it's you" never stands in for it. The unlock is for
                // this export only, the chat stays locked in normal use.
                PlusChatLock.authenticate(PlusChatLock.dialogName(account, did), success -> {
                    if (success) {
                        fragment.presentFragment(new HanakoChatExportActivity(did, true), true);
                    }
                });
                return true;
            }
            fragment.presentFragment(new HanakoChatExportActivity(did), true);
            return true;
        });
        presentFragment(picker);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (requestCode == REQ_CREATE_BACKUP) {
            char[] pw = pendingPassword;
            pendingPassword = null;
            if (uri != null && pw != null) {
                createBackup(uri, pw);
            } else if (pw != null) {
                Arrays.fill(pw, '\0');
            }
        } else if (requestCode == REQ_RESTORE_BACKUP && uri != null) {
            askRestorePassword(uri, false);
        }
    }

    // ---------------------------------------------------------------------------------
    // small dialog helpers

    private CheckBoxCell checkRow(Context ctx, CharSequence text, boolean checked) {
        final CheckBoxCell cell = new CheckBoxCell(ctx, 1, getResourceProvider());
        cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        cell.setMultiline(true);
        cell.getTextView().getLayoutParams().width = LayoutHelper.MATCH_PARENT;
        cell.getTextView().setSingleLine(false);
        cell.getTextView().setMaxLines(4);
        cell.setText(text, "", checked, false);
        cell.setPadding(LocaleController.isRTL ? AndroidUtilities.dp(16) : AndroidUtilities.dp(8), 0, LocaleController.isRTL ? AndroidUtilities.dp(8) : AndroidUtilities.dp(16), 0);
        cell.setOnClickListener(v -> cell.setChecked(!cell.isChecked(), true));
        return cell;
    }

    private TextView dialogText(Context ctx, CharSequence text, int sizeDp, int colorKey) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        tv.setTextColor(Theme.getColor(colorKey, getResourceProvider()));
        tv.setText(text);
        return tv;
    }

    // ---------------------------------------------------------------------------------
    // backup

    private void showBackupWarning() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String summary = LocaleController.formatString(R.string.HanakoBackupWillSave,
                LocaleController.formatPluralString("HanakoAccounts", HiddenAccountHelper.getVisibleAccountsCount()));
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupWarningTitle));
        b.setMessage(summary + "\n\n" + LocaleController.getString(R.string.HanakoBackupWarning));
        // the password first, then where to save: cancelling never leaves an empty file
        b.setPositiveButton(LocaleController.getString(R.string.HanakoBackupContinue), (d, w) -> askPassword(true, false, password -> {
            wipePending();
            pendingPassword = password;
            String name = "hanako-sessions-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date()) + ".hnkbak";
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(intent, REQ_CREATE_BACKUP);
            } catch (Exception e) {
                FileLog.e(e);
                wipePending();
                showError(e);
            }
        }));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void createBackup(Uri uri, char[] password) {
        final Context ctx = ApplicationLoader.applicationContext;
        final AlertDialog progress = showProgress();
        Utilities.globalQueue.postRunnable(() -> {
            int[] count = {0, 0};
            Throwable error = null;
            try {
                count = HanakoBackup.write(ctx, uri, password);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t;
            }
            final int n = count[0];
            final int skipped = count[1];
            final Throwable err = error;
            AndroidUtilities.runOnUIThread(() -> {
                dismiss(progress);
                if (err != null) {
                    deleteQuietly(uri);
                    showError(err);
                    return;
                }
                String name = documentName(uri);
                String accounts = LocaleController.formatPluralString("HanakoAccounts", n);
                String text = TextUtils.isEmpty(name)
                        ? LocaleController.formatString(R.string.HanakoBackupSavedDialogNoName, accounts)
                        : LocaleController.formatString(R.string.HanakoBackupSavedDialog, name, accounts);
                if (skipped > 0) {
                    text += "\n\n" + LocaleController.formatPluralString("HanakoBackupSkippedAccounts", skipped);
                }
                if (getParentActivity() == null) {
                    toast(text);
                    return;
                }
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                b.setTitle(LocaleController.getString(R.string.HanakoBackupSavedTitle));
                b.setMessage(text);
                b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                showDialog(b.create());
            });
        });
    }

    private static String documentName(Uri uri) {
        try (android.database.Cursor c = ApplicationLoader.applicationContext.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // restore

    private void askRestorePassword(Uri uri, boolean wrongBefore) {
        askPassword(false, wrongBefore, password -> readBackup(uri, password));
    }

    private void readBackup(Uri uri, char[] password) {
        final Context ctx = ApplicationLoader.applicationContext;
        final AlertDialog progress = showProgress();
        Utilities.globalQueue.postRunnable(() -> {
            HanakoBackup.Backup backup = null;
            Throwable error = null;
            try {
                backup = HanakoBackup.read(ctx, uri, password);
            } catch (Throwable t) {
                if (!(t instanceof HanakoBackup.WrongPasswordException)) FileLog.e(t);
                error = t;
            }
            final HanakoBackup.Backup b = backup;
            final Throwable err = error;
            AndroidUtilities.runOnUIThread(() -> {
                dismiss(progress);
                if (err instanceof HanakoBackup.WrongPasswordException) {
                    askRestorePassword(uri, true);
                } else if (b == null) {
                    showError(err);
                } else {
                    showRestorePlan(b);
                }
            });
        });
    }

    /**
     * One checkbox per account of the backup: new ones are ticked while free slots last; one
     * signed in here is shown as skipped, or not at all when it is hidden here.
     */
    private void showRestorePlan(HanakoBackup.Backup b) {
        Activity activity = getParentActivity();
        if (activity == null) {
            b.wipe();
            return;
        }
        final int freeSlots = HanakoBackup.freeSlots().size();
        final int n = b.accounts.size();
        final CheckBoxCell[] cells = new CheckBoxCell[n];
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(dialogText(activity, LocaleController.getString(R.string.HanakoRestorePick), 15, Theme.key_dialogTextBlack),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        int ticked = 0;
        for (int i = 0; i < n; i++) {
            HanakoBackup.Account e = b.accounts.get(i);
            int here = HanakoBackup.slotOf(e.user.id);
            if (here >= 0 && HiddenAccountHelper.isAccountHidden(here)) continue;
            String state;
            if (here >= 0) {
                state = LocaleController.getString(R.string.HanakoBackupAccountSkipExisting);
            } else if (ticked < freeSlots) {
                state = LocaleController.getString(R.string.HanakoBackupAccountNew);
            } else {
                state = LocaleController.getString(R.string.HanakoBackupAccountNoSlot);
            }
            CheckBoxCell cell = checkRow(activity, HanakoBackup.label(e) + "\n" + state, here < 0 && ticked < freeSlots);
            if (here >= 0) {
                cell.setEnabled(false);
                cell.setOnClickListener(null);
            } else {
                cells[i] = cell;
                if (ticked < freeSlots) ticked++;
            }
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        layout.addView(dialogText(activity, LocaleController.getString(R.string.HanakoBackupRestoreOldDevice), 13, Theme.key_dialogTextGray3),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(layout);
        final boolean[] handled = new boolean[1];
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.HanakoBackupRestoreTitle));
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.HanakoBackupRestoreAction), (d, w) -> {
            boolean[] picked = new boolean[n];
            int count = 0;
            for (int i = 0; i < n; i++) {
                picked[i] = cells[i] != null && cells[i].isChecked();
                if (picked[i]) count++;
            }
            if (count == 0) {
                toast(LocaleController.getString(R.string.HanakoRestoreNothingSelected));
                return;
            }
            if (count > freeSlots) {
                toast(LocaleController.getString(R.string.HanakoBackupNoSlots));
                return;
            }
            handled[0] = true;
            d.dismiss();
            stageRestore(b, picked);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        showDialog(dialog, dd -> {
            if (!handled[0]) {
                b.wipe();
            }
        });
        dialog.setCanceledOnTouchOutside(false);
    }

    private void stageRestore(HanakoBackup.Backup b, boolean[] picked) {
        final AlertDialog progress = showProgress();
        Utilities.globalQueue.postRunnable(() -> {
            int staged = 0;
            Throwable error = null;
            try {
                staged = HanakoBackup.stage(b, picked);
            } catch (Throwable t) {
                FileLog.e(t);
                HanakoBackup.discardStaged();
                error = t;
            } finally {
                b.wipe();
            }
            final int n = staged;
            final Throwable err = error;
            AndroidUtilities.runOnUIThread(() -> {
                dismiss(progress);
                if (err != null) {
                    showError(err);
                    return;
                }
                if (n == 0) {
                    toast(LocaleController.getString(R.string.HanakoBackupNoSlots));
                    return;
                }
                if (getParentActivity() == null) {
                    // never restart without a visible confirmation: the staged restore is
                    // applied at the next start (if within 30 minutes) and explains itself then
                    return;
                }
                AlertDialog.Builder done = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                done.setTitle(LocaleController.getString(R.string.HanakoBackupRestoreTitle));
                done.setMessage(LocaleController.formatString(R.string.HanakoRestoreReady, LocaleController.formatPluralString("HanakoAccounts", n)));
                done.setPositiveButton(LocaleController.getString(R.string.HanakoBackupRestartNow), (d, w) -> HanakoBackup.restartApp(getParentActivity()));
                AlertDialog dlg = done.create();
                dlg.setCancelable(false);
                showDialog(dlg);
                dlg.setCanceledOnTouchOutside(false);
            });
        });
    }

    // ---------------------------------------------------------------------------------
    // password dialog

    private EditText passwordField(Context ctx, int hintRes) {
        EditText field = new EditText(ctx);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        field.setHint(LocaleController.getString(hintRes));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        return field;
    }

    private static char[] take(EditText field) {
        if (field == null) return new char[0];
        Editable ed = field.getText();
        if (ed == null) return new char[0];
        char[] out = new char[ed.length()];
        ed.getChars(0, ed.length(), out, 0);
        ed.clear();
        return out;
    }

    private static void clear(EditText field) {
        if (field != null && field.getText() != null) {
            field.getText().clear();
        }
    }

    private static final String[] COMMON = {
            "password", "passwort", "qwerty", "qwertz", "azerty", "letmein", "iloveyou", "welcome", "admin",
            "telegram", "hanako", "123456", "654321", "111111", "000000", "abc123", "abcdef", "пароль", "йцукен",
    };

    /** Weak / OK / Strong from length, character classes and a few trivial patterns. */
    static int strength(CharSequence pw) {
        int len = pw.length();
        if (len == 0) return STRENGTH_WEAK;
        boolean lower = false, upper = false, digit = false, other = false;
        int distinct;
        java.util.HashSet<Character> chars = new java.util.HashSet<>();
        boolean sequential = len > 2;
        for (int i = 0; i < len; i++) {
            char c = pw.charAt(i);
            chars.add(c);
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else other = true;
            if (i > 0 && Math.abs(c - pw.charAt(i - 1)) != 1) sequential = false;
        }
        distinct = chars.size();
        if (len < HanakoBackup.MIN_PASSWORD_LENGTH) return STRENGTH_WEAK;
        for (String w : COMMON) {
            if (containsIgnoreCase(pw, w) && len - w.length() < 6) return STRENGTH_WEAK;
        }
        if (sequential || distinct <= Math.max(2, len / 4)) return STRENGTH_WEAK;
        int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
        if (len >= 16 || (len >= 12 && classes >= 3)) return STRENGTH_STRONG;
        return STRENGTH_OK; // at least MIN_PASSWORD_LENGTH (12) and not trivial
    }

    /* Substring test on the field's text without making a String copy of the password. */
    private static boolean containsIgnoreCase(CharSequence text, String word) {
        int n = word.length();
        for (int i = 0; i + n <= text.length(); i++) {
            int j = 0;
            while (j < n && Character.toLowerCase(text.charAt(i + j)) == word.charAt(j)) j++;
            if (j == n) return true;
        }
        return false;
    }

    /** Five groups of four random letters/digits, about 100 bits. A char[]: wiped after use. */
    private static char[] generatePassphrase() {
        final String alphabet = "abcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 5; g++) {
            if (g > 0) sb.append('-');
            for (int i = 0; i < 4; i++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
        }
        char[] out = new char[sb.length()];
        sb.getChars(0, sb.length(), out, 0);
        sb.setLength(0);
        return out;
    }

    /** Asks for the backup password: twice, with a strength check, when {@code create}. Nothing happens on cancel. */
    private void askPassword(boolean create, boolean wrongBefore, Utilities.Callback<char[]> callback) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        final Theme.ResourcesProvider rp = getResourceProvider();
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(dialogText(activity, LocaleController.getString(create ? R.string.HanakoBackupPasswordInfo : R.string.HanakoBackupPasswordEnter), 15, Theme.key_dialogTextBlack),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        final EditText first = passwordField(activity, R.string.HanakoBackupPasswordHint);
        layout.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        final TextView strengthView = create ? dialogText(activity, "", 13, Theme.key_dialogTextGray3) : null;
        if (strengthView != null) {
            layout.addView(strengthView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 0));
        }
        final EditText second = create ? passwordField(activity, R.string.HanakoBackupPasswordRepeat) : null;
        if (second != null) {
            layout.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));
        }
        final TextView error = dialogText(activity, "", 13, Theme.key_text_RedRegular);
        error.setVisibility(wrongBefore ? View.VISIBLE : View.GONE);
        if (wrongBefore) {
            error.setText(LocaleController.getString(R.string.HanakoBackupWrongPassword));
        }
        layout.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        final boolean[] shown = new boolean[1];
        final TextView toggle = dialogText(activity, LocaleController.getString(R.string.HanakoPasswordShow), 14, Theme.key_dialogTextBlue2);
        toggle.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        toggle.setMinHeight(AndroidUtilities.dp(48)); // touch target
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setOnClickListener(v -> {
            shown[0] = !shown[0];
            int type = InputType.TYPE_CLASS_TEXT | (shown[0] ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD);
            for (EditText f : new EditText[]{first, second}) {
                if (f == null) continue;
                int sel = f.getSelectionEnd();
                f.setInputType(type);
                if (sel >= 0 && f.getText() != null && sel <= f.getText().length()) f.setSelection(sel);
            }
            toggle.setText(LocaleController.getString(shown[0] ? R.string.HanakoPasswordHide : R.string.HanakoPasswordShow));
        });
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(toggle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL, 0, 0, 24, 0));
        if (create) {
            final TextView generate = dialogText(activity, LocaleController.getString(R.string.HanakoPasswordGenerate), 14, Theme.key_dialogTextBlue2);
            generate.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
            generate.setMinHeight(AndroidUtilities.dp(48));
            generate.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            generate.setOnClickListener(v -> {
                char[] phrase = generatePassphrase();
                first.setText(phrase, 0, phrase.length);
                if (second != null) second.setText(phrase, 0, phrase.length);
                Arrays.fill(phrase, '\0');
                if (!shown[0]) toggle.performClick();
                error.setText(LocaleController.getString(R.string.HanakoPasswordGeneratedNote));
                error.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
                error.setVisibility(View.VISIBLE);
            });
            actions.addView(generate, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        }
        layout.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        if (strengthView != null) {
            first.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (s == null || s.length() == 0) {
                        strengthView.setText("");
                        return;
                    }
                    int level = strength(s);
                    int res = level == STRENGTH_STRONG ? R.string.HanakoPasswordStrong : level == STRENGTH_OK ? R.string.HanakoPasswordOk : R.string.HanakoPasswordWeak;
                    strengthView.setText(LocaleController.getString(res));
                    if (level == STRENGTH_WEAK) {
                        strengthView.setTextColor(Theme.getColor(Theme.key_text_RedRegular, rp));
                    } else if (level == STRENGTH_OK) {
                        strengthView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
                    } else {
                        strengthView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText2, rp));
                    }
                }
            });
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity, rp);
        b.setTitle(LocaleController.getString(R.string.HanakoBackupPasswordTitle));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            Editable a1 = first.getText();
            String problem = null;
            if (a1 == null || a1.length() == 0) {
                problem = LocaleController.getString(create ? R.string.HanakoPasswordWeakError : R.string.HanakoPasswordEmpty);
            } else if (create && strength(a1) == STRENGTH_WEAK) {
                problem = LocaleController.getString(R.string.HanakoPasswordWeakError);
            } else if (second != null) {
                Editable a2 = second.getText();
                if (a2 == null || !TextUtils.equals(a1, a2)) {
                    problem = LocaleController.getString(R.string.HanakoBackupPasswordMismatch);
                }
            }
            if (problem != null) {
                // keep what was typed
                error.setTextColor(Theme.getColor(Theme.key_text_RedRegular, rp));
                error.setText(problem);
                error.setVisibility(View.VISIBLE);
                return;
            }
            char[] pw = take(first);
            clear(second);
            d.dismiss();
            callback.run(pw);
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        AlertDialog dialog = b.create();
        dialog.setDismissDialogByButtons(false);
        if (dialog.getWindow() != null) {
            // no screenshots, recents thumbnail or screen recording of the password
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        showDialog(dialog, d -> {
            clear(first);
            clear(second);
        });
        dialog.setCanceledOnTouchOutside(false);
        first.requestFocus();
    }

    // ---------------------------------------------------------------------------------
    // helpers

    private AlertDialog showProgress() {
        Activity activity = getParentActivity();
        if (activity == null) return null;
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        return progress;
    }

    private static void dismiss(AlertDialog dialog) {
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (Exception ignore) {
        }
    }

    private void deleteQuietly(Uri uri) {
        try {
            DocumentsContract.deleteDocument(ApplicationLoader.applicationContext.getContentResolver(), uri);
        } catch (Throwable ignore) {
        }
    }

    /** Localized message saying what to do, with the raw exception behind "Details". */
    private void showError(Throwable error) {
        final String text = HanakoBackup.describeError(error);
        final String raw = HanakoBackup.rawError(error);
        if (getParentActivity() == null) {
            toast(text);
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupTitle));
        b.setMessage(text);
        b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        if (!TextUtils.isEmpty(raw)) {
            b.setNeutralButton(LocaleController.getString(R.string.HanakoErrDetails), (d, w) -> {
                if (getParentActivity() == null) return;
                AlertDialog.Builder details = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                details.setTitle(LocaleController.getString(R.string.HanakoErrDetails));
                details.setMessage(raw);
                details.setPositiveButton(LocaleController.getString(R.string.OK), null);
                showDialog(details.create());
            });
        }
        showDialog(b.create());
    }

    private static void toast(String text) {
        if (!TextUtils.isEmpty(text)) {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
        }
    }
}
