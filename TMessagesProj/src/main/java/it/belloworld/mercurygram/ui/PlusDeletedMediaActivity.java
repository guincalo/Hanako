package it.belloworld.mercurygram.ui;

import android.os.Bundle;
import org.telegram.ui.ChatActivity;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import it.belloworld.mercurygram.MgMessageHistory;
import it.belloworld.mercurygram.PlusDeletedMedia;

/**
 * plus f20: media saved from deleted (and expired view-once) messages, for one chat or for
 * every chat of the account. Photos and videos are a thumbnail grid, files / voice / music are
 * rich rows; everything is grouped by chat (all-chats view) and by day, under a sticky header.
 * Tap opens the in-app viewer (photos, videos, GIFs) or another app (files); long-press offers
 * save to gallery / Downloads, share, details and delete. Local only: no request is ever sent
 * to Telegram from this screen.
 */
public class PlusDeletedMediaActivity extends BaseFragment {

    // f20 id range 2000-2099
    private static final int MENU_OTHER = 2010;
    private static final int MENU_TYPE_ALL = 2011;
    private static final int MENU_TYPE_PHOTO = 2012;
    private static final int MENU_TYPE_VIDEO = 2013;
    private static final int MENU_TYPE_FILE = 2014;
    private static final int MENU_CHATS = 2015;
    private static final int MENU_DELETE_SHOWN = 2016;
    private static final int MENU_TYPE_STICKER = 2017;
    private static final int MENU_RULE = 2018;

    private static final int ROW_CHAT = 0;
    private static final int ROW_SECTION = 1;
    private static final int ROW_TILE = 2;
    private static final int ROW_FILE = 3;
    /** hanako 2.01: what was not kept here and why, and "media off in this chat". */
    private static final int ROW_NOTE = 4;

    /** Fixed chat when opened from a chat's menu; 0 = global browser. */
    private final long fixedDialogId;
    private long chatFilter;
    private int typeFilter;

    private final ArrayList<PlusDeletedMedia.Item> allItems = new ArrayList<>();
    /** Shown items in display order (also the viewer's paging order). */
    private final ArrayList<PlusDeletedMedia.Item> shown = new ArrayList<>();
    private final ArrayList<Row> rows = new ArrayList<>();
    private boolean loading;
    private long shownSize;

    private RecyclerListView listView;
    private GridLayoutManager layoutManager;
    private ListAdapter adapter;
    private LinearLayout emptyView;
    private TextView emptyTitle;
    private TextView emptyText;
    private ImageView emptyIcon;
    private RadialProgressView progressView;
    private GraySectionCell pinnedHeader;
    private ActionBarMenuItem otherItem;
    /** Not-kept counts (MgDeletedRetention.NOTE_*) for {@link #notesDialog}; null until loaded. */
    private int[] notKept;
    private long notesDialog = Long.MIN_VALUE;

    private static final class Row {
        final int type;
        PlusDeletedMedia.Item item;
        long dialogId;
        CharSequence title;
        CharSequence info;
        /** Text the sticky header shows while this row is the first one on screen. */
        CharSequence sticky;

        Row(int type) {
            this.type = type;
        }
    }

    public PlusDeletedMediaActivity(long dialogId) {
        this.fixedDialogId = dialogId;
        this.chatFilter = dialogId;
    }

