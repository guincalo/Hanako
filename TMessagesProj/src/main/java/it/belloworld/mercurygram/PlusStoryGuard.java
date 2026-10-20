package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Stories.StoriesController;

import java.util.ArrayList;

/**
 * plus f06: story ghost guard.
 *
 * 1. Before the story viewer opens, ask when the view would be visible to the author
 *    (ghost mode off for this account, or "Don't mark stories as seen" off, and Telegram's own
 *    stealth mode not active). Choices: open without being seen (story views are dropped for
 *    this viewer session only), open normally, or cancel. Mode "always" also asks when the
 *    view is hidden. Based on AyuGram's "suggestGhostModeBeforeViewingStory"
 *    (AyuGram Desktop settings_ayu.cpp; Android port in AyuGhostUtils.maybeSuggestGhostBeforeStory).
 *
 * 2. Before reacting to or replying to a story while views are hidden: the server marks the story
 *    seen anyway (AyuGram docs: server-side limitation), so confirm first. Hooked into
 *    PeerStoriesView.applyMessageToChat, the gate every story reply/reaction already passes.
 *
 * 3. Preloading never marks stories seen: StoriesController.markStoryAsRead (stories.readStories)
 *    and StoriesList.markAsRead (stories.incrementStoryViews) are only called from
 *    PeerStoriesView.checkSendView, which requires the page to be the active one, so preloaded
 *    neighbour pages and the preloaded media never send a view. With ghost stories on, both
 *    requests are also dropped at the network layer by PlusGhost.intercept.
 */
public final class PlusStoryGuard {

    public static final int OPEN_NEVER = 0;
    /** default: ask only when the view would be visible */
    public static final int OPEN_IF_VISIBLE = 1;
    public static final int OPEN_ALWAYS = 2;

    // settings row ids (MercurygramSettingsActivity, plus f06 block)
    public static final int ID_OPEN_MODE = 6060;
    public static final int ID_CONFIRM_INTERACT = 6061;

    /** account -> story views are dropped for the current viewer session ("Open without being seen"). */
    private static final boolean[] sessionHide = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static boolean bypassOpen;
    private static boolean bypassInteract;

