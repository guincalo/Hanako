package it.belloworld.mercurygram.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

import it.belloworld.mercurygram.MgMessageHistory;
import it.belloworld.mercurygram.MgUpdateChecker;

/**
 * Hanako Classic: the Hanako settings rows, folded into Forkgram's settings screen
 * (org.telegram.ui.ForkSettingsActivity, shown as "Hanako"). This used to be its own
 * fragment (MercurygramSettingsActivity, a hub of sub-pages); now it only fills rows into
 * the host list and handles their clicks. The host offsets every row id by
 * {@link #ID_OFFSET} so they never collide with Forkgram's ids.
 */
public class HanakoSettingsSection {

    public static final int ID_OFFSET = 100000;

    // plus: ghost mode
    public static final int ID_GHOST_ON = 900;
    public static final int ID_GHOST_OPT = 910; // + PlusGhost.OPT_*
    public static final int ID_PLUS_GHOST_EXCEPTIONS = 9101; // plus f01
    public static final int ID_GHOST_SEND_SILENT = 950; // plus f09
    // plus f10: streamer mode
    public static final int ID_STREAMER_ON = 1000;
    public static final int ID_STREAMER_OPT = 1010; // + PlusStreamer.OPT_*
    // plus f13: message shot
    public static final int ID_MESSAGE_SHOT = 1300;
    // plus f14: profile ID / DC / registration date
    public static final int ID_F14_SHOW_DC = 1400;
    public static final int ID_F14_SHOW_REG_DATE = 1401;
    public static final int ID_SEND_PROMPT = 1600; // plus f16: + PlusSendPrompts.KIND_*
    // plus f17: network
    // plus f17 end
    // plus f19: transfer boost
    public static final int ID_PLUS_F19_DOWNLOAD = 1900;
    public static final int ID_PLUS_F19_UPLOAD = 1901;
    public static final int ID_MESSAGE_DETAILS_MENU = 1;
    public static final int ID_HIDE_CHAT_KEYBOARD = 2;
    public static final int ID_USE_SYSTEM_FONT = 4;
    public static final int ID_SAVED_MESSAGES_HISTORY = 5;
    public static final int ID_CLEAR_SAVED_HISTORY = 6;
    public static final int ID_HIDE_STORIES = 7;
    public static final int ID_DELETE_FOR_ALL_DEFAULT = 9;
    public static final int ID_DISABLE_LINK_PREVIEWS = 13;
    public static final int ID_PREFER_SECRET_CHATS = 14;
    public static final int ID_DISABLE_AUTO_UPDATE = 20;
    public static final int ID_ACCEPT_PRERELEASES = 21;
    public static final int ID_CHECK_FOR_UPDATES_NOW = 22;
    public static final int ID_REDUCE_TRACKING_FINGERPRINT = 40;
    public static final int ID_DISABLE_INSTANT_VIEW = 47;
    public static final int ID_EMOJI_PACK = 60;
    public static final int ID_STRIP_TRACKING_PARAMS = 61;
    public static final int ID_DISABLE_CLOUD_DRAFTS = 62;
    public static final int ID_CONFIRM_INTERNAL_LINKS = 63;
    public static final int ID_SHOW_CHAR_COUNTER = 64;
    public static final int ID_DISABLE_PROXIMITY_SENSOR = 65;
    // plus f07: vanished-chat log + deleted-message reactions (ids 700-799)
    public static final int ID_F07_LOG_DIALOGS = 700;
    public static final int ID_F07_OPEN_LOG = 701;
    public static final int ID_F07_KEEP_REACTIONS = 702;
    // plus f20: saved deleted-media browser (ids 2000-2099)
    public static final int ID_F20_OPEN = 2000;
    public static final int ID_F20_CHAT_MENU = 2001;
    public static final int ID_F20_INCLUDE_EDITS = 2002;

    // hanako: the old hub's page ids. Pages are gone (everything is one list in the merged
    // "Hanako" screen); a page id now only says which row to scroll to, see open().
    public static final int PAGE_HUB = 0;
    public static final int PAGE_GHOST = 1;
    public static final int PAGE_DELETED = 2;
    public static final int PAGE_ACTIVITY_LOG = 3;
    public static final int PAGE_STREAMER = 4;
    public static final int PAGE_CHATS = 5;
    public static final int PAGE_PRIVACY = 6;
    public static final int PAGE_UPDATES = 9;
    public static final int ID_HUB_CHAT_LOCK = 8820;
    public static final int ID_HUB_FILTERS = 8821;
    public static final int ID_HUB_BACKUP = 8822;
    public static final int ID_HUB_TELEMETRY = 8824;
    public static final int ID_HUB_API = 8825;

    private final BaseFragment host;
    private final Runnable refresh;
    /** "412 MB" for the deleted-media row, computed off the UI thread. */
    private String deletedMediaSize = "";
    private boolean sizeRequested;

    public HanakoSettingsSection(BaseFragment host, Runnable refresh) {
        this.host = host;
        this.refresh = refresh;
        it.belloworld.mercurygram.HanakoTelemetry.countPage(PAGE_HUB); // hanako: usage statistics (off by default)
    }

