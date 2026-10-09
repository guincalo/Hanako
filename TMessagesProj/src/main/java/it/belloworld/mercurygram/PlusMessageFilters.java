package it.belloworld.mercurygram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import it.belloworld.mercurygram.ui.PlusMessageFiltersActivity;

/**
 * plus f04: client-side message filters.
 *
 * - Regex filters (global or per chat), each with case-insensitive / enabled / "hide" vs "collapse".
 * - "Shadow-ban": hide every message from chosen senders, per chat or everywhere.
 * - Optionally hide messages from users you blocked (groups only).
 *
 * Purely local and display-only: nothing is sent to the server, messages stay in the
 * database and are still marked read by the normal max-id logic. Hidden rows are rendered
 * as a 1px placeholder (same trick as AyuGram/Nagram's DummyView), collapsed rows as a
 * small tappable service-style pill that reveals the message for this session.
 *
 * Design follows AyuGram Desktop's filters (ayu/ui/settings/filters, GPL-3.0) and
 * Nagram X Fixed's AyuFilter (tw/nekomimi/nekogram/filters, GPL-3.0); the code is a
 * fresh, much smaller implementation for this tree.
 */
public final class PlusMessageFilters {

    /** RecyclerView view types used by ChatActivity's adapter; must not collide with Telegram's (0..10, -1). */
    public static final int VIEW_TYPE_HIDDEN = -4040;
    public static final int VIEW_TYPE_COLLAPSED = -4041;
    /** Message context-menu option id (ChatActivity.OPTION_*). */
    public static final int OPTION_MESSAGE_FILTERS = 4040;

    public static final int RESULT_NONE = 0;
    public static final int RESULT_COLLAPSE = 1;
    public static final int RESULT_HIDE = 2;

    private static final String PREFS = "plus_f04_filters";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HIDE_BLOCKED = "hideBlocked";
    private static final String KEY_USERS_COLLAPSE = "usersCollapse";
    private static final String KEY_FILTERS = "filters";
    private static final String KEY_BANNED = "banned";

    /** Longest text a regex is run against; bounds the cost of a filter on the UI thread. */
    private static final int MAX_MATCH_LENGTH = 8192;

    public static final class Filter {
        public long id;
        public String pattern = "";
        /** 0 = all chats, otherwise the dialog id it applies to. */
        public long dialogId;
        public boolean enabled = true;
        public boolean caseInsensitive = true;
        /** true = collapse into a tappable placeholder, false = hide completely. */
        public boolean collapse;

        private Pattern compiled;
        private boolean compileFailed;

        Pattern pattern() {
            if (compiled == null && !compileFailed) {
                try {
                    compiled = Pattern.compile(pattern, caseInsensitive ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0);
                } catch (PatternSyntaxException e) {
                    compileFailed = true;
                }
            }
            return compiled;
        }

        public Filter copy() {
            Filter f = new Filter();
            f.id = id;
            f.pattern = pattern;
            f.dialogId = dialogId;
            f.enabled = enabled;
            f.caseInsensitive = caseInsensitive;
            f.collapse = collapse;
            return f;
        }
    }

    public static final class BannedUser {
        public long userId;
        /** 0 = everywhere, otherwise the dialog id it applies to. */
        public long dialogId;
    }

    private static boolean loaded;
    private static boolean enabled = true;
    private static boolean hideBlocked = false;
    private static boolean usersCollapse = false;
    private static final ArrayList<Filter> filters = new ArrayList<>();
    private static final ArrayList<BannedUser> banned = new ArrayList<>();
    private static final HashSet<String> revealed = new HashSet<>();
    private static int version = 1;
    private static final WeakHashMap<MessageObject, int[]> cache = new WeakHashMap<>();

