/*
 * hanako: options screen of the per-chat export (Telegram Desktop style).
 * Opened from a chat's ⋮ menu or from Backup & export > Export a chat.
 */
package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import it.belloworld.mercurygram.HanakoBackup;
import it.belloworld.mercurygram.HanakoChatExport;
import it.belloworld.mercurygram.HanakoChatExportService;
import it.belloworld.mercurygram.PlusChatLock;

public class HanakoChatExportActivity extends UniversalFragment {

    /** ChatActivity header menu id. */
    public static final int CHAT_MENU_ID = 7310;

    private static final int ID_FROM = 1;
    private static final int ID_TO = 2;
    private static final int ID_PHOTOS = 3;
    private static final int ID_VIDEOS = 4;
    private static final int ID_VOICE = 5;
    private static final int ID_FILES = 6;
    private static final int ID_SIZE = 7;
    private static final int ID_HTML = 8;
    private static final int ID_JSON = 9;
    private static final int ID_START = 10;

    private static final int REQ_TREE = 7311;

    private static final long[] SIZE_LIMITS = {1L << 20, 8L << 20, 32L << 20, 100L << 20, 500L << 20, 2000L << 20, 0};

    private final long dialogId;
    private final HanakoChatExport.Options options = new HanakoChatExport.Options();

    /** "Last 7 days" etc. shown instead of a bare date while the preset is in effect. */
    private CharSequence fromPresetLabel;
    private AlertDialog progressDialog;
    private TextView progressText;
    private android.widget.ProgressBar progressBar;

    public HanakoChatExportActivity(long dialogId) {
        super();
        this.dialogId = dialogId;
        options.dialogId = dialogId;
    }

