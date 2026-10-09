/*
 * hanako: Settings > Mercurygram > Backup & export.
 * Settings export/import (JSON), encrypted full backup/restore of logged-in accounts, and the
 * entry point of the per-chat export. Also opened in restore-only mode from the login screen.
 */
package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import it.belloworld.mercurygram.HanakoBackup;

public class HanakoBackupActivity extends UniversalFragment {

    private static final int ID_EXPORT_SETTINGS = 1;
    private static final int ID_IMPORT_SETTINGS = 2;
    private static final int ID_CREATE_BACKUP = 3;
    private static final int ID_RESTORE_BACKUP = 4;

    private static final int REQ_EXPORT_SETTINGS = 7301;
    private static final int REQ_IMPORT_SETTINGS = 7302;
    private static final int REQ_CREATE_BACKUP = 7303;
    private static final int REQ_RESTORE_BACKUP = 7304;

    private final boolean restoreOnly;

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
        items.add(UItem.asButton(ID_RESTORE_BACKUP, R.drawable.msg_shareout, LocaleController.getString(R.string.HanakoBackupRestore)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoBackupFullInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoBackupSettingsHeader)));
        if (!restoreOnly) {
            items.add(UItem.asButton(ID_EXPORT_SETTINGS, R.drawable.msg_saved, LocaleController.getString(R.string.HanakoBackupExportSettings)));
        }
        items.add(UItem.asButton(ID_IMPORT_SETTINGS, R.drawable.msg_log, LocaleController.getString(R.string.HanakoBackupImportSettings)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoBackupSettingsInfo)));

    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_EXPORT_SETTINGS:
                createDocument(REQ_EXPORT_SETTINGS, "application/json", "hanako-settings-" + stamp() + ".json");
                break;
            case ID_IMPORT_SETTINGS:
                openDocument(REQ_IMPORT_SETTINGS);
                break;
            case ID_CREATE_BACKUP:
                showBackupWarning();
                break;
            case ID_RESTORE_BACKUP:
                openDocument(REQ_RESTORE_BACKUP);
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // ---------------------------------------------------------------------------------
    // pickers

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
    }

    private void createDocument(int request, String mime, String name) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(intent, request);
        } catch (Exception e) {
            FileLog.e(e);
            toast(e.getMessage());
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
            toast(e.getMessage());
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        switch (requestCode) {
            case REQ_EXPORT_SETTINGS:
                exportSettings(uri);
                break;
            case REQ_IMPORT_SETTINGS:
                importSettings(uri);
                break;
            case REQ_CREATE_BACKUP:
                askPassword(true, new PasswordCallback() {
                    @Override
                    public void onPassword(char[] password) {
                        createBackup(uri, password);
                    }

                    @Override
                    public void onCancel() {
                        deleteQuietly(uri);
                    }
                });
                break;
            case REQ_RESTORE_BACKUP:
                askRestorePassword(uri);
                break;
        }
    }

    // ---------------------------------------------------------------------------------
    // settings

    private void exportSettings(Uri uri) {
        final Context ctx = ApplicationLoader.applicationContext;
        Utilities.globalQueue.postRunnable(() -> {
            String error = null;
            try {
                HanakoBackup.writeSettings(ctx, uri);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> toast(err == null
                    ? LocaleController.getString(R.string.HanakoBackupSettingsSaved)
                    : LocaleController.formatString(R.string.HanakoBackupFailed, err)));
        });
    }

    private void importSettings(Uri uri) {
        final Context ctx = ApplicationLoader.applicationContext;
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject root = null;
            String error = null;
            try {
                root = HanakoBackup.readSettings(ctx, uri);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final JSONObject doc = root;
            final String err = error;
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
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupImportSettings));
        b.setMessage(LocaleController.formatString(R.string.HanakoBackupImportSettingsConfirm, HanakoBackup.countSettings(doc)));
        b.setPositiveButton(LocaleController.getString(R.string.HanakoBackupRestartNow), (d, w) -> Utilities.globalQueue.postRunnable(() -> {
            String error = null;
            try {
                HanakoBackup.stageSettingsImport(doc);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (err != null) {
                    showError(err);
                } else {
                    HanakoBackup.restartApp(getParentActivity());
                }
            });
        }));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    // ---------------------------------------------------------------------------------
    // full backup

    private void showBackupWarning() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupWarningTitle));
        b.setMessage(LocaleController.getString(R.string.HanakoBackupWarning));
        b.setPositiveButton(LocaleController.getString(R.string.HanakoBackupContinue), (d, w) ->
                createDocument(REQ_CREATE_BACKUP, "application/octet-stream", "hanako-backup-" + stamp() + ".hnkbak"));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void createBackup(Uri uri, char[] password) {
        final Context ctx = ApplicationLoader.applicationContext;
        final AlertDialog progress = showProgress();
        Utilities.globalQueue.postRunnable(() -> {
            int count = 0;
            String error = null;
            try {
                count = HanakoBackup.writeFullBackup(ctx, uri, password, true);
            } catch (Throwable t) {
                FileLog.e(t);
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            } finally {
                Arrays.fill(password, '\0');
            }
            final int n = count;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                dismiss(progress);
                if (err != null) {
                    deleteQuietly(uri);
                    showError(err);
                } else {
                    toast(LocaleController.formatString(R.string.HanakoBackupSaved, n));
                }
            });
        });
    }

    private void askRestorePassword(Uri uri) {
        askPassword(false, new PasswordCallback() {
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
            String error = null;
            boolean wrongPassword = false;
            try {
                backup = HanakoBackup.readFullBackup(ctx, uri, password);
            } catch (HanakoBackup.WrongPasswordException e) {
                wrongPassword = true;
            } catch (Throwable t) {
                FileLog.e(t);
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            } finally {
                Arrays.fill(password, '\0');
            }
            final HanakoBackup.FullBackup b = backup;
            final String err = error;
            final boolean wrong = wrongPassword;
            AndroidUtilities.runOnUIThread(() -> {
                dismiss(progress);
                if (wrong) {
                    toast(LocaleController.getString(R.string.HanakoBackupWrongPassword));
                    askRestorePassword(uri);
                } else if (b == null) {
                    showError(err);
                } else {
                    showRestorePlan(b);
                }
            });
        });
    }

    private void showRestorePlan(HanakoBackup.FullBackup b) {
        if (getParentActivity() == null) {
            b.wipe();
            return;
        }
        final int[] add = HanakoBackup.planSlots(b, false);
        final int[] replace = HanakoBackup.planSlots(b, true);
        boolean anyExisting = false;
        boolean anyAdd = false;
        for (int i = 0; i < add.length; i++) {
            if (HanakoBackup.isLoggedInHere(b.accounts.get(i).userId)) anyExisting = true;
            if (add[i] >= 0) anyAdd = true;
        }
        StringBuilder msg = new StringBuilder(LocaleController.getString(R.string.HanakoBackupRestoreIntro));
        List<String> lines = HanakoBackup.describeAccounts(b, add);
        for (String line : lines) {
            msg.append('\n').append(line);
        }
        if (b.settings != null) {
            msg.append("\n\n").append(LocaleController.getString(R.string.HanakoBackupRestoreSettingsToo));
        }
        if (!anyAdd && !anyExisting) {
            msg.append("\n\n").append(LocaleController.getString(R.string.HanakoBackupNoSlots));
        }
        msg.append("\n\n").append(LocaleController.getString(R.string.HanakoBackupRestoreOldDevice));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.HanakoBackupRestoreTitle));
        builder.setMessage(msg.toString());
        if (anyAdd) {
            builder.setPositiveButton(LocaleController.getString(R.string.HanakoBackupRestoreAction), (d, w) -> stageRestore(b, add));
        }
        if (anyExisting) {
            builder.setNeutralButton(LocaleController.getString(R.string.HanakoBackupReplaceAction), (d, w) -> stageRestore(b, replace));
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> b.wipe());
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.setCanceledOnTouchOutside(false);
    }

    private void stageRestore(HanakoBackup.FullBackup b, int[] slots) {
        final AlertDialog progress = showProgress();
        Utilities.globalQueue.postRunnable(() -> {
            int staged = 0;
            String error = null;
            try {
                staged = HanakoBackup.stageFullRestore(b, slots, true);
            } catch (Throwable t) {
                FileLog.e(t);
                HanakoBackup.discardStaged();
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            } finally {
                b.wipe();
            }
            final int n = staged;
            final String err = error;
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
                    HanakoBackup.restartApp(null);
                    return;
                }
                AlertDialog.Builder done = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                done.setTitle(LocaleController.getString(R.string.HanakoBackupRestoreTitle));
                done.setMessage(LocaleController.formatString(R.string.HanakoBackupRestored, n));
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

    private void askPassword(boolean create, PasswordCallback callback) {
        Activity activity = getParentActivity();
        if (activity == null) {
            callback.onCancel();
            return;
        }
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView info = new TextView(activity);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        info.setText(LocaleController.getString(create ? R.string.HanakoBackupPasswordInfo : R.string.HanakoBackupPasswordEnter));
        layout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        final EditText first = passwordField(activity, R.string.HanakoBackupPasswordHint);
        layout.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        final EditText second = create ? passwordField(activity, R.string.HanakoBackupPasswordRepeat) : null;
        if (second != null) {
            layout.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        }

        final boolean[] handled = new boolean[1];
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupPasswordTitle));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            handled[0] = true;
            char[] pw = take(first);
            char[] again = second != null ? take(second) : null;
            String problem = null;
            if (pw.length < HanakoBackup.MIN_PASSWORD_LENGTH && create) {
                problem = LocaleController.getString(R.string.HanakoBackupPasswordTooShort);
            } else if (pw.length == 0) {
                problem = LocaleController.getString(R.string.HanakoBackupPasswordTooShort);
            } else if (again != null && !Arrays.equals(pw, again)) {
                problem = LocaleController.getString(R.string.HanakoBackupPasswordMismatch);
            }
            if (again != null) Arrays.fill(again, '\0');
            if (problem != null) {
                Arrays.fill(pw, '\0');
                toast(problem);
                AndroidUtilities.runOnUIThread(() -> askPassword(create, callback));
                return;
            }
            callback.onPassword(pw);
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            handled[0] = true;
            take(first);
            take(second);
            callback.onCancel();
        });
        AlertDialog dialog = b.create();
        showDialog(dialog, d -> {
            if (!handled[0]) {
                handled[0] = true;
                take(first);
                take(second);
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

    private void showError(String error) {
        if (getParentActivity() == null) {
            toast(LocaleController.formatString(R.string.HanakoBackupFailed, String.valueOf(error)));
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupTitle));
        b.setMessage(LocaleController.formatString(R.string.HanakoBackupFailed, TextUtils.isEmpty(error) ? "?" : error));
        b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(b.create());
    }

    private static void toast(String text) {
        if (!TextUtils.isEmpty(text)) {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
        }
    }
}