    /** The row (un-offset id) an old page id scrolls to, or 0 for the top of the screen. */
    public static int pageRow(int page) {
        switch (page) {
            case PAGE_GHOST:
                return ID_GHOST_ON;
            case PAGE_DELETED:
                return ID_SAVED_MESSAGES_HISTORY;
            case PAGE_ACTIVITY_LOG:
                return PlusActivityLogActivity.SETTINGS_ID_ENABLED;
            case PAGE_STREAMER:
                return ID_STREAMER_ON;
            case PAGE_CHATS:
                return ID_MESSAGE_DETAILS_MENU;
            case PAGE_PRIVACY:
                return ID_REDUCE_TRACKING_FINGERPRINT;
            case PAGE_UPDATES:
                return MgUpdateChecker.canSelfInstall() ? ID_DISABLE_AUTO_UPDATE : 0;
            default:
                return 0;
        }
    }

    /** The merged Hanako settings screen, scrolled to an old page's first row. */
    public static org.telegram.ui.ForkSettingsActivity screen(int page) {
        org.telegram.ui.ForkSettingsActivity f = new org.telegram.ui.ForkSettingsActivity();
        int row = pageRow(page);
        if (row != 0) {
            f.highlight(ID_OFFSET + row);
        }
        return f;
    }

    /** The merged Hanako settings screen, scrolled to one row (un-offset id). */
    public static org.telegram.ui.ForkSettingsActivity screenAt(int row) {
        return new org.telegram.ui.ForkSettingsActivity().highlight(ID_OFFSET + row);
    }

    /** Opens the merged Hanako settings screen at an old page. */
    public static void open(BaseFragment from, int page) {
        if (from != null) {
            from.presentFragment(screen(page));
        }
    }

    private UserConfig getUserConfig() {
        return host.getUserConfig();
    }

    private int getCurrentAccount() {
        return host.getCurrentAccount();
    }

    private MessagesController getMessagesController() {
        return host.getMessagesController();
    }

    private android.app.Activity getParentActivity() {
        return host.getParentActivity();
    }

    private void presentFragment(BaseFragment f) {
        host.presentFragment(f);
    }

    private void showDialog(Dialog d) {
        host.showDialog(d);
    }

    private static String onOff(boolean on) {
        return LocaleController.getString(on ? R.string.NotificationsOn : R.string.NotificationsOff);
    }

    private String filtersSummary() {
        if (!it.belloworld.mercurygram.PlusMessageFilters.isEnabled()) {
            return LocaleController.getString(R.string.PlusF04Off);
        }
        int filters = it.belloworld.mercurygram.PlusMessageFilters.getFilters().size();
        int users = it.belloworld.mercurygram.PlusMessageFilters.getBanned().size();
        if (filters == 0 && users == 0) {
            return LocaleController.getString(R.string.HanakoNone);
        }
        if (users == 0) {
            return LocaleController.formatPluralString("HanakoFilters", filters);
        }
        return LocaleController.formatPluralString("HanakoFilters", filters) + " · " + LocaleController.formatPluralString("HanakoHiddenUsers", users);
    }

