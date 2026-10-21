package it.belloworld.mercurygram.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.LinkedHashSet;

import it.belloworld.mercurygram.MgDeletedRetention;
import it.belloworld.mercurygram.PlusDeletedMedia;

/**
 * hanako 2.02: one keep rule for deleted and edited messages: which chat, which person, and what
 * is kept (text, media types). Opened from a message ("Keep deleted…", pre-filled with its sender
 * and chat), from Settings → Deleted messages → Rules, and from the deleted-media screen of a chat.
 * Changes save at once; "Remove rule" goes back to the defaults. See docs/deleted-media-ux.md.
 */
public class HanakoKeepRuleActivity extends UniversalFragment {

    public static final int OPTION_KEEP_RULE = 4050;

    private static final int ID_CHAT = 1;
    private static final int ID_USER = 2;
    private static final int ID_REMOVE = 3;
    private static final int ID_BIT = 100; // + index into BITS
    private static final int[] BITS = {MgDeletedRetention.KEEP_TEXT, MgDeletedRetention.KEEP_PHOTO, MgDeletedRetention.KEEP_VIDEO,
            MgDeletedRetention.KEEP_STICKER, MgDeletedRetention.KEEP_VOICE, MgDeletedRetention.KEEP_FILE};
    private static final int[] BIT_NAMES = {R.string.HanakoRuleText, R.string.PlusF20FilterPhotos, R.string.PlusF20FilterVideos,
            R.string.PlusF20FilterStickers, R.string.HanakoRuleVoice, R.string.PlusF20FilterFiles};

    private long chat;
    private long user;

    public HanakoKeepRuleActivity(long chat, long user) {
        this.chat = chat;
        this.user = user;
    }

    /** Message long-press menu: shown wherever deleted messages are being saved. */
    public static boolean canShowMenu(int account, MessageObject message, int chatMode) {
        return message != null && message.messageOwner != null && chatMode == 0
                && org.telegram.messenger.UserConfig.getInstance(account).mg.savedMessagesHistory
                && !DialogObject.isEncryptedDialog(message.getDialogId()) && message.getId() > 0;
    }

