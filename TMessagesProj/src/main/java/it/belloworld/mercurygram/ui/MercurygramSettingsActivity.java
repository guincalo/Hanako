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
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import org.unifiedpush.android.connector.UnifiedPush;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import it.belloworld.mercurygram.HiddenAccountHelper;
import it.belloworld.mercurygram.MgMessageHistory;
import it.belloworld.mercurygram.MgUpdateChecker;
import it.belloworld.mercurygram.push.MgEmbeddedFcmDistributor;
import it.belloworld.mercurygram.transcribe.MgWhisperModel;

public class MercurygramSettingsActivity extends UniversalFragment {

    private static final int ID_HIDDEN_ACCOUNTS = 0;
    // plus: ghost mode
    private static final int ID_GHOST_ON = 900;
    private static final int ID_GHOST_OPT = 910; // + PlusGhost.OPT_*
    private static final int ID_PLUS_GHOST_EXCEPTIONS = 9101; // plus f01
    private static final int ID_F04_MESSAGE_FILTERS = 400; // plus f04
    private static final int ID_GHOST_SEND_SILENT = 950; // plus f09
    // plus f10: streamer mode
    private static final int ID_STREAMER_ON = 1000;
    private static final int ID_STREAMER_OPT = 1010; // + PlusStreamer.OPT_*
    // plus f13: message shot
    private static final int ID_MESSAGE_SHOT = 1300;
    // plus f14: profile ID / DC / registration date
    private static final int ID_F14_SHOW_DC = 1400;
    private static final int ID_F14_SHOW_REG_DATE = 1401;
    private static final int ID_SEND_PROMPT = 1600; // plus f16: + PlusSendPrompts.KIND_*
    // plus f17: network
    private static final int ID_PLUS_DOH = 1700;
    private static final int ID_PLUS_DOH_URL = 1701;
    private static final int ID_PLUS_PROXY_SWITCH = 1710;
    private static final int ID_PLUS_VPN_NO_PROXY = 1720;
    // plus f17 end
    private static final int ID_MESSAGE_DETAILS_MENU = 1;
    private static final int ID_HIDE_CHAT_KEYBOARD = 2;
    private static final int ID_HIDE_ALL_TAB = 3;
    private static final int ID_DEFAULT_FOLDER = 15;
    private static final int ID_USE_SYSTEM_FONT = 4;
    private static final int ID_SAVED_MESSAGES_HISTORY = 5;
    private static final int ID_CLEAR_SAVED_HISTORY = 6;
    private static final int ID_HIDE_STORIES = 7;
    private static final int ID_HIDE_PREMIUM_PROMO = 8;
    private static final int ID_DELETE_FOR_ALL_DEFAULT = 9;
    private static final int ID_REAR_ROUND_VIDEOS = 11;
    private static final int ID_DISABLE_LIVE_PHOTOS = 12;
    private static final int ID_DISABLE_LINK_PREVIEWS = 13;
    private static final int ID_PREFER_SECRET_CHATS = 14;
    private static final int ID_DISABLE_AUTO_UPDATE = 20;
    private static final int ID_ACCEPT_PRERELEASES = 21;
    private static final int ID_CHECK_FOR_UPDATES_NOW = 22;
    private static final int ID_UNIFIED_PUSH = 30;
    private static final int ID_REDUCE_TRACKING_FINGERPRINT = 40;
    private static final int ID_TOR_SETTINGS = 41;
    private static final int ID_DISABLE_GLOBAL_SEARCH = 44;
    private static final int ID_DISABLE_AI_EDITOR = 45;
    private static final int ID_DISABLE_AI_SUMMARY = 46;
    private static final int ID_DISABLE_INSTANT_VIEW = 47;
    private static final int ID_TRANSLATION = 50;
    private static final int ID_TRANSCRIPTION = 51;
    private static final int ID_EMOJI_PACK = 60;
    private static final int ID_STRIP_TRACKING_PARAMS = 61;
    private static final int ID_DISABLE_CLOUD_DRAFTS = 62;
    private static final int ID_CONFIRM_INTERNAL_LINKS = 63;
    private static final int ID_SHOW_CHAR_COUNTER = 64;
    private static final int ID_DISABLE_PROXIMITY_SENSOR = 65;
    // plus f07: vanished-chat log + deleted-message reactions (ids 700-799)
    private static final int ID_F07_LOG_DIALOGS = 700;
    private static final int ID_F07_OPEN_LOG = 701;
    private static final int ID_F07_KEEP_REACTIONS = 702;
    private static final int ID_PLUS_PLUGINS = 1100; // plus f11

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (HiddenAccountHelper.shouldShowSettingsEntry(currentAccount)) {
            int hiddenCount = 0;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (HiddenAccountHelper.isAccountHidden(a)) {
                    hiddenCount++;
                }
            }
            String value = hiddenCount > 0 ? Integer.toString(hiddenCount) : LocaleController.getString(R.string.PasswordOff);
            items.add(UItem.asButton(ID_HIDDEN_ACCOUNTS, R.drawable.msg2_secret, LocaleController.getString(R.string.HiddenAccounts), value));
            items.add(UItem.asShadow(null));
        }

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
        items.add(UItem.asCheck(ID_HIDE_ALL_TAB, LocaleController.getString(R.string.HideAllTab))
                .setChecked(getUserConfig().mg.hideAllTab));
        items.add(UItem.asButton(ID_DEFAULT_FOLDER, LocaleController.getString(R.string.MercurygramDefaultFolder), defaultFolderLabel()));
        items.add(UItem.asCheck(ID_HIDE_STORIES, LocaleController.getString(R.string.MercurygramHideStories))
                .setChecked(getUserConfig().mg.hideStories));
        // Mercurygram: hide premium upsell promo (opt-in, UI-only, no gate removed)
        items.add(UItem.asCheck(ID_HIDE_PREMIUM_PROMO, LocaleController.getString(R.string.MercurygramHidePremiumPromo))
                .setChecked(getUserConfig().mg.hidePremiumPromo));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramHidePremiumPromoAbout)));
        items.add(MgSettingsScope.globalCheck(ID_USE_SYSTEM_FONT, LocaleController.getString(R.string.MercurygramUseSystemFont))
                .setChecked(SharedConfig.useSystemFont));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramUseSystemFontAbout)));

        items.add(UItem.asCheck(ID_SHOW_CHAR_COUNTER, LocaleController.getString(R.string.MercurygramShowCharCounter))
                .setChecked(getUserConfig().mg.showCharCounter));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramShowCharCounterAbout)));

        // plus f04 begin
        items.add(UItem.asButton(ID_F04_MESSAGE_FILTERS, R.drawable.msg_archive_hide, LocaleController.getString(R.string.PlusF04Title),
                LocaleController.getString(it.belloworld.mercurygram.PlusMessageFilters.isEnabled() ? R.string.PlusF04FilterEnabled : R.string.PlusF04Off)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF04EnableInfo)));
        // plus f04 end

        items.add(UItem.asButton(ID_EMOJI_PACK,
                LocaleController.getString(R.string.MercurygramEmojiTitle),
                emojiPackShortLabel()));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                LocaleController.getString(R.string.MercurygramEmojiRowAbout))));

        items.add(UItem.asCheck(ID_DELETE_FOR_ALL_DEFAULT,
                        LocaleController.getString(R.string.MercurygramDeleteForAllByDefault))
                .setChecked(getUserConfig().mg.deleteForAllByDefault));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDeleteForAllByDefaultAbout)));

        items.add(UItem.asCheck(ID_SAVED_MESSAGES_HISTORY, LocaleController.getString(R.string.MercurygramSavedMessagesHistory))
                .setChecked(getUserConfig().mg.savedMessagesHistory));
        if (getUserConfig().mg.savedMessagesHistory) {
            items.add(UItem.asButton(ID_CLEAR_SAVED_HISTORY, LocaleController.getString(R.string.MercurygramClearSavedHistory), ""));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramSavedMessagesHistoryAbout)));

        // plus f07 begin: vanished-chat log + deleted-message reactions
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF07Header)));
        items.add(UItem.asCheck(ID_F07_LOG_DIALOGS, LocaleController.getString(R.string.PlusF07LogDialogs))
                .setChecked(it.belloworld.mercurygram.PlusDeletedDialogs.isEnabled(getCurrentAccount())));
        items.add(UItem.asButton(ID_F07_OPEN_LOG, LocaleController.getString(R.string.PlusF07DeletedDialogsTitle),
                Integer.toString(it.belloworld.mercurygram.PlusDeletedDialogs.count(getCurrentAccount()))));
        items.add(UItem.asCheck(ID_F07_KEEP_REACTIONS, LocaleController.getString(R.string.PlusF07KeepReactions))
                .setChecked(it.belloworld.mercurygram.PlusDeletedReactions.isKeepEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF07SettingsAbout)));
        // plus f07 end

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsMedia)));
        items.add(UItem.asCheck(ID_REAR_ROUND_VIDEOS, LocaleController.getString(R.string.RearRoundVideos))
                .setChecked(getUserConfig().mg.rearRoundCamera));
        items.add(UItem.asCheck(ID_DISABLE_LIVE_PHOTOS, LocaleController.getString(R.string.MercurygramDisableLivePhotos))
                .setChecked(getUserConfig().mg.disableLivePhotosByDefault));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableLivePhotosAbout)));
        items.add(MgSettingsScope.globalCheck(ID_DISABLE_PROXIMITY_SENSOR, LocaleController.getString(R.string.MercurygramDisableProximitySensor))
                .setChecked(SharedConfig.mg_disableProximitySensor));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableProximitySensorAbout)));

        // plus f13: message shot (all accounts)
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusMessageShot)));
        items.add(MgSettingsScope.globalCheck(ID_MESSAGE_SHOT, LocaleController.getString(R.string.PlusMessageShotSetting))
                .setChecked(it.belloworld.mercurygram.PlusMessageShot.isEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusMessageShotSettingAbout)));
        // plus f13 end

        // plus f16: ask before sending voice / round / sticker / GIF and before calls (all accounts)
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusPromptHeader)));
        for (int k = 0; k < it.belloworld.mercurygram.PlusSendPrompts.KIND_COUNT; k++) {
            items.add(UItem.asCheck(ID_SEND_PROMPT + k, it.belloworld.mercurygram.PlusSendPrompts.label(k))
                    .setChecked(it.belloworld.mercurygram.PlusSendPrompts.isEnabled(k)));
        }
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(LocaleController.getString(R.string.PlusPromptAbout))));
        // plus f16 end

        // plus: ghost mode (this account)
        final int acc = getCurrentAccount();
        final boolean ghostOn = it.belloworld.mercurygram.PlusGhost.isEnabled(acc);
        items.add(UItem.asHeader("Ghost mode"));
        items.add(UItem.asCheck(ID_GHOST_ON, "Ghost mode").setChecked(ghostOn));
        if (ghostOn) {
            String[] ghostLabels = {"Don't send read receipts", "Don't send typing status", "Stay offline", "Don't mark stories as seen",
                    "Read the chat when I reply, react or vote", "Force offline when shown online (blinks if another device is open)"};
            for (int i = 0; i < ghostLabels.length; i++) {
                items.add(UItem.asCheck(ID_GHOST_OPT + i, ghostLabels[i])
                        .setChecked(it.belloworld.mercurygram.PlusGhost.isHidden(acc, i)));
            }
            // plus f09: send without sound while ghost mode is on
            items.add(UItem.asCheck(ID_GHOST_SEND_SILENT, LocaleController.getString(R.string.PlusGhostSendSilent))
                    .setChecked(it.belloworld.mercurygram.PlusGhostSilent.isEnabled(acc)));
            // plus f09 end
        }
        items.add(UItem.asShadow("Applies to this account. Mark as read (long-press a chat, or the notification button) sends a read receipt for that chat only; for a chat that already looks read, mark it unread first. With \"Read the chat when I reply\", replying, reacting or voting in a chat reads it too, as a normal client would. Stay offline never sends online and sends nothing on app start; it only sends one offline right after your own sends, reactions, votes, edits or calls (or on the next start, if the app was closed within 5 minutes of one). Channel views are not counted. Not covered: reacting to or replying to a story marks it seen, and Premium voice-to-text marks a voice message as listened (server side)."));
        // plus f01 begin: per-chat ghost exceptions
        if (ghostOn) {
            int plusExcCount = it.belloworld.mercurygram.PlusGhostExceptions.count(acc);
            items.add(UItem.asButton(ID_PLUS_GHOST_EXCEPTIONS, R.drawable.msg_secret,
                    LocaleController.getString(R.string.PlusGhostExcTitle),
                    plusExcCount > 0 ? Integer.toString(plusExcCount) : LocaleController.getString(R.string.PlusGhostExcNone)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusGhostExcSettingsInfo)));
        }
        // plus f01 end
        // plus f03 begin: ghost "send as scheduled"
        if (ghostOn) {
            items.add(UItem.asCheck(it.belloworld.mercurygram.PlusScheduledSend.SETTINGS_ROW_ID, LocaleController.getString(R.string.PlusF03ScheduledSend))
                    .setChecked(it.belloworld.mercurygram.PlusScheduledSend.isEnabled(acc)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF03ScheduledSendInfo)));
        }
        // plus f03 end

        // plus f05 begin
        PlusActivityLogActivity.fillSettings(items, getCurrentAccount());
        // plus f05 end

        // plus f06 begin
        it.belloworld.mercurygram.PlusStoryGuard.addSettingsItems(items, acc);
        // plus f06 end

        // plus f08 begin: chat lock + hidden chats
        items.add(UItem.asButton(it.belloworld.mercurygram.PlusChatLock.SETTINGS_ROW_ID, R.drawable.msg_secret,
                LocaleController.getString(R.string.PlusF08Title),
                it.belloworld.mercurygram.PlusChatLock.settingsSummary(getCurrentAccount())));
        items.add(UItem.asShadow(null));
        // plus f08 end

        // plus f10: streamer mode (all accounts)
        final boolean streamerOn = it.belloworld.mercurygram.PlusStreamer.isEnabled();
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF10StreamerMode)));
        items.add(MgSettingsScope.globalCheck(ID_STREAMER_ON, LocaleController.getString(R.string.PlusF10StreamerMode)).setChecked(streamerOn));
        final int[] streamerLabels = {R.string.PlusF10HideNames, R.string.PlusF10HideChatTitles, R.string.PlusF10HideAvatars,
                R.string.PlusF10HidePhones, R.string.PlusF10HideNotifications, R.string.PlusF10FlagSecure};
        for (int i = 0; i < streamerLabels.length; i++) {
            items.add(MgSettingsScope.globalCheck(ID_STREAMER_OPT + i, LocaleController.getString(streamerLabels[i]))
                    .setChecked(it.belloworld.mercurygram.PlusStreamer.getOption(i)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF10About)));
        // plus f10 end

        // plus f12 begin
        it.belloworld.mercurygram.PlusPeek.addSettingsItems(items);
        // plus f12 end

        // plus f14: profile ID / DC / registration date
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF14Header)));
        items.add(UItem.asCheck(ID_F14_SHOW_DC, LocaleController.getString(R.string.PlusF14ShowDc))
                .setChecked(it.belloworld.mercurygram.PlusProfileInfo.isShowDc()));
        items.add(UItem.asCheck(ID_F14_SHOW_REG_DATE, LocaleController.getString(R.string.PlusF14ShowRegDate))
                .setChecked(it.belloworld.mercurygram.PlusProfileInfo.isShowRegDate()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF14About)));
        // plus f14 end

        // plus f15 begin
        it.belloworld.mercurygram.PlusEmojiInteractions.addSettingsItems(items, acc);
        // plus f15 end

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

        // Tor lives on its own screen so the proxy list can reach it too
        // (that screen is available before login, where Settings is not).
        if (!it.belloworld.mercurygram.tor.MgTorClient.isFdroidPreS()) {
            items.add(UItem.asButton(ID_TOR_SETTINGS,
                    LocaleController.getString(R.string.MercurygramTor),
                    LocaleController.getString(SharedConfig.mg_useTor
                            ? R.string.NotificationsOn : R.string.NotificationsOff)));
            items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                    LocaleController.getString(R.string.MercurygramTorAbout))));
        }

        items.add(UItem.asCheck(ID_DISABLE_GLOBAL_SEARCH,
                        LocaleController.getString(R.string.MercurygramDisableGlobalSearch))
                .setChecked(getUserConfig().mg.disableGlobalSearch));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableGlobalSearchAbout)));

        items.add(UItem.asCheck(ID_DISABLE_AI_EDITOR,
                        LocaleController.getString(R.string.MercurygramDisableAiEditor))
                .setChecked(getUserConfig().mg.disableAiEditor));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableAiEditorAbout)));

        items.add(UItem.asCheck(ID_DISABLE_AI_SUMMARY,
                        LocaleController.getString(R.string.MercurygramDisableAiSummary))
                .setChecked(getUserConfig().mg.disableAiSummary));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableAiSummaryAbout)));

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

        items.add(UItem.asButton(ID_TRANSLATION,
                LocaleController.getString(R.string.MercurygramTranslationSettings),
                translationModeShortLabel()));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                LocaleController.getString(R.string.MercurygramTranslationRowAbout))));

        items.add(UItem.asButton(ID_TRANSCRIPTION,
                LocaleController.getString(R.string.MercurygramTranscriptionTitle),
                transcriptionShortLabel()));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                LocaleController.getString(R.string.MercurygramTranscriptionEnableInfo))));

        // plus f17: custom DoH, proxy auto-switch, disable proxy on VPN (app-wide)
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusNetworkHeader)));
        items.add(UItem.asCheck(ID_PLUS_DOH, LocaleController.getString(R.string.PlusDohEnable))
                .setChecked(it.belloworld.mercurygram.PlusDoh.isEnabled()));
        if (it.belloworld.mercurygram.PlusDoh.isEnabled()) {
            items.add(UItem.asButton(ID_PLUS_DOH_URL, LocaleController.getString(R.string.PlusDohResolver),
                    it.belloworld.mercurygram.PlusDoh.getLabel()));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusDohAbout)));
        items.add(UItem.asCheck(ID_PLUS_PROXY_SWITCH, LocaleController.getString(R.string.PlusProxyAutoSwitch))
                .setChecked(it.belloworld.mercurygram.PlusProxySwitch.isEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusProxyAutoSwitchAbout)));
        items.add(UItem.asCheck(ID_PLUS_VPN_NO_PROXY, LocaleController.getString(R.string.PlusVpnDisableProxy))
                .setChecked(it.belloworld.mercurygram.PlusVpnProxy.isEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(
                it.belloworld.mercurygram.PlusVpnProxy.shouldBypass() ? R.string.PlusVpnDisableProxyActive : R.string.PlusVpnDisableProxyAbout)));
        // plus f17 end

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

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsNotifications)));
        CharSequence pushValue;
        if (SharedConfig.disableUnifiedPush) {
            pushValue = LocaleController.getString(R.string.NotificationsOff);
        } else {
            String distributor = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
            if (distributor == null) {
                distributor = UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext);
            }
            pushValue = distributor != null
                    ? MgEmbeddedFcmDistributor.label(distributor)
                    : LocaleController.getString(R.string.NotSet);
        }
        items.add(UItem.asButton(ID_UNIFIED_PUSH, LocaleController.getString(R.string.MercurygramUnifiedPush), pushValue));
        items.add(UItem.asShadow(null));

        // plus f11: plugins
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF11Plugins)));
        items.add(UItem.asButton(ID_PLUS_PLUGINS, LocaleController.getString(R.string.PlusF11Plugins), it.belloworld.mercurygram.PlusPlugins.summary()));
        items.add(UItem.asShadow(null));
        // plus f11 end
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_PLUS_GHOST_EXCEPTIONS) { // plus f01
            presentFragment(new PlusGhostExceptionsActivity());
            return;
        }
        // plus f03 begin
        if (item.id == it.belloworld.mercurygram.PlusScheduledSend.SETTINGS_ROW_ID) {
            it.belloworld.mercurygram.PlusScheduledSend.setEnabled(getCurrentAccount(), !it.belloworld.mercurygram.PlusScheduledSend.isEnabled(getCurrentAccount()));
            refreshList();
            return;
        }
        // plus f03 end
        if (item.id == ID_F04_MESSAGE_FILTERS) { // plus f04: message filters
            presentFragment(new PlusMessageFiltersActivity());
            return;
        }
        // plus f05 begin
        if (PlusActivityLogActivity.onSettingsClick(this, item, this::refreshList)) {
            return;
        }
        // plus f05 end
        // plus f08 begin
        if (item.id == it.belloworld.mercurygram.PlusChatLock.SETTINGS_ROW_ID) {
            it.belloworld.mercurygram.PlusChatLock.openSettings(this);
            return;
        }
        // plus f08 end
        if (item.id == ID_PLUS_PLUGINS) { // plus f11
            presentFragment(new PlusPluginsActivity());
            return;
        }
        // plus f12 begin
        if (it.belloworld.mercurygram.PlusPeek.onSettingsClick(item.id, this::refreshList)) {
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
        if (it.belloworld.mercurygram.PlusEmojiInteractions.onSettingsClick(getCurrentAccount(), item.id, this::refreshList)) {
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
        // plus f17: network
        if (item.id == ID_PLUS_DOH) {
            it.belloworld.mercurygram.PlusDoh.setEnabled(!it.belloworld.mercurygram.PlusDoh.isEnabled());
            refreshList();
            return;
        }
        if (item.id == ID_PLUS_DOH_URL) {
            showPlusDohPicker();
            return;
        }
        if (item.id == ID_PLUS_PROXY_SWITCH) {
            it.belloworld.mercurygram.PlusProxySwitch.setEnabled(!it.belloworld.mercurygram.PlusProxySwitch.isEnabled());
            refreshList();
            return;
        }
        if (item.id == ID_PLUS_VPN_NO_PROXY) {
            it.belloworld.mercurygram.PlusVpnProxy.setEnabled(!it.belloworld.mercurygram.PlusVpnProxy.isEnabled());
            refreshList();
            return;
        }
        // plus f17 end
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
        if (it.belloworld.mercurygram.PlusStoryGuard.onSettingsClick(getParentActivity(), getCurrentAccount(), item.id, this::refreshList)) {
            return;
        }
        // plus f06 end
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
            it.belloworld.mercurygram.PlusStreamer.toggle(this);
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
        switch (item.id) {
            case ID_HIDDEN_ACCOUNTS:
                presentFragment(new HiddenAccountsActivity());
                break;
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
            case ID_HIDE_ALL_TAB:
                getUserConfig().mg.hideAllTab = !getUserConfig().mg.hideAllTab;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DEFAULT_FOLDER:
                handleDefaultFolderClick();
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
            // Mercurygram: hide premium upsell promo (opt-in, UI-only, no gate removed)
            case ID_HIDE_PREMIUM_PROMO:
                getUserConfig().mg.hidePremiumPromo = !getUserConfig().mg.hidePremiumPromo;
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
            case ID_REAR_ROUND_VIDEOS:
                getUserConfig().mg.rearRoundCamera = !getUserConfig().mg.rearRoundCamera;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DISABLE_LIVE_PHOTOS:
                getUserConfig().mg.disableLivePhotosByDefault = !getUserConfig().mg.disableLivePhotosByDefault;
                getUserConfig().saveConfig(false);
                MediaController.refreshLivePhotoDefault();
                refreshList();
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
            case ID_UNIFIED_PUSH:
                presentFragment(new MgUnifiedPushSettingsActivity());
                break;
            case ID_REDUCE_TRACKING_FINGERPRINT:
                handleReduceTrackingFingerprintClick();
                break;
            case ID_TOR_SETTINGS:
                presentFragment(new MgTorSettingsActivity());
                break;
            case ID_DISABLE_GLOBAL_SEARCH:
                getUserConfig().mg.disableGlobalSearch = !getUserConfig().mg.disableGlobalSearch;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DISABLE_AI_EDITOR:
                getUserConfig().mg.disableAiEditor = !getUserConfig().mg.disableAiEditor;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DISABLE_AI_SUMMARY:
                getUserConfig().mg.disableAiSummary = !getUserConfig().mg.disableAiSummary;
                getUserConfig().saveConfig(false);
                refreshList();
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
            case ID_TRANSCRIPTION:
                presentFragment(new MercurygramTranscriptionSettingsActivity());
                break;
            case ID_TRANSLATION:
                presentFragment(new MercurygramTranslationSettingsActivity());
                break;
            case ID_EMOJI_PACK:
                presentFragment(new MercurygramEmojiSettingsActivity());
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

    // Subtitle for the Voice-transcription row, mirroring translationModeShortLabel():
    // "Off" when disabled, otherwise the selected model tier so the active choice
    // is visible without opening the sub-screen.
    private static String transcriptionShortLabel() {
        if (!SharedConfig.mg_transcribeOffline) {
            return LocaleController.getString(R.string.MercurygramTranscriptionRowDisabled);
        }
        switch (MgWhisperModel.selected()) {
            case BASE:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelBase);
            case SMALL:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelSmall);
            case TINY:
            default:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelTiny);
        }
    }

    private static String translationModeShortLabel() {
        String mode = SharedConfig.mg_translateMode;
        if (mode == null) mode = SharedConfig.MG_TRANSLATE_MODE_DEFAULT;
        switch (mode) {
            case SharedConfig.MG_TRANSLATE_MODE_CLOUD:
                return LocaleController.getString(R.string.MercurygramTranslationModeCloud);
            case SharedConfig.MG_TRANSLATE_MODE_ALTERNATIVE:
                return LocaleController.getString(R.string.MercurygramTranslationModeAlternative);
            case SharedConfig.MG_TRANSLATE_MODE_OFFLINE:
                return LocaleController.getString(R.string.MercurygramTranslationModeOffline);
            default:
                return LocaleController.getString(R.string.MercurygramTranslationModeDefault);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        // The Translation row's subtitle reflects mg_translateMode, which the
        // user can change in the sub-screen. Refresh on return so the new
        // engine label shows immediately instead of after a full reopen.
        refreshList();
    }

    private void refreshList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
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
            positive.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private String defaultFolderLabel() {
        final int defaultFolderId = getUserConfig().mg.defaultFolderId;
        if (defaultFolderId != 0) {
            final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
            for (int a = 0; a < filters.size(); a++) {
                final MessagesController.DialogFilter filter = filters.get(a);
                if (!filter.isDefault() && filter.id == defaultFolderId && !filter.locked) {
                    return filter.name;
                }
            }
        }
        return LocaleController.getString(R.string.FilterAllChats);
    }

    private void handleDefaultFolderClick() {
        Context context = getParentActivity();
        if (context == null) return;
        // getDialogFilters() is kept sorted by order with the default "All chats"
        // filter at index 0, so the dialog already lists All chats first.
        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        final int current = getUserConfig().mg.defaultFolderId;
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < filters.size(); i++) {
            final MessagesController.DialogFilter filter = filters.get(i);
            // Locked (over-limit) folders are skipped by both honouring paths
            // (mgDefaultFolderStableId / first-build auto-select), so don't offer
            // them as selectable defaults: picking one would be silently ignored.
            if (filter.locked) {
                continue;
            }
            final int chosenId = filter.id;
            String label = filter.isDefault()
                    ? LocaleController.getString(R.string.FilterAllChats)
                    : filter.name;
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(label, chosenId == current);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                getUserConfig().mg.defaultFolderId = chosenId;
                getUserConfig().saveConfig(false);
                refreshList();
                Dialog d = dialogRef.get();
                if (d != null) d.dismiss();
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramDefaultFolder))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
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
                    MgMessageHistory.getInstance().clearAll();
                    refreshList();
                })
                .show();
    }

    // plus f17: DoH resolver picker (presets + custom url), then a one-shot test lookup
    private void showPlusDohPicker() {
        Context context = getParentActivity();
        if (context == null) return;
        final String current = it.belloworld.mercurygram.PlusDoh.getUrl();
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        boolean isPreset = false;
        int count = it.belloworld.mercurygram.PlusDoh.PRESET_URLS.length;
        for (int i = 0; i <= count; i++) {
            final boolean custom = i == count;
            final String url = custom ? null : it.belloworld.mercurygram.PlusDoh.PRESET_URLS[i];
            boolean checked = !custom && url.equals(current);
            isPreset |= checked;
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(custom ? LocaleController.getString(R.string.PlusDohCustom)
                    : it.belloworld.mercurygram.PlusDoh.PRESET_NAMES[i], custom ? !isPreset : checked);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                Dialog d = dialogRef.get();
                if (d != null) d.dismiss();
                if (custom) {
                    showPlusDohCustom();
                } else {
                    applyPlusDohUrl(url);
                }
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.PlusDohResolver))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private void showPlusDohCustom() {
        Context context = getParentActivity();
        if (context == null) return;
        org.telegram.ui.Components.EditTextBoldCursor editText = new org.telegram.ui.Components.EditTextBoldCursor(context);
        editText.setSingleLine(true);
        editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint(LocaleController.getString(R.string.PlusDohCustomHint));
        editText.setText(it.belloworld.mercurygram.PlusDoh.getUrl());
        editText.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.PlusDohResolver))
                .setView(editText)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.OK), (d, which) -> {
                    String url = it.belloworld.mercurygram.PlusDoh.normalizeUrl(editText.getText().toString());
                    if (url == null) {
                        Toast.makeText(context, LocaleController.getString(R.string.PlusDohInvalid), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyPlusDohUrl(url);
                })
                .show();
    }

    private void applyPlusDohUrl(String url) {
        if (!it.belloworld.mercurygram.PlusDoh.setUrl(url)) return;
        refreshList();
        final Context appContext = ApplicationLoader.applicationContext;
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            String ip = it.belloworld.mercurygram.PlusDoh.test(url);
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(appContext, ip != null
                    ? LocaleController.formatString(R.string.PlusDohTestOk, ip)
                    : LocaleController.getString(R.string.PlusDohTestFail), Toast.LENGTH_LONG).show());
        });
    }
    // plus f17 end
}
