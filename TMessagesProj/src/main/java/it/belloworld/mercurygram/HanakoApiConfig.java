/*
 * hanako: which Telegram app identity (api_id / api_hash) the client connects as. Global, not
 * per account: tgnet runs one identity per process. Read once at process start by
 * ApplicationLoader (before the native ConnectionsManager inits), written into BuildVars.APP_ID /
 * APP_HASH, which every caller already reads (ConnectionsManager.init -> initConnection.api_id,
 * auth.sendCode, auth.exportLoginToken, passkeys). A change therefore applies at the next start.
 */
package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;
import java.util.regex.Pattern;

public final class HanakoApiConfig {

    /** Global prefs file (deliberately not "mainconfig" + account). */
    public static final String PREFS = "hanako_api";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_CUSTOM_ID = "custom_id";
    private static final String KEY_CUSTOM_HASH = "custom_hash";

    public static final int PRESET_BUILD = 0;
    public static final int PRESET_GOREGRAM = 1;
    public static final int PRESET_MERCURYGRAM = 2;
    public static final int PRESET_OFFICIAL = 3;
    public static final int PRESET_CUSTOM = 4;

    /*
     * Preset sources:
     *  Goregram 105810: the pair Hanako's CI builds with (.github/workflows/hanako.yml, build step).
     *  Mercurygram 575730: upstream Mercurygram's F-Droid recipe (.github/fdroid/recipe-overlay.yml).
     *  Telegram 4: DrKLO/Telegram BuildVars.java (official Telegram for Android).
     */
    private static final int[] PRESET_IDS = {0, 105810, 575730, 4};
    private static final String[] PRESET_HASHES = {null,
            "3e7a52498eec003c5896a330e5d29397",
            "723c7927097f8487d229438af766e329",
            "014b35b6184100b085b0d0572f9b5103"};

    private static final Pattern HASH = Pattern.compile("^[0-9a-fA-F]{32}$");

    private static boolean applied;
    private static int activePreset = PRESET_BUILD;
    private static int activeId = BuildConfig.APP_ID;

    private HanakoApiConfig() {
    }