    private void requestDeletedMediaSize() {
        if (sizeRequested) return;
        sizeRequested = true;
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            long size = 0;
            try {
                size = dirSize(it.belloworld.mercurygram.MgHistoryMedia.getDir(), 0);
            } catch (Throwable ignore) {
            }
            final long total = size;
            AndroidUtilities.runOnUIThread(() -> {
                deletedMediaSize = total > 0 ? AndroidUtilities.formatFileSize(total) : "";
                refreshList();
            });
        });
    }

    private static long dirSize(java.io.File f, int depth) {
        if (f == null || !f.exists() || depth > 4) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) n += dirSize(c, depth + 1);
        return n;
    }

    /**
     * All Hanako rows, in sections with headers (un-offset ids; the host offsets them).
     * Order: privacy (ghost mode, chat lock, streamer mode, tracking), messages (deleted
     * messages, activity log, message filters, chats), device (updates, backup and export,
     * App API ID, telemetry).
     */
    public void fill(ArrayList<UItem> items) {
        final int acc = getCurrentAccount();
        fillGhost(items);

        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF08Title)));
        items.add(UItem.asButton(ID_HUB_CHAT_LOCK, R.drawable.msg_permissions, LocaleController.getString(R.string.PlusF08Title),
                it.belloworld.mercurygram.PlusChatLock.settingsSummary(acc)));
        items.add(UItem.asShadow(null));

        fillStreamer(items);
        fillPrivacy(items);
        fillDeleted(items);
        // plus f05 begin
        PlusActivityLogActivity.fillSettings(items, acc);
        // plus f05 end

        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF04Title)));
        items.add(UItem.asButton(ID_HUB_FILTERS, R.drawable.msg_archive_hide, LocaleController.getString(R.string.PlusF04Title), filtersSummary()));
        items.add(UItem.asShadow(null));

        fillChats(items);
        fillUpdates(items);

        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoHubDevice)));
        items.add(UItem.asButton(ID_HUB_BACKUP, R.drawable.msg_download, LocaleController.getString(R.string.HanakoBackupTitle)));
        items.add(UItem.asButton(ID_HUB_API, R.drawable.msg_link, LocaleController.getString(R.string.HanakoApiTitle),
                it.belloworld.mercurygram.HanakoApiConfig.summary()));
        items.add(UItem.asButton(ID_HUB_TELEMETRY, R.drawable.msg_stats, LocaleController.getString(R.string.HanakoTelemetryTitle),
                onOff(it.belloworld.mercurygram.HanakoTelemetry.isEnabled())));
        items.add(UItem.asShadow(null));
    }

    private void fillStreamer(ArrayList<UItem> items) {
        // plus f10: streamer mode (all accounts)
        final boolean streamerOn = it.belloworld.mercurygram.PlusStreamer.isEnabled();
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF10StreamerMode)));
        items.add(UItem.asCheck(ID_STREAMER_ON, LocaleController.getString(R.string.PlusF10StreamerMode)).setChecked(streamerOn));
        final int[] streamerLabels = {R.string.PlusF10HideNames, R.string.PlusF10HideChatTitles, R.string.PlusF10HideAvatars,
                R.string.PlusF10HidePhones, R.string.PlusF10HideNotifications, R.string.PlusF10FlagSecure};
        for (int i = 0; i < streamerLabels.length; i++) {
            items.add(UItem.asCheck(ID_STREAMER_OPT + i, LocaleController.getString(streamerLabels[i]))
                    .setChecked(it.belloworld.mercurygram.PlusStreamer.getOption(i)));
        }
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(LocaleController.getString(R.string.PlusF10About))));
        // plus f10 end
    }

    private void fillPrivacy(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsPrivacy)));
        items.add(MgSettingsScope.globalCheck(ID_REDUCE_TRACKING_FINGERPRINT,
                        LocaleController.getString(R.string.MercurygramReduceTrackingFingerprint))
                .setChecked(SharedConfig.reduceTrackingFingerprint));
        String reduceAbout = LocaleController.getString(R.string.MercurygramReduceTrackingFingerprintAbout);
        // If any active account got force-disabled out of reduced mode by the
        // ladder-exhaustion path (native onReducedTempKeyExhausted), call it
        // out so the user can see the toggle is "on" while specific accounts
        // are actually running standard 24h temp keys.
        String exhaustedNames = collectExhaustedAccountNames();
        if (SharedConfig.reduceTrackingFingerprint && exhaustedNames != null) {
            reduceAbout = reduceAbout + "\n\n" + LocaleController.formatString(
                    "MercurygramReduceTrackingFingerprintExhaustedFooter",
                    R.string.MercurygramReduceTrackingFingerprintExhaustedFooter,
                    exhaustedNames);
        }
        items.add(UItem.asShadow(reduceAbout));

        items.add(UItem.asCheck(ID_DISABLE_INSTANT_VIEW,
                        LocaleController.getString(R.string.MercurygramDisableInstantView))
                .setChecked(getUserConfig().mg.disableInstantView));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableInstantViewAbout)));

        items.add(UItem.asCheck(ID_DISABLE_LINK_PREVIEWS,
                        LocaleController.getString(R.string.MercurygramDisableLinkPreviews))
                .setChecked(getUserConfig().mg.disableLinkPreviews));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableLinkPreviewsAbout)));

        items.add(UItem.asCheck(ID_STRIP_TRACKING_PARAMS,
                        LocaleController.getString(R.string.MercurygramStripTrackingParams))
                .setChecked(getUserConfig().mg.stripTrackingParams));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramStripTrackingParamsAbout)));

        items.add(UItem.asCheck(ID_DISABLE_CLOUD_DRAFTS,
                        LocaleController.getString(R.string.MercurygramDisableCloudDrafts))
                .setChecked(getUserConfig().mg.disableCloudDrafts));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableCloudDraftsAbout)));

        items.add(UItem.asCheck(ID_CONFIRM_INTERNAL_LINKS,
                        LocaleController.getString(R.string.MercurygramConfirmInternalLinks))
                .setChecked(getUserConfig().mg.confirmInternalLinks));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramConfirmInternalLinksAbout)));

        items.add(UItem.asCheck(ID_PREFER_SECRET_CHATS,
                        LocaleController.getString(R.string.MercurygramPreferSecretChats))
                .setChecked(getUserConfig().mg.preferSecretChats));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramPreferSecretChatsAbout)));
    }

    private void fillUpdates(ArrayList<UItem> items) {
        if (MgUpdateChecker.canSelfInstall()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsUpdates)));
            items.add(MgSettingsScope.globalCheck(ID_DISABLE_AUTO_UPDATE, LocaleController.getString(R.string.MercurygramDisableAutoUpdate))
                    .setChecked(SharedConfig.disableAutoUpdate));
            items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableAutoUpdateAbout)));

            // Hidden on the .beta package — that channel already follows
            // /releases unconditionally, so the toggle would be meaningless.
            if (!MgUpdateChecker.isBetaChannel()) {
                items.add(MgSettingsScope.globalCheck(ID_ACCEPT_PRERELEASES,
                                LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdates))
                        .setChecked(SharedConfig.acceptPreReleaseUpdates));
                items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdatesAbout)));
            }

            String checkSubtitle = SharedConfig.mgLastUpdateCheckTime > 0
                    ? LocaleController.formatString("MercurygramCheckForUpdatesLastChecked",
                            R.string.MercurygramCheckForUpdatesLastChecked,
                            LocaleController.formatDateTime(SharedConfig.mgLastUpdateCheckTime / 1000, true))
                    : LocaleController.getString(R.string.MercurygramCheckForUpdatesNever);
            items.add(UItem.asButton(ID_CHECK_FOR_UPDATES_NOW,
                    LocaleController.getString(R.string.MercurygramCheckForUpdatesNow), checkSubtitle));
            items.add(UItem.asShadow(null));
        }
    }

    private void fillGhost(ArrayList<UItem> items) {
        // plus: ghost mode (this account)
        final int acc = getCurrentAccount();
        final boolean ghostOn = it.belloworld.mercurygram.PlusGhost.isEnabled(acc);
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusGhostMode)));
        items.add(UItem.asCheck(ID_GHOST_ON, LocaleController.getString(R.string.PlusGhostMode)).setChecked(ghostOn));
        if (ghostOn) {
            // order matches PlusGhost option indices
            final int[] ghostLabelRes = {R.string.PlusGhostOptNoReads, R.string.PlusGhostOptNoTyping, R.string.PlusGhostOptStayOffline,
                    R.string.PlusGhostOptNoStories, R.string.PlusGhostOptReadOnReply, R.string.PlusGhostOptForceOffline};
            final String[] ghostLabels = new String[ghostLabelRes.length];
            for (int i = 0; i < ghostLabelRes.length; i++) {
                ghostLabels[i] = LocaleController.getString(ghostLabelRes[i]);
            }
            for (int i = 0; i < ghostLabels.length; i++) {
                items.add(UItem.asCheck(ID_GHOST_OPT + i, ghostLabels[i])
                        .setChecked(it.belloworld.mercurygram.PlusGhost.isHidden(acc, i)));
            }
            // plus f09: send without sound while ghost mode is on
            items.add(UItem.asCheck(ID_GHOST_SEND_SILENT, LocaleController.getString(R.string.PlusGhostSendSilent))
                    .setChecked(it.belloworld.mercurygram.PlusGhostSilent.isEnabled(acc)));
            // plus f09 end
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusGhostSettingsInfo)));
        // plus f01 begin: per-chat ghost exceptions
        if (ghostOn) {
            int plusExcCount = it.belloworld.mercurygram.PlusGhostExceptions.count(acc);
            items.add(UItem.asButton(ID_PLUS_GHOST_EXCEPTIONS, R.drawable.plus_ghost,
                    LocaleController.getString(R.string.PlusGhostExcTitle),
                    plusExcCount > 0 ? Integer.toString(plusExcCount) : LocaleController.getString(R.string.PlusGhostExcNone)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusGhostExcSettingsInfo)));
        }
        // plus f01 end

        // plus f06 begin
        it.belloworld.mercurygram.PlusStoryGuard.addSettingsItems(items, acc);
        // plus f06 end

        // plus f12 begin
        it.belloworld.mercurygram.PlusPeek.addSettingsItems(items);
        // plus f12 end

        // plus f15 begin
        it.belloworld.mercurygram.PlusEmojiInteractions.addSettingsItems(items, acc);
        // plus f15 end

        // plus f16: ask before sending voice / round / sticker / GIF and before calls (all accounts)
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusPromptHeader)));
        for (int k = 0; k < it.belloworld.mercurygram.PlusSendPrompts.KIND_COUNT; k++) {
            items.add(UItem.asCheck(ID_SEND_PROMPT + k, it.belloworld.mercurygram.PlusSendPrompts.label(k))
                    .setChecked(it.belloworld.mercurygram.PlusSendPrompts.isEnabled(k)));
        }
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(LocaleController.getString(R.string.PlusPromptAbout))));
        // plus f16 end

    }

    private void fillDeleted(ArrayList<UItem> items) {
        // hanako: one screen for the whole feature, in the order you use it:
        // the switch, how long copies are kept (Telegram's Keep media shape),
        // what is on the device, how ghosts look in chats, then vanished chats.
        requestDeletedMediaSize();
        final boolean on = getUserConfig().mg.savedMessagesHistory;
        final int acc = getCurrentAccount();
        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoPageDeleted)));
        items.add(UItem.asCheck(ID_SAVED_MESSAGES_HISTORY, LocaleController.getString(R.string.MercurygramSavedMessagesHistory))
                .setChecked(on));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramSavedMessagesHistoryAbout)));

        if (on) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoKeepDeletedHeader)));
            for (int t = 0; t < it.belloworld.mercurygram.MgDeletedRetention.TYPE_COUNT; t++) {
                items.add(HanakoKeepDeletedActivity.TypeRow.of(ID_KEEP_TYPE + t, t, keepValue(acc, t)));
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoKeepDeletedInfo)));

            items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoDeletedInChatsHeader)));
            items.add(MgSettingsScope.globalCheck(ID_F07_KEEP_REACTIONS, LocaleController.getString(R.string.PlusF07KeepReactions))
                    .setChecked(it.belloworld.mercurygram.PlusDeletedReactions.isKeepEnabled()));
            items.add(UItem.asCheck(ID_F20_CHAT_MENU, LocaleController.getString(R.string.PlusF20ChatMenu))
                    .setChecked(it.belloworld.mercurygram.PlusDeletedMedia.isChatMenuEnabled()));
            items.add(UItem.asCheck(ID_F20_INCLUDE_EDITS, LocaleController.getString(R.string.PlusF20IncludeEdits))
                    .setChecked(it.belloworld.mercurygram.PlusDeletedMedia.isIncludeEdits()));
            items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(LocaleController.getString(R.string.HanakoDeletedInChatsInfo))));
        }

        requestSavedCounts();
        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoDeletedStorageHeader)));
        items.add(UItem.asButton(ID_F20_OPEN, R.drawable.msg_media, LocaleController.getString(R.string.PlusF20Title), deletedMediaSize));
        UItem clear = UItem.asButton(ID_CLEAR_SAVED_HISTORY, R.drawable.msg_clear, LocaleController.getString(R.string.MercurygramClearSavedHistory));
        clear.red = true;
        items.add(clear);
        items.add(UItem.asShadow(savedCounts != null ? savedCountsText(savedCounts)
                : LocaleController.getString(R.string.HanakoDeletedStorageInfo)));

        // plus f07: vanished-chat log
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF07DeletedDialogsTitle)));
        items.add(UItem.asCheck(ID_F07_LOG_DIALOGS, LocaleController.getString(R.string.PlusF07LogDialogs))
                .setChecked(it.belloworld.mercurygram.PlusDeletedDialogs.isEnabled(acc)));
        items.add(UItem.asButton(ID_F07_OPEN_LOG, LocaleController.getString(R.string.PlusF07DeletedDialogsTitle),
                Integer.toString(it.belloworld.mercurygram.PlusDeletedDialogs.count(acc))));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF07SettingsAbout)));
    }

    // hanako: retention rows + saved counts
    public static final int ID_KEEP_TYPE = 2100; // + MgDeletedRetention.TYPE_*
    private int[] savedCounts;
    private boolean countsRequested;

    private static CharSequence keepValue(int acc, int type) {
        String v = it.belloworld.mercurygram.MgDeletedRetention.periodLabel(
                it.belloworld.mercurygram.MgDeletedRetention.getTypePeriod(acc, type));
        int n = it.belloworld.mercurygram.MgDeletedRetention.exceptionsOf(acc, type).size();
        return n == 0 ? v : v + " · " + LocaleController.formatPluralString("ExceptionShort", n);
    }

    private static String savedCountsText(int[] c) {
        if (c[0] == 0 && c[1] == 0) {
            return LocaleController.getString(R.string.HanakoDeletedStorageEmpty);
        }
        return LocaleController.formatString(R.string.HanakoDeletedStorageCounts,
                LocaleController.formatPluralString("HanakoDeletedCount", c[0]),
                LocaleController.formatPluralString("HanakoEditedCount", c[1]));
    }

    private void requestSavedCounts() {
        if (countsRequested) return;
        countsRequested = true;
        final int acc = getCurrentAccount();
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            int[] c = MgMessageHistory.getInstance().counts(acc, 0);
            AndroidUtilities.runOnUIThread(() -> {
                savedCounts = c;
                refreshList();
            });
        });
    }

    private void fillChats(ArrayList<UItem> items) {
        // With several accounts logged in, state the default scope once; rows
        // backed by global SharedConfig carry their own "all accounts" label.
        if (MgSettingsScope.multiAccount()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramScopeDefaultFooter)));
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsGeneral)));
        items.add(UItem.asCheck(ID_MESSAGE_DETAILS_MENU, LocaleController.getString(R.string.MercurygramMessageDetailsMenu))
                .setChecked(getUserConfig().mg.messageDetailsMenu));
        items.add(UItem.asCheck(ID_HIDE_CHAT_KEYBOARD, LocaleController.getString(R.string.HideChatKeyboard))
                .setChecked(getUserConfig().mg.hideChatKeyboard));
        items.add(UItem.asCheck(ID_HIDE_STORIES, LocaleController.getString(R.string.MercurygramHideStories))
                .setChecked(getUserConfig().mg.hideStories));
        items.add(MgSettingsScope.globalCheck(ID_USE_SYSTEM_FONT, LocaleController.getString(R.string.MercurygramUseSystemFont))
                .setChecked(SharedConfig.useSystemFont));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramUseSystemFontAbout)));

        items.add(UItem.asCheck(ID_SHOW_CHAR_COUNTER, LocaleController.getString(R.string.MercurygramShowCharCounter))
                .setChecked(getUserConfig().mg.showCharCounter));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramShowCharCounterAbout)));

        items.add(UItem.asButton(ID_EMOJI_PACK,
                LocaleController.getString(R.string.MercurygramEmojiTitle),
                emojiPackShortLabel()));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                LocaleController.getString(R.string.MercurygramEmojiRowAbout))));

        items.add(UItem.asCheck(ID_DELETE_FOR_ALL_DEFAULT,
                        LocaleController.getString(R.string.MercurygramDeleteForAllByDefault))
                .setChecked(getUserConfig().mg.deleteForAllByDefault));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDeleteForAllByDefaultAbout)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsMedia)));
        items.add(MgSettingsScope.globalCheck(ID_DISABLE_PROXIMITY_SENSOR, LocaleController.getString(R.string.MercurygramDisableProximitySensor))
                .setChecked(SharedConfig.mg_disableProximitySensor));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableProximitySensorAbout)));

        // plus f13: message shot (all accounts)
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusMessageShot)));
        items.add(MgSettingsScope.globalCheck(ID_MESSAGE_SHOT, LocaleController.getString(R.string.PlusMessageShotSetting))
                .setChecked(it.belloworld.mercurygram.PlusMessageShot.isEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusMessageShotSettingAbout)));
        // plus f13 end

        // plus f19: transfer boost (all accounts)
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF19Header)));
        items.add(UItem.asButton(ID_PLUS_F19_DOWNLOAD, LocaleController.getString(R.string.PlusF19DownloadBoost),
                it.belloworld.mercurygram.PlusTransferBoost.modeLabel(it.belloworld.mercurygram.PlusTransferBoost.getDownloadMode())));
        items.add(UItem.asButton(ID_PLUS_F19_UPLOAD, LocaleController.getString(R.string.PlusF19UploadBoost),
                it.belloworld.mercurygram.PlusTransferBoost.modeLabel(it.belloworld.mercurygram.PlusTransferBoost.getUploadMode())));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(LocaleController.getString(R.string.PlusF19About))));
        // plus f19 end

        // plus f14: profile ID / DC / registration date
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF14Header)));
        items.add(UItem.asCheck(ID_F14_SHOW_DC, LocaleController.getString(R.string.PlusF14ShowDc))
                .setChecked(it.belloworld.mercurygram.PlusProfileInfo.isShowDc()));
        items.add(UItem.asCheck(ID_F14_SHOW_REG_DATE, LocaleController.getString(R.string.PlusF14ShowRegDate))
                .setChecked(it.belloworld.mercurygram.PlusProfileInfo.isShowRegDate()));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(LocaleController.getString(R.string.PlusF14About))));
        // plus f14 end

    }

    /** Handles a click on one of this section's rows (un-offset id); false when the row is not ours. */
    public boolean onClick(UItem item, View view) {
        clickHandled = true;
        handleClick(item, view);
        return clickHandled;
    }

    private boolean clickHandled;

    private void handleClick(UItem item, View view) {
        if (item.id == ID_HUB_CHAT_LOCK) {
            it.belloworld.mercurygram.PlusChatLock.openSettings(host);
            return;
        }
        if (item.id == ID_HUB_FILTERS) {
            presentFragment(new PlusMessageFiltersActivity());
            return;
        }
        if (item.id == ID_HUB_BACKUP) {
            presentFragment(new HanakoBackupActivity());
            return;
        }
        if (item.id == ID_HUB_API) {
            presentFragment(new HanakoApiActivity());
            return;
        }
        if (item.id == ID_HUB_TELEMETRY) {
            presentFragment(new HanakoTelemetryActivity());
            return;
        }
        if (item.id == ID_PLUS_GHOST_EXCEPTIONS) { // plus f01
            presentFragment(new PlusGhostExceptionsActivity());
            return;
        }
        // plus f05 begin
        if (PlusActivityLogActivity.onSettingsClick(host, item, refresh)) {
            return;
        }
        // plus f05 end
        // plus f08 begin
        if (item.id == it.belloworld.mercurygram.PlusChatLock.SETTINGS_ROW_ID) {
            it.belloworld.mercurygram.PlusChatLock.openSettings(host);
            return;
        }
        // plus f08 end
        // plus f12 begin
        if (it.belloworld.mercurygram.PlusPeek.onSettingsClick(item.id, refresh)) {
            return;
        }
        // plus f12 end
        // plus f13: message shot toggle
        if (item.id == ID_MESSAGE_SHOT) {
            it.belloworld.mercurygram.PlusMessageShot.setEnabled(!it.belloworld.mercurygram.PlusMessageShot.isEnabled());
            refreshList();
            return;
        }
        // plus f15 begin
        if (it.belloworld.mercurygram.PlusEmojiInteractions.onSettingsClick(getCurrentAccount(), item.id, refresh)) {
            return;
        }
        // plus f15 end
        // plus f16: send / call prompts
        if (item.id >= ID_SEND_PROMPT && item.id < ID_SEND_PROMPT + it.belloworld.mercurygram.PlusSendPrompts.KIND_COUNT) {
            int kind = item.id - ID_SEND_PROMPT;
            it.belloworld.mercurygram.PlusSendPrompts.setEnabled(kind, !it.belloworld.mercurygram.PlusSendPrompts.isEnabled(kind));
            refreshList();
            return;
        }
        // plus f19: transfer boost pickers
        if (item.id == ID_PLUS_F19_DOWNLOAD || item.id == ID_PLUS_F19_UPLOAD) {
            it.belloworld.mercurygram.PlusTransferBoost.showModePicker(host, item.id == ID_PLUS_F19_UPLOAD, refresh);
            return;
        }
        // plus: ghost mode toggles
        if (item.id == ID_GHOST_ON) {
            it.belloworld.mercurygram.PlusGhost.setEnabled(getCurrentAccount(), !it.belloworld.mercurygram.PlusGhost.isEnabled(getCurrentAccount()));
            refreshList();
            return;
        }
        // plus f09: send without sound while ghost mode is on
        if (item.id == ID_GHOST_SEND_SILENT) {
            it.belloworld.mercurygram.PlusGhostSilent.setEnabled(getCurrentAccount(), !it.belloworld.mercurygram.PlusGhostSilent.isEnabled(getCurrentAccount()));
            refreshList();
            return;
        }
        // plus f09 end
        if (item.id >= ID_GHOST_OPT && item.id < ID_GHOST_OPT + it.belloworld.mercurygram.PlusGhost.OPT_COUNT) {
            int opt = item.id - ID_GHOST_OPT;
            it.belloworld.mercurygram.PlusGhost.setHidden(getCurrentAccount(), opt, !it.belloworld.mercurygram.PlusGhost.isHidden(getCurrentAccount(), opt));
            refreshList();
            return;
        }
        // plus f06 begin
        if (it.belloworld.mercurygram.PlusStoryGuard.onSettingsClick(getParentActivity(), getCurrentAccount(), item.id, refresh)) {
            return;
        }
        // plus f06 end
        // hanako: retention type rows open Telegram's Keep media style menu
        if (item.id >= ID_KEEP_TYPE && item.id < ID_KEEP_TYPE + it.belloworld.mercurygram.MgDeletedRetention.TYPE_COUNT) {
            HanakoKeepDeletedActivity.showTypeMenu(host, view, getCurrentAccount(), item.id - ID_KEEP_TYPE, () -> {
                refreshList();
                // a shorter period prunes in the background; re-read the counts once it ran
                AndroidUtilities.runOnUIThread(() -> {
                    countsRequested = false;
                    sizeRequested = false;
                    refreshList();
                }, 800);
            });
            return;
        }
        // plus f07 begin
        if (item.id == ID_F07_LOG_DIALOGS) {
            it.belloworld.mercurygram.PlusDeletedDialogs.setEnabled(getCurrentAccount(), !it.belloworld.mercurygram.PlusDeletedDialogs.isEnabled(getCurrentAccount()));
            refreshList();
            return;
        }
        if (item.id == ID_F07_OPEN_LOG) {
            presentFragment(new PlusDeletedDialogsActivity());
            return;
        }
        if (item.id == ID_F07_KEEP_REACTIONS) {
            it.belloworld.mercurygram.PlusDeletedReactions.setKeepEnabled(!it.belloworld.mercurygram.PlusDeletedReactions.isKeepEnabled());
            refreshList();
            return;
        }
        // plus f07 end
        // plus f10: streamer mode toggles
        if (item.id == ID_STREAMER_ON) {
            it.belloworld.mercurygram.PlusStreamer.toggle(host);
            refreshList();
            return;
        }
        if (item.id >= ID_STREAMER_OPT && item.id < ID_STREAMER_OPT + it.belloworld.mercurygram.PlusStreamer.OPT_COUNT) {
            int opt = item.id - ID_STREAMER_OPT;
            it.belloworld.mercurygram.PlusStreamer.setOption(opt, !it.belloworld.mercurygram.PlusStreamer.getOption(opt));
            refreshList();
            return;
        }
        // plus f14: profile ID / DC / registration date
        if (item.id == ID_F14_SHOW_DC) {
            it.belloworld.mercurygram.PlusProfileInfo.setShowDc(!it.belloworld.mercurygram.PlusProfileInfo.isShowDc());
            refreshList();
            return;
        }
        if (item.id == ID_F14_SHOW_REG_DATE) {
            it.belloworld.mercurygram.PlusProfileInfo.setShowRegDate(!it.belloworld.mercurygram.PlusProfileInfo.isShowRegDate());
            refreshList();
            return;
        }
        // plus f14 end
        // plus f20 begin: saved deleted-media browser
        if (item.id == ID_F20_OPEN) {
            presentFragment(new PlusDeletedMediaActivity(0));
            return;
        }
        if (item.id == ID_F20_CHAT_MENU) {
            it.belloworld.mercurygram.PlusDeletedMedia.setChatMenuEnabled(!it.belloworld.mercurygram.PlusDeletedMedia.isChatMenuEnabled());
            refreshList();
            return;
        }
        if (item.id == ID_F20_INCLUDE_EDITS) {
            it.belloworld.mercurygram.PlusDeletedMedia.setIncludeEdits(!it.belloworld.mercurygram.PlusDeletedMedia.isIncludeEdits());
            refreshList();
            return;
        }
        // plus f20 end
        switch (item.id) {
            case ID_MESSAGE_DETAILS_MENU:
                getUserConfig().mg.messageDetailsMenu = !getUserConfig().mg.messageDetailsMenu;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_HIDE_CHAT_KEYBOARD:
                getUserConfig().mg.hideChatKeyboard = !getUserConfig().mg.hideChatKeyboard;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_SHOW_CHAR_COUNTER:
                getUserConfig().mg.showCharCounter = !getUserConfig().mg.showCharCounter;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_USE_SYSTEM_FONT:
                SharedConfig.toggleUseSystemFont();
                refreshList();
                break;
            case ID_HIDE_STORIES:
                getUserConfig().mg.hideStories = !getUserConfig().mg.hideStories;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DELETE_FOR_ALL_DEFAULT:
                getUserConfig().mg.deleteForAllByDefault = !getUserConfig().mg.deleteForAllByDefault;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_SAVED_MESSAGES_HISTORY:
                getUserConfig().mg.savedMessagesHistory = !getUserConfig().mg.savedMessagesHistory;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_CLEAR_SAVED_HISTORY:
                confirmClearSavedHistory();
                break;
            case ID_DISABLE_PROXIMITY_SENSOR:
                SharedConfig.toggleMgDisableProximitySensor();
                refreshList();
                break;
            case ID_DISABLE_AUTO_UPDATE:
                SharedConfig.toggleDisableAutoUpdate();
                refreshList();
                break;
            case ID_ACCEPT_PRERELEASES:
                handleAcceptPreReleasesClick();
                break;
            case ID_CHECK_FOR_UPDATES_NOW:
                MgUpdateChecker.checkForUpdates(true);
                showCheckingForUpdatesToast(getParentActivity());
                refreshList();
                break;
            case ID_REDUCE_TRACKING_FINGERPRINT:
                handleReduceTrackingFingerprintClick();
                break;
            case ID_DISABLE_INSTANT_VIEW:
                getUserConfig().mg.disableInstantView = !getUserConfig().mg.disableInstantView;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DISABLE_LINK_PREVIEWS:
                getUserConfig().mg.disableLinkPreviews = !getUserConfig().mg.disableLinkPreviews;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_STRIP_TRACKING_PARAMS:
                getUserConfig().mg.stripTrackingParams = !getUserConfig().mg.stripTrackingParams;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DISABLE_CLOUD_DRAFTS:
                getUserConfig().mg.disableCloudDrafts = !getUserConfig().mg.disableCloudDrafts;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_CONFIRM_INTERNAL_LINKS:
                getUserConfig().mg.confirmInternalLinks = !getUserConfig().mg.confirmInternalLinks;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_PREFER_SECRET_CHATS:
                getUserConfig().mg.preferSecretChats = !getUserConfig().mg.preferSecretChats;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_EMOJI_PACK:
                presentFragment(new MercurygramEmojiSettingsActivity());
                break;
            default:
                clickHandled = false;
                break;
        }
    }

    // Subtitle for the Custom-emoji-pack row: "Off" when disabled; the installed
    // glyph count when a pack is loaded; "No pack installed" when the toggle is
    // on but nothing is imported — every glyph still falls back to the bundled
    // set, so the row must not imply custom emoji are active.
    private static String emojiPackShortLabel() {
        if (!SharedConfig.mg_useCustomEmojiPack) {
            return LocaleController.getString(R.string.MercurygramEmojiRowDisabled);
        }
        int installed = it.belloworld.mercurygram.emoji.MgEmojiPack.installedCount();
        return installed > 0
                ? LocaleController.formatString("MercurygramEmojiInstalled",
                        R.string.MercurygramEmojiInstalled, installed)
                : LocaleController.getString(R.string.MercurygramEmojiNotInstalled);
    }

    /** Host resumed: deleted-message exceptions or media may have changed. */
    public void onResume() {
        countsRequested = false;
        sizeRequested = false;
    }

    private void refreshList() {
        if (refresh != null) {
            refresh.run();
        }
    }

    private void showCheckingForUpdatesToast(Context context) {
        Toast.makeText(context,
                LocaleController.getString(R.string.MercurygramCheckForUpdatesToast),
                Toast.LENGTH_SHORT).show();
    }

    private void handleAcceptPreReleasesClick() {
        // Post-stable betas (X.Y.Z.W.K) share MG_VC with X.Y.Z.W and can be
        // rolled back; toggling off triggers the download of that stable.
        if (SharedConfig.acceptPreReleaseUpdates) {
            MgUpdateChecker.setPreReleaseOptIn(false);
            // Fetching the stable is a network round trip, so the toast is the
            // only immediate feedback -- and only when a rollback really
            // started: a stable or a pre-stable X.Y.Z.0.K has none to return
            // to, and the unchecked row is the whole outcome there.
            if (MgUpdateChecker.checkForDowngradeToStable()) {
                showCheckingForUpdatesToast(getParentActivity());
            }
            refreshList();
            return;
        }
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdatesWarningTitle))
                .setMessage(LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdatesWarningMessage))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdatesEnable),
                        (d, which) -> {
                            MgUpdateChecker.setPreReleaseOptIn(true);
                            // Opting in is only meaningful once a pre-release is
                            // actually offered, and the periodic check is up to an
                            // hour away (never, with auto-update off), so force one
                            // now -- same forced call the "check now" row makes.
                            MgUpdateChecker.checkForUpdates(true);
                            showCheckingForUpdatesToast(context);
                            refreshList();
                        })
                .create();
        showDialog(dialog);
        TextView positive = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void handleReduceTrackingFingerprintClick() {
        if (SharedConfig.reduceTrackingFingerprint) {
            SharedConfig.toggleReduceTrackingFingerprint();
            refreshList();
            return;
        }
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramReduceTrackingFingerprintWarningTitle))
                .setMessage(LocaleController.getString(R.string.MercurygramReduceTrackingFingerprintWarningMessage))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.MercurygramReduceTrackingFingerprintEnable),
                        (d, which) -> {
                            // Fresh enable cycle: clear any stale per-account
                            // exhaustion flag so the footer doesn't shame the
                            // user with a result from a prior cycle. Native
                            // ladder state is already cleared by the toggle's
                            // setReducedTempKeyMode(false→true) path.
                            clearReducedTrackingExhaustedFlags();
                            SharedConfig.toggleReduceTrackingFingerprint();
                            refreshList();
                        })
                .create();
        showDialog(dialog);
    }

    private static String collectExhaustedAccountNames() {
        StringBuilder sb = null;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.isClientActivated() || !uc.mg.mgReducedTrackingExhausted) continue;
            String name = uc.getCurrentUser() != null
                    ? org.telegram.messenger.UserObject.getFirstName(uc.getCurrentUser())
                    : "#" + (a + 1);
            if (sb == null) sb = new StringBuilder(name);
            else sb.append(", ").append(name);
        }
        return sb == null ? null : sb.toString();
    }

    private static void clearReducedTrackingExhaustedFlags() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.mg.mgReducedTrackingExhausted) continue;
            uc.mg.mgReducedTrackingExhausted = false;
            uc.saveConfig(false);
        }
    }

    private void confirmClearSavedHistory() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramClearSavedHistory))
                .setMessage(LocaleController.getString(R.string.MercurygramClearSavedHistoryConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                    // hanako: off the UI thread, then refresh the counts and the media size
                    org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                        MgMessageHistory.getInstance().clearAll();
                        AndroidUtilities.runOnUIThread(() -> {
                            savedCounts = null;
                            countsRequested = false;
                            sizeRequested = false;
                            deletedMediaSize = "";
                            refreshList();
                        }, 300);
                    });
                })
                .show();
    }

}
