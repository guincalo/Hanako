package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import it.belloworld.mercurygram.PlusDeletedMedia;

/**
 * plus f20: grid of media saved from deleted (and expired view-once) messages,
 * for one chat or for every chat of the account. Tap opens the in-app viewer
 * (photos, videos, GIFs) or another app (files); long-press offers save to
 * gallery / Downloads, share, details and delete. Local only: no request is
 * ever sent to Telegram from this screen.
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

    /** Fixed chat when opened from a chat's menu; 0 = global browser. */
    private final long fixedDialogId;
    private long chatFilter;
    private int typeFilter;

    private final ArrayList<PlusDeletedMedia.Item> allItems = new ArrayList<>();
    private final ArrayList<PlusDeletedMedia.Item> shown = new ArrayList<>();
    private boolean loading;

    private RecyclerListView listView;
    private GridLayoutManager layoutManager;
    private GridAdapter adapter;
    private TextView emptyView;

    public PlusDeletedMediaActivity(long dialogId) {
        this.fixedDialogId = dialogId;
        this.chatFilter = dialogId;
    }

    @Override
    public boolean onFragmentCreate() {
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
                    finishFragment();
                } else if (id == MENU_TYPE_ALL) {
                    setTypeFilter(0);
                } else if (id == MENU_TYPE_PHOTO) {
                    setTypeFilter(PlusDeletedMedia.CAT_PHOTO);
                } else if (id == MENU_TYPE_VIDEO) {
                    setTypeFilter(PlusDeletedMedia.CAT_VIDEO);
                } else if (id == MENU_TYPE_FILE) {
                    setTypeFilter(PlusDeletedMedia.CAT_FILE);
                } else if (id == MENU_CHATS) {
                    showChatPicker();
                } else if (id == MENU_DELETE_SHOWN) {
                    confirmDeleteShown();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        other.addSubItem(MENU_TYPE_ALL, R.drawable.msg_media, LocaleController.getString(R.string.PlusF20FilterAll));
        other.addSubItem(MENU_TYPE_PHOTO, R.drawable.msg_photos, LocaleController.getString(R.string.PlusF20FilterPhotos));
        other.addSubItem(MENU_TYPE_VIDEO, R.drawable.msg_video, LocaleController.getString(R.string.PlusF20FilterVideos));
        other.addSubItem(MENU_TYPE_FILE, R.drawable.msg_list, LocaleController.getString(R.string.PlusF20FilterFiles));
        if (fixedDialogId == 0) {
            other.addSubItem(MENU_CHATS, R.drawable.msg_discussion, LocaleController.getString(R.string.PlusF20FilterChat));
        }
        other.addSubItem(MENU_DELETE_SHOWN, R.drawable.msg_delete, LocaleController.getString(R.string.PlusF20DeleteShown));

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        layoutManager = new GridLayoutManager(context, spanCount());
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(adapter = new GridAdapter(context));
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((view, position) -> openItem(position));
        listView.setOnItemLongClickListener((view, position) -> {
            showItemMenu(position);
            return true;
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateHeader();
        return fragmentView;
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

    private void applyFilters() {
        shown.clear();
        for (int i = 0; i < allItems.size(); i++) {
            PlusDeletedMedia.Item item = allItems.get(i);
            if (chatFilter != 0 && item.entry.dialogId != chatFilter) {
                continue;
            }
            if (typeFilter != 0 && item.category != typeFilter) {
                continue;
            }
            shown.add(item);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateHeader();
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
            default:
                type = null;
        }
        String subtitle = loading ? LocaleController.getString(R.string.Loading)
                : scope + (type != null ? " · " + type : "") + " · " + shown.size();
        actionBar.setSubtitle(subtitle);
        if (emptyView != null) {
            emptyView.setText(loading ? LocaleController.getString(R.string.Loading)
                    : LocaleController.getString(R.string.PlusF20Empty));
        }
    }

    // ---- actions ----

    private void openItem(int position) {
        if (position < 0 || position >= shown.size() || getParentActivity() == null) {
            return;
        }
        PlusDeletedMedia.Item item = shown.get(position);
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

    private void showItemMenu(int position) {
        if (position < 0 || position >= shown.size() || getParentActivity() == null) {
            return;
        }
        final PlusDeletedMedia.Item item = shown.get(position);
        CharSequence save = LocaleController.getString(item.category == PlusDeletedMedia.CAT_FILE
                ? R.string.PlusF20SaveDownloads : R.string.PlusF20SaveGallery);
        CharSequence[] labels = {
                LocaleController.getString(R.string.PlusF20Open),
                save,
                LocaleController.getString(R.string.PlusF20Share),
                LocaleController.getString(R.string.PlusF20Details),
                LocaleController.getString(R.string.Delete)
        };
        int[] icons = {R.drawable.msg_openin, R.drawable.msg_gallery, R.drawable.msg_shareout,
                R.drawable.msg_info, R.drawable.msg_delete};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(item.displayName);
        builder.setItems(labels, icons, (dialog, which) -> {
            switch (which) {
                case 0:
                    openItem(position);
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
                    confirmDelete(item);
                    break;
            }
        });
        showDialog(builder.create());
    }

    private void exportItem(PlusDeletedMedia.Item item) {
        if (!PlusDeletedMedia.checkStoragePermission(getParentActivity())) {
            toast(LocaleController.getString(R.string.PlusF20NeedStorage));
            return;
        }
        PlusDeletedMedia.export(item, ok -> toast(LocaleController.getString(ok
                ? (item.category == PlusDeletedMedia.CAT_FILE ? R.string.PlusF20SavedDownloads : R.string.PlusF20SavedGallery)
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
        sb.append(LocaleController.getString(R.string.PlusF20DetailOrigin)).append(": ")
                .append(LocaleController.getString(originRes)).append('\n');
        TLRPC.Message m = item.entry.message;
        if (m != null && m.date != 0) {
            sb.append(LocaleController.getString(R.string.PlusF20DetailSent)).append(": ")
                    .append(LocaleController.getInstance().getFormatterStats().format(m.date * 1000L)).append('\n');
        }
        sb.append(LocaleController.getString(R.string.PlusF20DetailSaved)).append(": ")
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
        allItems.remove(item);
        int idx = shown.indexOf(item);
        if (idx >= 0) {
            shown.remove(idx);
            if (adapter != null) {
                adapter.notifyItemRemoved(idx);
            }
        }
        updateHeader();
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
                    chatFilter = which >= 0 && which < ids.length ? ids[which] : 0;
                    applyFilters();
                })
                .show();
    }

    // ---- grid ----

    private class GridAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        GridAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Tile tile = new Tile(context);
            tile.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(tile);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position >= 0 && position < shown.size()) {
                ((Tile) holder.itemView).bind(shown.get(position));
            }
        }
    }

    private static class Tile extends FrameLayout {
        private final BackupImageView image;
        private final TextView name;
        private final TextView badge;
        private PlusDeletedMedia.Item bound;

        Tile(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1));

            FrameLayout inner = new FrameLayout(context);
            inner.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            name = new TextView(context);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(3);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
            inner.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            image = new BackupImageView(context);
            image.getImageReceiver().setAllowStartAnimation(false);
            inner.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            badge = new TextView(context);
            badge.setTextColor(0xffffffff);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            badge.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), 0x99000000));
            badge.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(1), AndroidUtilities.dp(4), AndroidUtilities.dp(1));
            inner.addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 4, 4));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY));
        }

        void bind(PlusDeletedMedia.Item item) {
            bound = item;
            image.setImageDrawable(null);
            String preview = item.previewPath();
            if (preview != null) {
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
            String badgeText = null;
            TLRPC.Document doc = item.document();
            if (item.category == PlusDeletedMedia.CAT_VIDEO && doc != null) {
                if (MessageObject.isGifDocument(doc)) {
                    badgeText = "GIF";
                } else {
                    int duration = (int) Math.round(MessageObject.getDocumentDuration(doc));
                    badgeText = AndroidUtilities.formatShortDuration(duration);
                }
            } else if (item.category == PlusDeletedMedia.CAT_FILE) {
                badgeText = AndroidUtilities.formatFileSize(item.file.length());
            }
            if (item.origin == PlusDeletedMedia.ORIGIN_VIEW_ONCE) {
                badgeText = badgeText == null ? "1×" : "1× · " + badgeText;
            }
            badge.setText(badgeText);
            badge.setVisibility(badgeText != null ? View.VISIBLE : View.GONE);
        }
    }
}
