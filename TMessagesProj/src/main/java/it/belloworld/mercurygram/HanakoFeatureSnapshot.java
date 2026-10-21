package it.belloworld.mercurygram;

import org.telegram.messenger.forkgram.HiddenAccountHelper;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;

import java.util.Map;

/**
 * hanako: the daily feature-state snapshot of {@link HanakoTelemetry}: which Hanako features and
 * settings are switched on right now, so a week of data says what is in use even when nothing
 * is tapped (ghost blocking, history saving, DoH ... all work passively).
 *
 * <p>Values are only 0/1, small numbers (counts, modes, periods in days) or one of a fixed set
 * of short ids ("offline", "parakeet-tdt-0.6b-v3-int8"). Never text, ids, names, URLs or
 * anything the user typed. Per-account settings are read for the account in use.
 * Every value is read on its own: one that throws is left out, the rest still go.
 */
final class HanakoFeatureSnapshot {

    private interface Val {
        Object get() throws Throwable;
    }

    private HanakoFeatureSnapshot() {
    }

    private static void put(JSONObject s, String key, Val v) {
        try {
            Object o = v.get();
            if (o instanceof Boolean) {
                s.put(key, ((Boolean) o) ? 1 : 0);
            } else if (o instanceof Integer || o instanceof Long) {
                s.put(key, ((Number) o).longValue());
            } else if (o instanceof String && HanakoTelemetry.isSafeEnum((String) o)) {
                s.put(key, o);
            }
        } catch (Throwable ignore) {
            // a feature that can't be read is simply missing from the snapshot
        }
    }

