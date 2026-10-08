package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import it.belloworld.mercurygram.PlusDeletedDialogs;

/**
 * plus f07: list of chats that vanished (see {@link PlusDeletedDialogs}) for the
 * current account. Read-only and local: tapping an entry shows its details and
 * lets the user copy the id/username or drop the entry. It never opens the
 * chat, since loading a gone chat would hit the network.
 */
public class PlusDeletedDialogsActivity extends UniversalFragment {

    // plus f07 ids: 700-799
    private static final int ID_ENTRY = 760;
    private static final int ID_CLEAR = 761;

    private List<PlusDeletedDialogs.Entry> entries;
    private boolean loading;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PlusF07DeletedDialogsTitle);
    }

    @Override
    public boolean onFragmentCreate() {
        load();
        return super.onFragmentCreate();
    }

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        final int account = currentAccount;
        Utilities.globalQueue.postRunnable(() -> {
            List<PlusDeletedDialogs.Entry> list = PlusDeletedDialogs.list(account);
            AndroidUtilities.runOnUIThread(() -> {
                entries = list;
                loading = false;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (entries == null) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.Loading)));
            return;
        }
        if (entries.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF07DeletedDialogsEmpty)));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            PlusDeletedDialogs.Entry e = entries.get(i);
            UItem item = UItem.asButton(ID_ENTRY, title(e), LocaleController.formatDateTime(e.whenMs / 1000, true));
            item.object = e;
            items.add(item);
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF07DeletedDialogsAbout)));
        items.add(UItem.asButton(ID_CLEAR, LocaleController.getString(R.string.PlusF07DeletedDialogsClear)).red());
        items.add(UItem.asShadow(null));
    }

    private static String title(PlusDeletedDialogs.Entry e) {
        if (!TextUtils.isEmpty(e.title)) {
            return e.title;
        }
        if (!TextUtils.isEmpty(e.username)) {
            return "@" + e.username;
        }
        return Long.toString(e.dialogId);
    }

    private static String typeLabel(int type) {
        switch (type) {
            case PlusDeletedDialogs.TYPE_BOT:
                return LocaleController.getString(R.string.PlusF07TypeBot);
            case PlusDeletedDialogs.TYPE_GROUP:
                return LocaleController.getString(R.string.PlusF07TypeGroup);
            case PlusDeletedDialogs.TYPE_CHANNEL:
                return LocaleController.getString(R.string.PlusF07TypeChannel);
            case PlusDeletedDialogs.TYPE_USER:
            default:
                return LocaleController.getString(R.string.PlusF07TypeUser);
        }
    }

    private static String causeLabel(int cause) {
        switch (cause) {
            case PlusDeletedDialogs.CAUSE_KICKED:
                return LocaleController.getString(R.string.PlusF07CauseKicked);
            case PlusDeletedDialogs.CAUSE_FORBIDDEN:
                return LocaleController.getString(R.string.PlusF07CauseForbidden);
            case PlusDeletedDialogs.CAUSE_LEFT:
                return LocaleController.getString(R.string.PlusF07CauseLeft);
            case PlusDeletedDialogs.CAUSE_HISTORY:
                return LocaleController.getString(R.string.PlusF07CauseHistory);
            case PlusDeletedDialogs.CAUSE_ACCOUNT_GONE:
                return LocaleController.getString(R.string.PlusF07CauseAccountGone);
            default:
                return LocaleController.getString(R.string.PlusF07CauseUnknown);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENTRY && item.object instanceof PlusDeletedDialogs.Entry) {
            showEntry((PlusDeletedDialogs.Entry) item.object);
        } else if (item.id == ID_CLEAR) {
            confirmClear();
        }
    }

    private void showEntry(PlusDeletedDialogs.Entry e) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(typeLabel(e.type)).append('\n');
        sb.append("ID: ").append(e.dialogId).append('\n');
        if (!TextUtils.isEmpty(e.username)) {
            sb.append('@').append(e.username).append('\n');
        }
        sb.append(LocaleController.getString(R.string.PlusF07Reason)).append(": ").append(causeLabel(e.cause)).append('\n');
        sb.append(LocaleController.getString(R.string.PlusF07When)).append(": ")
                .append(LocaleController.formatDateTime(e.whenMs / 1000, false)).append('\n');
        sb.append(LocaleController.formatString("PlusF07CachedCount", R.string.PlusF07CachedCount, e.cachedMessages));
        if (!TextUtils.isEmpty(e.lastMessage)) {
            sb.append("\n\n").append(LocaleController.getString(R.string.PlusF07LastMessage)).append(":\n").append(e.lastMessage);
        }
        new AlertDialog.Builder(context)
                .setTitle(title(e))
                .setMessage(sb.toString())
                .setPositiveButton(LocaleController.getString(R.string.PlusF07CopyId), (d, w) -> {
                    String copy = !TextUtils.isEmpty(e.username) ? e.dialogId + " @" + e.username : Long.toString(e.dialogId);
                    AndroidUtilities.addToClipboard(copy);
                    Toast.makeText(context, LocaleController.getString(R.string.TextCopied), Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    final long rowId = e.rowId;
                    Utilities.globalQueue.postRunnable(() -> {
                        PlusDeletedDialogs.remove(rowId);
                        AndroidUtilities.runOnUIThread(this::load);
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .show();
    }

    private void confirmClear() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final int account = currentAccount;
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.PlusF07DeletedDialogsClear))
                .setMessage(LocaleController.getString(R.string.PlusF07DeletedDialogsClearConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> Utilities.globalQueue.postRunnable(() -> {
                    PlusDeletedDialogs.clear(account);
                    AndroidUtilities.runOnUIThread(this::load);
                }))
                .show();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
