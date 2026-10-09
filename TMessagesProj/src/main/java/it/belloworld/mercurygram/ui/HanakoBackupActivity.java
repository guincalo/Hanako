/*
 * hanako: Settings > Hanako > Backup & export.
 * Settings export/import (JSON), encrypted full backup/restore of logged-in accounts, and the
 * entry point of the per-chat export. Also opened in restore-only mode from the login screen.
 *
 * Everything that copies accounts or rewrites private settings (create backup, restore over
 * existing accounts, import settings, export per-chat lists, export a chat) asks HanakoAuthGate
 * first: biometrics / screen lock, or Telegram's passcode when the phone has neither.
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

import org.json.JSONObject;
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
import java.util.HashMap;
import java.util.Locale;

import it.belloworld.mercurygram.HanakoAuthGate;
import it.belloworld.mercurygram.HanakoBackup;
import it.belloworld.mercurygram.PlusChatLock;

public class HanakoBackupActivity extends UniversalFragment {

    private static final int ID_EXPORT_SETTINGS = 1;
    private static final int ID_IMPORT_SETTINGS = 2;
    private static final int ID_CREATE_BACKUP = 3;
    private static final int ID_RESTORE_BACKUP = 4;
    private static final int ID_EXPORT_CHAT = 5;
    private static final int ID_UNDO_IMPORT = 6;

    private static final int REQ_EXPORT_SETTINGS = 7301;
    private static final int REQ_IMPORT_SETTINGS = 7302;
    private static final int REQ_CREATE_BACKUP = 7303;
    private static final int REQ_RESTORE_BACKUP = 7304;

    /** Password strength levels of {@link #strength(CharSequence)}. */
    private static final int STRENGTH_WEAK = 0;
    private static final int STRENGTH_OK = 1;
    private static final int STRENGTH_STRONG = 2;

    private final boolean restoreOnly;

    // state carried across the system file picker (wiped when the picker is cancelled)
    private char[] pendingPassword;
    private boolean pendingIncludeHidden;
    private boolean pendingExportLists;

    public HanakoBackupActivity() {
        this(false);
    }

    /** @param restoreOnly opened from the login screen: only the import actions make sense there. */
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

        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoBackupSettingsHeader)));
        if (!restoreOnly) {
            items.add(UItem.asButton(ID_EXPORT_SETTINGS, R.drawable.msg_saved, LocaleController.getString(R.string.HanakoBackupExportSettings)));
        }
        items.add(UItem.asButton(ID_IMPORT_SETTINGS, R.drawable.msg_openin, LocaleController.getString(R.string.HanakoBackupImportSettings)));
        if (!restoreOnly && HanakoBackup.hasUndo()) {
            items.add(UItem.asButton(ID_UNDO_IMPORT, R.drawable.msg_retry, LocaleController.getString(R.string.HanakoUndoImport)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoBackupSettingsInfo)));

        if (!restoreOnly) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoBackupChatHeader)));
            items.add(UItem.asButton(ID_EXPORT_CHAT, R.drawable.msg_share, LocaleController.getString(R.string.HanakoBackupExportChat)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoBackupChatInfo)));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_EXPORT_SETTINGS:
                showExportSettingsOptions();
                break;
            case ID_IMPORT_SETTINGS:
                gate(R.string.HanakoAuthImport, () -> openDocument(REQ_IMPORT_SETTINGS));
                break;
            case ID_UNDO_IMPORT:
                confirmUndo();
                break;
            case ID_CREATE_BACKUP:
                // a full backup copies the login sessions: refused on a phone with no lock at all
                HanakoAuthGate.require(this, LocaleController.getString(R.string.HanakoAuthBackup), true, this::showBackupWarning);
                break;
            case ID_RESTORE_BACKUP:
                if (UserConfig.getActivatedAccountsCount() > 0) {
                    // restoring can replace sessions and settings of this phone
                    gate(R.string.HanakoAuthRestore, () -> openDocument(REQ_RESTORE_BACKUP));
                } else {
                    openDocument(REQ_RESTORE_BACKUP);
                }
                break;
            case ID_EXPORT_CHAT:
                gate(R.string.HanakoAuthExportChat, this::openChatPicker);
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        wipePending();
        HanakoAuthGate.forget();
    }

    private void gate(int subtitleRes, Runnable action) {
        HanakoAuthGate.require(this, LocaleController.getString(subtitleRes), action);
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void wipePending() {
        if (pendingPassword != null) {
            Arrays.fill(pendingPassword, '\0');
            pendingPassword = null;
        }
    }

    // ---------------------------------------------------------------------------------
    // pickers

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
    }

    private boolean createDocument(int request, String mime, String name) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(intent, request);
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            showError(e);
            return false;
        }
    }

    private void openDocument(int request) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, request);
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
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_CREATE_BACKUP) {
                wipePending();
            }
            return;
        }
        Uri uri = data.getData();
        switch (requestCode) {
            case REQ_EXPORT_SETTINGS:
                exportSettings(uri, pendingExportLists, pendingIncludeHidden);
                break;
            case REQ_IMPORT_SETTINGS:
                importSettings(uri);
                break;
            case REQ_CREATE_BACKUP: {
                char[] pw = pendingPassword;
                pendingPassword = null;
                if (pw == null) {
                    deleteQuietly(uri);
                    return;
                }
                createBackup(uri, pw, pendingIncludeHidden);
                break;
            }
            case REQ_RESTORE_BACKUP:
                askRestorePassword(uri);
                break;
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
    // settings

    private void showExportSettingsOptions() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(dialogText(activity, LocaleController.getString(R.string.HanakoExportSettingsInfo), 15, Theme.key_dialogTextBlack),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        final CheckBoxCell lists = checkRow(activity, LocaleController.getString(R.string.HanakoExportSettingsLists), false);
        layout.addView(lists, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(dialogText(activity, LocaleController.getString(R.string.HanakoExportSettingsListsInfo), 13, Theme.key_dialogTextGray3),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 4));
        final CheckBoxCell hidden = HanakoBackup.canOfferHiddenAccounts()
                ? checkRow(activity, LocaleController.getString(R.string.HanakoIncludeHiddenAccounts), false) : null;
        if (hidden != null) {
            layout.addView(hidden, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupExportSettings));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.HanakoExportAction), (d, w) -> {
            final boolean withLists = lists.isChecked();
            final boolean withHidden = hidden != null && hidden.isChecked();
            Runnable pick = () -> {
                pendingExportLists = withLists;
                pendingIncludeHidden = withHidden;
                createDocument(REQ_EXPORT_SETTINGS, "application/json", "hanako-settings-" + stamp() + ".json");
            };
            if (withLists || withHidden) {
                gate(R.string.HanakoAuthExportLists, pick);
            } else {
                pick.run();
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void exportSettings(Uri uri, boolean withLists, boolean withHidden) {
        final Context ctx = ApplicationLoader.applicationContext;
        Utilities.globalQueue.postRunnable(() -> {
            Throwable error = null;
            try {
                HanakoBackup.writeSettings(ctx, uri, withLists, withHidden);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t;
            }
            final Throwable err = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (err != null) {
                    deleteQuietly(uri);
                    showError(err);
                } else {
                    toast(LocaleController.getString(withLists ? R.string.HanakoSettingsSavedLists : R.string.HanakoBackupSettingsSaved));
                }
            });
        });
    }

    private void importSettings(Uri uri) {
        final Context ctx = ApplicationLoader.applicationContext;
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject root = null;
            Throwable error = null;
            try {
                root = HanakoBackup.readSettings(ctx, uri);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t;
            }
            final JSONObject doc = root;
            final Throwable err = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (doc == null) {
                    showError(err);
                    return;
                }
                confirmSettingsImport(doc);
            });
        });
    }

    private void confirmSettingsImport(JSONObject doc) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String version = doc.optString("app_version", "");
        StringBuilder msg = new StringBuilder(TextUtils.isEmpty(version)
                ? LocaleController.getString(R.string.HanakoImportConfirm)
                : LocaleController.formatString(R.string.HanakoImportConfirmVersion, version));
        final boolean hasLists = HanakoBackup.hasLists(doc);
        if (hasLists) {
            int[] match = HanakoBackup.matchingAccounts(doc);
            if (match[1] > 0) {
                msg.append("\n\n").append(LocaleController.formatString(R.string.HanakoImportAccountsMatch, match[0], match[1]));
            }
        }
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(dialogText(activity, msg, 15, Theme.key_dialogTextBlack),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        final CheckBoxCell lists = hasLists ? checkRow(activity, LocaleController.getString(R.string.HanakoImportLists), true) : null;
        if (lists != null) {
            layout.addView(lists, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupImportSettings));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.HanakoImportAndRestart), (d, w) -> {
            final boolean withLists = lists != null && lists.isChecked();
            Utilities.globalQueue.postRunnable(() -> {
                Throwable error = null;
                try {
                    HanakoBackup.stageSettingsImport(doc, withLists);
                } catch (Throwable t) {
                    FileLog.e(t);
                    HanakoBackup.discardStaged();
                    error = t;
                }
                final Throwable err = error;
                AndroidUtilities.runOnUIThread(() -> {
                    if (err != null) {
                        showError(err);
                    } else {
                        HanakoBackup.restartApp(getParentActivity());
                    }
                });
            });
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void confirmUndo() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoUndoImport));
        b.setMessage(LocaleController.getString(R.string.HanakoUndoImportConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.HanakoUndoAndRestart), (d, w) -> gate(R.string.HanakoAuthImport, () ->
                Utilities.globalQueue.postRunnable(() -> {
                    Throwable error = null;
                    try {
                        HanakoBackup.stageUndo();
                    } catch (Throwable t) {
                        FileLog.e(t);
                        HanakoBackup.discardStaged();
                        error = t;
                    }
                    final Throwable err = error;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (err != null) {
                            showError(err);
                            refresh();
                        } else {
                            HanakoBackup.restartApp(getParentActivity());
                        }
                    });
                })));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    // ---------------------------------------------------------------------------------
    // full backup

    private void showBackupWarning() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int visible = HanakoBackup.visibleAccountCount();
        String summary = LocaleController.formatString(R.string.HanakoBackupWillSave, LocaleController.formatPluralString("HanakoAccounts", visible));
        layout.addView(dialogText(activity, summary + "\n\n" + LocaleController.getString(R.string.HanakoBackupWarning), 15, Theme.key_dialogTextBlack),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        final CheckBoxCell hidden = HanakoBackup.canOfferHiddenAccounts()
                ? checkRow(activity, LocaleController.getString(R.string.HanakoIncludeHiddenAccounts), false) : null;
        if (hidden != null) {
            layout.addView(hidden, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            layout.addView(dialogText(activity, LocaleController.getString(R.string.HanakoIncludeHiddenAccountsInfo), 13, Theme.key_dialogTextGray3),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 4));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupWarningTitle));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.HanakoBackupContinue), (d, w) -> {
            final boolean withHidden = hidden != null && hidden.isChecked();
            // ask for the password first, then where to save: cancelling never leaves an empty file
            askPassword(true, new PasswordCallback() {
                @Override
                public void onPassword(char[] password) {
                    wipePending();
                    pendingPassword = password;
                    pendingIncludeHidden = withHidden;
                    if (!createDocument(REQ_CREATE_BACKUP, "application/octet-stream", "hanako-backup-" + stamp() + ".hnkbak")) {
                        wipePending();
                    }
                }

                @Override
                public void onCancel() {
                }
            });
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void createBackup(Uri uri, char[] password, boolean withHidden) {
        final Context ctx = ApplicationLoader.applicationContext;
        final AlertDialog progress = showProgress();
        Utilities.globalQueue.postRunnable(() -> {
            int[] count = {0, 0};
            Throwable error = null;
            try {
                count = HanakoBackup.writeFullBackup(ctx, uri, password, true, withHidden);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t;
            } finally {
                Arrays.fill(password, '\0');
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
                    // an account whose session file could not be read is not in the file
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

    private void askRestorePassword(Uri uri) {
        askRestorePassword(uri, false);
    }

    private void askRestorePassword(Uri uri, boolean wrongBefore) {
        askPassword(false, wrongBefore, new PasswordCallback() {
            @Override
            public void onPassword(char[] password) {
                readBackup(uri, password);
            }

            @Override
            public void onCancel() {
            }
        });
    }

    private void readBackup(Uri uri, char[] password) {
        final Context ctx = ApplicationLoader.applicationContext;
        final AlertDialog progress = showProgress();
        Utilities.globalQueue.postRunnable(() -> {
            HanakoBackup.FullBackup backup = null;
            Throwable error = null;
            boolean wrongPassword = false;
            try {
                backup = HanakoBackup.readFullBackup(ctx, uri, password);
            } catch (HanakoBackup.WrongPasswordException e) {
                wrongPassword = true;
            } catch (Throwable t) {
                FileLog.e(t);
                error = t;
            } finally {
                Arrays.fill(password, '\0');
            }
            final HanakoBackup.FullBackup b = backup;
            final Throwable err = error;
            final boolean wrong = wrongPassword;
            AndroidUtilities.runOnUIThread(() -> {
                dismiss(progress);
                if (wrong) {
                    askRestorePassword(uri, true);
                } else if (b == null) {
                    showError(err);
                } else {
                    showRestorePlan(b);
                }
            });
        });
    }

    /** One checkbox per account (new / replaces the session here / no free slot), plus "also restore settings". */
    private void showRestorePlan(HanakoBackup.FullBackup b) {
        Activity activity = getParentActivity();
        if (activity == null) {
            b.wipe();
            return;
        }
        final HashMap<Long, Integer> current = HanakoBackup.currentAccountsByUser();
        final int freeSlots = HanakoBackup.freeSlots().size();
        final int n = b.accounts.size();
        final CheckBoxCell[] cells = new CheckBoxCell[n];
        final boolean[] existing = new boolean[n];

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(dialogText(activity, LocaleController.getString(R.string.HanakoRestorePick), 15, Theme.key_dialogTextBlack),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        int newChecked = 0;
        final boolean hiddenUnlocked = HanakoBackup.canOfferHiddenAccounts();
        for (int i = 0; i < n; i++) {
            HanakoBackup.AccountEntry e = b.accounts.get(i);
            existing[i] = current.containsKey(e.userId);
            if (existing[i] && !hiddenUnlocked && it.belloworld.mercurygram.HiddenAccountHelper.isAccountHidden(current.get(e.userId))) {
                // hidden on this phone: never listed (or replaced) unless signed in to a hidden account
                continue;
            }
            String state;
            boolean checked;
            if (existing[i]) {
                state = LocaleController.getString(R.string.HanakoBackupAccountReplace);
                checked = false; // replacing a working session is opt-in
            } else if (newChecked < freeSlots) {
                state = LocaleController.getString(R.string.HanakoBackupAccountNew);
                checked = true;
                newChecked++;
            } else {
                state = LocaleController.getString(R.string.HanakoBackupAccountNoSlot);
                checked = false;
            }
            final CheckBoxCell cell = checkRow(activity, HanakoBackup.accountLabel(e) + "\n" + state, checked);
            final int index = i;
            cell.setOnClickListener(v -> {
                boolean want = !cell.isChecked();
                if (want && !existing[index]) {
                    int used = 0;
                    for (int k = 0; k < n; k++) {
                        if (k != index && !existing[k] && cells[k] != null && cells[k].isChecked()) used++;
                    }
                    if (used >= freeSlots) {
                        toast(LocaleController.getString(R.string.HanakoBackupNoSlots));
                        return;
                    }
                }
                cell.setChecked(want, true);
            });
            cells[i] = cell;
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        final CheckBoxCell settings = b.settings != null ? checkRow(activity, LocaleController.getString(R.string.HanakoRestoreSettingsToo), true) : null;
        if (settings != null) {
            layout.addView(settings, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }
        layout.addView(dialogText(activity, LocaleController.getString(R.string.HanakoBackupRestoreOldDevice) + "\n\n"
                        + LocaleController.getString(R.string.HanakoRestoreReplaceNote), 13, Theme.key_dialogTextGray3),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(layout);

        final boolean[] handled = new boolean[1];
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.HanakoBackupRestoreTitle));
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.HanakoBackupRestoreAction), (d, w) -> {
            int[] slots = new int[n];
            ArrayList<Integer> free = HanakoBackup.freeSlots();
            int picked = 0;
            for (int i = 0; i < n; i++) {
                slots[i] = -1;
                if (cells[i] == null || !cells[i].isChecked()) continue;
                Integer slot = current.get(b.accounts.get(i).userId);
                if (slot != null) {
                    slots[i] = slot;
                } else if (!free.isEmpty()) {
                    slots[i] = free.remove(0);
                }
                if (slots[i] >= 0) picked++;
            }
            if (picked == 0) {
                toast(LocaleController.getString(R.string.HanakoRestoreNothingSelected));
                return;
            }
            handled[0] = true;
            d.dismiss();
            stageRestore(b, slots, settings != null && settings.isChecked());
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

    private void stageRestore(HanakoBackup.FullBackup b, int[] slots, boolean restoreSettings) {
        final AlertDialog progress = showProgress();
        Utilities.globalQueue.postRunnable(() -> {
            int staged = 0;
            Throwable error = null;
            try {
                staged = HanakoBackup.stageFullRestore(b, slots, restoreSettings);
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

    private interface PasswordCallback {
        void onPassword(char[] password);

        void onCancel();
    }

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

    private void askPassword(boolean create, PasswordCallback callback) {
        askPassword(create, false, callback);
    }

    private void askPassword(boolean create, boolean wrongBefore, PasswordCallback callback) {
        Activity activity = getParentActivity();
        if (activity == null) {
            callback.onCancel();
            return;
        }
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

        final boolean[] handled = new boolean[1];
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
                // keep what was typed; only the repeat field is cleared on a mismatch
                error.setTextColor(Theme.getColor(Theme.key_text_RedRegular, rp));
                error.setText(problem);
                error.setVisibility(View.VISIBLE);
                return;
            }
            handled[0] = true;
            char[] pw = take(first);
            clear(second);
            d.dismiss();
            callback.onPassword(pw);
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            handled[0] = true;
            clear(first);
            clear(second);
            d.dismiss();
            callback.onCancel();
        });
        AlertDialog dialog = b.create();
        dialog.setDismissDialogByButtons(false);
        if (dialog.getWindow() != null) {
            // no screenshots, recents thumbnail or screen recording of the password
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        showDialog(dialog, d -> {
            clear(first);
            clear(second);
            if (!handled[0]) {
                handled[0] = true;
                callback.onCancel();
            }
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