    private PlusMessageFilters() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        SharedPreferences p = prefs();
        enabled = p.getBoolean(KEY_ENABLED, true);
        hideBlocked = p.getBoolean(KEY_HIDE_BLOCKED, false);
        usersCollapse = p.getBoolean(KEY_USERS_COLLAPSE, false);
        filters.clear();
        banned.clear();
        try {
            JSONArray arr = new JSONArray(p.getString(KEY_FILTERS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Filter f = new Filter();
                f.id = o.optLong("id", System.nanoTime() + i);
                f.pattern = o.optString("p", "");
                f.dialogId = o.optLong("d", 0);
                f.enabled = o.optBoolean("e", true);
                f.caseInsensitive = o.optBoolean("i", true);
                f.collapse = o.optBoolean("c", false);
                if (!TextUtils.isEmpty(f.pattern)) {
                    filters.add(f);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            JSONArray arr = new JSONArray(p.getString(KEY_BANNED, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                BannedUser b = new BannedUser();
                b.userId = o.optLong("u", 0);
                b.dialogId = o.optLong("d", 0);
                if (b.userId != 0) {
                    banned.add(b);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void save() {
        try {
            JSONArray fa = new JSONArray();
            for (Filter f : filters) {
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("p", f.pattern);
                o.put("d", f.dialogId);
                o.put("e", f.enabled);
                o.put("i", f.caseInsensitive);
                o.put("c", f.collapse);
                fa.put(o);
            }
            JSONArray ba = new JSONArray();
            for (BannedUser b : banned) {
                JSONObject o = new JSONObject();
                o.put("u", b.userId);
                o.put("d", b.dialogId);
                ba.put(o);
            }
            prefs().edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .putBoolean(KEY_HIDE_BLOCKED, hideBlocked)
                    .putBoolean(KEY_USERS_COLLAPSE, usersCollapse)
                    .putString(KEY_FILTERS, fa.toString())
                    .putString(KEY_BANNED, ba.toString())
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
        invalidate();
    }

    private static void invalidate() {
        version++;
        cache.clear();
    }

    // ---- settings accessors ----

    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    public static void setEnabled(boolean value) {
        ensureLoaded();
        enabled = value;
        save();
    }

    public static boolean isHideBlocked() {
        ensureLoaded();
        return hideBlocked;
    }

    public static void setHideBlocked(boolean value) {
        ensureLoaded();
        hideBlocked = value;
        save();
    }

    public static boolean isUsersCollapse() {
        ensureLoaded();
        return usersCollapse;
    }

    public static void setUsersCollapse(boolean value) {
        ensureLoaded();
        usersCollapse = value;
        save();
    }

    /** Copies of the stored filters; edit and pass back to {@link #putFilter}. */
    public static ArrayList<Filter> getFilters() {
        ensureLoaded();
        ArrayList<Filter> out = new ArrayList<>(filters.size());
        for (Filter f : filters) {
            out.add(f.copy());
        }
        return out;
    }

    /** Inserts or replaces (by id) a filter. */
    public static void putFilter(Filter filter) {
        HanakoTelemetry.count(HanakoTelemetry.MESSAGE_FILTER_ADD); // hanako: usage statistics (off by default)
        ensureLoaded();
        if (filter == null || TextUtils.isEmpty(filter.pattern)) {
            return;
        }
        Filter stored = filter.copy();
        if (stored.id == 0) {
            stored.id = System.currentTimeMillis() * 1000 + filters.size();
        }
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i).id == stored.id) {
                filters.set(i, stored);
                save();
                return;
            }
        }
        filters.add(stored);
        save();
    }

    public static void removeFilter(long id) {
        ensureLoaded();
        for (int i = filters.size() - 1; i >= 0; i--) {
            if (filters.get(i).id == id) {
                filters.remove(i);
            }
        }
        save();
    }

    /** @return null when valid, otherwise the error description. */
    public static String validatePattern(String pattern) {
        if (TextUtils.isEmpty(pattern)) {
            return LocaleController.getString(R.string.PlusF04PatternEmpty);
        }
        try {
            Pattern.compile(pattern);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    public static ArrayList<BannedUser> getBanned() {
        ensureLoaded();
        ArrayList<BannedUser> out = new ArrayList<>(banned.size());
        for (BannedUser b : banned) {
            BannedUser c = new BannedUser();
            c.userId = b.userId;
            c.dialogId = b.dialogId;
            out.add(c);
        }
        return out;
    }

    public static void addBanned(long userId, long dialogId) {
        HanakoTelemetry.count(HanakoTelemetry.USER_HIDE); // hanako: usage statistics (off by default)
        ensureLoaded();
        if (userId == 0) {
            return;
        }
        for (BannedUser b : banned) {
            if (b.userId == userId && b.dialogId == dialogId) {
                return;
            }
        }
        BannedUser b = new BannedUser();
        b.userId = userId;
        b.dialogId = dialogId;
        banned.add(b);
        save();
    }

    public static void removeBanned(long userId, long dialogId) {
        ensureLoaded();
        for (int i = banned.size() - 1; i >= 0; i--) {
            BannedUser b = banned.get(i);
            if (b.userId == userId && b.dialogId == dialogId) {
                banned.remove(i);
            }
        }
        save();
    }

    /** Removes every ban (global and per chat) that would hide userId in dialogId. */
    public static void unbanEverywhereIn(long userId, long dialogId) {
        ensureLoaded();
        for (int i = banned.size() - 1; i >= 0; i--) {
            BannedUser b = banned.get(i);
            if (b.userId == userId && (b.dialogId == 0 || b.dialogId == dialogId)) {
                banned.remove(i);
            }
        }
        save();
    }

    public static boolean isBanned(long userId, long dialogId) {
        ensureLoaded();
        for (int i = 0, n = banned.size(); i < n; i++) {
            BannedUser b = banned.get(i);
            if (b.userId == userId && (b.dialogId == 0 || b.dialogId == dialogId)) {
                return true;
            }
        }
        return false;
    }

    // ---- matching ----

    private static boolean isFilterable(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) {
            return false;
        }
        if (msg.getId() == 0 || msg.isOut() || msg.isSponsored() || msg.type == MessageObject.TYPE_DATE) {
            return false;
        }
        return !(msg.messageOwner instanceof TLRPC.TL_messageService);
    }

    private static String revealKey(MessageObject primary) {
        return primary.getDialogId() + "_" + primary.getId();
    }

    private static String collectText(MessageObject primary, MessageObject.GroupedMessages group) {
        if (group == null || group.messages == null || group.messages.size() <= 1) {
            String t = primary.messageOwner.message;
            return t == null ? "" : t;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < group.messages.size(); i++) {
            MessageObject m = group.messages.get(i);
            if (m == null || m.messageOwner == null || TextUtils.isEmpty(m.messageOwner.message)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.messageOwner.message);
        }
        return sb.toString();
    }

    /** Decides what to do with a message (or an album, judged by its primary message). */
    public static int decide(MessageObject primary, MessageObject.GroupedMessages group) {
        ensureLoaded();
        if (!enabled || !isFilterable(primary)) {
            return RESULT_NONE;
        }
        if (filters.isEmpty() && banned.isEmpty() && !hideBlocked) {
            return RESULT_NONE;
        }
        String text = collectText(primary, group);
        int textHash = text.hashCode();
        int[] cached = cache.get(primary);
        if (cached != null && cached[0] == version && cached[1] == textHash) {
            return cached[2];
        }
        int result = RESULT_NONE;
        if (!revealed.contains(revealKey(primary))) {
            result = compute(primary, text);
        }
        cache.put(primary, new int[]{version, textHash, result});
        return result;
    }

    private static int compute(MessageObject primary, String text) {
        long dialogId = primary.getDialogId();
        long senderId = primary.getSenderId();
        int userResult = usersCollapse ? RESULT_COLLAPSE : RESULT_HIDE;
        if (senderId != 0 && senderId != dialogId && isBanned(senderId, dialogId)) {
            return userResult;
        }
        if (hideBlocked && senderId > 0 && dialogId < 0) {
            MessagesController mc = MessagesController.getInstance(primary.currentAccount);
            if (mc.blockePeers.indexOfKey(senderId) >= 0) {
                return userResult;
            }
        }
        if (TextUtils.isEmpty(text) || filters.isEmpty()) {
            return RESULT_NONE;
        }
        CharSequence input = text.length() > MAX_MATCH_LENGTH ? text.substring(0, MAX_MATCH_LENGTH) : text;
        int result = RESULT_NONE;
        for (int i = 0, n = filters.size(); i < n; i++) {
            Filter f = filters.get(i);
            if (!f.enabled || (f.dialogId != 0 && f.dialogId != dialogId)) {
                continue;
            }
            Pattern p = f.pattern();
            if (p == null) {
                continue;
            }
            try {
                if (p.matcher(input).find()) {
                    if (!f.collapse) {
                        return RESULT_HIDE;
                    }
                    result = RESULT_COLLAPSE;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return result;
    }

    private static MessageObject primaryOf(MessageObject msg, MessageObject.GroupedMessages group) {
        MessageObject primary = group != null ? group.findPrimaryMessageObject() : null;
        return primary != null ? primary : msg;
    }

    /**
     * ChatActivity adapter hook: the view type to use instead of the message's own, or 0.
     * Albums are judged as a whole; when collapsed only the primary row shows the pill.
     */
    public static int getViewType(MessageObject msg, MessageObject.GroupedMessages group) {
        if (msg == null) {
            return 0;
        }
        MessageObject primary = primaryOf(msg, group);
        int r = decide(primary, group);
        if (r == RESULT_HIDE) {
            return VIEW_TYPE_HIDDEN;
        } else if (r == RESULT_COLLAPSE) {
            return group == null || primary == msg ? VIEW_TYPE_COLLAPSED : VIEW_TYPE_HIDDEN;
        }
        return 0;
    }

    public static void reveal(MessageObject primary) {
        if (primary == null) {
            return;
        }
        revealed.add(revealKey(primary));
        invalidate();
    }

    // ---- views ----

    public static boolean isOwnViewType(int viewType) {
        return viewType == VIEW_TYPE_HIDDEN || viewType == VIEW_TYPE_COLLAPSED;
    }

    /** ChatActivity adapter hook: creates the row view for one of our view types. */
    public static View createView(Context context, int viewType, Theme.ResourcesProvider resourcesProvider) {
        if (viewType == VIEW_TYPE_COLLAPSED) {
            return new CollapsedView(context, resourcesProvider);
        }
        View v = new View(context);
        // 1px rather than 0 so findFirstVisibleItemPosition() & co. still see the row.
        v.setMinimumHeight(1);
        return v;
    }

    /** ChatActivity adapter hook: binds a collapsed row; no-op for any other view. */
    public static void bindView(View view, MessageObject message, Runnable onReveal) {
        if (view instanceof CollapsedView) {
            ((CollapsedView) view).bind(message, onReveal);
        }
    }

    public static final class CollapsedView extends FrameLayout {
        private final TextView textView;
        private final Theme.ResourcesProvider resourcesProvider;
        private MessageObject message;
        private Runnable onReveal;

        public CollapsedView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            textView.setGravity(Gravity.CENTER);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 4, 24, 4));
            textView.setOnClickListener(v -> {
                if (message != null) {
                    reveal(message);
                    if (onReveal != null) {
                        onReveal.run();
                    }
                }
            });
            updateColors();
        }

        private void updateColors() {
            textView.setTextColor(Theme.getColor(Theme.key_chat_serviceText, resourcesProvider));
            textView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider)));
        }

        public void bind(MessageObject message, Runnable onReveal) {
            this.message = message;
            this.onReveal = onReveal;
            updateColors();
            String name = null;
            if (message != null) {
                long senderId = message.getSenderId();
                if (senderId != 0) {
                    name = DialogObject.getName(message.currentAccount, senderId);
                }
            }
            if (TextUtils.isEmpty(name)) {
                textView.setText(LocaleController.getString(R.string.PlusF04CollapsedNoName));
            } else {
                textView.setText(LocaleController.formatString(R.string.PlusF04Collapsed, name));
            }
        }

        public MessageObject getMessageObject() {
            return message;
        }
    }

    // ---- chat context menu ----

    /** Whether the "Message filters" item belongs in the long-press menu of this message. */
    public static boolean canShowMenu(MessageObject msg, int chatMode) {
        return chatMode == 0 && isFilterable(msg) && msg.getId() > 0;
    }

    /** Opens the per-message actions: shadow-ban sender here / everywhere, add a chat filter, open settings. */
    public static void showChatMenu(BaseFragment fragment, MessageObject msg, long dialogId, Runnable onChanged) {
        if (fragment == null || msg == null) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final long senderId = msg.getSenderId();
        final boolean canBan = senderId != 0 && senderId != dialogId && dialogId < 0;
        final String name = senderId != 0 ? DialogObject.getName(msg.currentAccount, senderId) : "";
        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayList<Integer> actions = new ArrayList<>();
        if (canBan) {
            if (isBanned(senderId, dialogId)) {
                labels.add(LocaleController.formatString(R.string.PlusF04UnhideUser, name));
                actions.add(0);
            } else {
                labels.add(LocaleController.formatString(R.string.PlusF04HideUserHere, name));
                actions.add(1);
                labels.add(LocaleController.formatString(R.string.PlusF04HideUserEverywhere, name));
                actions.add(2);
            }
        }
        labels.add(LocaleController.getString(R.string.PlusF04AddChatFilter));
        actions.add(3);
        labels.add(LocaleController.getString(R.string.PlusF04Settings));
        actions.add(4);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.PlusF04Title));
        builder.setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
            if (which < 0 || which >= actions.size()) {
                return;
            }
            switch (actions.get(which)) {
                case 0:
                    unbanEverywhereIn(senderId, dialogId);
                    break;
                case 1:
                    addBanned(senderId, dialogId);
                    break;
                case 2:
                    addBanned(senderId, 0);
                    break;
                case 3:
                    PlusMessageFiltersActivity.showEditDialog(fragment, null, dialogId, onChanged);
                    return;
                case 4:
                    fragment.presentFragment(new PlusMessageFiltersActivity());
                    return;
            }
            if (onChanged != null) {
                onChanged.run();
            }
        });
        fragment.showDialog(builder.create());
    }
}
