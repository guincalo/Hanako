package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import it.belloworld.mercurygram.MgMessageHistory;

/**
 * Mercurygram — per-message edit-history viewer, reworked for Hanako.
 *
 * <p>Newest first: the current version, then every saved earlier version down
 * to the original. Each version is a real chat bubble (media resolved to the
 * saved copy when an edit replaced it) under a header that says what it is and
 * when it was written ("Original · sent …", "Edit 2 · …", "Current · edited …").
 * Under each edited version a "Changes" card shows what that edit did to the
 * text: removed words struck through in red, added words in green.</p>
 *
 * <p>Tap Copy to copy a version's text; long-press it for Copy / Remove this
 * version (saved versions only; nothing is ever sent to the server).</p>
 */
public class MgMessageEditHistoryActivity extends UniversalFragment {

    private final TLRPC.Message fallbackMessage;
    private TLRPC.Message currentMessage;
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
        /** Saved version (removable); null for the current server version. */
        final MgMessageHistory.Entry entry;
        /** What the edit that produced this version changed, or null. */
        CharSequence changes;
        View bubbleView;
        View changesView;

        Row(CharSequence body, String meta, CharSequence detail, String title, boolean hasText,
            MessageObject bubbleObject, MgMessageHistory.Entry entry) {
            this.body = body;
            this.meta = meta;
            this.detail = detail;
            this.title = title;
            this.hasText = hasText;
            this.bubbleObject = bubbleObject;
            this.entry = entry;
        }
    }

    public MgMessageEditHistoryActivity(MessageObject current) {
        this.fallbackMessage = current != null ? current.messageOwner : null;
        this.currentMessage = this.fallbackMessage;
        this.dialogId = current != null ? current.getDialogId() : 0;
        this.messageId = current != null ? current.getId() : 0;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramEditHistoryTitle);
    }

    @Override
    public boolean onFragmentCreate() {
        it.belloworld.mercurygram.HanakoTelemetry.count(it.belloworld.mercurygram.HanakoTelemetry.EDIT_HISTORY_OPEN); // hanako: usage statistics (off by default)
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
            items.add(UItem.asHeader(r.meta));
            if (bubble != null) {
                items.add(UItem.asCustom(bubble, LayoutHelper.WRAP_CONTENT));
            } else {
                items.add(UItem.asButton(i, r.body, null));
            }
            View changes = getChangesView(r);
            if (changes != null) {
                items.add(UItem.asCustom(changes, LayoutHelper.WRAP_CONTENT));
            }
            if (r.hasText && bubble != null) {
                items.add(UItem.asButton(i, R.drawable.msg_copy, LocaleController.getString(R.string.Copy)));
            }
            items.add(UItem.asShadow(null));
        }
        if (rows.size() > 1) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoEditHistoryInfo)));
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

    /** "Changes" card: white background, small caption, the word diff. */
    private View getChangesView(Row r) {
        if (r.changes == null) {
            return null;
        }
        if (r.changesView == null) {
            Context context = getContext();
            if (context == null) {
                return null;
            }
            TextView tv = new TextView(context);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            tv.setLineSpacing(AndroidUtilities.dp(2), 1f);
            tv.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(4), AndroidUtilities.dp(21), AndroidUtilities.dp(12));
            SpannableStringBuilder sb = new SpannableStringBuilder();
            String caption = LocaleController.getString(R.string.HanakoEditHistoryChanges);
            sb.append(caption).append('\n');
            sb.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, getResourceProvider())),
                    0, caption.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(13, true), 0, caption.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(r.changes);
            tv.setText(sb);
            tv.setTextIsSelectable(true);
            FrameLayout frame = new FrameLayout(context);
            frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
            frame.addView(tv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            r.changesView = frame;
        }
        return r.changesView;
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
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
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
        if (item.id < 0 || item.id >= rows.size()) {
            return false;
        }
        final Row r = rows.get(item.id);
        try {
            ItemOptions io = ItemOptions.makeOptions(this, view);
            if (r.hasText) {
                io.add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
                    AndroidUtilities.addToClipboard(r.detail);
                    BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                });
            }
            if (r.entry != null) {
                io.add(R.drawable.msg_delete, LocaleController.getString(R.string.HanakoEditHistoryRemove), true, () -> removeVersion(r));
            }
            io.show();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private void removeVersion(Row r) {
        if (getParentActivity() == null || r.entry == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HanakoEditHistoryRemove))
                .setMessage(LocaleController.getString(R.string.HanakoEditHistoryRemoveConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) ->
                        MgMessageHistory.getInstance().forgetRevision(currentAccount, dialogId, messageId, r.entry.whenMs,
                                () -> AndroidUtilities.runOnUIThread(this::loadEntries)))
                .create();
        showDialog(dialog);
        dialog.redPositive();
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

    private static String fmt(long ms) {
        return LocaleController.formatDateTime(ms / 1000, true);
    }

    private void loadEntries() {
        final int account = currentAccount;
        final long did = dialogId;
        final int mid = messageId;
        final TLRPC.Message fallback = fallbackMessage;
        Utilities.globalQueue.postRunnable(() -> {
            // Re-read the current message from storage: the MessageObject handed
            // to us may be stale if the activity opened before the edited update
            // replaced the in-memory chat row.
            TLRPC.Message fresh = MessagesStorage.getInstance(account).getMessage(did, mid);
            TLRPC.Message current = fresh != null ? fresh : fallback;
            currentMessage = current;

            List<MgMessageHistory.Entry> rawEntries =
                    MgMessageHistory.getInstance().getEditHistoryFor(account, did, mid);

            // Drop archived versions that are identical to the version that
            // immediately follows them (oldest-first walk). This also drops the
            // newest archive when it matches the live current message.
            ArrayList<MgMessageHistory.Entry> entries = new ArrayList<>(rawEntries.size());
            for (int i = 0; i < rawEntries.size(); i++) {
                MgMessageHistory.Entry e = rawEntries.get(i);
                if (!entries.isEmpty() && sameVersion(entries.get(entries.size() - 1).message, e.message)) {
                    entries.set(entries.size() - 1, e); // keep the newer duplicate
                } else {
                    entries.add(e);
                }
            }
            if (!entries.isEmpty() && sameVersion(entries.get(entries.size() - 1).message, current)) {
                entries.remove(entries.size() - 1);
            }

            ArrayList<Row> built = new ArrayList<>(entries.size() + 1);

            // current version on top
            String currentLabel = LocaleController.getString(R.string.MercurygramEditHistoryCurrent);
            String currentMeta = current != null && current.edit_date != 0
                    ? LocaleController.formatString(R.string.HanakoEditHistoryCurrentEdited, fmt(current.edit_date * 1000L))
                    : currentLabel;
            String currentBody = textOf(current);
            Row top = new Row(currentBody, currentMeta, currentBody, currentLabel, hasText(current),
                    buildBubble(account, current), null);
            built.add(top);

            // entries are oldest-first; walk newest to oldest under "current"
            Row newer = top;
            TLRPC.Message newerMsg = current;
            final boolean hasOriginal = !entries.isEmpty() && entries.get(0).message != null
                    && entries.get(0).message.edit_date == 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                MgMessageHistory.Entry e = entries.get(i);
                TLRPC.Message m = e.message;
                String title;
                String meta;
                if (i == 0 && hasOriginal) {
                    title = LocaleController.getString(R.string.HanakoEditHistoryOriginal);
                    meta = m != null && m.date != 0
                            ? LocaleController.formatString(R.string.HanakoEditHistoryOriginalSent, fmt(m.date * 1000L))
                            : title;
                } else {
                    title = LocaleController.formatString(R.string.HanakoEditHistoryEditN, hasOriginal ? i : i + 1);
                    long when = m != null && m.edit_date != 0 ? m.edit_date * 1000L : e.whenMs;
                    meta = title + " · " + fmt(when);
                }
                String body = textOf(m);
                Row row = new Row(body, meta, body, title, hasText(m), buildBubble(account, m), e);
                // the newer version shows what the edit from this one changed
                newer.changes = diff(m, newerMsg);
                built.add(row);
                newer = row;
                newerMsg = m;
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

    // ---- version identity / dedup ----

    /** True when two versions are visually identical and only differ in edit metadata. */
    private static boolean sameVersion(TLRPC.Message a, TLRPC.Message b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (!TextUtils.equals(a.message, b.message)) return false;
        if (replyToMsgId(a) != replyToMsgId(b)) return false;
        return sameMediaKey(a, b);
    }

    private static int replyToMsgId(TLRPC.Message m) {
        if (m == null || m.reply_to == null) return 0;
        return m.reply_to.reply_to_msg_id;
    }

    private static boolean sameMediaKey(TLRPC.Message a, TLRPC.Message b) {
        if (a == null || b == null) return a == b;
        TLRPC.MessageMedia ma = MessageObject.getMedia(a);
        TLRPC.MessageMedia mb = MessageObject.getMedia(b);
        if (ma == null && mb == null) return true;
        if (ma == null || mb == null) return false;
        return mediaId(ma) == mediaId(mb);
    }

    private static long mediaId(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            return media.photo.id;
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            return media.document.id;
        }
        return 0;
    }

    // ---- diff ----

    /** Differences between an older and newer version: word-level text diff plus
     *  labels for reply/forward/media changes. */
    private CharSequence diff(TLRPC.Message older, TLRPC.Message newer) {
        CharSequence text = diffText(older, newer);
        CharSequence extras = changesDescription(older, newer);
        if (text == null) return extras;
        if (extras == null) return text;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(text).append("\n").append(extras);
        return sb;
    }

    private CharSequence changesDescription(TLRPC.Message older, TLRPC.Message newer) {
        ArrayList<String> parts = new ArrayList<>();
        int oldReply = replyToMsgId(older);
        int newReply = replyToMsgId(newer);
        if (oldReply == 0 && newReply != 0) {
            parts.add(LocaleController.getString(R.string.HanakoEditHistoryReplyAdded));
        } else if (oldReply != 0 && newReply == 0) {
            parts.add(LocaleController.getString(R.string.HanakoEditHistoryReplyRemoved));
        } else if (oldReply != 0 && oldReply != newReply) {
            parts.add(LocaleController.getString(R.string.HanakoEditHistoryReplyChanged));
        }

        boolean oldFwd = older != null && older.fwd_from != null;
        boolean newFwd = newer != null && newer.fwd_from != null;
        if (!oldFwd && newFwd) {
            parts.add(LocaleController.getString(R.string.HanakoEditHistoryForwardAdded));
        } else if (oldFwd && !newFwd) {
            parts.add(LocaleController.getString(R.string.HanakoEditHistoryForwardRemoved));
        } else if (oldFwd && newFwd && !sameForward(older.fwd_from, newer.fwd_from)) {
            parts.add(LocaleController.getString(R.string.HanakoEditHistoryForwardChanged));
        }

        if (!sameMediaKey(older, newer)) {
            TLRPC.MessageMedia olderMedia = older != null ? MessageObject.getMedia(older) : null;
            TLRPC.MessageMedia newerMedia = newer != null ? MessageObject.getMedia(newer) : null;
            if (olderMedia == null) {
                parts.add(LocaleController.getString(R.string.HanakoEditHistoryMediaAdded));
            } else if (newerMedia == null) {
                parts.add(LocaleController.getString(R.string.HanakoEditHistoryMediaRemoved));
            } else {
                parts.add(LocaleController.getString(R.string.HanakoEditHistoryMediaChanged));
            }
        }

        if (parts.isEmpty()) return null;
        return TextUtils.join(" · ", parts);
    }

    private static boolean sameForward(TLRPC.MessageFwdHeader a, TLRPC.MessageFwdHeader b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        long fromA = a.from_id != null ? MessageObject.getPeerId(a.from_id) : 0;
        long fromB = b.from_id != null ? MessageObject.getPeerId(b.from_id) : 0;
        if (fromA != fromB) return false;
        long savedA = a.saved_from_peer != null ? MessageObject.getPeerId(a.saved_from_peer) : 0;
        long savedB = b.saved_from_peer != null ? MessageObject.getPeerId(b.saved_from_peer) : 0;
        return savedA == savedB && a.saved_from_msg_id == b.saved_from_msg_id;
    }

    /** Word-level diff from {@code older} to {@code newer} text, or null when the text did not change. */
    private CharSequence diffText(TLRPC.Message older, TLRPC.Message newer) {
        String a = older != null && older.message != null ? older.message : "";
        String b = newer != null && newer.message != null ? newer.message : "";
        if (a.equals(b)) {
            return null;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return null; // caption added/removed with media: the bubbles already show it
        }
        ArrayList<String> x = tokens(a);
        ArrayList<String> y = tokens(b);
        int n = x.size(), m = y.size();
        if ((long) n * m > 1_500_000L) {
            return null; // too long to diff on a phone; the bubbles show both versions anyway
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x.get(i).equals(y.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int red = Theme.getColor(Theme.key_text_RedRegular, getResourceProvider());
        int green = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText, getResourceProvider());
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && x.get(i).equals(y.get(j))) {
                sb.append(x.get(i));
                i++;
                j++;
            } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                appendStyled(sb, y.get(j), green, false);
                j++;
            } else {
                appendStyled(sb, x.get(i), red, true);
                i++;
            }
        }
        return sb;
    }

    private static void appendStyled(SpannableStringBuilder sb, String s, int color, boolean removed) {
        int start = sb.length();
        sb.append(s);
        int end = sb.length();
        if (s.trim().isEmpty()) {
            return; // don't paint whitespace
        }
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new BackgroundColorSpan(ColorUtils.setAlphaComponent(color, 0x26)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (removed) {
            sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Words and the whitespace between them, so joining tokens gives the text back. */
    private static ArrayList<String> tokens(String s) {
        ArrayList<String> out = new ArrayList<>();
        int i = 0, len = s.length();
        while (i < len) {
            int j = i;
            boolean ws = Character.isWhitespace(s.charAt(i));
            while (j < len && Character.isWhitespace(s.charAt(j)) == ws) {
                j++;
            }
            out.add(s.substring(i, j));
            i = j;
        }
        return out;
    }
}