    private static SharedPreferences prefs(Context ctx) {
        Context c = ctx != null ? ctx : ApplicationLoader.applicationContext;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Called once per process from ApplicationLoader.onCreate, before tgnet starts. */
    public static synchronized void applyAtStartup(Context ctx) {
        if (applied) return;
        applied = true;
        int preset = PRESET_BUILD;
        int id = BuildConfig.APP_ID;
        String hash = BuildConfig.APP_HASH;
        try {
            SharedPreferences p = prefs(ctx);
            preset = p.getInt(KEY_PRESET, PRESET_BUILD);
            if (preset >= PRESET_GOREGRAM && preset <= PRESET_OFFICIAL) {
                id = PRESET_IDS[preset];
                hash = PRESET_HASHES[preset];
            } else if (preset == PRESET_CUSTOM) {
                int cid = p.getInt(KEY_CUSTOM_ID, 0);
                String ch = p.getString(KEY_CUSTOM_HASH, null);
                if (isValidId(cid) && isValidHash(ch)) {
                    id = cid;
                    hash = ch.toLowerCase(Locale.US);
                } else {
                    preset = PRESET_BUILD;
                }
            } else {
                preset = PRESET_BUILD;
            }
        } catch (Throwable t) {
            preset = PRESET_BUILD;
            id = BuildConfig.APP_ID;
            hash = BuildConfig.APP_HASH;
            FileLog.e(t);
        }
        BuildVars.APP_ID = id;
        BuildVars.APP_HASH = hash;
        activePreset = preset;
        activeId = id;
    }

    /**
     * Called by each account's ConnectionsManager right after native init. tgnet re-sends
     * initConnection (which carries api_id) only when its persisted lastInitVersion is stale (app
     * version, system language or temp key change), so a new api_id alone would not reach the
     * server for an account that is already signed in, nor for a session restored from a backup
     * made under another api_id. When the identity this account last started with differs (or
     * is unknown), rotate the temp auth keys once: that resets lastInitVersion, so the next
     * request is wrapped in initConnection with the active api_id. The perm key is untouched.
     */
    public static void afterNativeInit(org.telegram.tgnet.ConnectionsManager cm, int account, long userId) {
        try {
            String signature = activeId + ":" + BuildVars.APP_HASH + ":" + userId;
            SharedPreferences p = prefs(null);
            String key = "inited_" + account;
            if (signature.equals(p.getString(key, null))) return;
            p.edit().putString(key, signature).apply();
            if (userId != 0) cm.rotateTempAuthKeys();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static boolean isValidId(int id) {
        return id > 0;
    }

    public static boolean isValidHash(String hash) {
        return hash != null && HASH.matcher(hash).matches();
    }

    /** Parses a typed api_id; 0 when not a positive int. */
    public static int parseId(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.isEmpty() || s.length() > 10) return 0;
        try {
            long v = Long.parseLong(s);
            return v > 0 && v <= Integer.MAX_VALUE ? (int) v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int getActiveId() {
        return activeId;
    }

    public static int getActivePreset() {
        return activePreset;
    }

    /** Preset saved for the next start (custom without a valid pair counts as build). */
    public static int getSavedPreset() {
        int preset = prefs(null).getInt(KEY_PRESET, PRESET_BUILD);
        if (preset == PRESET_CUSTOM && !hasCustom()) return PRESET_BUILD;
        return preset >= PRESET_BUILD && preset <= PRESET_CUSTOM ? preset : PRESET_BUILD;
    }

    public static boolean hasCustom() {
        SharedPreferences p = prefs(null);
        return isValidId(p.getInt(KEY_CUSTOM_ID, 0)) && isValidHash(p.getString(KEY_CUSTOM_HASH, null));
    }

    public static int getCustomId() {
        return prefs(null).getInt(KEY_CUSTOM_ID, 0);
    }

    public static String getCustomHash() {
        String h = prefs(null).getString(KEY_CUSTOM_HASH, "");
        return h != null ? h : "";
    }

    /** api_id a preset stands for (custom: the saved custom id, 0 if none). */
    public static int idOf(int preset) {
        if (preset == PRESET_BUILD) return BuildConfig.APP_ID;
        if (preset == PRESET_CUSTOM) return getCustomId();
        return PRESET_IDS[preset];
    }

    /** The api_id the next start will use. */
    public static int getSavedId() {
        return idOf(getSavedPreset());
    }

    public static boolean restartPending() {
        return getSavedId() != activeId || !TextUtils.equals(savedHash(), BuildVars.APP_HASH);
    }

    private static String savedHash() {
        int preset = getSavedPreset();
        if (preset == PRESET_BUILD) return BuildConfig.APP_HASH;
        if (preset == PRESET_CUSTOM) return getCustomHash().toLowerCase(Locale.US);
        return PRESET_HASHES[preset];
    }

    /** Saves a preset (not custom). commit(): a restart (System.exit) may follow at once. */
    public static void savePreset(int preset) {
        if (preset < PRESET_BUILD || preset > PRESET_OFFICIAL) return;
        prefs(null).edit().putInt(KEY_PRESET, preset).commit();
    }

    /** Saves and selects a custom pair. Returns false (nothing saved) when invalid. */
    public static boolean saveCustom(int id, String hash) {
        if (!isValidId(id) || !isValidHash(hash)) return false;
        prefs(null).edit()
                .putInt(KEY_CUSTOM_ID, id)
                .putString(KEY_CUSTOM_HASH, hash.toLowerCase(Locale.US))
                .putInt(KEY_PRESET, PRESET_CUSTOM)
                .commit();
        return true;
    }

    /** "105810", or "105810 → 4" when a different id waits for a restart. */
    public static String summary() {
        if (restartPending()) {
            return LocaleController.formatString(R.string.HanakoApiSummaryPending, activeId, getSavedId());
        }
        return Integer.toString(activeId);
    }

    /** Adds "API ID: 105810" under the phone input of the login screen. Returns the height used (dp). */
    public static int addLoginButton(LinearLayout parent, BaseFragment fragment) {
        TextView button = new TextView(parent.getContext());
        button.setText(LocaleController.formatString(R.string.HanakoApiLoginButton, summary()));
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        int pad = AndroidUtilities.dp(8);
        button.setPadding(pad, pad, pad, pad);
        button.setMinHeight(AndroidUtilities.dp(48)); // touch target
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(v -> fragment.presentFragment(new it.belloworld.mercurygram.ui.HanakoApiActivity()));
        parent.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
        return 48;
    }
}