    private PlusStoryGuard() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_f06", Context.MODE_PRIVATE);
    }

    public static int getOpenMode(int account) {
        int v = prefs().getInt("open_mode_" + account, OPEN_IF_VISIBLE);
        return v < OPEN_NEVER || v > OPEN_ALWAYS ? OPEN_IF_VISIBLE : v;
    }

    public static void setOpenMode(int account, int mode) {
        prefs().edit().putInt("open_mode_" + account, mode).apply();
    }

    public static boolean isConfirmInteract(int account) {
        return prefs().getBoolean("confirm_interact_" + account, true);
    }

    public static void setConfirmInteract(int account, boolean value) {
        prefs().edit().putBoolean("confirm_interact_" + account, value).apply();
    }

    // ---- view state ----

    private static boolean ghostHidesStories(int account) {
        return PlusGhost.isEnabled(account) && PlusGhost.isHidden(account, PlusGhost.OPT_STORIES);
    }

    private static boolean telegramStealthActive(int account) {
        try {
            TL_stories.TL_storiesStealthMode sm = MessagesController.getInstance(account).getStoriesController().getStealthMode();
            return sm != null && ConnectionsManager.getInstance(account).getCurrentTime() < sm.active_until_date;
        } catch (Exception e) {
            return false;
        }
    }

    /** true when opening a story now would not tell the author. */
    public static boolean viewsHidden(int account) {
        return PlusUtil.validAccount(account) && (ghostHidesStories(account) || sessionHide[account] || telegramStealthActive(account));
    }

    /**
     * Hook from PlusGhost.intercept, before its master-switch check: drop story view requests
     * while "Open without being seen" is in effect for this account.
     */
    public static boolean dropStoryView(int account, TLObject o) {
        return PlusUtil.validAccount(account) && sessionHide[account]
                && (o instanceof TL_stories.TL_stories_readStories || o instanceof TL_stories.TL_stories_incrementStoryViews);
    }

    // ---- open ----

    private static boolean hasUnseen(int account, TL_stories.StoryItem storyItem, ArrayList<Long> peerIds, int position, StoriesController.StoriesList storiesList) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        final StoriesController sc = MessagesController.getInstance(account).getStoriesController();
        if (storiesList != null) {
            // profile / archive / album list: views go through stories.incrementStoryViews; we can't
            // cheaply tell which ones were counted already, so treat it as unseen unless it's ours.
            return storiesList.dialogId != self;
        }
        if (storyItem != null && (peerIds == null || peerIds.size() <= 1)) {
            long did = storyItem.dialogId;
            if (did == 0 && peerIds != null && !peerIds.isEmpty()) {
                did = peerIds.get(0);
            }
            return did != self && did != 0 && storyItem.id > sc.getMaxStoriesReadId(did);
        }
        if (peerIds != null) {
            for (int i = Math.max(0, position); i < peerIds.size(); i++) {
                Long did = peerIds.get(i);
                if (did != null && did != self && sc.hasUnreadStories(did)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Hook at the start of StoryViewer.open. Returns true when a dialog was shown: the caller
     * must return and leave the opening to {@code proceed} / {@code cancel}.
     */
    public static boolean confirmOpen(Context context, int account, TL_stories.StoryItem storyItem, ArrayList<Long> peerIds, int position,
                                      StoriesController.StoriesList storiesList, Runnable proceed, Runnable cancel) {
        if (!PlusUtil.validAccount(account)) {
            return false;
        }
        if (bypassOpen) {
            return false; // re-entry from our own dialog; sessionHide already set by the choice
        }
        sessionHide[account] = false; // a new viewer session starts here
        if (context == null || proceed == null) {
            return false;
        }
        final int mode = getOpenMode(account);
        if (mode == OPEN_NEVER) {
            return false;
        }
        final boolean hidden;
        final boolean unseen;
        try {
            hidden = viewsHidden(account);
            unseen = hasUnseen(account, storyItem, peerIds, position, storiesList);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        if (!unseen) {
            return false;
        }
        if (hidden && mode != OPEN_ALWAYS) {
            return false;
        }
        final boolean[] handled = new boolean[1];
        AlertDialog dialog = new AlertDialog(context, 0);
        if (hidden) {
            dialog.setTitle(LocaleController.getString(R.string.PlusF06OpenHiddenTitle));
            dialog.setMessage(LocaleController.getString(R.string.PlusF06OpenHiddenMessage));
            dialog.setPositiveButton(LocaleController.getString(R.string.PlusF06Open), (d, w) -> {
                handled[0] = true;
                runOpen(account, false, proceed);
            });
        } else {
            dialog.setTitle(LocaleController.getString(R.string.PlusF06OpenVisibleTitle));
            dialog.setMessage(LocaleController.getString(R.string.PlusF06OpenVisibleMessage));
            dialog.setPositiveButton(LocaleController.getString(R.string.PlusF06OpenUnseen), (d, w) -> {
                handled[0] = true;
                runOpen(account, true, proceed);
            });
            dialog.setNeutralButton(LocaleController.getString(R.string.PlusF06OpenSeen), (d, w) -> {
                handled[0] = true;
                runOpen(account, false, proceed);
            });
        }
        dialog.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            handled[0] = true;
            if (cancel != null) {
                cancel.run();
            }
        });
        dialog.setOnDismissListener(d -> {
            if (!handled[0]) {
                handled[0] = true;
                if (cancel != null) {
                    cancel.run();
                }
            }
        });
        try {
            dialog.show();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        return true;
    }

    private static void runOpen(int account, boolean hide, Runnable proceed) {
        sessionHide[account] = hide;
        bypassOpen = true;
        try {
            proceed.run();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            bypassOpen = false;
        }
    }

    // ---- react / reply ----

    /**
     * Hook at the start of PeerStoriesView.applyMessageToChat (every story reply and reaction
     * passes there). Returns true when a dialog was shown; {@code proceed} re-runs the gate.
     *
     * @param telegramWillConfirm Telegram's own stealth-mode confirm is about to show the same warning
     */
    public static boolean confirmInteraction(Context context, int account, long dialogId, Theme.ResourcesProvider resourcesProvider,
                                             boolean telegramWillConfirm, Runnable proceed) {
        if (bypassInteract || context == null || proceed == null || !PlusUtil.validAccount(account) || telegramWillConfirm) {
            return false;
        }
        if (!isConfirmInteract(account) || dialogId == 0 || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        if (!viewsHidden(account)) {
            return false; // the view is visible already, nothing new is revealed
        }
        AlertDialog dialog = new AlertDialog(context, 0, resourcesProvider);
        dialog.setTitle(LocaleController.getString(R.string.PlusF06InteractTitle));
        dialog.setMessage(LocaleController.getString(R.string.PlusF06InteractMessage));
        dialog.setPositiveButton(LocaleController.getString(R.string.PlusF06InteractProceed), (d, w) -> {
            bypassInteract = true;
            try {
                proceed.run();
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                bypassInteract = false;
            }
        });
        dialog.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        try {
            dialog.show();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        return true;
    }

    // ---- settings (MercurygramSettingsActivity) ----

    private static String openModeLabel(int mode) {
        switch (mode) {
            case OPEN_NEVER:
                return LocaleController.getString(R.string.PlusF06ModeNever);
            case OPEN_ALWAYS:
                return LocaleController.getString(R.string.PlusF06ModeAlways);
            default:
                return LocaleController.getString(R.string.PlusF06ModeIfVisible);
        }
    }

    public static void addSettingsItems(ArrayList<UItem> items, int account) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF06Header)));
        items.add(UItem.asButton(ID_OPEN_MODE, LocaleController.getString(R.string.PlusF06OpenMode), openModeLabel(getOpenMode(account))));
        items.add(UItem.asCheck(ID_CONFIRM_INTERACT, LocaleController.getString(R.string.PlusF06ConfirmInteract)).setChecked(isConfirmInteract(account)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF06About)));
    }

    /** @return true when the click was ours; {@code refresh} is run after a change. */
    public static boolean onSettingsClick(Context context, int account, int id, Runnable refresh) {
        if (id == ID_CONFIRM_INTERACT) {
            setConfirmInteract(account, !isConfirmInteract(account));
            if (refresh != null) {
                refresh.run();
            }
            return true;
        }
        if (id == ID_OPEN_MODE) {
            if (context == null) {
                return true;
            }
            final int[] modes = {OPEN_IF_VISIBLE, OPEN_ALWAYS, OPEN_NEVER};
            CharSequence[] labels = new CharSequence[modes.length];
            for (int i = 0; i < modes.length; i++) {
                labels[i] = openModeLabel(modes[i]);
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(LocaleController.getString(R.string.PlusF06OpenMode));
            builder.setItems(labels, (d, which) -> {
                if (which >= 0 && which < modes.length) {
                    setOpenMode(account, modes[which]);
                    if (refresh != null) {
                        refresh.run();
                    }
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.show();
            return true;
        }
        return false;
    }
}
