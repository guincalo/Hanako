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
 * <p>The password is only ever held in a char[] / byte[] that is wiped after the key derivation;
 * it is never logged or stored.
 */
public final class HanakoBackup {

    private static final String TAG = "HanakoBackup";

    // ---- settings JSON ----
    public static final String SETTINGS_FORMAT = "hanako-settings";
    public static final int SETTINGS_VERSION = 1;

    // ---- encrypted full backup ----
    public static final String FULL_FORMAT = "hanako-full-backup";
    private static final byte[] MAGIC = {'H', 'N', 'K', 'B', 'A', 'K', '0', '1'};
    private static final int FILE_VERSION = 1;
    private static final int KDF_PBKDF2_HMAC_SHA256 = 1;
    public static final int PBKDF2_ITERATIONS = 400_000;
    private static final int MIN_ITERATIONS = 310_000;
    private static final int MAX_ITERATIONS = 20_000_000;
    private static final int SALT_LEN = 32;
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_BACKUP_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_SETTINGS_BYTES = 4 * 1024 * 1024;
    public static final int MIN_PASSWORD_LENGTH = 8;

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
        public InvalidBackupException(String message) {
            super(message);
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

    /** Builds the settings document from the live state. Call off the UI thread. */
    public static JSONObject buildSettingsJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", SETTINGS_FORMAT);
        root.put("version", SETTINGS_VERSION);
        root.put("app", ApplicationLoader.applicationContext.getPackageName());
        root.put("app_version", appVersion());
        root.put("created", System.currentTimeMillis() / 1000L);

        root.put("global", encodeMap(prefs("userconfing").getAll(), HanakoBackup::isGlobalSettingKey));

        JSONObject files = new JSONObject();
        for (String name : SETTINGS_PREF_FILES) {
            Map<String, ?> all = prefs(name).getAll();
            if (!all.isEmpty()) {
                files.put(name, encodeMap(all, null));
            }
        }
        root.put("files", files);

        JSONArray accounts = new JSONArray();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.isClientActivated()) {
                continue;
            }
            CaptureEditor cap = new CaptureEditor();
            uc.mg.save(cap);
            JSONObject acc = new JSONObject();
            acc.put("user_id", Long.toString(uc.getClientUserId()));
            acc.put("prefs", encodeMap(cap.values, MG_ACCOUNT_KEYS::contains));
            accounts.put(acc);
        }
        root.put("accounts", accounts);
        return root;
    }

    public static void writeSettings(Context context, Uri uri) throws Exception {
        byte[] data = buildSettingsJson().toString(2).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = context.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) {
                throw new IOException("cannot open output");
            }
            os.write(data);
        }
    }

    /** Parses and validates a settings document. */
    public static JSONObject readSettings(Context context, Uri uri) throws Exception {
        byte[] data = readAll(context.getContentResolver(), uri, MAX_SETTINGS_BYTES);
        JSONObject root;
        try {
            root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new InvalidBackupException("not a JSON file");
        }
        validateSettings(root);
        return root;
    }

    public static void validateSettings(JSONObject root) throws InvalidBackupException {
        if (!SETTINGS_FORMAT.equals(root.optString("format"))) {
            throw new InvalidBackupException("not a Hanako settings file");
        }
        int version = root.optInt("version", -1);
        if (version < 1 || version > SETTINGS_VERSION) {
            throw new InvalidBackupException("unsupported settings version " + version);
        }
        JSONObject global = root.optJSONObject("global");
        if (global != null) {
            validateTyped(global, HanakoBackup::isGlobalSettingKey);
        }
        JSONObject files = root.optJSONObject("files");
        if (files != null) {
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
        JSONArray accounts = root.optJSONArray("accounts");
        if (accounts != null) {
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
    }

    /** Number of individual values the document would apply (for the confirmation dialog). */
    public static int countSettings(JSONObject root) {
        int n = 0;
        JSONObject global = root.optJSONObject("global");
        if (global != null) {
            Iterator<String> it = global.keys();
            while (it.hasNext()) {
                if (isGlobalSettingKey(it.next())) n++;
            }
        }
        JSONObject files = root.optJSONObject("files");
        if (files != null) {
            Iterator<String> it = files.keys();
            while (it.hasNext()) {
                String name = it.next();
                JSONObject f = files.optJSONObject(name);
                if (isSettingsFile(name) && f != null) n += f.length();
            }
        }
        JSONArray accounts = root.optJSONArray("accounts");
        if (accounts != null) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject acc = accounts.optJSONObject(i);
                JSONObject p = acc != null ? acc.optJSONObject("prefs") : null;
                if (p != null) n += p.length();
            }
        }
        return n;
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

    /**
     * Turns a validated settings document into the staged form: account sections are resolved to
     * slots (by Telegram user id); sections for accounts that are not here are dropped.
     */
    private static JSONObject resolveSettings(JSONObject root, Map<Long, Integer> userToSlot) throws JSONException {
        JSONObject out = new JSONObject();
        JSONObject global = root.optJSONObject("global");
        if (global != null) out.put("global", global);
        JSONObject files = root.optJSONObject("files");
        if (files != null) out.put("files", files);
        JSONArray slots = new JSONArray();
        JSONArray accounts = root.optJSONArray("accounts");
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
                if (slot == null || p == null) continue;
                slots.put(new JSONObject().put("slot", slot).put("prefs", p));
            }
        }
        out.put("accounts", slots);
        return out;
    }

    /** Stages a settings import; it is applied on the next start (see {@link #restartApp(Activity)}). */
    public static void stageSettingsImport(JSONObject root) throws Exception {
        JSONObject plan = new JSONObject();
        plan.put("version", 1);
        plan.put("settings", resolveSettings(root, currentAccountsByUser()));
        writePlan(plan, null);
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

    /** Plain (unencrypted) zip of the full backup. Wipe the returned array after use. */
    private static byte[] buildFullPlain(boolean includeSettings, int[] outAccountCount) throws Exception {
        Context ctx = ApplicationLoader.applicationContext;
        JSONObject manifest = new JSONObject();
        manifest.put("format", FULL_FORMAT);
        manifest.put("version", 1);
        manifest.put("app", ctx.getPackageName());
        manifest.put("app_version", appVersion());
        manifest.put("created", System.currentTimeMillis() / 1000L);
        JSONArray accounts = new JSONArray();
        ArrayList<byte[]> sessions = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.isClientActivated()) continue;
            byte[] session = readSessionFile(ctx, a);
            if (session == null) {
                FileLog.d("hanako backup: no session file for slot " + a);
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
            acc.put("name", user != null ? UserObject.getUserName(user) : "");
            acc.put("username", user != null && UserObject.getPublicUsername(user) != null ? UserObject.getPublicUsername(user) : "");
            acc.put("phone_hint", user != null ? maskPhone(user.phone) : "");
            acc.put("test_backend", ConnectionsManager.getInstance(a).isTestBackend());
            acc.put("prefs", encodeMap(keep, null));
            accounts.put(acc);
            sessions.add(session);
        }
        manifest.put("accounts", accounts);
        if (outAccountCount != null) outAccountCount[0] = sessions.size();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            if (includeSettings) {
                zos.putNextEntry(new ZipEntry("settings.json"));
                zos.write(buildSettingsJson().toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            for (int i = 0; i < sessions.size(); i++) {
                zos.putNextEntry(new ZipEntry("accounts/" + i + "/" + TGNET));
                zos.write(sessions.get(i));
                zos.closeEntry();
                Arrays.fill(sessions.get(i), (byte) 0);
            }
        }
        return bos.toByteArray();
    }

    /**
     * Writes an encrypted full backup to {@code uri}. Wipes {@code password}. Returns the number of
     * accounts written. Call off the UI thread (the key derivation takes a second or two).
     */
    public static int writeFullBackup(Context context, Uri uri, char[] password, boolean includeSettings) throws Exception {
        byte[] plain = null;
        byte[] key = null;
        try {
            int[] count = new int[1];
            plain = buildFullPlain(includeSettings, count);
            if (count[0] == 0) {
                throw new InvalidBackupException("no accounts");
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
            try (OutputStream os = context.getContentResolver().openOutputStream(uri, "w")) {
                if (os == null) throw new IOException("cannot open output");
                os.write(header);
                os.write(ct);
            }
            return count[0];
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
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
            byte[] magic = new byte[MAGIC.length];
            try {
                in.readFully(magic);
            } catch (IOException e) {
                throw new InvalidBackupException("not a Hanako backup");
            }
            if (!Arrays.equals(magic, MAGIC)) {
                throw new InvalidBackupException("not a Hanako backup");
            }
            int version = in.readUnsignedByte();
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
            while ((e = zis.getNextEntry()) != null) {
                if (++n > 64) throw new InvalidBackupException("too many entries");
                String name = e.getName();
                if (e.isDirectory() || name.contains("..") || name.startsWith("/")) continue;
                entries.put(name, readStream(zis, MAX_ENTRY_BYTES));
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
        if (!FULL_FORMAT.equals(b.manifest.optString("format"))) throw new InvalidBackupException("not a Hanako backup");
        if (b.manifest.optInt("version", -1) != 1) throw new InvalidBackupException("unsupported manifest version");
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
        if (accounts == null) throw new InvalidBackupException("no accounts");
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
            if (e.prefs == null || !e.prefs.has("user")) throw new InvalidBackupException("bad account entry");
            validateTyped(e.prefs, null);
            e.tgnet = entries.get("accounts/" + acc.optInt("index", -1) + "/" + TGNET);
            if (e.tgnet == null || e.tgnet.length == 0) throw new InvalidBackupException("session file missing");
            b.accounts.add(e);
        }
        if (b.accounts.isEmpty()) throw new InvalidBackupException("no accounts");
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
        Context ctx = ApplicationLoader.applicationContext;
        File dir = stageDir(ctx);
        deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create staging dir");
        JSONObject plan = new JSONObject();
        plan.put("version", 1);
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
            accs.put(new JSONObject().put("slot", slot).put("user_id", Long.toString(e.userId)).put("prefs", prefs));
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
            plan.put("settings", resolveSettings(b.settings, userToSlot));
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
            if (Build.VERSION.SDK_INT >= 28) {
                String proc = Application.getProcessName();
                if (proc != null && !proc.equals(ctx.getPackageName())) {
                    return; // only the main process applies (and deletes) the staging
                }
            }
            File planFile = new File(dir, STAGE_PLAN);
            if (!planFile.isFile()) {
                return; // incomplete staging: dropped below
            }
            byte[] data;
            try (InputStream in = new FileInputStream(planFile)) {
                data = readStream(in, MAX_BACKUP_BYTES);
            }
            JSONObject plan = new JSONObject(new String(data, StandardCharsets.UTF_8));
            int restored = applyAccounts(ctx, dir, plan.optJSONArray("accounts"));
            JSONObject settings = plan.optJSONObject("settings");
            if (settings != null) {
                applySettings(ctx, settings);
            }
            Log.i(TAG, "applied staged restore: accounts=" + restored + " settings=" + (settings != null));
        } catch (Throwable t) {
            Log.e(TAG, "staged restore failed", t);
        } finally {
            deleteRecursive(dir);
        }
    }

    private static int applyAccounts(Context ctx, File dir, JSONArray accs) throws Exception {
        if (accs == null) return 0;
        int done = 0;
        int firstSlot = -1;
        for (int i = 0; i < accs.length(); i++) {
            JSONObject acc = accs.optJSONObject(i);
            if (acc == null) continue;
            int slot = acc.optInt("slot", -1);
            if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT) continue;
            File session = new File(dir, "slot" + slot + ".tgnet");
            JSONObject prefs = acc.optJSONObject("prefs");
            if (!session.isFile() || prefs == null) continue;

            File target = slotDir(ctx, slot);
            if (!target.isDirectory() && !target.mkdirs()) continue;
            for (String name : SLOT_FILES_TO_DROP) {
                File f = new File(target, name);
                if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + name);
            }
            copyFile(session, new File(target, TGNET));

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
            putTyped(editor, prefs, null);
            editor.commit();
            done++;
            if (firstSlot < 0) firstSlot = slot;
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
        JSONObject files = settings.optJSONObject("files");
        if (files != null) {
            Iterator<String> it = files.keys();
            while (it.hasNext()) {
                String name = it.next();
                JSONObject f = files.optJSONObject(name);
                if (!isSettingsFile(name) || f == null) continue;
                SharedPreferences.Editor e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
                e.clear();
                putTyped(e, f, null);
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
                if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT || p == null) continue;
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
        button.setPadding(pad, pad * 2, pad, pad * 2);
        button.setOnClickListener(v -> fragment.presentFragment(new it.belloworld.mercurygram.ui.HanakoBackupActivity(true)));
        parent.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
        return 44;
    }

    // =====================================================================================
    // Crypto / IO helpers
    // =====================================================================================

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

    private static void copyFile(File from, File to) throws IOException {
        File tmp = new File(to.getParentFile(), to.getName() + ".hanako_tmp");
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.getFD().sync();
        }
        if (!tmp.renameTo(to)) {
            tmp.delete();
            throw new IOException("cannot move " + to.getName());
        }
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

    /** For the restore summary dialog. */
    public static List<String> describeAccounts(FullBackup b, int[] slots) {
        ArrayList<String> lines = new ArrayList<>();
        HashMap<Long, Integer> current = currentAccountsByUser();
        for (int i = 0; i < b.accounts.size(); i++) {
            AccountEntry e = b.accounts.get(i);
            StringBuilder sb = new StringBuilder("• ");
            sb.append(TextUtils.isEmpty(e.name) ? Long.toString(e.userId) : e.name);
            if (!TextUtils.isEmpty(e.username)) sb.append(" @").append(e.username);
            if (!TextUtils.isEmpty(e.phoneHint)) sb.append(" ").append(e.phoneHint);
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