    /** Call off the UI thread (a few values read small local databases). */
    static JSONObject collect() {
        final JSONObject s = new JSONObject();
        final int a = UserConfig.selectedAccount;

        // accounts
        put(s, "accounts", () -> {
            int n = 0;
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (UserConfig.getInstance(i).isClientActivated()) n++;
            }
            return n;
        });
        put(s, "hidden_accounts", () -> {
            int n = 0;
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (HiddenAccountHelper.isAccountHidden(i)) n++;
            }
            return n;
        });

        // ghost family (account in use)
        put(s, "ghost_on", () -> PlusGhost.isEnabled(a));
        final String[] ghostOpts = {"reads", "typing", "online", "stories", "read_on_send", "force_offline"};
        for (int i = 0; i < ghostOpts.length && i < PlusGhost.OPT_COUNT; i++) {
            final int opt = i;
            put(s, "ghost_" + ghostOpts[i], () -> PlusGhost.isHidden(a, opt));
        }
        put(s, "ghost_silent", () -> PlusGhostSilent.isEnabled(a));
        put(s, "ghost_exceptions", () -> PlusGhostExceptions.count(a));
        put(s, "story_open_mode", () -> PlusStoryGuard.getOpenMode(a));
        put(s, "story_confirm_interact", () -> PlusStoryGuard.isConfirmInteract(a));
        put(s, "emoji_interactions_off", () -> PlusEmojiInteractions.isInteractionsDisabled(a));
        put(s, "choosing_sticker_hidden", () -> PlusEmojiInteractions.isChoosingStickerHidden(a));
        final String[] prompts = {"voice", "round", "sticker", "gif", "voice_call", "video_call"};
        for (int i = 0; i < prompts.length && i < PlusSendPrompts.KIND_COUNT; i++) {
            final int kind = i;
            put(s, "ask_before_" + prompts[i], () -> PlusSendPrompts.isEnabled(kind));
        }

        // streamer mode (all accounts)
        put(s, "streamer_on", PlusStreamer::isEnabled);
        final String[] streamer = {"names", "chat_titles", "avatars", "phones", "notifications", "flag_secure"};
        for (int i = 0; i < streamer.length && i < PlusStreamer.OPT_COUNT; i++) {
            final int opt = i;
            put(s, "streamer_hide_" + streamer[i], () -> PlusStreamer.getOption(opt));
        }

        // chat lock
        put(s, "chat_lock_chats", () -> PlusChatLock.getLocked(a).size());
        put(s, "chat_lock_hide_locked", () -> PlusChatLock.getBool(PlusChatLock.KEY_HIDE_LOCKED));
        put(s, "chat_lock_archive", () -> PlusChatLock.getBool(PlusChatLock.KEY_LOCK_ARCHIVE));
        put(s, "chat_lock_secret", () -> PlusChatLock.getBool(PlusChatLock.KEY_LOCK_SECRET));
        put(s, "chat_lock_mask_notifications", () -> PlusChatLock.getBool(PlusChatLock.KEY_MASK_NOTIFICATIONS));
        put(s, "chat_lock_device_credential", () -> PlusChatLock.getBool(PlusChatLock.KEY_ALLOW_DEVICE_CREDENTIAL));

        // activity log
        put(s, "activity_log_on", () -> PlusActivityLog.isEnabled(a));
        put(s, "activity_log_scope", () -> PlusActivityLog.getScope(a));
        put(s, "activity_log_retention_days", PlusActivityLog::getRetentionDays);

        // deleted and edited history
        put(s, "history_on", () -> UserConfig.getInstance(a).mg.savedMessagesHistory);
        final String[] types = {"private", "group", "channel"};
        for (int t = 0; t < types.length && t < MgDeletedRetention.TYPE_COUNT; t++) {
            final int type = t;
            // days; 0 = forever, -1 = don't save
            put(s, "history_keep_" + types[t], () -> MgDeletedRetention.getTypePeriod(a, type));
        }
        put(s, "history_keep_reactions", PlusDeletedReactions::isKeepEnabled);
        put(s, "deleted_keep_rules", () -> MgDeletedRetention.rules(org.telegram.messenger.UserConfig.selectedAccount).size()); // hanako 2.02
        put(s, "vanished_chats_log", () -> PlusDeletedDialogs.isEnabled(a));

        // message filters
        put(s, "filters_on", PlusMessageFilters::isEnabled);
        put(s, "filters_count", () -> PlusMessageFilters.getFilters().size());
        put(s, "filters_hidden_users", () -> PlusMessageFilters.getBanned().size());
        put(s, "filters_hide_blocked", PlusMessageFilters::isHideBlocked);
        put(s, "filters_users_collapse", PlusMessageFilters::isUsersCollapse);

        // security and plugins
        put(s, "custom_api_id", HanakoApiConfig::hasCustom);

        // network
        put(s, "proxy_on", SharedConfig::isProxyEnabled);
        put(s, "boost_download", PlusTransferBoost::getDownloadMode);
        put(s, "boost_upload", PlusTransferBoost::getUploadMode);

        // voice and translation

        // look
        put(s, "theme_rin", () -> {
            Theme.ThemeInfo t = Theme.getActiveTheme();
            return t != null && "Rin".equals(t.name) && t.assetName != null;
        });
        put(s, "theme_dark", () -> {
            Theme.ThemeInfo t = Theme.getActiveTheme();
            return t != null && t.isDark();
        });
        put(s, "system_font", () -> SharedConfig.useSystemFont);
        put(s, "system_emoji", () -> SharedConfig.useSystemEmoji);
        put(s, "custom_emoji_pack", () -> SharedConfig.mg_useCustomEmojiPack);
        put(s, "emoji_pack_installed", () -> it.belloworld.mercurygram.emoji.MgEmojiPack.installedCount() > 0);

        // chats page
        put(s, "message_shot_button", PlusMessageShot::isEnabled);
        put(s, "message_shot_background", PlusMessageShot::isBackgroundEnabled);
        put(s, "profile_show_dc", PlusProfileInfo::isShowDc);
        put(s, "profile_show_reg_date", PlusProfileInfo::isShowRegDate);
        final MgAccountConfig mg = UserConfig.getInstance(a).mg;
        put(s, "message_details_menu", () -> mg.messageDetailsMenu);
        put(s, "hide_chat_keyboard", () -> mg.hideChatKeyboard);
        put(s, "hide_all_tab", () -> mg.hideAllTab);
        put(s, "default_folder_set", () -> mg.defaultFolderId != 0);
        put(s, "hide_stories", () -> mg.hideStories);
        put(s, "hide_premium_promo", () -> mg.hidePremiumPromo);
        put(s, "char_counter", () -> mg.showCharCounter);
        put(s, "delete_for_all_default", () -> mg.deleteForAllByDefault);
        put(s, "rear_round_camera", () -> mg.rearRoundCamera);
        put(s, "live_photos_off", () -> mg.disableLivePhotosByDefault);
        put(s, "proximity_sensor_off", () -> SharedConfig.mg_disableProximitySensor);

        // tracking and AI
        put(s, "reduce_tracking_fingerprint", () -> SharedConfig.reduceTrackingFingerprint);
        put(s, "strip_tracking_params", () -> mg.stripTrackingParams);
        put(s, "no_global_search", () -> mg.disableGlobalSearch);
        put(s, "no_ai_editor", () -> mg.disableAiEditor);
        put(s, "no_ai_summary", () -> mg.disableAiSummary);
        put(s, "no_instant_view", () -> mg.disableInstantView);
        put(s, "no_link_previews", () -> mg.disableLinkPreviews);
        put(s, "no_cloud_drafts", () -> mg.disableCloudDrafts);
        put(s, "confirm_internal_links", () -> mg.confirmInternalLinks);
        put(s, "prefer_secret_chats", () -> mg.preferSecretChats);

        // updates
        put(s, "auto_update_off", () -> SharedConfig.disableAutoUpdate);
        put(s, "prereleases", () -> SharedConfig.acceptPreReleaseUpdates);
        return s;
    }
}
