package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import it.belloworld.mercurygram.ui.MercurygramSettingsActivity;

/**
 * hanako: small chat-list helpers.
 * <ul>
 * <li>Streamer mode indicator in the chat list action bar (P2-8), same pattern as the ghost
 *     indicator: visible while streamer mode is on, tap opens Turn off / Settings.</li>
 * <li>One-time "Ghost mode is on" notice per account after login (P1-6), with a quick toggle.</li>
 * <li>The once-only result of a restore / settings import staged before a restart (P2-3).</li>
 * </ul>
 * UI state lives in its own "hanako_ui" preferences file, which settings export never includes.
 */
public final class HanakoQuick {

    private static final int STREAMER_INDICATOR_ID = 7604;
    private static final String UI_PREFS = "hanako_ui";
    private static final String KEY_INTRO_PREFIX = "privacy_intro_";

    private static final class Indicator {
        // weak: the item's click listener captures the fragment, which is this map entry's key
        final java.lang.ref.WeakReference<ActionBarMenuItem> item;
        float factor = 1f;

        Indicator(ActionBarMenuItem item) {
            this.item = new java.lang.ref.WeakReference<>(item);
        }
    }

    private static final Map<BaseFragment, Indicator> indicators = new WeakHashMap<>();
    private static SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    private HanakoQuick() {
    }

    private static SharedPreferences ui() {
        return ApplicationLoader.applicationContext.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ streamer indicator

    public static void addStreamerIndicator(BaseFragment f, ActionBarMenu menu) {
        if (f == null || menu == null) return;
        try {
            ActionBarMenuItem item = menu.addItem(STREAMER_INDICATOR_ID, R.drawable.msg_screencast);
            item.setContentDescription(LocaleController.getString(R.string.HanakoStreamerIndicator));
            item.setOnClickListener(v -> showStreamerMenu(f, v));
            item.setOnLongClickListener(v -> {
                openStreamerSettings(f);
                return true;
            });
            Indicator ind = new Indicator(item);
            synchronized (indicators) {
                indicators.put(f, ind);
            }
            ensurePrefsListener();
            apply(ind);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void updateStreamerIndicator(BaseFragment f, float factor) {
        Indicator ind;
        synchronized (indicators) {
            ind = indicators.get(f);
        }
        if (ind != null) {
            ind.factor = factor;
            apply(ind);
        }
    }

    private static void apply(Indicator ind) {
        ActionBarMenuItem item = ind.item.get();
        if (item != null) FragmentFloatingButton.setAnimatedVisibility(item, PlusStreamer.isEnabled() ? ind.factor : 0f);
    }

    private static void refreshIndicators() {
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<Indicator> list;
            synchronized (indicators) {
                list = new ArrayList<>(indicators.values());
            }
            for (Indicator ind : list) apply(ind);
        });
    }

    private static synchronized void ensurePrefsListener() {
        if (prefsListener != null) return;
        prefsListener = (prefs, key) -> {
            if (key == null || "enabled".equals(key)) refreshIndicators();
        };
        ApplicationLoader.applicationContext.getSharedPreferences("plus_f10", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private static void openStreamerSettings(BaseFragment f) {
        f.presentFragment(new MercurygramSettingsActivity(MercurygramSettingsActivity.PAGE_STREAMER));
    }

    private static void showStreamerMenu(BaseFragment f, View anchor) {
        try {
            ItemOptions io = ItemOptions.makeOptions(f, anchor);
            int color = Theme.getColor(Theme.key_actionBarDefaultTitle, f.getResourceProvider());
            io.setColors(color, color);
            io.addText(LocaleController.getString(R.string.HanakoStreamerIsOn), 13);
            io.addGap();
            io.add(R.drawable.msg_screencast_off, LocaleController.getString(R.string.HanakoStreamerTurnOff), () -> {
                PlusStreamer.setEnabled(false, f);
                refreshIndicators();
            });
            io.add(R.drawable.msg_settings_old, LocaleController.getString(R.string.HanakoStreamerSettings), () -> openStreamerSettings(f));
            io.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------ chat list resume

    /** From DialogsActivity.onResume (main chat list only). */
    public static void onDialogsResume(BaseFragment f) {
        if (f == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (f.getParentActivity() == null || f.isPaused() || f.isRemovingFromStack() || f.getParentLayout() == null
                    || f.getParentLayout().getLastFragment() != f || f.getVisibleDialog() != null) {
                return;
            }
            HanakoBackup.showPendingResult(f);
            if (f.getVisibleDialog() == null) {
                maybeShowPrivacyIntro(f);
            }
        }, 900);
    }

    private static void maybeShowPrivacyIntro(BaseFragment f) {
        final int account = f.getCurrentAccount();
        UserConfig uc = UserConfig.getInstance(account);
        if (!uc.isClientActivated()) return;
        final String key = KEY_INTRO_PREFIX + uc.getClientUserId();
        if (ui().getBoolean(key, false)) return;
        ui().edit().putBoolean(key, true).apply();
        final boolean ghost = PlusGhost.isEnabled(account);
        StringBuilder msg = new StringBuilder();
        if (ghost) {
            msg.append(LocaleController.getString(R.string.HanakoIntroGhost));
        } else {
            msg.append(LocaleController.getString(R.string.HanakoIntroGhostOff));
        }
        if (uc.mg.savedMessagesHistory) {
            msg.append("\n\n").append(LocaleController.getString(R.string.HanakoIntroDeleted));
        }
        if (uc.mg.hidePremiumPromo) {
            msg.append("\n\n").append(LocaleController.getString(R.string.HanakoIntroPromo));
        }
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(f.getParentActivity(), f.getResourceProvider());
            b.setTitle(LocaleController.getString(ghost ? R.string.HanakoIntroTitle : R.string.HanakoIntroTitleOff));
            b.setMessage(msg.toString());
            if (ghost) {
                b.setPositiveButton(LocaleController.getString(R.string.HanakoIntroKeep), null);
                b.setNegativeButton(LocaleController.getString(R.string.HanakoIntroTurnOff), (d, w) -> {
                    PlusGhost.setEnabled(account, false);
                    try {
                        BulletinFactory.of(f).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.PlusGhostModeOff)).show();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                });
            } else {
                b.setPositiveButton(LocaleController.getString(R.string.OK), null);
            }
            b.setNeutralButton(LocaleController.getString(R.string.PlusGhostSettings), (d, w) ->
                    f.presentFragment(new MercurygramSettingsActivity(MercurygramSettingsActivity.PAGE_GHOST)));
            f.showDialog(b.create());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
