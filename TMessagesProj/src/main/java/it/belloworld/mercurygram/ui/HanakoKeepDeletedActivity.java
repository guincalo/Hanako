package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

import it.belloworld.mercurygram.MgDeletedRetention;

/**
 * hanako: per-chat exceptions to how long saved deleted / edited messages are
 * kept, for one chat type. Same shape as Telegram's Storage usage → Keep media
 * → exceptions: "Add an exception" opens the chat picker filtered to the type,
 * each chat shows its period, tapping one opens the period menu.
 */
public class HanakoKeepDeletedActivity extends UniversalFragment {

    private static final int ID_ADD = 1;
    private static final int ID_DELETE_ALL = 2;
    private static final int ID_CHAT_BASE = 1000;

    private final int type;
    private final ArrayList<Long> shown = new ArrayList<>();

    public HanakoKeepDeletedActivity(int type) {
        this.type = type;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.NotificationsExceptions);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final int acc = getCurrentAccount();
        shown.clear();
        for (long did : MgDeletedRetention.exceptionsOf(acc, type)) {
            if (!it.belloworld.mercurygram.PlusChatLock.isHiddenFromLists(acc, did)) { // plus f08: hidden locked chats stay out of lists
                shown.add(did);
            }
        }
        items.add(UItem.asHeader(MgDeletedRetention.typeLabel(type)));
        UItem add = UItem.asButton(ID_ADD, R.drawable.msg_add, LocaleController.getString(R.string.AddAnException));
        add.accent = true;
        items.add(add);
        for (int i = 0; i < shown.size(); i++) {
            long did = shown.get(i);
            items.add(ChatRow.of(ID_CHAT_BASE + i, acc, did, statusFor(MgDeletedRetention.getException(acc, did))));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoKeepDeletedExceptionsInfo)));
        if (!shown.isEmpty()) {
            UItem del = UItem.asButton(ID_DELETE_ALL, LocaleController.getString(R.string.NotificationsDeleteAllException));
            del.red = true;
            items.add(del);
            items.add(UItem.asShadow(null));
        }
    }

    private static CharSequence statusFor(int period) {
        return LocaleController.formatString(R.string.HanakoKeepDeletedFor, MgDeletedRetention.periodLabel(period));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD) {
            openPicker();
        } else if (item.id == ID_DELETE_ALL) {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.NotificationsDeleteAllExceptionTitle))
                    .setMessage(LocaleController.getString(R.string.NotificationsDeleteAllExceptionAlert))
                    .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                        MgDeletedRetention.clearExceptions(getCurrentAccount(), type);
                        refresh();
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create();
            showDialog(dialog);
            dialog.redPositive();
        } else {
            int idx = item.id - ID_CHAT_BASE;
            if (idx >= 0 && idx < shown.size()) {
                showPeriodMenu(this, view, getCurrentAccount(), shown.get(idx), true, this::refresh);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void openPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putInt("dialogsType", type == MgDeletedRetention.TYPE_GROUP ? DialogsActivity.DIALOGS_TYPE_GROUPS_ONLY
                : type == MgDeletedRetention.TYPE_CHANNEL ? DialogsActivity.DIALOGS_TYPE_CHANNELS_ONLY
                : DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        args.putBoolean("allowGlobalSearch", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            fragment.finishFragment();
            final int acc = getCurrentAccount();
            long last = 0;
            for (MessagesStorage.TopicKey key : dids) {
                long did = key.dialogId;
                if (DialogObject.isEncryptedDialog(did)) {
                    continue; // secret chats are never saved
                }
                if (MgDeletedRetention.getException(acc, did) == MgDeletedRetention.KEEP_DEFAULT) {
                    // Most exceptions are "stop saving this noisy chat" or "keep this one forever".
                    int typePeriod = MgDeletedRetention.getTypePeriod(acc, type);
                    MgDeletedRetention.setException(acc, did, typePeriod == MgDeletedRetention.KEEP_OFF
                            ? MgDeletedRetention.KEEP_FOREVER : MgDeletedRetention.KEEP_OFF);
                }
                last = did;
            }
            refresh();
            final long focus = last;
            if (focus != 0) {
                AndroidUtilities.runOnUIThread(() -> {
                    int idx = shown.indexOf(focus);
                    View row = idx >= 0 && listView != null ? listView.findViewByItemId(ID_CHAT_BASE + idx) : null;
                    if (row != null) {
                        showPeriodMenu(this, row, acc, focus, true, this::refresh);
                    }
                }, 350);
            }
            return true;
        });
        presentFragment(picker);
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * Period menu for one chat. With {@code exceptionList} the last item removes
     * the exception; otherwise (chat menu) the first item is "Default (…)".
     */
    public static void showPeriodMenu(BaseFragment f, View anchor, int acc, long did, boolean exceptionList, Runnable onChanged) {
        try {
            int current = MgDeletedRetention.getException(acc, did);
            ItemOptions io = ItemOptions.makeOptions(f, anchor);
            if (!exceptionList) {
                int typePeriod = MgDeletedRetention.getTypePeriod(acc, MgDeletedRetention.typeOf(acc, did));
                io.addChecked(current == MgDeletedRetention.KEEP_DEFAULT,
                        LocaleController.formatString(R.string.HanakoKeepDeletedDefaultValue, MgDeletedRetention.periodLabel(typePeriod)),
                        () -> set(acc, did, MgDeletedRetention.KEEP_DEFAULT, onChanged));
                io.addGap();
            }
            for (int p : MgDeletedRetention.PERIODS) {
                io.addChecked(current == p, MgDeletedRetention.periodLabel(p), () -> set(acc, did, p, onChanged));
            }
            io.addChecked(current == MgDeletedRetention.KEEP_OFF, MgDeletedRetention.periodLabel(MgDeletedRetention.KEEP_OFF),
                    () -> set(acc, did, MgDeletedRetention.KEEP_OFF, onChanged));
            if (exceptionList) {
                io.addGap();
                io.add(R.drawable.msg_delete, LocaleController.getString(R.string.HanakoKeepDeletedRemoveException), true,
                        () -> set(acc, did, MgDeletedRetention.KEEP_DEFAULT, onChanged));
            }
            io.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void set(int acc, long did, int period, Runnable onChanged) {
        MgDeletedRetention.setException(acc, did, period);
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /**
     * Period menu for a chat type (settings page): the periods, then the
     * type's exceptions list, like Telegram's Keep media popup.
     */
    public static void showTypeMenu(BaseFragment f, View anchor, int acc, int type, Runnable onChanged) {
        try {
            int current = MgDeletedRetention.getTypePeriod(acc, type);
            ItemOptions io = ItemOptions.makeOptions(f, anchor);
            for (int p : MgDeletedRetention.PERIODS) {
                io.addChecked(current == p, MgDeletedRetention.periodLabel(p), () -> {
                    MgDeletedRetention.setTypePeriod(acc, type, p);
                    if (onChanged != null) onChanged.run();
                });
            }
            io.addChecked(current == MgDeletedRetention.KEEP_OFF, MgDeletedRetention.periodLabel(MgDeletedRetention.KEEP_OFF), () -> {
                MgDeletedRetention.setTypePeriod(acc, type, MgDeletedRetention.KEEP_OFF);
                if (onChanged != null) onChanged.run();
            });
            io.addGap();
            int n = MgDeletedRetention.exceptionsOf(acc, type).size();
            io.add(R.drawable.msg_customize, n == 0
                    ? LocaleController.getString(R.string.AddAnException)
                    : LocaleController.formatPluralString("ExceptionShort", n), () -> f.presentFragment(new HanakoKeepDeletedActivity(type)));
            io.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Chat ⋮ menu id of "Deleted messages" (see ChatActivity). */
    public static final int CHAT_MENU_ID = 2110;

    public static boolean showChatMenuItem(int acc, long did, int chatMode) {
        return chatMode == 0 && did != 0 && !DialogObject.isEncryptedDialog(did)
                && org.telegram.messenger.UserConfig.getInstance(acc).mg.savedMessagesHistory;
    }

    /**
     * Chat ⋮ → Deleted messages: how long this chat keeps saved copies
     * (Default follows the chat type), its deleted media, and clearing what
     * this chat has saved.
     */
    public static void showChatMenu(org.telegram.ui.ChatActivity chat, View anchor, int acc, long did) {
        try {
            int current = MgDeletedRetention.getException(acc, did);
            int typePeriod = MgDeletedRetention.getTypePeriod(acc, MgDeletedRetention.typeOf(acc, did));
            ItemOptions io = ItemOptions.makeOptions(chat, anchor);
            io.addText(LocaleController.getString(R.string.HanakoKeepDeletedChatTitle), 13);
            io.addChecked(current == MgDeletedRetention.KEEP_DEFAULT,
                    LocaleController.formatString(R.string.HanakoKeepDeletedDefaultValue, MgDeletedRetention.periodLabel(typePeriod)),
                    () -> MgDeletedRetention.setException(acc, did, MgDeletedRetention.KEEP_DEFAULT));
            for (int p : MgDeletedRetention.PERIODS) {
                io.addChecked(current == p, MgDeletedRetention.periodLabel(p), () -> MgDeletedRetention.setException(acc, did, p));
            }
            io.addChecked(current == MgDeletedRetention.KEEP_OFF, MgDeletedRetention.periodLabel(MgDeletedRetention.KEEP_OFF),
                    () -> MgDeletedRetention.setException(acc, did, MgDeletedRetention.KEEP_OFF));
            io.addGap();
            io.add(R.drawable.msg_media, LocaleController.getString(R.string.PlusF20Title),
                    () -> chat.presentFragment(new PlusDeletedMediaActivity(did)));
            io.add(R.drawable.msg_clear, LocaleController.getString(R.string.HanakoDeletedClearChat), true,
                    () -> confirmClearChat(chat, acc, did));
            io.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void confirmClearChat(org.telegram.ui.ChatActivity chat, int acc, long did) {
        if (chat.getParentActivity() == null) {
            return;
        }
        it.belloworld.mercurygram.MgMessageHistory.getInstance().countsAsync(acc, did, c -> {
            if (chat.getParentActivity() == null) {
                return;
            }
            String body = c[0] == 0 && c[1] == 0
                    ? LocaleController.getString(R.string.HanakoDeletedClearChatNothing)
                    : LocaleController.formatString(R.string.HanakoDeletedClearChatConfirm,
                    LocaleController.formatPluralString("HanakoDeletedCount", c[0]),
                    LocaleController.formatPluralString("HanakoEditedCount", c[1]));
            AlertDialog.Builder b = new AlertDialog.Builder(chat.getParentActivity())
                    .setTitle(LocaleController.getString(R.string.HanakoDeletedClearChat))
                    .setMessage(body);
            if (c[0] == 0 && c[1] == 0) {
                b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                chat.showDialog(b.create());
                return;
            }
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            b.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> chat.mgGhost.clearChat());
            AlertDialog dialog = b.create();
            chat.showDialog(dialog);
            dialog.redPositive();
        });
    }

    // ---- rows ----

    /** Chat row: avatar, title, "Keep for: …" under it (Telegram's exception row). */
    public static class ChatRow extends UItem.UItemFactory<UserCell> {
        static {
            setup(new ChatRow());
        }

        public static UItem of(int id, int account, long dialogId, CharSequence status) {
            UItem item = UItem.ofFactory(ChatRow.class);
            item.id = id;
            item.intValue = account;
            item.dialogId = dialogId;
            item.subtext = status;
            return item;
        }

        @Override
        public UserCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            UserCell cell = new UserCell(context, 4, 0, false, false, resourcesProvider);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            return cell;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            UserCell cell = (UserCell) view;
            int acc = item.intValue;
            MessagesController mc = MessagesController.getInstance(acc);
            TLObject peer = item.dialogId > 0 ? mc.getUser(item.dialogId) : mc.getChat(-item.dialogId);
            CharSequence name = peer == null ? it.belloworld.mercurygram.PlusGhostExceptions.dialogTitle(acc, item.dialogId) : null;
            cell.setData(peer, name, item.subtext, 0, divider);
        }
    }

    /** Chat type row with Telegram's colorful icon (Storage usage style). */
    public static class TypeRow extends UItem.UItemFactory<TextCell> {
        static {
            setup(new TypeRow());
        }

        public static UItem of(int id, int type, CharSequence value) {
            UItem item = UItem.ofFactory(TypeRow.class);
            item.id = id;
            item.intValue = type;
            item.text = MgDeletedRetention.typeLabel(type);
            item.textValue = value;
            return item;
        }

        @Override
        public TextCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            TextCell cell = new TextCell(context, resourcesProvider);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            return cell;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            TextCell cell = (TextCell) view;
            String text = item.text == null ? "" : item.text.toString();
            switch (item.intValue) {
                case MgDeletedRetention.TYPE_PRIVATE:
                    cell.setTextAndValueAndColorfulIcon(text, item.textValue, false, R.drawable.msg_filled_menu_users, 0xFF4F85F6, 0xFF3568E8, divider);
                    break;
                case MgDeletedRetention.TYPE_GROUP:
                    cell.setTextAndValueAndColorfulIcon(text, item.textValue, false, R.drawable.msg_filled_menu_groups, 0xFF55CA47, 0xFF27B434, divider);
                    break;
                default:
                    cell.setTextAndValueAndColorfulIcon(text, item.textValue, false, R.drawable.msg_filled_menu_channels, 0xFFF09F1B, 0xFFE18A11, divider);
                    break;
            }
        }
    }
}