    public static boolean showChatMenu(long dialogId) {
        return HanakoChatExport.canExport(dialogId);
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HanakoChatExportTitle);
    }

    private String chatName() {
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User u = getMessagesController().getUser(dialogId);
            if (u != null) {
                return UserObject.isUserSelf(u) ? LocaleController.getString(R.string.SavedMessages) : UserObject.getUserName(u);
            }
        } else {
            TLRPC.Chat c = getMessagesController().getChat(-dialogId);
            if (c != null) return c.title;
        }
        return Long.toString(dialogId);
    }

    private static String formatDate(int unix) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(unix * 1000L));
    }

    private static String sizeLabel(long bytes) {
        return bytes <= 0 ? LocaleController.getString(R.string.HanakoChatExportNoLimit) : AndroidUtilities.formatFileSize(bytes);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(chatName()));
        items.add(UItem.asButton(ID_FROM, LocaleController.getString(R.string.HanakoChatExportFrom),
                options.fromDate > 0 ? (fromPresetLabel != null ? fromPresetLabel : formatDate(options.fromDate)) : LocaleController.getString(R.string.HanakoChatExportBeginning)));
        items.add(UItem.asButton(ID_TO, LocaleController.getString(R.string.HanakoChatExportTo),
                options.toDate > 0 ? formatDate(options.toDate) : LocaleController.getString(R.string.HanakoChatExportNow)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoChatExportMediaHeader)));
        items.add(UItem.asCheck(ID_PHOTOS, LocaleController.getString(R.string.HanakoChatExportPhotos)).setChecked(options.photos));
        items.add(UItem.asCheck(ID_VIDEOS, LocaleController.getString(R.string.HanakoChatExportVideos)).setChecked(options.videos));
        items.add(UItem.asCheck(ID_VOICE, LocaleController.getString(R.string.HanakoChatExportVoice)).setChecked(options.voice));
        items.add(UItem.asCheck(ID_FILES, LocaleController.getString(R.string.HanakoChatExportFiles)).setChecked(options.files));
        items.add(UItem.asButton(ID_SIZE, LocaleController.getString(R.string.HanakoChatExportSizeLimit), sizeLabel(options.sizeLimit)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoChatExportFormatHeader)));
        items.add(UItem.asCheck(ID_HTML, LocaleController.getString(R.string.HanakoChatExportHtml)).setChecked(options.html));
        items.add(UItem.asCheck(ID_JSON, LocaleController.getString(R.string.HanakoChatExportJson)).setChecked(options.json));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoChatExportFormatInfo)));

        items.add(UItem.asButton(ID_START, R.drawable.msg_download, LocaleController.getString(R.string.HanakoChatExportStart)));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_FROM:
                pickFrom();
                return;
            case ID_TO:
                pickTo();
                return;
            case ID_PHOTOS:
                options.photos = !options.photos;
                break;
            case ID_VIDEOS:
                options.videos = !options.videos;
                break;
            case ID_VOICE:
                options.voice = !options.voice;
                break;
            case ID_FILES:
                options.files = !options.files;
                break;
            case ID_SIZE:
                pickSize();
                return;
            case ID_HTML:
                options.html = !options.html;
                break;
            case ID_JSON:
                options.json = !options.json;
                break;
            case ID_START:
                start();
                return;
            default:
                return;
        }
        refresh();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void pickFrom() {
        if (getParentActivity() == null) return;
        CharSequence[] labels = {
                LocaleController.getString(R.string.HanakoChatExportAllTime),
                LocaleController.getString(R.string.HanakoChatExportLastDay),
                LocaleController.getString(R.string.HanakoChatExportLastWeek),
                LocaleController.getString(R.string.HanakoChatExportLastMonth),
                LocaleController.getString(R.string.HanakoChatExportLastYear),
                LocaleController.getString(R.string.HanakoChatExportPickDate),
        };
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoChatExportFrom));
        b.setItems(labels, (d, which) -> {
            int now = (int) (System.currentTimeMillis() / 1000L);
            fromPresetLabel = which >= 1 && which <= 4 ? labels[which] : null;
            switch (which) {
                case 0:
                    options.fromDate = 0;
                    break;
                case 1:
                    options.fromDate = now - 86400;
                    break;
                case 2:
                    options.fromDate = now - 7 * 86400;
                    break;
                case 3:
                    options.fromDate = now - 30 * 86400;
                    break;
                case 4:
                    options.fromDate = now - 365 * 86400;
                    break;
                case 5:
                    pickDate(true);
                    return;
            }
            refresh();
        });
        showDialog(b.create());
    }

    private void pickTo() {
        if (getParentActivity() == null) return;
        CharSequence[] labels = {
                LocaleController.getString(R.string.HanakoChatExportNow),
                LocaleController.getString(R.string.HanakoChatExportPickDate),
        };
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoChatExportTo));
        b.setItems(labels, (d, which) -> {
            if (which == 0) {
                options.toDate = 0;
                refresh();
            } else {
                pickDate(false);
            }
        });
        showDialog(b.create());
    }

    private void pickDate(boolean from) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Calendar cal = Calendar.getInstance();
        int current = from ? options.fromDate : options.toDate;
        if (current > 0) cal.setTimeInMillis(current * 1000L);
        try {
            DatePickerDialog dlg = new DatePickerDialog(activity, (view, year, month, dayOfMonth) -> {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(year, month, dayOfMonth, 0, 0, 0);
                if (from) {
                    fromPresetLabel = null;
                    options.fromDate = (int) (c.getTimeInMillis() / 1000L);
                } else {
                    // inclusive: up to the end of the picked day
                    options.toDate = (int) (c.getTimeInMillis() / 1000L) + 86399;
                }
                refresh();
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
            dlg.getDatePicker().setMaxDate(System.currentTimeMillis());
            dlg.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void pickSize() {
        if (getParentActivity() == null) return;
        CharSequence[] labels = new CharSequence[SIZE_LIMITS.length];
        for (int i = 0; i < SIZE_LIMITS.length; i++) labels[i] = sizeLabel(SIZE_LIMITS[i]);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoChatExportSizeLimit));
        b.setItems(labels, (d, which) -> {
            if (which >= 0 && which < SIZE_LIMITS.length) {
                options.sizeLimit = SIZE_LIMITS[which];
                refresh();
            }
        });
        showDialog(b.create());
    }

    private void start() {
        if (!options.html && !options.json) {
            toast(LocaleController.getString(R.string.HanakoChatExportNoFormat));
            return;
        }
        if (!HanakoChatExport.canExport(dialogId)) {
            toast(LocaleController.getString(R.string.HanakoChatExportSecret));
            return;
        }
        if (options.toDate > 0 && options.fromDate > options.toDate) {
            toast(LocaleController.getString(R.string.HanakoChatExportBadRange));
            return;
        }
        if (HanakoChatExportService.isRunning()) {
            if (HanakoChatExportService.runningDialogId() == dialogId) {
                showProgressDialog();
            } else {
                toast(LocaleController.getString(R.string.HanakoChatExportBusy));
            }
            return;
        }
        Runnable pick = () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(intent, REQ_TREE);
            } catch (Exception e) {
                FileLog.e(e);
                toast(HanakoBackup.describeError(e));
            }
        };
        // a locked chat (reached some other way) is never exported without its unlock
        if (PlusChatLock.isDialogLockedNow(currentAccount, dialogId)) {
            PlusChatLock.authenticate(PlusChatLock.dialogName(currentAccount, dialogId), success -> {
                if (success) {
                    PlusChatLock.markSessionUnlocked(currentAccount, dialogId);
                    pick.run();
                }
            });
            return;
        }
        pick.run();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_TREE || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        try {
            ApplicationLoader.applicationContext.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignore) {
            // the grant from the picker is enough for this session
        }
        runExport(tree);
    }

    private final HanakoChatExport.Listener listener = new HanakoChatExport.Listener() {
        @Override
        public void onProgress(HanakoChatExport.Progress p) {
            updateProgressViews(p);
        }

        @Override
        public void onFinished(boolean cancelled, Throwable error, HanakoChatExport.Progress p, Uri exportDir) {
            dismissProgressDialog();
            if (getParentActivity() == null) {
                return;
            }
            if (cancelled) {
                askKeepPartial(exportDir);
                return;
            }
            HanakoChatExportService.cancelDoneNotification();
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            b.setTitle(LocaleController.getString(error != null ? R.string.HanakoChatExportFailedTitle : R.string.HanakoChatExportDoneTitle));
            b.setMessage(HanakoChatExportService.summary(error, p));
            if (error == null && exportDir != null) {
                b.setNeutralButton(LocaleController.getString(R.string.HanakoChatExportOpenFolder), (d, w) -> openFolder(exportDir));
            }
            b.setPositiveButton(LocaleController.getString(R.string.HanakoChatExportDoneButton), null);
            showDialog(b.create());
        }
    };

    private void openFolder(Uri exportDir) {
        Intent intent = HanakoChatExportService.openFolderIntent(exportDir);
        if (intent == null || getParentActivity() == null) return;
        try {
            getParentActivity().startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
            toast(LocaleController.getString(R.string.HanakoChatExportNoFileManager));
        }
    }

    private void askKeepPartial(Uri exportDir) {
        if (exportDir == null || getParentActivity() == null) {
            toast(LocaleController.getString(R.string.HanakoChatExportCancelled));
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoChatExportTitle));
        b.setMessage(LocaleController.getString(R.string.HanakoChatExportStoppedAsk));
        b.setPositiveButton(LocaleController.getString(R.string.HanakoChatExportKeep), null);
        b.setNegativeButton(LocaleController.getString(R.string.Delete), (d, w) ->
                HanakoChatExportService.deleteExport(exportDir, () -> toast(LocaleController.getString(R.string.HanakoChatExportDeleted))));
        b.makeRed(android.content.DialogInterface.BUTTON_NEGATIVE);
        showDialog(b.create());
    }

    private void runExport(Uri tree) {
        if (!HanakoChatExportService.start(currentAccount, options, tree, chatName())) {
            toast(LocaleController.getString(R.string.HanakoChatExportBusy));
            return;
        }
        HanakoChatExportService.addListener(listener);
        showProgressDialog();
    }

    private void showProgressDialog() {
        Activity activity = getParentActivity();
        if (activity == null || progressDialog != null) return;
        HanakoChatExportService.addListener(listener);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        progressBar = new android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        layout.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 4));
        progressText = new TextView(activity);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        progressText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        layout.addView(progressText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 4));
        TextView hint = new TextView(activity);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, getResourceProvider()));
        hint.setText(LocaleController.getString(R.string.HanakoChatExportBackgroundHint));
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 4));
        HanakoChatExport.Progress p = HanakoChatExportService.lastProgress();
        updateProgressViews(p != null ? p : new HanakoChatExport.Progress());

        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoChatExportTitle));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.HanakoChatExportHide), (d, w) -> dismissProgressDialog());
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            HanakoChatExportService.cancelRunning();
            dismissProgressDialog();
        });
        progressDialog = b.create();
        progressDialog.setCanceledOnTouchOutside(false);
        showDialog(progressDialog, d -> {
            progressDialog = null;
            progressText = null;
            progressBar = null;
        });
    }

    private void updateProgressViews(HanakoChatExport.Progress p) {
        if (p == null) return;
        if (progressText != null) {
            progressText.setText(HanakoChatExportService.progressText(p));
        }
        if (progressBar != null) {
            if (p.total > 0) {
                progressBar.setIndeterminate(false);
                progressBar.setMax(p.total);
                progressBar.setProgress(Math.min(p.messages, p.total));
            } else {
                progressBar.setIndeterminate(true);
            }
        }
    }

    private void dismissProgressDialog() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignore) {
            }
            progressDialog = null;
        }
        progressText = null;
        progressBar = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (HanakoChatExportService.isRunning() && HanakoChatExportService.runningDialogId() == dialogId) {
            showProgressDialog();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        // leaving the screen does not stop a running export: the notification takes over
        HanakoChatExportService.removeListener(listener);
        dismissProgressDialog();
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private static void toast(String text) {
        if (!TextUtils.isEmpty(text)) {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
        }
    }
}