    @Override
    public boolean onFragmentCreate() {
        it.belloworld.mercurygram.HanakoTelemetry.count(it.belloworld.mercurygram.HanakoTelemetry.DELETED_MEDIA_OPEN); // hanako: usage statistics (off by default)
        reload();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PlusF20Title));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == MENU_TYPE_ALL) {
                    setTypeFilter(0);
                } else if (id == MENU_TYPE_PHOTO) {
                    setTypeFilter(PlusDeletedMedia.CAT_PHOTO);
                } else if (id == MENU_TYPE_VIDEO) {
                    setTypeFilter(PlusDeletedMedia.CAT_VIDEO);
                } else if (id == MENU_TYPE_FILE) {
                    setTypeFilter(PlusDeletedMedia.CAT_FILE);
                } else if (id == MENU_TYPE_STICKER) {
                    setTypeFilter(PlusDeletedMedia.CAT_STICKER);
                } else if (id == MENU_RULE) {
                    presentFragment(new HanakoKeepRuleActivity(chatFilter, 0));
                } else if (id == MENU_CHATS) {
                    showChatPicker();
                } else if (id == MENU_DELETE_SHOWN) {
                    confirmDeleteShown();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        other.addSubItem(MENU_TYPE_ALL, R.drawable.msg_media, LocaleController.getString(R.string.PlusF20FilterAll));
        other.addSubItem(MENU_TYPE_PHOTO, R.drawable.msg_photos, LocaleController.getString(R.string.PlusF20FilterPhotos));
        other.addSubItem(MENU_TYPE_VIDEO, R.drawable.msg_video, LocaleController.getString(R.string.PlusF20FilterVideos));
        other.addSubItem(MENU_TYPE_FILE, R.drawable.msg_list, LocaleController.getString(R.string.PlusF20FilterFiles));
        other.addSubItem(MENU_TYPE_STICKER, R.drawable.msg_sticker, LocaleController.getString(R.string.PlusF20FilterStickers));
        if (fixedDialogId == 0) {
            other.addSubItem(MENU_CHATS, R.drawable.msg_discussion, LocaleController.getString(R.string.PlusF20FilterChat));
        }
        if (fixedDialogId != 0) {
            other.addSubItem(MENU_RULE, R.drawable.msg_customize, LocaleController.getString(R.string.HanakoRuleChatMenu));
        }
        other.addSubItem(MENU_DELETE_SHOWN, R.drawable.msg_delete, LocaleController.getString(R.string.PlusF20DeleteShown));

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        // ---- empty state: icon in a soft circle, title, explanation ----
        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), AndroidUtilities.dp(48));

        emptyIcon = new ImageView(context);
        emptyIcon.setScaleType(ImageView.ScaleType.CENTER);
        emptyIcon.setImageResource(R.drawable.msg_media);
        emptyIcon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
        emptyIcon.setScaleX(1.5f);
        emptyIcon.setScaleY(1.5f);
        FrameLayout iconCircle = new FrameLayout(context);
        iconCircle.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(80),
                Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.12f)));
        iconCircle.addView(emptyIcon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView.addView(iconCircle, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        emptyTitle = new TextView(context);
        emptyTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        emptyTitle.setTypeface(AndroidUtilities.bold());
        emptyTitle.setGravity(Gravity.CENTER);
        emptyView.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));

        emptyText = new TextView(context);
        emptyText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(AndroidUtilities.dp(2), 1f);
        emptyView.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progressView = new RadialProgressView(context);
        progressView.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        frame.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        // ---- list: grid tiles for photos/videos, full-width rows for the rest ----
        listView = new RecyclerListView(context);
        layoutManager = new GridLayoutManager(context, spanCount());
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position >= 0 && position < rows.size() && rows.get(position).type == ROW_TILE) {
                    return 1;
                }
                return layoutManager.getSpanCount();
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.setVerticalScrollBarEnabled(true);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            Row row = rowAt(position);
            if (row == null) {
                return;
            }
            if (row.type == ROW_NOTE) {
                if (chatFilter != 0) {
                    presentFragment(new HanakoKeepRuleActivity(chatFilter, 0));
                }
                return;
            }
            if (row.type == ROW_CHAT) {
                openChat(row.dialogId); // hanako 2.02: a chat is its own screen, same filters, normal back
            } else if (row.item != null) {
                openItem(row.item);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            Row row = rowAt(position);
            if (row == null || row.item == null) {
                return false;
            }
            showItemMenu(row.item);
            return true;
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updatePinnedHeader();
            }
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        pinnedHeader = new GraySectionCell(context);
        pinnedHeader.setVisibility(View.GONE);
        frame.addView(pinnedHeader, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

        updateHeader();
        return fragmentView;
    }


    private Row rowAt(int position) {
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    private static int spanCount() {
        int width = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        if (AndroidUtilities.isTablet() || AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            width = AndroidUtilities.displaySize.x;
        }
        return Math.max(3, width / AndroidUtilities.dp(120));
    }

    private void reload() {
        loading = true;
        final int account = currentAccount;
        final long did = fixedDialogId;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<PlusDeletedMedia.Item> items = PlusDeletedMedia.load(account, did);
            if (did == 0) {
                // plus f20 + f08: the all-chats view leaves out chats that are behind the chat lock right now
                for (int i = items.size() - 1; i >= 0; i--) {
                    if (it.belloworld.mercurygram.PlusChatLock.isDialogLockedNow(account, items.get(i).entry.dialogId)) {
                        items.remove(i);
                    }
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                allItems.clear();
                allItems.addAll(items);
                applyFilters();
            });
        });
    }

    private void setTypeFilter(int type) {
        typeFilter = type;
        applyFilters();
    }

    // ---- ordering and grouping ----

    private static long timeOf(PlusDeletedMedia.Item item) {
        TLRPC.Message m = item.entry.message;
        return m != null && m.date != 0 ? m.date * 1000L : item.entry.whenMs;
    }

    private static int dayKey(Calendar calendar, long ms) {
        calendar.setTimeInMillis(ms);
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private String countAndSize(int count, long size) {
        return LocaleController.formatString(R.string.PlusF20CountSize, count, AndroidUtilities.formatFileSize(size));
    }

    private void applyFilters() {
        shown.clear();
        shownSize = 0;
        for (int i = 0; i < allItems.size(); i++) {
            PlusDeletedMedia.Item item = allItems.get(i);
            if (chatFilter != 0 && item.entry.dialogId != chatFilter) {
                continue;
            }
            if (typeFilter != 0 && item.category != typeFilter) {
                continue;
            }
            shown.add(item);
            shownSize += item.size;
        }
        final boolean groupByChat = chatFilter == 0;
        // chats ordered by their newest item, items newest first inside a chat
        final HashMap<Long, Long> newestOfChat = new HashMap<>();
        final HashMap<Long, int[]> countOfChat = new HashMap<>();
        final HashMap<Long, long[]> sizeOfChat = new HashMap<>();
        for (int i = 0; i < shown.size(); i++) {
            PlusDeletedMedia.Item item = shown.get(i);
            long did = item.entry.dialogId;
            long t = timeOf(item);
            Long newest = newestOfChat.get(did);
            if (newest == null || t > newest) {
                newestOfChat.put(did, t);
            }
            int[] c = countOfChat.get(did);
            if (c == null) {
                countOfChat.put(did, c = new int[1]);
            }
            c[0]++;
            long[] s = sizeOfChat.get(did);
            if (s == null) {
                sizeOfChat.put(did, s = new long[1]);
            }
            s[0] += item.size;
        }
        Collections.sort(shown, (a, b) -> {
            if (groupByChat && a.entry.dialogId != b.entry.dialogId) {
                int byChat = Long.compare(newestOfChat.get(b.entry.dialogId), newestOfChat.get(a.entry.dialogId));
                return byChat != 0 ? byChat : Long.compare(a.entry.dialogId, b.entry.dialogId);
            }
            return Long.compare(timeOf(b), timeOf(a));
        });

        rows.clear();
        if (chatFilter != notesDialog) {
            loadNotes();
        }
        CharSequence note = notesText();
        if (note != null) {
            Row n = new Row(ROW_NOTE);
            n.title = note;
            rows.add(n);
        }
        Calendar calendar = Calendar.getInstance();
        long lastDialog = Long.MIN_VALUE;
        int lastDay = -1;
        String chatName = null;
        // per-day totals, filled after the section row is added
        Row section = null;
        int sectionCount = 0;
        long sectionSize = 0;
        for (int i = 0; i < shown.size(); i++) {
            PlusDeletedMedia.Item item = shown.get(i);
            long did = item.entry.dialogId;
            long t = timeOf(item);
            int day = dayKey(calendar, t);
            boolean newChat = groupByChat && did != lastDialog;
            if (newChat) {
                if (section != null) {
                    section.info = countAndSize(sectionCount, sectionSize);
                    section = null;
                }
                chatName = PlusDeletedMedia.chatTitle(currentAccount, did);
                Row chat = new Row(ROW_CHAT);
                chat.dialogId = did;
                chat.title = chatName;
                chat.info = countAndSize(countOfChat.get(did)[0], sizeOfChat.get(did)[0]);
                chat.sticky = chatName;
                rows.add(chat);
                lastDialog = did;
                lastDay = -1;
            }
            if (day != lastDay) {
                if (section != null) {
                    section.info = countAndSize(sectionCount, sectionSize);
                }
                section = new Row(ROW_SECTION);
                section.title = LocaleController.formatDateChat(t / 1000L);
                section.sticky = groupByChat ? chatName + " · " + section.title : section.title;
                rows.add(section);
                sectionCount = 0;
                sectionSize = 0;
                lastDay = day;
            }
            Row row = new Row(item.category == PlusDeletedMedia.CAT_FILE ? ROW_FILE : ROW_TILE);
            row.item = item;
            row.sticky = section.sticky;
            rows.add(row);
            sectionCount++;
            sectionSize += item.size;
        }
        if (section != null) {
            section.info = countAndSize(sectionCount, sectionSize);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateHeader();
        if (listView != null) {
            listView.post(this::updatePinnedHeader);
        }
    }

    /** Sticky header: the current chat / day stays on top, pushed up by the next one. */
    private void updatePinnedHeader() {
        if (pinnedHeader == null || listView == null || layoutManager == null) {
            return;
        }
        int first = layoutManager.findFirstVisibleItemPosition();
        Row row = rowAt(first);
        View firstView = first >= 0 ? layoutManager.findViewByPosition(first) : null;
        if (row == null || firstView == null || (first == 0 && firstView.getTop() >= 0) || row.sticky == null) {
            pinnedHeader.setVisibility(View.GONE);
            return;
        }
        if (!TextUtils.equals(pinnedHeader.getText(), row.sticky)) {
            pinnedHeader.setText(row.sticky);
        }
        pinnedHeader.setVisibility(View.VISIBLE);
        int height = pinnedHeader.getMeasuredHeight() > 0 ? pinnedHeader.getMeasuredHeight() : AndroidUtilities.dp(32);
        float offset = 0;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            int pos = listView.getChildAdapterPosition(child);
            Row r = rowAt(pos);
            if (pos > first && r != null && (r.type == ROW_SECTION || r.type == ROW_CHAT)) {
                int top = child.getTop();
                if (top < height && top > 0) {
                    offset = Math.min(offset, top - height);
                }
            }
        }
        pinnedHeader.setTranslationY(offset);
    }

    private void updateHeader() {
        if (actionBar == null) {
            return;
        }
        String scope = chatFilter != 0
                ? PlusDeletedMedia.chatTitle(currentAccount, chatFilter)
                : LocaleController.getString(R.string.PlusF20AllChats);
        String type;
        switch (typeFilter) {
            case PlusDeletedMedia.CAT_PHOTO:
                type = LocaleController.getString(R.string.PlusF20FilterPhotos);
                break;
            case PlusDeletedMedia.CAT_VIDEO:
                type = LocaleController.getString(R.string.PlusF20FilterVideos);
                break;
            case PlusDeletedMedia.CAT_FILE:
                type = LocaleController.getString(R.string.PlusF20FilterFiles);
                break;
            case PlusDeletedMedia.CAT_STICKER:
                type = LocaleController.getString(R.string.PlusF20FilterStickers);
                break;
            default:
                type = null;
        }
        String subtitle = loading ? LocaleController.getString(R.string.Loading)
                : scope + (type != null ? " · " + type : "") + " · " + countAndSize(shown.size(), shownSize);
        actionBar.setSubtitle(subtitle);
        if (emptyView != null) {
            boolean filtered = typeFilter != 0 || (chatFilter != 0 && fixedDialogId == 0);
            emptyTitle.setText(LocaleController.getString(filtered ? R.string.PlusF20EmptyFilteredTitle : R.string.PlusF20EmptyTitle));
            CharSequence note = notesText();
            String empty = LocaleController.getString(filtered ? R.string.PlusF20EmptyFiltered : R.string.PlusF20Empty);
            emptyText.setText(note != null ? empty + "\n\n" + note : empty);
            emptyView.setVisibility(!loading && shown.isEmpty() ? View.VISIBLE : View.GONE);
            progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
            listView.setVisibility(!loading && !shown.isEmpty() ? View.VISIBLE : View.GONE);
            if (shown.isEmpty() && pinnedHeader != null) {
                pinnedHeader.setVisibility(View.GONE);
            }
        }
    }

    // ---- actions ----

    private void openItem(PlusDeletedMedia.Item item) {
        if (item == null || getParentActivity() == null) {
            return;
        }
        it.belloworld.mercurygram.HanakoTelemetry.count(it.belloworld.mercurygram.HanakoTelemetry.DELETED_MEDIA_VIEW); // hanako: usage statistics (off by default)
        if (item.category == PlusDeletedMedia.CAT_STICKER) {
            showStickerPreview(item);
            return;
        }
        if (!item.opensInViewer()) {
            PlusDeletedMedia.openExternal(getParentActivity(), item);
            return;
        }
        ArrayList<MessageObject> objects = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < shown.size(); i++) {
            PlusDeletedMedia.Item it = shown.get(i);
            if (it.opensInViewer()) {
                if (it == item) {
                    index = objects.size();
                }
                objects.add(it.viewerObject);
            }
        }
        // dialogId 0: the viewer must not page through (and load) the chat's shared media.
        PhotoViewer.getInstance().setParentActivity(this);
        PhotoViewer.getInstance().openPhoto(objects, index, 0, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    private void showItemMenu(final PlusDeletedMedia.Item item) {
        if (item == null || getParentActivity() == null) {
            return;
        }
        CharSequence save = LocaleController.getString(!toGallery(item)
                ? R.string.PlusF20SaveDownloads : R.string.PlusF20SaveGallery);
        final long did = item.entry.dialogId;
        CharSequence[] labels = {
                LocaleController.getString(R.string.PlusF20Open),
                save,
                LocaleController.getString(R.string.PlusF20Share),
                LocaleController.getString(R.string.PlusF20Details),
                LocaleController.getString(R.string.HanakoShowInChat),
                LocaleController.getString(R.string.Delete)
        };
        int[] icons = {R.drawable.msg_openin, R.drawable.msg_gallery, R.drawable.msg_shareout,
                R.drawable.msg_info, R.drawable.msg_message, R.drawable.msg_delete};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(item.displayName);
        builder.setItems(labels, icons, (dialog, which) -> {
            switch (which) {
                case 0:
                    openItem(item);
                    break;
                case 1:
                    exportItem(item);
                    break;
                case 2:
                    PlusDeletedMedia.share(getParentActivity(), item);
                    break;
                case 3:
                    showDetails(item);
                    break;
                case 4:
                    showInChat(did, item.entry.message != null ? item.entry.message.id : 0);
                    break;
                case 5:
                    confirmDelete(item);
                    break;
            }
        });
        showDialog(builder.create());
    }

    private static boolean toGallery(PlusDeletedMedia.Item item) {
        return item.category == PlusDeletedMedia.CAT_PHOTO || item.category == PlusDeletedMedia.CAT_VIDEO;
    }

    // ---- hanako 2.01: what was not kept, and "stop keeping media here" ----

    private void loadNotes() {
        final long did = chatFilter;
        final int account = currentAccount;
        notesDialog = did;
        notKept = null;
        Utilities.globalQueue.postRunnable(() -> {
            int[] counts = MgMessageHistory.getInstance().countNotKept(account, did);
            AndroidUtilities.runOnUIThread(() -> {
                if (notesDialog == did) {
                    notKept = counts;
                    applyFilters();
                }
            });
        });
    }

    /** "Media not kept: 12 never downloaded · 3 over the size limit", plus the chat's own switch. */
    private CharSequence notesText() {
        StringBuilder sb = new StringBuilder();
        it.belloworld.mercurygram.MgDeletedRetention.Rule rule = chatFilter != 0
                ? it.belloworld.mercurygram.MgDeletedRetention.getRule(currentAccount, chatFilter, 0) : null;
        if (rule != null) {
            sb.append(LocaleController.getString(R.string.HanakoRulesHeader)).append(": ")
                    .append(it.belloworld.mercurygram.MgDeletedRetention.keepLabel(rule.keep));
        }
        if (notKept != null && notesDialog == chatFilter) {
            final int[] res = {0, R.string.HanakoNotKeptMediaOff, R.string.HanakoNotKeptTooBig, R.string.HanakoNotKeptChatOff,
                    R.string.HanakoNotKeptNotDownloaded, R.string.HanakoNotKeptPruned};
            StringBuilder parts = new StringBuilder();
            for (int i = 1; i < notKept.length && i < res.length; i++) {
                if (notKept[i] > 0) {
                    if (parts.length() > 0) {
                        parts.append(" · ");
                    }
                    parts.append(LocaleController.formatString(res[i], notKept[i]));
                }
            }
            if (parts.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(LocaleController.formatString(R.string.HanakoNotKept, parts));
            }
        }
        return sb.length() > 0 ? sb : null;
    }

    /** One chat's deleted media, as its own screen with the current type filter. */
    private void openChat(long did) {
        PlusDeletedMediaActivity f = new PlusDeletedMediaActivity(did);
        f.typeFilter = typeFilter;
        presentFragment(f);
    }

    /** The deleted message in its chat, where it stays as a grayed-out "deleted" bubble. */
    private void showInChat(long did, int mid) {
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did);
        } else {
            args.putLong("chat_id", -did);
        }
        if (mid > 0) {
            args.putInt("message_id", mid);
        }
        presentFragment(new ChatActivity(args));
    }

    /** Sticker shown whole, animated (Lottie / video stickers play), on a plain dialog. */
    private void showStickerPreview(PlusDeletedMedia.Item item) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        FrameLayout box = new FrameLayout(context);
        BackupImageView view = new BackupImageView(context);
        view.getImageReceiver().setAspectFit(true);
        view.getImageReceiver().setAutoRepeat(1);
        view.setImage(ImageLocation.getForPath(item.file.getAbsolutePath()), "512_512", item.stickerExt(), null, null);
        box.addView(view, LayoutHelper.createFrame(220, 220, Gravity.CENTER, 0, 16, 0, 8));
        String from = senderName(currentAccount, item);
        showDialog(new AlertDialog.Builder(context)
                .setTitle(from != null ? from : PlusDeletedMedia.chatTitle(currentAccount, item.entry.dialogId))
                .setView(box)
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .setPositiveButton(LocaleController.getString(R.string.PlusF20Share), (d, w) -> PlusDeletedMedia.share(getParentActivity(), item))
                .create());
    }

    private void exportItem(PlusDeletedMedia.Item item) {
        if (!PlusDeletedMedia.checkStoragePermission(getParentActivity())) {
            toast(LocaleController.getString(R.string.PlusF20NeedStorage));
            return;
        }
        PlusDeletedMedia.export(item, ok -> toast(LocaleController.getString(ok
                ? (!toGallery(item) ? R.string.PlusF20SavedDownloads : R.string.PlusF20SavedGallery)
                : R.string.PlusF20SaveFailed)));
    }

    private void toast(String text) {
        Context ctx = getParentActivity();
        if (ctx != null) {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        }
    }

    private void showDetails(PlusDeletedMedia.Item item) {
        if (getParentActivity() == null) {
            return;
        }
        int originRes;
        switch (item.origin) {
            case PlusDeletedMedia.ORIGIN_VIEW_ONCE:
                originRes = R.string.PlusF20OriginViewOnce;
                break;
            case PlusDeletedMedia.ORIGIN_EDITED:
                originRes = R.string.PlusF20OriginEdited;
                break;
            default:
                originRes = R.string.PlusF20OriginDeleted;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.getString(R.string.PlusF20DetailChat)).append(": ")
                .append(PlusDeletedMedia.chatTitle(currentAccount, item.entry.dialogId)).append('\n');
        String sender = senderName(currentAccount, item);
        if (sender != null) {
            sb.append(LocaleController.getString(R.string.PlusF20DetailFrom)).append(": ")
                    .append(sender).append('\n');
        }
        sb.append(LocaleController.getString(R.string.PlusF20DetailOrigin)).append(": ")
                .append(LocaleController.getString(originRes)).append('\n');
        TLRPC.Message m = item.entry.message;
        if (m != null && m.date != 0) {
            sb.append(LocaleController.getString(R.string.PlusF20DetailSent)).append(": ")
                    .append(LocaleController.getInstance().getFormatterStats().format(m.date * 1000L)).append('\n');
        }
        int savedLabelRes = item.origin == PlusDeletedMedia.ORIGIN_DELETED
                ? R.string.PlusF20DetailDeleted : R.string.PlusF20DetailSaved;
        sb.append(LocaleController.getString(savedLabelRes)).append(": ")
                .append(LocaleController.getInstance().getFormatterStats().format(item.entry.whenMs)).append('\n');
        sb.append(LocaleController.getString(R.string.PlusF20DetailSize)).append(": ")
                .append(AndroidUtilities.formatFileSize(item.file.length()));
        if (m != null && !TextUtils.isEmpty(m.message)) {
            sb.append("\n\n").append(m.message);
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(item.displayName)
                .setMessage(sb.toString())
                .setPositiveButton(LocaleController.getString(R.string.Close), null)
                .show();
    }

    private void confirmDelete(PlusDeletedMedia.Item item) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.PlusF20DeleteTitle))
                .setMessage(LocaleController.getString(R.string.PlusF20DeleteConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) ->
                        PlusDeletedMedia.delete(item, () -> removeItem(item)))
                .show();
    }

    private void confirmDeleteShown() {
        if (getParentActivity() == null || shown.isEmpty()) {
            return;
        }
        final ArrayList<PlusDeletedMedia.Item> victims = new ArrayList<>(shown);
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.PlusF20DeleteShown))
                .setMessage(LocaleController.formatString(R.string.PlusF20DeleteShownConfirm, victims.size()))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    for (int i = 0; i < victims.size(); i++) {
                        PlusDeletedMedia.Item item = victims.get(i);
                        PlusDeletedMedia.delete(item, () -> removeItem(item));
                    }
                })
                .show();
    }

    private void removeItem(PlusDeletedMedia.Item item) {
        if (allItems.remove(item)) {
            applyFilters();
        }
    }

    private void showChatPicker() {
        if (getParentActivity() == null) {
            return;
        }
        LinkedHashMap<Long, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < allItems.size(); i++) {
            long did = allItems.get(i).entry.dialogId;
            Integer c = counts.get(did);
            counts.put(did, c == null ? 1 : c + 1);
        }
        final long[] ids = new long[counts.size() + 1];
        CharSequence[] labels = new CharSequence[counts.size() + 1];
        labels[0] = LocaleController.getString(R.string.PlusF20AllChats) + " (" + allItems.size() + ")";
        int n = 1;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            ids[n] = e.getKey();
            labels[n] = PlusDeletedMedia.chatTitle(currentAccount, e.getKey()) + " (" + e.getValue() + ")";
            n++;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.PlusF20FilterChat))
                .setItems(labels, (dialog, which) -> {
                    if (which > 0 && which < ids.length) openChat(ids[which]);
                })
                .show();
    }

    // ---- labels ----

    /** Who sent the message, from memory only; "You" for own messages, null for channel posts. */
    static String senderName(int account, PlusDeletedMedia.Item item) {
        TLRPC.Message m = item.entry.message;
        if (m == null) {
            return null;
        }
        if (m.out) {
            return LocaleController.getString(R.string.FromYou);
        }
        long from = MessageObject.getFromChatId(m);
        if (from == 0 && DialogObject.isUserDialog(item.entry.dialogId)) {
            from = item.entry.dialogId;
        }
        if (from == 0) {
            return null;
        }
        if (from == UserConfig.getInstance(account).getClientUserId()) {
            return LocaleController.getString(R.string.FromYou);
        }
        if (from > 0) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(from);
            return user != null ? UserObject.getFirstName(user) : null;
        }
        if (from == item.entry.dialogId) {
            return null; // the channel / group itself: its title is already the header
        }
        return PlusDeletedMedia.chatTitle(account, from);
    }

    private static String timeText(PlusDeletedMedia.Item item) {
        if (item.entry.kind == MgMessageHistory.KIND_DELETED) {
            return LocaleController.formatDateTime(item.entry.whenMs / 1000, true);
        }
        return LocaleController.getInstance().getFormatterDay().format(timeOf(item));
    }

    // ---- list ----

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != ROW_SECTION;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            Row row = rowAt(position);
            return row != null ? row.type : ROW_SECTION;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case ROW_CHAT:
                    view = new ChatHeaderCell(context);
                    break;
                case ROW_TILE:
                    view = new Tile(context);
                    break;
                case ROW_FILE:
                    view = new FileCell(context);
                    break;
                case ROW_NOTE:
                    view = new org.telegram.ui.Cells.TextInfoPrivacyCell(context);
                    break;
                default:
                    view = new GraySectionCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rowAt(position);
            if (row == null) {
                return;
            }
            switch (row.type) {
                case ROW_CHAT:
                    ((ChatHeaderCell) holder.itemView).bind(currentAccount, row);
                    break;
                case ROW_TILE:
                    ((Tile) holder.itemView).bind(currentAccount, row.item);
                    break;
                case ROW_FILE: {
                    Row next = rowAt(position + 1);
                    ((FileCell) holder.itemView).bind(currentAccount, row.item, next != null && next.type == ROW_FILE);
                    break;
                }
                case ROW_NOTE:
                    ((org.telegram.ui.Cells.TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                default:
                    ((GraySectionCell) holder.itemView).setText(row.title, row.info, null);
                    break;
            }
        }
    }

    /** Chat group header (all-chats view): avatar, title, "N saved · size". Tap = only this chat. */
    private static class ChatHeaderCell extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView title;
        private final TextView info;
        private final ImageView arrow;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();

        ChatHeaderCell(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(false));
            boolean rtl = LocaleController.isRTL;

            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(18));
            addView(avatar, LayoutHelper.createFrame(36, 36, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 0 : 16, 0, rtl ? 16 : 0, 0));

            title = new TextView(context);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 44 : 64, 9, rtl ? 64 : 44, 0));

            info = new TextView(context);
            info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            info.setSingleLine(true);
            info.setEllipsize(TextUtils.TruncateAt.END);
            info.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
            addView(info, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 44 : 64, 31, rtl ? 64 : 44, 0));

            arrow = new ImageView(context);
            arrow.setScaleType(ImageView.ScaleType.CENTER);
            arrow.setImageResource(R.drawable.msg_arrowright);
            arrow.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            if (rtl) {
                arrow.setScaleX(-1f);
            }
            addView(arrow, LayoutHelper.createFrame(24, 24, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 16, 0, 16, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(56), MeasureSpec.EXACTLY));
        }

        void bind(int account, Row row) {
            title.setText(row.title);
            info.setText(row.info);
            long did = row.dialogId;
            MessagesController mc = MessagesController.getInstance(account);
            TLObject peer = null;
            if (DialogObject.isEncryptedDialog(did)) {
                TLRPC.EncryptedChat ec = mc.getEncryptedChat(DialogObject.getEncryptedChatId(did));
                peer = ec != null ? mc.getUser(ec.user_id) : null;
            } else if (DialogObject.isUserDialog(did)) {
                peer = mc.getUser(did);
            } else {
                peer = mc.getChat(-did);
            }
            if (peer != null) {
                avatarDrawable.setInfo(account, peer);
                avatar.setForUserOrChat(peer, avatarDrawable);
            } else {
                avatarDrawable.setInfo(did, row.title != null ? row.title.toString() : "", null);
                avatar.setImageDrawable(avatarDrawable);
            }
        }
    }

    /** Square grid tile: thumbnail, duration / GIF / view-once badges, sender and time strip. */
    private static class Tile extends FrameLayout {
        private final BackupImageView image;
        private final TextView name;
        private final TextView duration;
        private final TextView once;
        private final TextView copies;
        private final View shade;
        private final TextView sender;
        private final TextView time;
        private PlusDeletedMedia.Item bound;

        Tile(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1));

            FrameLayout inner = new FrameLayout(context);
            inner.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            name = new TextView(context);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(3);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
            inner.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            image = new BackupImageView(context);
            image.getImageReceiver().setAllowStartAnimation(false);
            inner.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // media overlays are white on a dark scrim in stock Telegram, in every theme
            shade = new View(context);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, 0x00000000}));
            inner.addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 30, Gravity.BOTTOM));

            sender = new TextView(context);
            sender.setTextColor(0xffffffff);
            sender.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            sender.setSingleLine(true);
            sender.setEllipsize(TextUtils.TruncateAt.END);
            inner.addView(sender, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.BOTTOM, 5, 0, 42, 3));

            time = new TextView(context);
            time.setTextColor(0xffffffff);
            time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            time.setSingleLine(true);
            inner.addView(time, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 5, 3));

            duration = new TextView(context);
            duration.setTextColor(0xffffffff);
            duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            duration.setTypeface(AndroidUtilities.bold());
            duration.setGravity(Gravity.CENTER_VERTICAL);
            duration.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), 0x66000000));
            duration.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(1), AndroidUtilities.dp(5), AndroidUtilities.dp(1));
            duration.setCompoundDrawablePadding(AndroidUtilities.dp(2));
            inner.addView(duration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.TOP, 0, 4, 4, 0));

            once = new TextView(context);
            once.setTextColor(0xffffffff);
            once.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            once.setTypeface(AndroidUtilities.bold());
            once.setText("1×");
            once.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(9), Theme.getColor(Theme.key_featuredStickers_addButton)));
            once.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(1), AndroidUtilities.dp(6), AndroidUtilities.dp(1));
            inner.addView(once, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.TOP, 4, 4, 0, 0));

            // hanako 2.01: "×N" when the same file was deleted N times in this chat
            copies = new TextView(context);
            copies.setTextColor(0xffffffff);
            copies.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            copies.setTypeface(AndroidUtilities.bold());
            copies.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), 0x66000000));
            copies.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(1), AndroidUtilities.dp(5), AndroidUtilities.dp(1));
            inner.addView(copies, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.TOP, 4, 4, 0, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY));
        }

        void bind(int account, PlusDeletedMedia.Item item) {
            bound = item;
            image.setImageDrawable(null);
            String preview = item.previewPath();
            boolean sticker = item.category == PlusDeletedMedia.CAT_STICKER;
            // stickers: whole (fit, not cropped), transparent, first frame; they play when opened
            image.getImageReceiver().setAspectFit(sticker);
            image.getImageReceiver().setAllowStartLottieAnimation(false);
            ((View) image.getParent()).setBackgroundColor(sticker ? 0 : Theme.getColor(Theme.key_windowBackgroundGray));
            int pad = sticker ? AndroidUtilities.dp(10) : 0;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) image.getLayoutParams();
            if (lp.leftMargin != pad) {
                lp.setMargins(pad, pad, pad, pad + (sticker ? AndroidUtilities.dp(8) : 0));
                image.setLayoutParams(lp);
            }
            if (sticker) {
                image.setImage(ImageLocation.getForPath(preview), "200_200", item.stickerExt(), null, null);
                name.setText(null);
            } else if (preview != null) {
                image.setImage(ImageLocation.getForPath(preview), "200_200", (Drawable) null, null);
                name.setText(null);
            } else if (item.category == PlusDeletedMedia.CAT_VIDEO) {
                name.setText(null);
                final String path = item.file.getAbsolutePath();
                Bitmap cached = PlusDeletedMedia.cachedVideoThumb(path);
                if (cached != null) {
                    image.setImageBitmap(cached);
                } else {
                    PlusDeletedMedia.loadVideoThumb(path, bmp -> {
                        if (bound == item && bmp != null) {
                            image.setImageBitmap(bmp);
                        }
                    });
                }
            } else {
                name.setText(item.displayName);
            }

            String durationText = null;
            boolean play = false;
            TLRPC.Document doc = item.document();
            if (item.category == PlusDeletedMedia.CAT_VIDEO && doc != null) {
                if (MessageObject.isGifDocument(doc)) {
                    durationText = LocaleController.getString(R.string.AttachGif);
                } else {
                    int seconds = (int) Math.round(MessageObject.getDocumentDuration(doc));
                    durationText = AndroidUtilities.formatShortDuration(seconds);
                    play = true;
                }
            }
            duration.setText(durationText);
            duration.setCompoundDrawablesWithIntrinsicBounds(play ? R.drawable.play_mini_video : 0, 0, 0, 0);
            duration.setVisibility(durationText != null ? View.VISIBLE : View.GONE);
            once.setVisibility(item.origin == PlusDeletedMedia.ORIGIN_VIEW_ONCE ? View.VISIBLE : View.GONE);
            copies.setText(item.copies > 1 ? "×" + item.copies : null);
            copies.setVisibility(item.copies > 1 && item.origin != PlusDeletedMedia.ORIGIN_VIEW_ONCE ? View.VISIBLE : View.GONE);

            String from = senderName(account, item);
            sender.setText(from);
            sender.setVisibility(from != null ? View.VISIBLE : View.GONE);
            time.setText(timeText(item));
        }
    }

    /** Full-width row for files, voice and music: type icon or thumbnail, name, details line. */
    private static class FileCell extends FrameLayout {
        private final FrameLayout iconFrame;
        private final BackupImageView thumb;
        private final ImageView icon;
        private final TextView ext;
        private final TextView title;
        private final TextView info;
        private boolean divider;

        FileCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackground(Theme.getSelectorDrawable(false));
            boolean rtl = LocaleController.isRTL;

            iconFrame = new FrameLayout(context);
            addView(iconFrame, LayoutHelper.createFrame(44, 44, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 0 : 14, 0, rtl ? 14 : 0, 0));

            thumb = new BackupImageView(context);
            thumb.setRoundRadius(AndroidUtilities.dp(6));
            iconFrame.addView(thumb, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            iconFrame.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            ext = new TextView(context);
            ext.setTextColor(Theme.getColor(Theme.key_files_iconText));
            ext.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            ext.setTypeface(AndroidUtilities.bold());
            ext.setSingleLine(true);
            ext.setEllipsize(TextUtils.TruncateAt.END);
            ext.setGravity(Gravity.CENTER);
            iconFrame.addView(ext, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 3, 0, 3, 5));

            title = new TextView(context);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 16 : 72, 10, rtl ? 72 : 16, 0));

            info = new TextView(context);
            info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            info.setSingleLine(true);
            info.setEllipsize(TextUtils.TruncateAt.END);
            info.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
            addView(info, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 16 : 72, 34, rtl ? 72 : 16, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(60), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (divider && Theme.dividerPaint != null) {
                int inset = AndroidUtilities.dp(72);
                if (LocaleController.isRTL) {
                    canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth() - inset, getMeasuredHeight() - 1, Theme.dividerPaint);
                } else {
                    canvas.drawLine(inset, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
                }
            }
        }

        void bind(int account, PlusDeletedMedia.Item item, boolean divider) {
            this.divider = divider;
            TLRPC.Document doc = item.document();
            boolean voice = doc != null && MessageObject.isVoiceDocument(doc);
            boolean music = doc != null && !voice && MessageObject.isMusicDocument(doc);

            String preview = item.previewPath();
            thumb.setImageDrawable(null);
            icon.setImageDrawable(null);
            icon.setBackground(null);
            ext.setText(null);
            if (voice || music) {
                icon.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(44), Theme.getColor(Theme.key_featuredStickers_addButton)));
                icon.setImageResource(voice ? R.drawable.msg_filled_data_voice : R.drawable.msg_filled_data_music);
                icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
            } else if (preview != null) {
                icon.clearColorFilter();
                thumb.setImage(ImageLocation.getForPath(preview), "88_88", (Drawable) null, null);
            } else {
                icon.clearColorFilter();
                icon.setImageResource(AndroidUtilities.getThumbForNameOrMime(item.displayName, item.mime, false));
                String e = FileLoader.getFileExtension(item.file);
                ext.setText(e != null ? e.toLowerCase() : null);
            }

            String name;
            if (voice) {
                name = LocaleController.getString(R.string.AttachAudio);
            } else if (music) {
                String t = MessageObject.getMusicTitle(doc, false);
                String p = MessageObject.getMusicAuthor(doc, false);
                name = !TextUtils.isEmpty(p) && !TextUtils.isEmpty(t) ? p + " – " + t : item.displayName;
            } else {
                name = item.displayName;
            }
            title.setText(name);

            StringBuilder sb = new StringBuilder();
            String from = senderName(account, item);
            if (from != null) {
                sb.append(from).append(" · ");
            }
            sb.append(timeText(item)).append(" · ").append(AndroidUtilities.formatFileSize(item.size));
            if ((voice || music) && doc != null) {
                int seconds = (int) Math.round(MessageObject.getDocumentDuration(doc));
                if (seconds > 0) {
                    sb.append(" · ").append(AndroidUtilities.formatShortDuration(seconds));
                }
            }
            if (item.origin == PlusDeletedMedia.ORIGIN_VIEW_ONCE) {
                sb.append(" · 1×");
            }
            info.setText(sb);
            invalidate();
        }
    }
}
