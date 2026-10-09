package it.belloworld.mercurygram;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * hanako: Backup &amp; export.
 *
 * <p><b>Settings export</b>: every Hanako/Mercurygram preference (the {@code mg_*} keys of the
 * global "userconfing" file, the plus_* feature preference files, and the per-account MG keys,
 * matched by Telegram user id) as a JSON document with typed values.
 *
 * <p><b>Full backup</b>: per logged-in account the native session file (tgnet.dat: auth keys,
 * DC config) plus the few UserConfig keys that make the slot "activated" (the serialized current
 * user), optionally with the settings above, zipped and encrypted with a password:
 * PBKDF2-HMAC-SHA256 (400k iterations, 32-byte random salt) -&gt; AES-256-GCM (random 12-byte
 * nonce, header authenticated as AAD). The format only uses paths relative to the account slot,
 * so a backup made by one package id restores into any other build of this feature.
 *
 * <p><b>Restore</b> never touches live files: the import is staged under files/hanako_restore and
 * applied from {@link #applyPendingAtStartup(Context)} at the very start of the next process,
 * before SharedConfig, UserConfig or the native ConnectionsManager have read anything, then the
 * app is restarted. Restored slots get a fresh message cache (cache4.db is removed), so dialogs
 * re-sync from the server; secret chats are not carried over.
 *
 * <p>The password is kept in a char[] / byte[] and wiped after the key derivation where this
 * code controls the copy; it is never logged or stored. Copies made by the platform (the text
 * field, SecretKeySpec, a staged session file in the app's private storage until the next start)
 * are outside that promise.
 *
 * <p>Per-account feature settings (ghost mode, activity log) are keyed by Telegram user id
 * ("on_u12345", see PlusUtil.accountKey), so they follow the account to any slot.
 */
public final class HanakoBackup {

    private static final String TAG = "HanakoBackup";

    // ---- settings JSON ----
    public static final String SETTINGS_FORMAT = "hanako-settings";
    /** v2: preferences and per-chat lists in separate sections (v1 files are still read). */
    public static final int SETTINGS_VERSION = 2;

    // ---- encrypted full backup ----
    public static final String FULL_FORMAT = "hanako-full-backup";
    private static final byte[] MAGIC = {'H', 'N', 'K', 'B', 'A', 'K', '0', '1'};
    private static final int FILE_VERSION = 1;
    private static final int KDF_PBKDF2_HMAC_SHA256 = 1;
    public static final int PBKDF2_ITERATIONS = 400_000;
    private static final int MIN_ITERATIONS = 310_000;
    /** Upper bound of a file's iteration count: more would freeze the restore for minutes. */
    private static final int MAX_ITERATIONS = 2_000_000;
    private static final int SALT_LEN = 32;
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    // The whole file is held in memory a few times over (zip, ciphertext, plaintext), so the
    // limit is kept low: sessions are a few KB and settings at most MAX_SETTINGS_BYTES.
    private static final int MAX_BACKUP_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;
    /** All zip entries together, so a crafted file can't inflate to a gigabyte. */
    private static final int MAX_INFLATED_BYTES = MAX_BACKUP_BYTES;
    private static final int MAX_SETTINGS_BYTES = 4 * 1024 * 1024;
    /** Same rule as the UI text: 12 or more characters (or a generated passphrase). */
    public static final int MIN_PASSWORD_LENGTH = 12;
    /** A staged restore older than this is dropped at start instead of applied without the user. */
    private static final long PLAN_MAX_AGE_MS = 30L * 60 * 1000;

    // ---- error codes (see describeError) ----
    public static final int ERR_DAMAGED = 0;
    public static final int ERR_NOT_SETTINGS = 1;
    public static final int ERR_IS_FULL_BACKUP = 2;
    public static final int ERR_NOT_BACKUP = 3;
    public static final int ERR_IS_SETTINGS = 4;
    public static final int ERR_NEWER = 5;
    public static final int ERR_NO_ACCOUNTS = 6;

    private static final String RESULT_FILE = "hanako_restore_result.json";
    private static final String UNDO_FILE = "hanako_settings_undo.json";
    private static final long UNDO_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final String HIDDEN_PREFS = "mainconfig";
    private static final String HIDDEN_HASH_PREFIX = "mg_hiddenAccountHash_";
    private static final String HIDDEN_SALT_PREFIX = "mg_hiddenAccountSalt_";
    private static final String HIDDEN_STEALTH_KEY = "mg_hiddenAccountsStealthMode";

    private static final String STAGE_DIR = "hanako_restore";
    private static final String STAGE_PLAN = "plan.json";

    /** Per-slot native session file. The rest of the slot dir (dc*conf.dat, cdnkeys.dat, cache) is rebuilt. */
    private static final String TGNET = "tgnet.dat";
    private static final String[] SLOT_FILES_TO_DROP = {
            "tgnet.dat", "tgnet.dat.bak",
            "dc1conf.dat", "dc2conf.dat", "dc3conf.dat", "dc4conf.dat", "dc5conf.dat",
            "dc1conf.dat.bak", "dc2conf.dat.bak", "dc3conf.dat.bak", "dc4conf.dat.bak", "dc5conf.dat.bak",
            "cdnkeys.dat", "cdnkeys.dat.bak",
            "cache4.db", "cache4.db-wal", "cache4.db-shm",
    };

    /** Feature preference files that hold settings (not logs / recovered data / plugins). */
    private static final String[] SETTINGS_PREF_FILES = {
            "plus_ghost", "plus_ghost_exceptions", "plus_f03", "plus_f04_filters", "plus_f06",
            "plus_f08_chatlock", "plus_f09", "plus_f10", "plus_f12", "plus_f13", "plus_f14",
            "plus_f15", "plus_f16", "plus_f17", "plus_f18", "plus_f19", "mg_locationsharing",
    };

    /** mg_* keys of "userconfing" that are device state, secrets or update bookkeeping rather than settings. */
    private static final Set<String> GLOBAL_DENY = new HashSet<>(Arrays.asList(
            "mg_pushStringSimple", "mg_webPushPrivateKey", "mg_webPushPublicKey", "mg_webPushAuthSecret",
            "mg_unifiedPushEndpointUrl", "mg_embeddedFcmChosen", "mg_pendingUpdate", "mg_lastUpdateCheckTime",
            "mg_updateApkPath", "mg_dismissedPendingTag", "mg_dismissedPluginPromptTag", "mg_lastPreReleaseTag"
    ));

    /**
     * Text-valued global settings a shareable file may carry: names of modes, nothing typed in
     * by the user. Every other mg_* string (Tor bridges, custom instance URLs, push gateway and
     * keys, update paths, and any key added later) only goes into files with per-chat lists.
     */
    private static final Set<String> SHAREABLE_GLOBAL_STRINGS = new HashSet<>(Arrays.asList(
            "mg_translateMode", "mg_translateAltEngine", "mg_translateAltInstanceMode", "mg_transcribeModel"
    ));

    /** Per-account Mercurygram keys (MgAccountConfig) that are user settings. */
    private static final Set<String> MG_ACCOUNT_KEYS = new HashSet<>(Arrays.asList(
            "rearRoundCamera", "hideChatKeyboard", "hideAllTab", "defaultFolderId", "messageDetailsMenu",
            "disableLivePhotosByDefault", "savedMessagesHistory", "transcribeLang", "hideStories",
            "hidePremiumPromo", "disableGlobalSearch", "disableAiEditor", "disableAiSummary",
            "disableInstantView", "disableLinkPreviews", "preferSecretChats", "deleteForAllByDefault",
            "stripTrackingParams", "disableCloudDrafts", "confirmInternalLinks", "showCharCounter", "mainPins"
    ));

    /** UserConfig keys carried by a full backup besides the MG ones: enough to make the slot activated. */
    private static final Set<String> ACCOUNT_SESSION_KEYS = new HashSet<>(Arrays.asList(
            "user", "loginTime", "syncContacts", "suggestContacts", "showCallsTab"
    ));

    /**
     * The only keys a restore writes into a slot's userconfig file. That file is "userconfing"
     * for slot 0, which also holds SharedConfig's global keys (passcode, proxy, update path), so
     * a crafted backup must not be able to set anything else.
     */
    private static boolean isRestorableAccountKey(String key) {
        return ACCOUNT_SESSION_KEYS.contains(key) || MG_ACCOUNT_KEYS.contains(key) || "registeredForPush".equals(key);
    }

    /** Keys UserConfig writes per account; removed from slot 0's file (which also holds global keys) before a restore. */
    private static final Set<String> USERCONFIG_ACCOUNT_KEYS = new HashSet<>(Arrays.asList(
            "registeredForPush", "lastSendMessageId", "contactsSavedCount", "lastBroadcastId",
            "lastContactsSyncTime", "lastHintsSyncTime", "draftsLoaded", "unreadDialogsLoaded", "ratingLoadTime",
            "botRatingLoadTime", "botGuestRatingLoadTime", "webappRatingLoadTime", "contactsReimported",
            "loginTime", "syncContacts", "showCallsTab", "suggestContacts", "hasSecureData",
            "notificationsSettingsLoaded4", "notificationsSignUpSettingsLoaded", "autoDownloadConfigLoadTime",
            "hasValidDialogLoadIds", "sharingMyLocationUntil", "lastMyLocationShareTime", "filtersLoaded",
            "premiumGiftsStickerPack", "lastUpdatedPremiumGiftsStickerPack", "genericAnimationsStickerPack",
            "lastUpdatedGenericAnimations", "terms", "tmpPassword", "user", "folderSyncUpdated",
            "folderEmoticons", "mgReducedTrackingExhausted"
    ));
    private static final String[] USERCONFIG_ACCOUNT_PREFIXES = {
            "2dialogsLoadOffset", "2pinnedDialogsLoaded", "2totalDialogsLoadCount", "6migrateOffset", "dialogsLoadOffset"
    };

    private HanakoBackup() {
    }

    public static class WrongPasswordException extends Exception {
        public WrongPasswordException() {
            super("wrong password");
        }
    }

    public static class InvalidBackupException extends Exception {
        public final int code;

        public InvalidBackupException(String message) {
            this(ERR_DAMAGED, message);
        }

        public InvalidBackupException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Raw exception text, for the "Details" button only. */
    public static String rawError(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (TextUtils.isEmpty(m) ? "" : ": " + m);
    }

    /** Maps a failure to a short localized message that says what to do. */
    public static String describeError(Throwable t) {
        if (t instanceof HanakoChatExport.FloodWaitTooLongException) {
            int sec = ((HanakoChatExport.FloodWaitTooLongException) t).seconds;
            String wait = sec >= 3600 ? LocaleController.formatPluralString("Hours", (sec + 3599) / 3600)
                    : LocaleController.formatPluralString("Minutes", (sec + 59) / 60);
            return LocaleController.formatString(R.string.HanakoErrFloodWaitLong, wait);
        }
        if (t instanceof InvalidBackupException) {
            switch (((InvalidBackupException) t).code) {
                case ERR_NOT_SETTINGS:
                    return LocaleController.getString(R.string.HanakoErrNotSettings);
                case ERR_IS_FULL_BACKUP:
                    return LocaleController.getString(R.string.HanakoErrIsFullBackup);
                case ERR_NOT_BACKUP:
                    return LocaleController.getString(R.string.HanakoErrNotBackup);
                case ERR_IS_SETTINGS:
                    return LocaleController.getString(R.string.HanakoErrIsSettings);
                case ERR_NEWER:
                    return LocaleController.getString(R.string.HanakoErrNewer);
                case ERR_NO_ACCOUNTS:
                    return LocaleController.getString(R.string.HanakoErrNoAccounts);
                default:
                    return LocaleController.getString(R.string.HanakoErrDamaged);
            }
        }
        String raw = rawError(t).toLowerCase(java.util.Locale.ROOT);
        if (raw.contains("enospc") || raw.contains("no space")) {
            return LocaleController.getString(R.string.HanakoErrNoSpace);
        }
        if (raw.contains("file too large")) {
            return LocaleController.getString(R.string.HanakoErrTooLarge);
        }
        if (raw.contains("cannot open output") || raw.contains("cannot create") || raw.contains("permission") || t instanceof SecurityException) {
            return LocaleController.getString(R.string.HanakoErrCannotWrite);
        }
        if (raw.contains("cannot open input") || t instanceof java.io.FileNotFoundException) {
            return LocaleController.getString(R.string.HanakoErrCannotRead);
        }
        if (raw.contains("timeout") || raw.contains("network")) {
            return LocaleController.getString(R.string.HanakoErrNetwork);
        }
        return LocaleController.getString(R.string.HanakoErrGeneric);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    /** A Hanako settings JSON picked where a full backup was expected (cheap sniff, no full parse). */
    private static boolean looksLikeSettings(byte[] data) {
        if (data == null || data.length == 0) return false;
        int i = 0;
        while (i < data.length && (data[i] == ' ' || data[i] == '\n' || data[i] == '\r' || data[i] == '\t' || (data[i] & 0xff) == 0xEF || (data[i] & 0xff) == 0xBB || (data[i] & 0xff) == 0xBF)) i++;
        if (i >= data.length || data[i] != '{') return false;
        String head = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.UTF_8);
        return head.contains(SETTINGS_FORMAT);
    }

    // =====================================================================================
    // Hidden accounts (Mercurygram HiddenAccountHelper)
    // =====================================================================================

    /** Slots and Telegram user ids of the hidden accounts on this device. */
    static final class HiddenSet {
        final HashSet<Integer> slots = new HashSet<>();
        final HashSet<String> userIds = new HashSet<>();

        boolean isEmpty() {
            return slots.isEmpty() && userIds.isEmpty();
        }

        /**
         * Whether a preference key belongs to one of these accounts: a "_"-separated part of the
         * key is one of their user ids (also as "u&lt;id&gt;"), or, after the first part, one of
         * their slot numbers. Whole parts only, so "-100&lt;id…&gt;" channel keys never match.
         */
        boolean touches(String key) {
            if (key == null || isEmpty()) return false;
            String[] parts = key.split("_");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                String id = part.startsWith("u") ? part.substring(1) : part;
                if (userIds.contains(part) || userIds.contains(id)) return true;
                if (i > 0 && !part.isEmpty() && part.length() <= 2 && TextUtils.isDigitsOnly(part)
                        && slots.contains(Integer.parseInt(part))) return true;
            }
            return false;
        }

        JSONObject toJson() throws JSONException {
            JSONArray s = new JSONArray();
            for (Integer slot : slots) s.put(slot.intValue());
            JSONArray u = new JSONArray();
            for (String id : userIds) u.put(id);
            return new JSONObject().put("slots", s).put("ids", u);
        }

        static HiddenSet fromJson(JSONObject o) {
            HiddenSet h = new HiddenSet();
            if (o == null) return h;
            JSONArray s = o.optJSONArray("slots");
            if (s != null) for (int i = 0; i < s.length(); i++) h.slots.add(s.optInt(i, -1));
            JSONArray u = o.optJSONArray("ids");
            if (u != null) for (int i = 0; i < u.length(); i++) {
                String id = u.optString(i, "");
                if (isLong(id) && id.length() >= 5) h.userIds.add(id);
            }
            h.slots.remove(-1);
            return h;
        }
    }

    /** Hidden accounts on this device right now. Call after UserConfig is loaded. */
    static HiddenSet hiddenAccounts() {
        HiddenSet h = new HiddenSet();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!HiddenAccountHelper.isAccountHidden(a)) continue;
            h.slots.add(a);
            UserConfig uc = UserConfig.getInstance(a);
            if (uc.isClientActivated()) h.userIds.add(Long.toString(uc.getClientUserId()));
        }
        return h;
    }

    /** True when there are hidden accounts and the user is signed in to one right now (so they know its code). */
    public static boolean canOfferHiddenAccounts() {
        if (!HiddenAccountHelper.hasAnyHiddenAccounts()) return false;
        return HiddenAccountHelper.isAccountHidden(UserConfig.selectedAccount);
    }

    public static int visibleAccountCount() {
        return HiddenAccountHelper.getVisibleAccountsCount();
    }

    // =====================================================================================
    // Preferences vs per-chat lists
    // =====================================================================================

    /**
     * Keys that are lists of chats, users, accounts or keys rather than switches. They are only
     * exported when the user ticks "per-chat lists", because they identify people and chats.
     */
    static boolean isListKey(String file, String key) {
        if (file == null || key == null) return false;
        switch (file) {
            case "plus_ghost":
                return PlusUtil.isUserKey(key); // per-account ("on_u12345"): names a user id
            case "plus_ghost_exceptions":
                return true;
            case "plus_f04_filters":
                return "filters".equals(key) || "banned".equals(key);
            case "plus_f08_chatlock":
                return key.startsWith("locked_");
            case "plus_f18":
                return !"enabled".equals(key) && !"provider".equals(key);
            default:
                return false;
        }
    }

    // =====================================================================================
    // Typed preference values
    // =====================================================================================

    private static JSONObject encodeValue(Object v) throws JSONException {
        JSONObject o = new JSONObject();
        if (v instanceof Boolean) {
            o.put("t", "b").put("v", (boolean) (Boolean) v);
        } else if (v instanceof Integer) {
            o.put("t", "i").put("v", (int) (Integer) v);
        } else if (v instanceof Long) {
            // as a string: JSON numbers above 2^53 lose precision in other readers
            o.put("t", "l").put("v", Long.toString((Long) v));
        } else if (v instanceof Float) {
            o.put("t", "f").put("v", (double) (Float) v);
        } else if (v instanceof String) {
            o.put("t", "s").put("v", v);
        } else if (v instanceof Set) {
            JSONArray arr = new JSONArray();
            for (Object s : (Set<?>) v) {
                arr.put(String.valueOf(s));
            }
            o.put("t", "ss").put("v", arr);
        } else {
            return null;
        }
        return o;
    }

    private static JSONObject encodeMap(Map<String, ?> map, KeyFilter filter) throws JSONException {
        JSONObject out = new JSONObject();
        ArrayList<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            if (filter != null && !filter.accept(key)) {
                continue;
            }
            JSONObject enc = encodeValue(map.get(key));
            if (enc != null) {
                out.put(key, enc);
            }
        }
        return out;
    }

    private interface KeyFilter {
        boolean accept(String key);
    }

    /** Validates every value first, so a malformed document never half-applies. */
    private static void validateTyped(JSONObject typed, KeyFilter filter) throws InvalidBackupException {
        Iterator<String> it = typed.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (TextUtils.isEmpty(key) || key.length() > 256) {
                throw new InvalidBackupException("bad key");
            }
            if (filter != null && !filter.accept(key)) {
                continue;
            }
            JSONObject o = typed.optJSONObject(key);
            if (o == null) {
                throw new InvalidBackupException("bad value for " + key);
            }
            String t = o.optString("t", "");
            Object v = o.opt("v");
            boolean ok;
            switch (t) {
                case "b":
                    ok = v instanceof Boolean;
                    break;
                case "i":
                    ok = v instanceof Integer;
                    break;
                case "l":
                    ok = v != null && isLong(String.valueOf(v));
                    break;
                case "f":
                    ok = v instanceof Number;
                    break;
                case "s":
                    ok = v instanceof String;
                    break;
                case "ss":
                    ok = v instanceof JSONArray;
                    break;
                default:
                    ok = false;
            }
            if (!ok) {
                throw new InvalidBackupException("bad value for " + key);
            }
        }
    }

    private static boolean isLong(String s) {
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int putTyped(SharedPreferences.Editor editor, JSONObject typed, KeyFilter filter) {
        int count = 0;
        Iterator<String> it = typed.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (filter != null && !filter.accept(key)) {
                continue;
            }
            JSONObject o = typed.optJSONObject(key);
            if (o == null) {
                continue;
            }
            try {
                switch (o.getString("t")) {
                    case "b":
                        editor.putBoolean(key, o.getBoolean("v"));
                        break;
                    case "i":
                        editor.putInt(key, o.getInt("v"));
                        break;
                    case "l":
                        editor.putLong(key, Long.parseLong(o.get("v").toString()));
                        break;
                    case "f":
                        editor.putFloat(key, (float) o.getDouble("v"));
                        break;
                    case "s":
                        editor.putString(key, o.getString("v"));
                        break;
                    case "ss": {
                        JSONArray arr = o.getJSONArray("v");
                        HashSet<String> set = new HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            set.add(arr.getString(i));
                        }
                        editor.putStringSet(key, set);
                        break;
                    }
                    default:
                        continue;
                }
                count++;
            } catch (Exception e) {
                Log.w(TAG, "skip key " + key);
            }
        }
        return count;
    }

    private static boolean isGlobalSettingKey(String key) {
        return key != null && key.startsWith("mg_") && !GLOBAL_DENY.contains(key) && !key.toLowerCase().contains("migrat");
    }

    private static boolean isShareableGlobal(String key, Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Float) return true;
        return value instanceof String && SHAREABLE_GLOBAL_STRINGS.contains(key) && isShareableValue(value);
    }

    /** A feature setting that is a switch, a number or a short word: no URL, no long digit run. */
    private static boolean isShareableValue(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Float) return true;
        if (!(value instanceof String)) return false;
        String v = (String) value;
        return v.length() <= 40 && !v.contains("://") && !v.matches(".*\\d{6,}.*");
    }

    private static boolean isSettingsFile(String name) {
        for (String f : SETTINGS_PREF_FILES) {
            if (f.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** SharedPreferences.Editor that only records what MgAccountConfig.save() writes. */
    private static final class CaptureEditor implements SharedPreferences.Editor {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            values.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            values.clear();
            return this;
        }

        @Override
        public boolean commit() {
            return true;
        }

        @Override
        public void apply() {
        }
    }

    private static SharedPreferences prefs(String name) {
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static String userConfigPrefsName(int slot) {
        return slot == 0 ? "userconfing" : "userconfig" + slot;
    }

    private static String appVersion() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // =====================================================================================
    // Settings export / import
    // =====================================================================================

    private static JSONObject accountPrefs(UserConfig uc) throws JSONException {
        CaptureEditor cap = new CaptureEditor();
        uc.mg.save(cap);
        return encodeMap(cap.values, MG_ACCOUNT_KEYS::contains);
    }

    /**
     * Builds the settings document from the live state. Call off the UI thread.
     *
     * @param includeLists  add the "lists" section: per-account options keyed by Telegram user id,
     *                      locked / hidden chats, ghost exceptions, message filters and hidden
     *                      users, OpenPGP key ids. Off for a file meant to be shared.
     * @param includeHidden keep hidden accounts in (only offered while signed in to one); otherwise
     *                      nothing that names a hidden account's slot or user id is written.
     */
    public static JSONObject buildSettingsJson(boolean includeLists, boolean includeHidden) throws JSONException {
        final HiddenSet hidden = includeHidden ? new HiddenSet() : hiddenAccounts();
        JSONObject root = new JSONObject();
        root.put("format", SETTINGS_FORMAT);
        root.put("version", SETTINGS_VERSION);
        root.put("app", ApplicationLoader.applicationContext.getPackageName());
        root.put("app_version", appVersion());
        root.put("created", System.currentTimeMillis() / 1000L);

        // A file without lists is meant to be shared: it is an allow-list of switches and numbers,
        // plus the few mode names above; nothing that could be a URL, a key or an id.
        final Map<String, ?> global = prefs("userconfing").getAll();
        root.put("global", encodeMap(global, key -> isGlobalSettingKey(key)
                && (includeLists || isShareableGlobal(key, global.get(key)))));

        JSONObject files = new JSONObject();
        JSONObject listFiles = new JSONObject();
        for (String name : SETTINGS_PREF_FILES) {
            Map<String, ?> all = prefs(name).getAll();
            if (all.isEmpty()) continue;
            JSONObject p = encodeMap(all, key -> !isListKey(name, key) && !hidden.touches(key)
                    && (includeLists || isShareableValue(all.get(key))));
            if (p.length() > 0) files.put(name, p);
            if (includeLists) {
                JSONObject l = encodeMap(all, key -> isListKey(name, key) && !hidden.touches(key));
                if (l.length() > 0) listFiles.put(name, l);
            }
        }
        root.put("files", files);

        // the options of the account in use, without any id: applied to the account in use on import
        int sel = UserConfig.selectedAccount;
        if (sel >= 0 && sel < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(sel).isClientActivated() && !hidden.slots.contains(sel)) {
            UserConfig uc = UserConfig.getInstance(sel);
            root.put("current_account", accountPrefs(uc));
            // its per-account feature settings with the "_u<id>" suffix taken off
            String sfx = "_u" + uc.getClientUserId();
            JSONObject currentFiles = new JSONObject();
            for (String name : SETTINGS_PREF_FILES) {
                JSONObject f = new JSONObject();
                for (Map.Entry<String, ?> e : prefs(name).getAll().entrySet()) {
                    String key = e.getKey();
                    if (!key.endsWith(sfx) || !PlusUtil.isUserKey(key)) continue;
                    JSONObject enc = encodeValue(e.getValue());
                    if (enc != null) f.put(key.substring(0, key.length() - sfx.length()), enc);
                }
                if (f.length() > 0) currentFiles.put(name, f);
            }
            if (currentFiles.length() > 0) root.put("current_files", currentFiles);
            // which account that was: only in files that may name accounts (lists)
            if (includeLists) root.put("current_user_id", Long.toString(uc.getClientUserId()));
        }

        if (includeLists) {
            JSONArray accounts = new JSONArray();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig uc = UserConfig.getInstance(a);
                if (!uc.isClientActivated() || hidden.slots.contains(a)) continue;
                JSONObject acc = new JSONObject();
                acc.put("user_id", Long.toString(uc.getClientUserId()));
                acc.put("prefs", accountPrefs(uc));
                accounts.put(acc);
            }
            JSONObject lists = new JSONObject();
            lists.put("files", listFiles);
            lists.put("accounts", accounts);
            root.put("lists", lists);
        }
        return root;
    }

    public static void writeSettings(Context context, Uri uri, boolean includeLists, boolean includeHidden) throws Exception {
        HanakoTelemetry.count(HanakoTelemetry.SETTINGS_EXPORT); // hanako: usage statistics (off by default)
        byte[] data = buildSettingsJson(includeLists, includeHidden).toString(2).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = openTruncated(context, uri)) {
            if (os == null) {
                throw new IOException("cannot open output");
            }
            os.write(data);
        }
    }

    /** Parses and validates a settings document. */
    public static JSONObject readSettings(Context context, Uri uri) throws Exception {
        byte[] data = readAll(context.getContentResolver(), uri, MAX_SETTINGS_BYTES);
        if (startsWith(data, MAGIC)) {
            throw new InvalidBackupException(ERR_IS_FULL_BACKUP, "full backup picked in Import settings");
        }
        JSONObject root;
        try {
            root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new InvalidBackupException(ERR_NOT_SETTINGS, "not a JSON file");
        }
        validateSettings(root);
        return root;
    }

    private static void validateFiles(JSONObject files) throws InvalidBackupException {
        if (files == null) return;
        Iterator<String> it = files.keys();
        while (it.hasNext()) {
            String name = it.next();
            if (!isSettingsFile(name)) {
                continue;
            }
            JSONObject f = files.optJSONObject(name);
            if (f == null) {
                throw new InvalidBackupException("bad section " + name);
            }
            validateTyped(f, null);
        }
    }

    private static void validateAccounts(JSONArray accounts) throws InvalidBackupException {
        if (accounts == null) return;
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject acc = accounts.optJSONObject(i);
            if (acc == null || !isLong(acc.optString("user_id"))) {
                throw new InvalidBackupException("bad account entry");
            }
            JSONObject p = acc.optJSONObject("prefs");
            if (p != null) {
                validateTyped(p, MG_ACCOUNT_KEYS::contains);
            }
        }
    }

    public static void validateSettings(JSONObject root) throws InvalidBackupException {
        String format = root.optString("format");
        if (!SETTINGS_FORMAT.equals(format)) {
            if (FULL_FORMAT.equals(format)) {
                throw new InvalidBackupException(ERR_IS_FULL_BACKUP, "full backup manifest");
            }
            throw new InvalidBackupException(ERR_NOT_SETTINGS, "not a Hanako settings file");
        }
        int version = root.optInt("version", -1);
        if (version > SETTINGS_VERSION) {
            throw new InvalidBackupException(ERR_NEWER, "settings version " + version);
        }
        if (version < 1) {
            throw new InvalidBackupException("bad settings version " + version);
        }
        JSONObject global = root.optJSONObject("global");
        if (global != null) {
            validateTyped(global, HanakoBackup::isGlobalSettingKey);
        }
        validateFiles(root.optJSONObject("files"));
        validateAccounts(root.optJSONArray("accounts")); // v1
        JSONObject current = root.optJSONObject("current_account");
        if (current != null) {
            validateTyped(current, MG_ACCOUNT_KEYS::contains);
        }
        validateFiles(root.optJSONObject("current_files"));
        if (root.has("current_user_id") && !isLong(root.optString("current_user_id"))) {
            throw new InvalidBackupException("bad current_user_id");
        }
        JSONObject lists = root.optJSONObject("lists");
        if (lists != null) {
            validateFiles(lists.optJSONObject("files"));
            validateAccounts(lists.optJSONArray("accounts"));
        }
    }

    /** Whether the document carries per-chat lists (v2 "lists" section, or any v1 file). */
    public static boolean hasLists(JSONObject root) {
        if (root.optInt("version", 1) < 2) return true;
        return root.optJSONObject("lists") != null;
    }

    /** How many accounts of the document's per-account section are logged in here (and visible). */
    public static int[] matchingAccounts(JSONObject root) {
        JSONArray accounts = root.optInt("version", 1) < 2 ? root.optJSONArray("accounts")
                : (root.optJSONObject("lists") != null ? root.optJSONObject("lists").optJSONArray("accounts") : null);
        int total = 0;
        int match = 0;
        if (accounts != null) {
            HashMap<Long, Integer> here = currentAccountsByUser();
            HiddenSet hidden = hiddenAccounts();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject acc = accounts.optJSONObject(i);
                if (acc == null) continue;
                total++;
                try {
                    Integer slot = here.get(Long.parseLong(acc.optString("user_id")));
                    if (slot != null && !hidden.slots.contains(slot)) match++;
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return new int[]{match, total};
    }

    /** user id -&gt; slot of the accounts logged in right now. */
    public static HashMap<Long, Integer> currentAccountsByUser() {
        HashMap<Long, Integer> map = new HashMap<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (uc.isClientActivated()) {
                map.put(uc.getClientUserId(), a);
            }
        }
        return map;
    }

    private static void mergeInto(JSONObject target, JSONObject files) throws JSONException {
        if (files == null) return;
        Iterator<String> it = files.keys();
        while (it.hasNext()) {
            String name = it.next();
            JSONObject f = files.optJSONObject(name);
            if (f == null) continue;
            JSONObject t = target.optJSONObject(name);
            if (t == null) {
                t = new JSONObject();
                target.put(name, t);
            }
            Iterator<String> keys = f.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                t.put(k, f.get(k));
            }
        }
    }

    /**
     * Turns a validated settings document into the staged form: account sections are resolved to
     * slots (by Telegram user id; sections for accounts that are not here are dropped), and the
     * hidden accounts of this device are recorded so the apply step never touches their keys.
     *
     * @param withLists also replace the per-chat lists (when the document has them)
     */
    private static JSONObject resolveSettings(JSONObject root, Map<Long, Integer> userToSlot, int currentSlot, boolean withLists, HiddenSet preserve) throws JSONException {
        boolean v1 = root.optInt("version", 1) < 2;
        boolean lists = withLists && hasLists(root);
        JSONObject out = new JSONObject();
        JSONObject global = root.optJSONObject("global");
        if (global != null) out.put("global", global);

        JSONObject files = new JSONObject();
        mergeInto(files, root.optJSONObject("files"));
        JSONArray accounts;
        if (v1) {
            accounts = lists ? root.optJSONArray("accounts") : null;
        } else {
            JSONObject l = lists ? root.optJSONObject("lists") : null;
            if (l != null) mergeInto(files, l.optJSONObject("files"));
            accounts = l != null ? l.optJSONArray("accounts") : null;
        }
        out.put("files", files);
        out.put("replace_prefs", true);
        out.put("replace_lists", lists);

        dropForeignUserKeys(files, userToSlot);

        // The "account in use" section goes to the same account it came from when the document
        // says which one (full backups and files with lists); if that account is not on this
        // phone it is skipped. A shareable file has no id and goes to the account in use here.
        int targetSlot = currentSlot;
        String sourceUser = root.optString("current_user_id", "");
        if (isLong(sourceUser)) {
            Integer s = userToSlot.get(Long.parseLong(sourceUser));
            targetSlot = s != null ? s : -1;
        }
        long targetUser = 0;
        if (targetSlot >= 0) {
            targetUser = isLong(sourceUser) ? Long.parseLong(sourceUser) : UserConfig.getInstance(targetSlot).getClientUserId();
        }
        JSONArray slots = new JSONArray();
        JSONObject current = root.optJSONObject("current_account");
        if (current != null && targetSlot >= 0 && !preserve.slots.contains(targetSlot)) {
            slots.put(new JSONObject().put("slot", targetSlot).put("prefs", current));
        }
        JSONObject currentFiles = root.optJSONObject("current_files");
        if (currentFiles != null && targetUser != 0 && !preserve.slots.contains(targetSlot)) {
            out.put("account_files", userKeyed(currentFiles, "_u" + targetUser));
        }
        if (accounts != null) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject acc = accounts.optJSONObject(i);
                if (acc == null) continue;
                Integer slot;
                try {
                    slot = userToSlot.get(Long.parseLong(acc.optString("user_id")));
                } catch (NumberFormatException e) {
                    slot = null;
                }
                JSONObject p = acc.optJSONObject("prefs");
                if (slot == null || p == null || preserve.slots.contains(slot)) continue;
                slots.put(new JSONObject().put("slot", slot).put("prefs", p));
            }
        }
        out.put("accounts", slots);
        out.put("preserve", preserve.toJson());
        return out;
    }

    /**
     * Per-account keys of the settings files: "_u<id>" keys of users that are not on this phone
     * are dropped, and so are the old slot-numbered keys ("on_3") of documents made before the
     * keys followed the user id, because a slot number from another phone (or from before a
     * restore) can name a different account here. Those settings fall back to their defaults
     * (ghost mode on, everything hidden), the private side.
     */
    private static void dropForeignUserKeys(JSONObject files, Map<Long, Integer> userToSlot) throws JSONException {
        Iterator<String> names = files.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject f = files.optJSONObject(name);
            if (f == null || !PlusUtil.isPerAccountFile(name)) continue;
            ArrayList<String> drop = new ArrayList<>();
            Iterator<String> keys = f.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long uid = PlusUtil.userOfKey(key);
                if (uid != 0 ? !userToSlot.containsKey(uid) : PlusUtil.isSlotKey(key)) drop.add(key);
            }
            for (String key : drop) f.remove(key);
        }
    }

    /** Copy of {@code files} with {@code suffix} appended to every key. */
    private static JSONObject userKeyed(JSONObject files, String suffix) throws JSONException {
        JSONObject out = new JSONObject();
        Iterator<String> names = files.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject f = files.optJSONObject(name);
            if (f == null || !isSettingsFile(name)) continue;
            JSONObject t = new JSONObject();
            Iterator<String> keys = f.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                t.put(key + suffix, f.get(key));
            }
            out.put(name, t);
        }
        return out;
    }

    /**
     * Stages a settings import; it is applied on the next start (see {@link #restartApp(Activity)}).
     * The current settings are saved first, so the import can be undone for 24 hours.
     */
    public static void stageSettingsImport(JSONObject root, boolean withLists) throws Exception {
        HanakoTelemetry.count(HanakoTelemetry.SETTINGS_IMPORT); // hanako: usage statistics (off by default)
        saveUndoSnapshot(); // throws: no import without the promised undo
        JSONObject plan = new JSONObject();
        plan.put("version", 1);
        plan.put("created_ms", System.currentTimeMillis());
        plan.put("settings", resolveSettings(root, currentAccountsByUser(), UserConfig.selectedAccount, withLists, hiddenAccounts()));
        writePlan(plan, null);
    }

    private static File undoFile() {
        File files = ApplicationLoader.applicationContext.getFilesDir();
        if (files == null) files = ApplicationLoader.getFilesDirFixed();
        return new File(files, UNDO_FILE);
    }

    private static void saveUndoSnapshot() throws Exception {
        byte[] data = buildSettingsJson(true, false).toString().getBytes(StandardCharsets.UTF_8);
        File f = undoFile();
        File tmp = new File(f.getParentFile(), UNDO_FILE + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("cannot save undo snapshot");
        }
    }

    /** An import made in the last 24 hours can be undone. */
    public static boolean hasUndo() {
        File f = undoFile();
        if (!f.isFile()) return false;
        if (System.currentTimeMillis() - f.lastModified() > UNDO_MAX_AGE_MS) {
            f.delete();
            return false;
        }
        return true;
    }

    /** Stages the settings that were in place before the last import. */
    public static void stageUndo() throws Exception {
        HanakoTelemetry.count(HanakoTelemetry.SETTINGS_UNDO); // hanako: usage statistics (off by default)
        File f = undoFile();
        byte[] data;
        try (InputStream in = new FileInputStream(f)) {
            data = readStream(in, MAX_SETTINGS_BYTES);
        }
        JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        validateSettings(root);
        JSONObject plan = new JSONObject();
        plan.put("version", 1);
        plan.put("created_ms", System.currentTimeMillis());
        plan.put("settings", resolveSettings(root, currentAccountsByUser(), UserConfig.selectedAccount, true, hiddenAccounts()));
        writePlan(plan, null);
        f.delete();
    }

    // =====================================================================================
    // Full encrypted backup
    // =====================================================================================

    public static final class AccountEntry {
        public long userId;
        public String name;
        public String username;
        public String phoneHint;
        public byte[] tgnet;
        public JSONObject prefs;
        /** Hidden account (HiddenAccountHelper): never named in the UI, stays hidden after restore. */
        public boolean hidden;
        public String hiddenHash;
        public String hiddenSalt;
    }

    public static final class FullBackup {
        public JSONObject manifest;
        public JSONObject settings;
        public final ArrayList<AccountEntry> accounts = new ArrayList<>();

        public void wipe() {
            for (AccountEntry e : accounts) {
                if (e.tgnet != null) Arrays.fill(e.tgnet, (byte) 0);
            }
        }
    }

    private static File slotDir(Context ctx, int slot) {
        File files = ctx.getFilesDir();
        if (files == null) {
            files = ApplicationLoader.getFilesDirFixed();
        }
        return slot == 0 ? files : new File(files, "account" + slot);
    }

    private static byte[] readSessionFile(Context ctx, int slot) throws IOException {
        File dir = slotDir(ctx, slot);
        File f = new File(dir, TGNET);
        if (!f.isFile() || f.length() == 0) {
            // Config writes by renaming the live file to .bak first; a crash in between leaves only the .bak.
            f = new File(dir, TGNET + ".bak");
        }
        if (!f.isFile() || f.length() == 0 || f.length() > MAX_ENTRY_BYTES) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in, MAX_ENTRY_BYTES);
        }
    }

    private static String maskPhone(String phone) {
        if (TextUtils.isEmpty(phone)) return "";
        if (phone.length() <= 4) return "+" + phone;
        StringBuilder sb = new StringBuilder("+");
        for (int i = 0; i < phone.length() - 4; i++) sb.append('•');
        sb.append(phone.substring(phone.length() - 4));
        return sb.toString();
    }

    /**
     * Plain (unencrypted) zip of the full backup. Wipe the returned array after use.
     *
     * @param outAccountCount [visible, hidden, skipped]; skipped = signed in, but its session
     *                        file could not be read
     */
    private static byte[] buildFullPlain(boolean includeSettings, boolean includeHidden, int[] outAccountCount) throws Exception {
        Context ctx = ApplicationLoader.applicationContext;
        JSONObject manifest = new JSONObject();
        manifest.put("format", FULL_FORMAT);
        manifest.put("version", 1);
        manifest.put("app", ctx.getPackageName());
        manifest.put("app_version", appVersion());
        manifest.put("created", System.currentTimeMillis() / 1000L);
        JSONArray accounts = new JSONArray();
        ArrayList<byte[]> sessions = new ArrayList<>();
        SharedPreferences hiddenPrefs = ctx.getSharedPreferences(HIDDEN_PREFS, Context.MODE_PRIVATE);
        int visibleCount = 0;
        int hiddenCount = 0;
        int skippedCount = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.isClientActivated()) continue;
            final boolean hidden = HiddenAccountHelper.isAccountHidden(a);
            if (hidden && !includeHidden) continue;
            byte[] session = readSessionFile(ctx, a);
            if (session == null) {
                FileLog.d("hanako backup: no session file for slot " + a);
                if (!hidden) skippedCount++;
                continue;
            }
            TLRPC.User user = uc.getCurrentUser();
            SharedPreferences sp = uc.getPreferences();
            Map<String, ?> all = sp.getAll();
            LinkedHashMap<String, Object> keep = new LinkedHashMap<>();
            for (String key : ACCOUNT_SESSION_KEYS) {
                if (all.containsKey(key)) keep.put(key, all.get(key));
            }
            if (!(keep.get("user") instanceof String) && user != null) {
                SerializedData data = new SerializedData(user.getObjectSize());
                user.serializeToStream(data);
                keep.put("user", Base64.encodeToString(data.toByteArray(), Base64.DEFAULT));
                data.cleanup();
            }
            if (!(keep.get("user") instanceof String)) {
                if (!hidden) skippedCount++;
                continue;
            }
            CaptureEditor cap = new CaptureEditor();
            uc.mg.save(cap);
            for (Map.Entry<String, Object> e : cap.values.entrySet()) {
                if (MG_ACCOUNT_KEYS.contains(e.getKey())) keep.put(e.getKey(), e.getValue());
            }
            JSONObject acc = new JSONObject();
            int idx = sessions.size();
            acc.put("index", idx);
            acc.put("user_id", Long.toString(uc.getClientUserId()));
            if (hidden) {
                // never shown by name; the 4-digit code's salted hash travels inside the encrypted file
                acc.put("name", "");
                acc.put("username", "");
                acc.put("phone_hint", "");
                acc.put("hidden", true);
                acc.put("hidden_hash", hiddenPrefs.getString(HIDDEN_HASH_PREFIX + a, ""));
                acc.put("hidden_salt", hiddenPrefs.getString(HIDDEN_SALT_PREFIX + a, ""));
                hiddenCount++;
            } else {
                acc.put("name", user != null ? UserObject.getUserName(user) : "");
                acc.put("username", user != null && UserObject.getPublicUsername(user) != null ? UserObject.getPublicUsername(user) : "");
                acc.put("phone_hint", user != null ? maskPhone(user.phone) : "");
                visibleCount++;
            }
            acc.put("test_backend", ConnectionsManager.getInstance(a).isTestBackend());
            acc.put("prefs", encodeMap(keep, null));
            accounts.put(acc);
            sessions.add(session);
        }
        manifest.put("accounts", accounts);
        if (outAccountCount != null) {
            outAccountCount[0] = visibleCount;
            if (outAccountCount.length > 1) outAccountCount[1] = hiddenCount;
            if (outAccountCount.length > 2) outAccountCount[2] = skippedCount;
        }

        WipeableBytes bos = new WipeableBytes();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            if (includeSettings) {
                zos.putNextEntry(new ZipEntry("settings.json"));
                zos.write(buildSettingsJson(true, includeHidden).toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            for (int i = 0; i < sessions.size(); i++) {
                zos.putNextEntry(new ZipEntry("accounts/" + i + "/" + TGNET));
                zos.write(sessions.get(i));
                zos.closeEntry();
                Arrays.fill(sessions.get(i), (byte) 0);
            }
        }
        byte[] out = bos.toByteArray();
        bos.wipe(); // the stream's own buffer holds the sessions too
        return out;
    }

    /** ByteArrayOutputStream whose internal buffer can be zeroed. */
    private static final class WipeableBytes extends ByteArrayOutputStream {
        void wipe() {
            Arrays.fill(buf, (byte) 0);
        }
    }

    /**
     * Writes an encrypted full backup to {@code uri}. Wipes {@code password}. Returns
     * [visible accounts written, visible accounts that could not be included] (hidden ones are
     * never counted in the UI). Call off the UI thread (the key derivation takes a second or two).
     */
    public static int[] writeFullBackup(Context context, Uri uri, char[] password, boolean includeSettings, boolean includeHidden) throws Exception {
        HanakoTelemetry.count(HanakoTelemetry.BACKUP_CREATE); // hanako: usage statistics (off by default)
        byte[] plain = null;
        byte[] key = null;
        try {
            if (password == null || password.length < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("password too short");
            }
            int[] count = new int[3];
            plain = buildFullPlain(includeSettings, includeHidden, count);
            if (count[0] + count[1] == 0) {
                throw new InvalidBackupException(ERR_NO_ACCOUNTS, "no accounts");
            }
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_LEN];
            byte[] nonce = new byte[NONCE_LEN];
            random.nextBytes(salt);
            random.nextBytes(nonce);
            byte[] header = header(PBKDF2_ITERATIONS, salt, nonce);
            key = pbkdf2(password, salt, PBKDF2_ITERATIONS, 32);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] ct = cipher.doFinal(plain);
            try (OutputStream os = openTruncated(context, uri)) {
                if (os == null) throw new IOException("cannot open output");
                os.write(header);
                os.write(ct);
            }
            return new int[]{count[0], count[2]};
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private static byte[] header(int iterations, byte[] salt, byte[] nonce) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.write(MAGIC);
        out.writeByte(FILE_VERSION);
        out.writeByte(KDF_PBKDF2_HMAC_SHA256);
        out.writeInt(iterations);
        out.writeByte(salt.length);
        out.write(salt);
        out.writeByte(nonce.length);
        out.write(nonce);
        out.flush();
        return bos.toByteArray();
    }

    /** Reads, decrypts and validates a full backup. Wipes {@code password}. Call off the UI thread. */
    public static FullBackup readFullBackup(Context context, Uri uri, char[] password) throws Exception {
        byte[] key = null;
        byte[] plain = null;
        try {
            byte[] file = readAll(context.getContentResolver(), uri, MAX_BACKUP_BYTES);
            if (looksLikeSettings(file)) {
                throw new InvalidBackupException(ERR_IS_SETTINGS, "settings file picked in Restore full backup");
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
            byte[] magic = new byte[MAGIC.length];
            try {
                in.readFully(magic);
            } catch (IOException e) {
                throw new InvalidBackupException(ERR_NOT_BACKUP, "not a Hanako backup");
            }
            if (!Arrays.equals(magic, MAGIC)) {
                throw new InvalidBackupException(ERR_NOT_BACKUP, "not a Hanako backup");
            }
            int version = in.readUnsignedByte();
            if (version > FILE_VERSION) {
                throw new InvalidBackupException(ERR_NEWER, "backup file version " + version);
            }
            if (version != FILE_VERSION) {
                throw new InvalidBackupException("unsupported backup version " + version);
            }
            int kdf = in.readUnsignedByte();
            if (kdf != KDF_PBKDF2_HMAC_SHA256) {
                throw new InvalidBackupException("unsupported key derivation " + kdf);
            }
            int iterations = in.readInt();
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                throw new InvalidBackupException("bad iteration count");
            }
            int saltLen = in.readUnsignedByte();
            if (saltLen < 16) throw new InvalidBackupException("bad salt");
            byte[] salt = new byte[saltLen];
            in.readFully(salt);
            int nonceLen = in.readUnsignedByte();
            if (nonceLen != NONCE_LEN) throw new InvalidBackupException("bad nonce");
            byte[] nonce = new byte[nonceLen];
            in.readFully(nonce);
            int headerLen = MAGIC.length + 1 + 1 + 4 + 1 + saltLen + 1 + nonceLen;
            int ctLen = file.length - headerLen;
            if (ctLen < GCM_TAG_BITS / 8) throw new InvalidBackupException("truncated backup");

            key = pbkdf2(password, salt, iterations, 32);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(file, 0, headerLen);
            try {
                plain = cipher.doFinal(file, headerLen, ctLen);
            } catch (AEADBadTagException e) {
                throw new WrongPasswordException();
            }
            return parsePlain(plain);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private static FullBackup parsePlain(byte[] plain) throws Exception {
        HashMap<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(plain))) {
            ZipEntry e;
            int n = 0;
            long inflated = 0;
            while ((e = zis.getNextEntry()) != null) {
                if (++n > 64) throw new InvalidBackupException("too many entries");
                String name = e.getName();
                if (e.isDirectory() || name.contains("..") || name.startsWith("/")) continue;
                byte[] data = readStream(zis, MAX_ENTRY_BYTES);
                inflated += data.length;
                if (inflated > MAX_INFLATED_BYTES) throw new InvalidBackupException("backup too large");
                entries.put(name, data);
            }
        }
        byte[] m = entries.get("manifest.json");
        if (m == null) throw new InvalidBackupException("manifest missing");
        FullBackup b = new FullBackup();
        try {
            b.manifest = new JSONObject(new String(m, StandardCharsets.UTF_8));
        } catch (JSONException ex) {
            throw new InvalidBackupException("bad manifest");
        }
        if (!FULL_FORMAT.equals(b.manifest.optString("format"))) throw new InvalidBackupException(ERR_NOT_BACKUP, "not a Hanako backup");
        int manifestVersion = b.manifest.optInt("version", -1);
        if (manifestVersion > 1) throw new InvalidBackupException(ERR_NEWER, "manifest version " + manifestVersion);
        if (manifestVersion != 1) throw new InvalidBackupException("unsupported manifest version");
        byte[] s = entries.get("settings.json");
        if (s != null) {
            try {
                b.settings = new JSONObject(new String(s, StandardCharsets.UTF_8));
                validateSettings(b.settings);
            } catch (JSONException | InvalidBackupException ex) {
                b.settings = null;
            }
        }
        JSONArray accounts = b.manifest.optJSONArray("accounts");
        if (accounts == null) throw new InvalidBackupException(ERR_NO_ACCOUNTS, "no accounts");
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject acc = accounts.optJSONObject(i);
            if (acc == null) throw new InvalidBackupException("bad account entry");
            AccountEntry e = new AccountEntry();
            String uid = acc.optString("user_id");
            if (!isLong(uid)) throw new InvalidBackupException("bad account entry");
            e.userId = Long.parseLong(uid);
            e.name = acc.optString("name");
            e.username = acc.optString("username");
            e.phoneHint = acc.optString("phone_hint");
            e.prefs = acc.optJSONObject("prefs");
            e.hidden = acc.optBoolean("hidden", false);
            if (e.hidden) {
                e.hiddenHash = acc.optString("hidden_hash", "");
                e.hiddenSalt = acc.optString("hidden_salt", "");
                if (TextUtils.isEmpty(e.hiddenHash) || TextUtils.isEmpty(e.hiddenSalt)) e.hidden = false;
            }
            if (e.prefs == null || !e.prefs.has("user")) throw new InvalidBackupException("bad account entry");
            validateTyped(e.prefs, HanakoBackup::isRestorableAccountKey);
            e.tgnet = entries.get("accounts/" + acc.optInt("index", -1) + "/" + TGNET);
            if (e.tgnet == null || e.tgnet.length == 0) throw new InvalidBackupException("session file missing");
            b.accounts.add(e);
        }
        if (b.accounts.isEmpty()) throw new InvalidBackupException(ERR_NO_ACCOUNTS, "no accounts");
        return b;
    }

    /** Lowest free account slots, in order. */
    public static ArrayList<Integer> freeSlots() {
        ArrayList<Integer> free = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) free.add(a);
        }
        return free;
    }

    /**
     * Picks a target slot for every account of the backup: an account already logged in here keeps
     * its slot when {@code replaceExisting}, and is skipped (-1) otherwise; the rest take free slots
     * in order (-1 when there is none left).
     */
    public static int[] planSlots(FullBackup b, boolean replaceExisting) {
        HashMap<Long, Integer> current = currentAccountsByUser();
        ArrayList<Integer> free = freeSlots();
        int[] slots = new int[b.accounts.size()];
        for (int i = 0; i < slots.length; i++) {
            Integer existing = current.get(b.accounts.get(i).userId);
            if (existing != null) {
                slots[i] = replaceExisting ? existing : -1;
            } else {
                slots[i] = free.isEmpty() ? -1 : free.remove(0);
            }
        }
        return slots;
    }

    public static boolean isLoggedInHere(long userId) {
        return currentAccountsByUser().containsKey(userId);
    }

    /** Stages the restore; applied at the next process start. Returns the number of staged accounts. */
    public static int stageFullRestore(FullBackup b, int[] slots, boolean restoreSettings) throws Exception {
        HanakoTelemetry.count(HanakoTelemetry.BACKUP_RESTORE); // hanako: usage statistics (off by default)
        Context ctx = ApplicationLoader.applicationContext;
        File dir = stageDir(ctx);
        deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create staging dir");
        JSONObject plan = new JSONObject();
        plan.put("version", 1);
        plan.put("created_ms", System.currentTimeMillis());
        JSONArray accs = new JSONArray();
        HashMap<Long, Integer> userToSlot = currentAccountsByUser();
        HashSet<Integer> used = new HashSet<>();
        for (int i = 0; i < b.accounts.size() && i < slots.length; i++) {
            int slot = slots[i];
            if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT || !used.add(slot)) continue;
            AccountEntry e = b.accounts.get(i);
            File f = new File(dir, "slot" + slot + ".tgnet");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(e.tgnet);
                fos.getFD().sync();
            }
            JSONObject prefs = new JSONObject(e.prefs.toString());
            prefs.put("registeredForPush", new JSONObject().put("t", "b").put("v", false));
            JSONObject entry = new JSONObject().put("slot", slot).put("user_id", Long.toString(e.userId)).put("prefs", prefs);
            if (e.hidden) {
                entry.put("hidden_hash", e.hiddenHash).put("hidden_salt", e.hiddenSalt);
            } else if (HiddenAccountHelper.isAccountHidden(slot) && UserConfig.getInstance(slot).isClientActivated()) {
                // replacing the session of an account that is hidden here: it stays hidden
                entry.put("keep_hidden", true);
            }
            accs.put(entry);
            // a slot that held another account stops mapping to it
            Iterator<Map.Entry<Long, Integer>> it = userToSlot.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() == slot) it.remove();
            }
            userToSlot.put(e.userId, slot);
        }
        if (accs.length() == 0) {
            deleteRecursive(dir);
            return 0;
        }
        plan.put("accounts", accs);
        if (restoreSettings && b.settings != null) {
            int current = UserConfig.selectedAccount;
            plan.put("settings", resolveSettings(b.settings, userToSlot, current, true, hiddenAccounts()));
        }
        writePlan(plan, dir);
        return accs.length();
    }

    private static File stageDir(Context ctx) {
        File files = ctx.getFilesDir();
        if (files == null) files = ApplicationLoader.getFilesDirFixed();
        return new File(files, STAGE_DIR);
    }

    /** The plan file is written last and renamed into place: it is the commit marker of a staging. */
    private static void writePlan(JSONObject plan, File dir) throws Exception {
        Context ctx = ApplicationLoader.applicationContext;
        if (dir == null) {
            dir = stageDir(ctx);
            deleteRecursive(dir);
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create staging dir");
        File tmp = new File(dir, STAGE_PLAN + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(plan.toString().getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
        File target = new File(dir, STAGE_PLAN);
        if (!tmp.renameTo(target)) throw new IOException("cannot commit staging");
    }

    public static void discardStaged() {
        deleteRecursive(stageDir(ApplicationLoader.applicationContext));
    }

    // =====================================================================================
    // Startup apply
    // =====================================================================================

    /**
     * Applies a staged settings import / account restore. Called from ApplicationLoader.onCreate
     * before anything reads SharedConfig / UserConfig or starts the native ConnectionsManager.
     * Never throws; the staging dir is always removed afterwards.
     */
    public static void applyPendingAtStartup(Context ctx) {
        if (ctx == null) return;
        int planned = 0;
        int restored = 0;
        boolean settingsPlanned = false;
        boolean settingsApplied = false;
        String error = null;
        File dir;
        try {
            File files = ctx.getFilesDir();
            if (files == null) return;
            dir = new File(files, STAGE_DIR);
            if (!dir.exists()) return;
        } catch (Throwable t) {
            return;
        }
        try {
            String proc = processName();
            if (proc != null && !proc.equals(ctx.getPackageName())) {
                return; // only the main process applies (and deletes) the staging
            }
        } catch (Throwable t) {
            return;
        }
        try {
            File planFile = new File(dir, STAGE_PLAN);
            if (!planFile.isFile()) {
                return; // incomplete staging: dropped below
            }
            byte[] data;
            try (InputStream in = new FileInputStream(planFile)) {
                data = readStream(in, MAX_BACKUP_BYTES);
            }
            JSONObject plan = new JSONObject(new String(data, StandardCharsets.UTF_8));
            // A restore the user started but never restarted into (app swiped away) must not
            // change sessions much later, e.g. at a background push start.
            long created = plan.optLong("created_ms", 0);
            if (created > 0 && Math.abs(System.currentTimeMillis() - created) > PLAN_MAX_AGE_MS) {
                error = "expired";
                return;
            }
            JSONArray accs = plan.optJSONArray("accounts");
            planned = accs != null ? accs.length() : 0;
            String[] firstError = new String[1];
            restored = applyAccounts(ctx, dir, accs, firstError);
            if (firstError[0] != null) error = firstError[0];
            JSONObject settings = plan.optJSONObject("settings");
            settingsPlanned = settings != null;
            if (settings != null) {
                applySettings(ctx, settings);
                settingsApplied = true;
            }
            Log.i(TAG, "applied staged restore: accounts=" + restored + "/" + planned + " settings=" + settingsApplied);
        } catch (Throwable t) {
            Log.e(TAG, "staged restore failed", t);
            error = rawError(t);
        } finally {
            writeResult(ctx, planned, restored, settingsPlanned, settingsApplied, error);
            deleteRecursive(dir);
        }
    }

    /** Name of this process (only the main one applies a staging). */
    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        try (InputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            int len = 0;
            while (len < n && buf[len] != 0) len++;
            return len > 0 ? new String(buf, 0, len, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Outcome of the staged restore / import, shown once after the restart (showPendingResult). */
    private static void writeResult(Context ctx, int planned, int restored, boolean settingsPlanned, boolean settingsApplied, String error) {
        try {
            if (planned == 0 && !settingsPlanned && error == null) return; // incomplete staging, nothing was tried
            if (error != null && error.length() > 500) error = error.substring(0, 500);
            JSONObject r = new JSONObject();
            r.put("planned", planned);
            r.put("restored", restored);
            r.put("settings_planned", settingsPlanned);
            r.put("settings", settingsApplied);
            if (error != null) r.put("error", error);
            File f = new File(ctx.getFilesDir(), RESULT_FILE);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(r.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.e(TAG, "cannot write restore result", t);
        }
    }

    /** Shows, once, what the last restore / settings import did. Call on the UI thread from a visible screen. */
    public static void showPendingResult(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        File f;
        JSONObject r;
        try {
            f = new File(ApplicationLoader.applicationContext.getFilesDir(), RESULT_FILE);
            if (!f.isFile()) return;
            byte[] data;
            try (InputStream in = new FileInputStream(f)) {
                data = readStream(in, 64 * 1024);
            }
            f.delete();
            r = new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            FileLog.e(t);
            return;
        }
        int planned = r.optInt("planned", 0);
        int restored = r.optInt("restored", 0);
        boolean settingsPlanned = r.optBoolean("settings_planned", false);
        boolean settings = r.optBoolean("settings", false);
        boolean failed = r.has("error");
        final String errorText = r.optString("error", "");
        StringBuilder msg = new StringBuilder();
        String title;
        if ("expired".equals(errorText)) {
            title = LocaleController.getString(R.string.HanakoBackupRestoreTitle);
            msg.append(LocaleController.getString(R.string.HanakoResultExpired));
        } else if (planned > 0) {
            title = LocaleController.getString(R.string.HanakoBackupRestoreTitle);
            String accounts = LocaleController.formatPluralString("HanakoAccounts", restored);
            if (restored == 0) {
                msg.append(LocaleController.getString(R.string.HanakoResultRestoreFailed));
            } else if (restored < planned) {
                msg.append(LocaleController.formatString(R.string.HanakoResultRestorePartial, accounts, planned - restored));
            } else if (settings) {
                msg.append(LocaleController.formatString(R.string.HanakoResultRestoredWithSettings, accounts));
            } else {
                msg.append(LocaleController.formatString(R.string.HanakoResultRestored, accounts));
            }
            if (settingsPlanned && !settings && restored > 0) {
                msg.append("\n\n").append(LocaleController.getString(R.string.HanakoResultSettingsFailed));
            }
            if (restored > 0) {
                msg.append("\n\n").append(LocaleController.getString(R.string.HanakoResultPasscodeNote));
            }
        } else {
            title = LocaleController.getString(R.string.HanakoBackupImportSettings);
            msg.append(LocaleController.getString(settings && !failed ? R.string.HanakoResultSettingsImported : R.string.HanakoResultSettingsFailed));
        }
        try {
            org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
            b.setTitle(title);
            b.setMessage(msg.toString());
            b.setPositiveButton(LocaleController.getString(R.string.OK), null);
            if (failed && !"expired".equals(errorText)) {
                b.setNeutralButton(LocaleController.getString(R.string.HanakoErrDetails), (d, w) -> {
                    org.telegram.ui.ActionBar.AlertDialog.Builder details = new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
                    details.setTitle(LocaleController.getString(R.string.HanakoErrDetails));
                    details.setMessage(errorText);
                    details.setPositiveButton(LocaleController.getString(R.string.OK), null);
                    fragment.showDialog(details.create());
                });
            }
            fragment.showDialog(b.create());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * Restores each staged slot on its own. Per slot, the new session is first copied next to the
     * live one; only when that worked are the old session and cache dropped and the copy renamed
     * in, so a failed copy (no space, I/O error) leaves that slot exactly as it was. The first
     * error is returned in {@code firstError}; the other slots still go ahead.
     */
    private static int applyAccounts(Context ctx, File dir, JSONArray accs, String[] firstError) {
        if (accs == null) return 0;
        int done = 0;
        int firstSlot = -1;
        for (int i = 0; i < accs.length(); i++) {
            JSONObject acc = accs.optJSONObject(i);
            if (acc == null) continue;
            int slot = acc.optInt("slot", -1);
            if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT) continue;
            try {
                if (applyAccount(ctx, dir, slot, acc)) {
                    done++;
                    if (firstSlot < 0) firstSlot = slot;
                }
            } catch (Throwable t) {
                Log.e(TAG, "restore of one account failed", t);
                if (firstError[0] == null) firstError[0] = rawError(t);
            }
        }
        if (firstSlot >= 0) {
            // make sure the app opens on an account that exists
            SharedPreferences main = ctx.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
            int selected = main.getInt("selectedAccount", 0);
            boolean selectedOk = selected >= 0 && selected < UserConfig.MAX_ACCOUNT_COUNT
                    && ctx.getSharedPreferences(userConfigPrefsName(selected), Context.MODE_PRIVATE).contains("user");
            if (!selectedOk) {
                main.edit().putInt("selectedAccount", firstSlot).commit();
            }
        }
        return done;
    }

    private static boolean applyAccount(Context ctx, File dir, int slot, JSONObject acc) throws IOException {
        File session = new File(dir, "slot" + slot + ".tgnet");
        JSONObject prefs = acc.optJSONObject("prefs");
        if (!session.isFile() || prefs == null) return false;

        File target = slotDir(ctx, slot);
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create account dir");
        File live = new File(target, TGNET);
        File tmp = copyToTemp(session, live); // throws before anything is touched
        for (String name : SLOT_FILES_TO_DROP) {
            File f = new File(target, name);
            if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + name);
        }
        if (!tmp.renameTo(live)) {
            tmp.delete();
            throw new IOException("cannot move " + TGNET);
        }

        SharedPreferences sp = ctx.getSharedPreferences(userConfigPrefsName(slot), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        if (slot != 0) {
            editor.clear();
        } else {
            // slot 0 shares its file with SharedConfig's global keys: only drop the per-account ones
            for (String key : sp.getAll().keySet()) {
                if (isPerAccountKey(key)) editor.remove(key);
            }
        }
        putTyped(editor, prefs, HanakoBackup::isRestorableAccountKey);
        editor.commit();
        dropSlotKeys(ctx, slot);

        // hidden-account state of the slot (Mercurygram HiddenAccountHelper keys in mainconfig)
        SharedPreferences hiddenPrefs = ctx.getSharedPreferences(HIDDEN_PREFS, Context.MODE_PRIVATE);
        String hash = acc.optString("hidden_hash", "");
        String salt = acc.optString("hidden_salt", "");
        if (!TextUtils.isEmpty(hash) && !TextUtils.isEmpty(salt)) {
            // stealth mode lets the code be typed into chat search when no passcode is set;
            // HiddenAccountHelper turns it off again by itself if a passcode exists
            hiddenPrefs.edit()
                    .putString(HIDDEN_HASH_PREFIX + slot, hash)
                    .putString(HIDDEN_SALT_PREFIX + slot, salt)
                    .putBoolean(HIDDEN_STEALTH_KEY, true)
                    .commit();
        } else if (!acc.optBoolean("keep_hidden", false)) {
            // a stale hidden marker on a reused slot must not hide the restored account
            hiddenPrefs.edit().remove(HIDDEN_HASH_PREFIX + slot).remove(HIDDEN_SALT_PREFIX + slot).commit();
        }
        return true;
    }

    /*
     * Old slot-numbered feature keys ("on_3", "u_3_<user>") of a restored slot belonged to the
     * account that was there before; left alone they would be moved onto the restored account
     * the first time it reads them (PlusUtil.accountKey).
     */
    private static void dropSlotKeys(Context ctx, int slot) {
        for (String name : new String[]{"plus_ghost", "plus_activity_log"}) {
            SharedPreferences sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            for (String key : sp.getAll().keySet()) {
                boolean slotKey = !PlusUtil.isUserKey(key) && PlusUtil.slotOfKey(key) == slot;
                if (slotKey || key.startsWith("u_" + slot + "_")) e.remove(key);
            }
            e.commit();
        }
    }

    private static boolean isPerAccountKey(String key) {
        if (USERCONFIG_ACCOUNT_KEYS.contains(key) || MG_ACCOUNT_KEYS.contains(key)) return true;
        for (String p : USERCONFIG_ACCOUNT_PREFIXES) {
            if (key.startsWith(p)) return true;
        }
        return false;
    }

    private static void applySettings(Context ctx, JSONObject settings) {
        JSONObject global = settings.optJSONObject("global");
        if (global != null) {
            SharedPreferences.Editor e = ctx.getSharedPreferences("userconfing", Context.MODE_PRIVATE).edit();
            putTyped(e, global, HanakoBackup::isGlobalSettingKey);
            e.commit();
        }
        // plans staged by older builds carry neither flag: they replaced whole files
        final boolean replacePrefs = settings.optBoolean("replace_prefs", true);
        final boolean replaceLists = settings.optBoolean("replace_lists", true);
        final HiddenSet preserve = HiddenSet.fromJson(settings.optJSONObject("preserve"));
        JSONObject files = settings.optJSONObject("files");
        if (files != null) {
            Iterator<String> it = files.keys();
            while (it.hasNext()) {
                final String name = it.next();
                JSONObject f = files.optJSONObject(name);
                if (!isSettingsFile(name) || f == null) continue;
                SharedPreferences sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
                SharedPreferences.Editor e = sp.edit();
                // drop only the kind of values being replaced, and never a hidden account's
                for (String key : sp.getAll().keySet()) {
                    boolean list = isListKey(name, key);
                    if ((list ? replaceLists : replacePrefs) && !preserve.touches(key)) e.remove(key);
                }
                putTyped(e, f, key -> (isListKey(name, key) ? replaceLists : replacePrefs) && !preserve.touches(key));
                e.commit();
            }
        }
        // the account in use's feature settings, already keyed by its user id (resolveSettings)
        JSONObject accountFiles = settings.optJSONObject("account_files");
        if (accountFiles != null) {
            Iterator<String> it = accountFiles.keys();
            while (it.hasNext()) {
                final String name = it.next();
                JSONObject f = accountFiles.optJSONObject(name);
                if (!isSettingsFile(name) || f == null) continue;
                SharedPreferences.Editor e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
                putTyped(e, f, key -> PlusUtil.isUserKey(key) && !preserve.touches(key));
                e.commit();
            }
        }
        JSONArray accounts = settings.optJSONArray("accounts");
        if (accounts != null) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject acc = accounts.optJSONObject(i);
                if (acc == null) continue;
                int slot = acc.optInt("slot", -1);
                JSONObject p = acc.optJSONObject("prefs");
                if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT || p == null || preserve.slots.contains(slot)) continue;
                SharedPreferences.Editor e = ctx.getSharedPreferences(userConfigPrefsName(slot), Context.MODE_PRIVATE).edit();
                putTyped(e, p, MG_ACCOUNT_KEYS::contains);
                e.commit();
            }
        }
    }

    // =====================================================================================
    // Restart / login entry
    // =====================================================================================

    /** Same restart as the debug "tablet mode" toggle: relaunch the launcher activity and exit the process. */
    public static void restartApp(Activity activity) {
        try {
            Context ctx = activity != null ? activity : ApplicationLoader.applicationContext;
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (activity != null) {
                activity.finishAffinity();
                if (intent != null) activity.startActivity(intent);
            } else if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                ctx.startActivity(intent);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        System.exit(0);
    }

    /** Adds "Restore from Hanako backup" under the phone input of the login screen. Returns the height used (dp). */
    public static int addLoginButton(LinearLayout parent, BaseFragment fragment) {
        TextView button = new TextView(parent.getContext());
        button.setText(LocaleController.getString(R.string.HanakoBackupRestoreLogin));
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        int pad = org.telegram.messenger.AndroidUtilities.dp(4);
        button.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);
        button.setMinHeight(org.telegram.messenger.AndroidUtilities.dp(48)); // touch target
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(v -> fragment.presentFragment(new it.belloworld.mercurygram.ui.HanakoBackupActivity(true)));
        // a restore that ended back here (nothing restored) explains itself once
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> showPendingResult(fragment), 600);
        parent.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
        return 52;
    }

    // =====================================================================================
    // Crypto / IO helpers
    // =====================================================================================

    /**
     * Opens a picked file for writing from the start. "w" alone does not truncate on some
     * providers, so overwriting a longer old backup would leave its tail and break the GCM tag.
     */
    private static OutputStream openTruncated(Context context, Uri uri) throws IOException {
        try {
            return context.getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException | java.io.FileNotFoundException e) {
            return context.getContentResolver().openOutputStream(uri, "w");
        }
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018) over the UTF-8 bytes of the password. Same result on every device. */
    static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int dkLen) throws GeneralSecurityException {
        if (password == null || password.length == 0) throw new GeneralSecurityException("empty password");
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] pw = new byte[bb.remaining()];
        bb.get(pw);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pw, "HmacSHA256"));
            int hLen = mac.getMacLength();
            int blocks = (dkLen + hLen - 1) / hLen;
            byte[] out = new byte[dkLen];
            byte[] u = new byte[hLen];
            byte[] t = new byte[hLen];
            for (int block = 1; block <= blocks; block++) {
                mac.update(salt);
                mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
                mac.doFinal(u, 0);
                System.arraycopy(u, 0, t, 0, hLen);
                for (int i = 1; i < iterations; i++) {
                    mac.update(u);
                    mac.doFinal(u, 0);
                    for (int k = 0; k < hLen; k++) t[k] ^= u[k];
                }
                int off = (block - 1) * hLen;
                System.arraycopy(t, 0, out, off, Math.min(hLen, dkLen - off));
            }
            Arrays.fill(u, (byte) 0);
            Arrays.fill(t, (byte) 0);
            return out;
        } finally {
            Arrays.fill(pw, (byte) 0);
        }
    }

    static byte[] readAll(ContentResolver resolver, Uri uri, int max) throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open input");
            return readStream(in, max);
        }
    }

    private static byte[] readStream(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            total += r;
            if (total > max) throw new IOException("file too large");
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    /** Copies {@code from} to "&lt;to&gt;.hanako_tmp" next to {@code to} (synced) and returns it. */
    private static File copyToTemp(File from, File to) throws IOException {
        File tmp = new File(to.getParentFile(), to.getName() + ".hanako_tmp");
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        return tmp;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Name shown for a backup account; hidden accounts are never named. */
    public static String accountLabel(AccountEntry e) {
        if (e.hidden) {
            return LocaleController.getString(R.string.HanakoBackupHiddenAccount);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(TextUtils.isEmpty(e.name) ? Long.toString(e.userId) : e.name);
        if (!TextUtils.isEmpty(e.username)) sb.append(" @").append(e.username);
        if (!TextUtils.isEmpty(e.phoneHint)) sb.append(" ").append(e.phoneHint);
        return sb.toString();
    }

    /** For the restore summary dialog. */
    public static List<String> describeAccounts(FullBackup b, int[] slots) {
        ArrayList<String> lines = new ArrayList<>();
        HashMap<Long, Integer> current = currentAccountsByUser();
        for (int i = 0; i < b.accounts.size(); i++) {
            AccountEntry e = b.accounts.get(i);
            StringBuilder sb = new StringBuilder("• ");
            sb.append(accountLabel(e));
            sb.append(" — ");
            if (slots[i] < 0) {
                sb.append(LocaleController.getString(current.containsKey(e.userId) ? R.string.HanakoBackupAccountSkipExisting : R.string.HanakoBackupAccountNoSlot));
            } else if (current.containsKey(e.userId)) {
                sb.append(LocaleController.getString(R.string.HanakoBackupAccountReplace));
            } else {
                sb.append(LocaleController.getString(R.string.HanakoBackupAccountNew));
            }
            lines.add(sb.toString());
        }
        return lines;
    }
}
