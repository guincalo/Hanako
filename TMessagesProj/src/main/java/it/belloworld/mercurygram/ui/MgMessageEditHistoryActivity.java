package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import it.belloworld.mercurygram.MgMessageHistory;

/**
 * Mercurygram — per-message edit-history viewer. Lists the current server
 * version on top followed by each archived pre-edit version, newest first.
 * Each version is drawn as a chat bubble (text and media, the media resolved
 * to the copy saved by MgHistoryMedia when the edit replaced it), with a
 * copy-text row under it.
 */
public class MgMessageEditHistoryActivity extends UniversalFragment {

    private final TLRPC.Message currentMessage;
    private final long dialogId;
    private final int messageId;

    private final ArrayList<Row> rows = new ArrayList<>();
    private boolean loaded;

    private static class Row {
        final CharSequence body;
        final String meta;
        final CharSequence detail;
        final String title;
        final boolean hasText;
        final MessageObject bubbleObject;
        View bubbleView;

        Row(CharSequence body, String meta, CharSequence detail, String title, boolean hasText, MessageObject bubbleObject) {
            this.body = body;
            this.meta = meta;
            this.detail = detail;
            this.title = title;
            this.hasText = hasText;
            this.bubbleObject = bubbleObject;
        }
    }

    public MgMessageEditHistoryActivity(MessageObject current) {
        this.currentMessage = current != null ? current.messageOwner : null;
        this.dialogId = current != null ? current.getDialogId() : 0;
        this.messageId = current != null ? current.getId() : 0;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramEditHistoryTitle);
    }

    @Override
    public boolean onFragmentCreate() {
        loadEntries();
        return super.onFragmentCreate();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!loaded) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.Loading)));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            View bubble = getBubbleView(r);
            if (bubble != null) {
                items.add(UItem.asHeader(r.meta));
                items.add(UItem.asCustom(bubble, LayoutHelper.WRAP_CONTENT));
                if (r.hasText) {
                    items.add(UItem.asButton(i, R.drawable.msg_copy, LocaleController.getString(R.string.Copy)));
                }
                items.add(UItem.asShadow(null));
            } else {
                items.add(UItem.asButton(i, r.body, r.meta));
            }
        }
    }

    private View getBubbleView(Row r) {
        if (r.bubbleObject == null) {
            return null;
        }
        if (r.bubbleView == null) {
            Context context = getContext();
            if (context == null) {
                return null;
            }
            try {
                r.bubbleView = new BubbleView(context, r.bubbleObject);
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            }
        }
        return r.bubbleView;
    }

    /** One non-scrolling chat bubble; media plays from the saved copy when there is one. */
    private class BubbleView extends FrameLayout {

        BubbleView(Context context, MessageObject messageObject) {
            super(context);
            setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
            ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean needPlayMessage(ChatMessageCell c, MessageObject mo, boolean muted) {
                    if (mo != null && (mo.isVoice() || mo.isMusic())) {
                        return MediaController.getInstance().playMessage(mo);
                    }
                    return false;
                }
            });
            cell.isChat = false;
            cell.setFullyDraw(true);
            cell.setMessageObject(messageObject, null, false, false, false);
            addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= rows.size() || getParentActivity() == null) {
            return;
        }
        Row r = rows.get(item.id);
        if (r.bubbleObject != null && r.bubbleView != null) {
            if (r.hasText) {
                AndroidUtilities.addToClipboard(r.detail);
            }
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(r.title)
                .setMessage(r.detail)
                .setPositiveButton(LocaleController.getString(R.string.Copy), (d, w) ->
                        AndroidUtilities.addToClipboard(r.detail))
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .show();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private static MessageObject buildBubble(int account, TLRPC.Message m) {
        if (m == null) {
            return null;
        }
        try {
            MessageObject mo = new MessageObject(account, m, true, true);
            if (mo.type < 0) {
                return null;
            }
            return mo;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void loadEntries() {
        final int account = currentAccount;
        final long did = dialogId;
        final int mid = messageId;
        final TLRPC.Message current = currentMessage;
        Utilities.globalQueue.postRunnable(() -> {
            List<MgMessageHistory.Entry> entries =
                    MgMessageHistory.getInstance().getEditHistoryFor(account, did, mid);
            ArrayList<Row> built = new ArrayList<>(entries.size() + 1);

            String currentLabel = LocaleController.getString(R.string.MercurygramEditHistoryCurrent);
            int editDate = current != null ? current.edit_date : 0;
            String currentTs = editDate != 0
                    ? LocaleController.getInstance().getFormatterStats().format(editDate * 1000L)
                    : "";
            String currentBody = textOf(current);
            built.add(new Row(currentBody,
                    currentLabel + (currentTs.isEmpty() ? "" : " · " + currentTs),
                    currentBody,
                    currentLabel,
                    hasText(current),
                    buildBubble(account, current)));

            // entries are oldest-first; show newest revision right under "current".
            for (int i = entries.size() - 1; i >= 0; i--) {
                MgMessageHistory.Entry e = entries.get(i);
                String label = LocaleController.formatString(R.string.MercurygramEditHistoryRevision,
                        i + 1, entries.size());
                String when = LocaleController.getInstance().getFormatterStats().format(e.whenMs);
                String body = textOf(e.message);
                built.add(new Row(body, label + " · " + when, body, label,
                        hasText(e.message), buildBubble(account, e.message)));
            }

            AndroidUtilities.runOnUIThread(() -> {
                rows.clear();
                rows.addAll(built);
                loaded = true;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    private static boolean hasText(TLRPC.Message m) {
        return m != null && m.message != null && !m.message.isEmpty();
    }

    private static String textOf(TLRPC.Message m) {
        if (m == null) {
            return "";
        }
        if (m.message != null && !m.message.isEmpty()) {
            return m.message;
        }
        return LocaleController.getString(R.string.MercurygramSavedMessagesEmpty);
    }
}