    /** Pre-filled with the message's sender and chat (no person for channel posts and own messages). */
    public static HanakoKeepRuleActivity forMessage(int account, long dialogId, MessageObject message) {
        long from = MgDeletedRetention.senderOf(dialogId, message.messageOwner);
        if (from < 0 || message.isOut() || from == org.telegram.messenger.UserConfig.getInstance(account).getClientUserId()) {
            from = 0;
        }
        return new HanakoKeepRuleActivity(dialogId, from);
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HanakoRuleTitle);
    }

    private int keep() {
        MgDeletedRetention.Rule r = MgDeletedRetention.getRule(currentAccount, chat, user);
        return r != null ? r.keep : MgDeletedRetention.keepFor(currentAccount, chat, user);
    }

    public static String chatName(int account, long chat) {
        return chat == 0 ? LocaleController.getString(R.string.PlusF20AllChats) : PlusDeletedMedia.chatTitle(account, chat);
    }

    public static String userName(int account, long user) {
        if (user == 0) {
            return LocaleController.getString(R.string.HanakoRuleEveryone);
        }
        TLRPC.User u = MessagesController.getInstance(account).getUser(user);
        return u != null ? UserObject.getUserName(u) : "#" + user;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean exists = MgDeletedRetention.getRule(currentAccount, chat, user) != null;
        final int keep = keep();
        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoRuleFor)));
        items.add(UItem.asButton(ID_CHAT, R.drawable.msg_discussion, LocaleController.getString(R.string.HanakoRuleChat), chatName(currentAccount, chat)));
        if (!isChannel(chat)) {
            items.add(UItem.asButton(ID_USER, R.drawable.msg_contacts, LocaleController.getString(R.string.HanakoRulePerson), userName(currentAccount, user)));
        }
        items.add(UItem.asShadow(LocaleController.getString(exists ? R.string.HanakoRuleForInfo : R.string.HanakoRuleNewInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoRuleKeep)));
        for (int i = 0; i < BITS.length; i++) {
            items.add(UItem.asCheck(ID_BIT + i, LocaleController.getString(BIT_NAMES[i])).setChecked((keep & BITS[i]) != 0));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoRuleKeepInfo)));
        if (exists) {
            UItem remove = UItem.asButton(ID_REMOVE, R.drawable.msg_delete, LocaleController.getString(R.string.HanakoRuleRemove));
            remove.red = true;
            items.add(remove);
            items.add(UItem.asShadow(null));
        }
    }

    private boolean isChannel(long did) {
        if (!DialogObject.isChatDialog(did)) {
            return false;
        }
        return ChatObject.isChannelAndNotMegaGroup(getMessagesController().getChat(-did));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_CHAT) {
            pickChat();
        } else if (item.id == ID_USER) {
            presentFragment(new PickUser(chat, user, uid -> {
                user = uid;
                refresh();
            }));
        } else if (item.id == ID_REMOVE) {
            MgDeletedRetention.setRule(currentAccount, chat, user, -1);
            finishFragment();
        } else if (item.id >= ID_BIT && item.id < ID_BIT + BITS.length) {
            MgDeletedRetention.setRule(currentAccount, chat, user, keep() ^ BITS[item.id - ID_BIT]);
            refresh();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void pickChat() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowGlobalSearch", false);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (!dids.isEmpty()) {
                chat = dids.get(0).dialogId;
                if (isChannel(chat) || DialogObject.isUserDialog(chat) && user != 0 && user != chat
                        && user != getUserConfig().getClientUserId()) {
                    user = 0; // channel posts have no person; a private chat only has its two people
                }
                refresh();
            }
            fragment.finishFragment();
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
     * People to pick from: the members Telegram has loaded for the chat (both people of a private
     * chat), or for "All chats" your contacts and private chats. A search box filters by name.
     */
    public static class PickUser extends UniversalFragment {

        public interface Done {
            void run(long uid);
        }

        private static final int ID_EVERYONE = 1;
        private static final int ID_USER0 = 1000;

        private final long chat;
        private final long current;
        private final Done done;
        private final ArrayList<TLRPC.User> people = new ArrayList<>();
        private String query = "";

        public PickUser(long chat, long current, Done done) {
            this.chat = chat;
            this.current = current;
            this.done = done;
        }

        @Override
        public boolean onFragmentCreate() {
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            MessagesController mc = getMessagesController();
            if (current > 0) {
                ids.add(current);
            }
            if (DialogObject.isUserDialog(chat)) {
                ids.add(chat);
                ids.add(getUserConfig().getClientUserId());
            } else if (chat != 0) {
                TLRPC.ChatFull full = mc.getChatFull(-chat);
                if (full != null && full.participants != null) {
                    for (int i = 0; i < full.participants.participants.size(); i++) {
                        ids.add(full.participants.participants.get(i).user_id);
                    }
                }
                if (ids.isEmpty() && full == null) {
                    mc.loadFullChat(-chat, 0, true);
                }
            } else {
                ArrayList<TLRPC.TL_contact> contacts = ContactsController.getInstance(currentAccount).contacts;
                for (int i = 0; i < contacts.size(); i++) {
                    ids.add(contacts.get(i).user_id);
                }
                ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
                for (int i = 0; i < dialogs.size(); i++) {
                    if (DialogObject.isUserDialog(dialogs.get(i).id)) {
                        ids.add(dialogs.get(i).id);
                    }
                }
            }
            for (MgDeletedRetention.Rule r : MgDeletedRetention.rules(currentAccount)) {
                if (r.user > 0 && (r.chat == chat || chat == 0)) {
                    ids.add(r.user);
                }
            }
            for (long id : ids) {
                TLRPC.User u = mc.getUser(id);
                if (u != null && !u.deleted) {
                    people.add(u);
                }
            }
            return super.onFragmentCreate();
        }

        @Override
        public View createView(android.content.Context context) {
            View v = super.createView(context);
            ActionBarMenuItem search = actionBar.createMenu().addItem(0, R.drawable.outline_header_search).setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override
                        public void onTextChanged(android.widget.EditText editText) {
                            query = editText.getText().toString().trim().toLowerCase();
                            if (listView != null && listView.adapter != null) listView.adapter.update(true);
                        }

                        @Override
                        public void onSearchCollapse() {
                            query = "";
                            if (listView != null && listView.adapter != null) listView.adapter.update(true);
                        }
                    });
            search.setSearchFieldHint(LocaleController.getString(R.string.Search));
            return v;
        }

        @Override
        protected CharSequence getTitle() {
            return LocaleController.getString(R.string.HanakoRulePerson);
        }

        @Override
        protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
            if (TextUtils.isEmpty(query)) {
                items.add(UItem.asButton(ID_EVERYONE, R.drawable.msg_groups, LocaleController.getString(R.string.HanakoRuleEveryone)));
                items.add(UItem.asShadow(null));
            }
            for (int i = 0; i < people.size(); i++) {
                TLRPC.User u = people.get(i);
                String name = UserObject.getUserName(u);
                String handle = UserObject.getPublicUsername(u);
                if (!TextUtils.isEmpty(query) && !name.toLowerCase().contains(query)
                        && (handle == null || !handle.toLowerCase().contains(query))) {
                    continue;
                }
                items.add(UItem.asButton(ID_USER0 + i, name, handle != null ? "@" + handle : null));
            }
            if (people.isEmpty()) {
                items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoRuleNoPeople)));
            }
        }

        @Override
        protected void onClick(UItem item, View view, int position, float x, float y) {
            if (item.id == ID_EVERYONE) {
                done.run(0);
            } else if (item.id >= ID_USER0 && item.id - ID_USER0 < people.size()) {
                done.run(people.get(item.id - ID_USER0).id);
            } else {
                return;
            }
            finishFragment();
        }

        @Override
        protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
            return false;
        }
    }
}
