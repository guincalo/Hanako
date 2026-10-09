package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.utils.proxy.ProxySettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * hanako: optional usage statistics for the owner of a build, to see which features are used
 * at all (and which are dead weight). OFF by default; nothing is collected while it is off.
 *
 * <p>What is kept, in files/hanako_telemetry/pending.json:
 * <ul>
 * <li>per day (UTC), how many times each named feature was used: fixed names only
 *     ({@link #count}), never an argument from the user;</li>
 * <li>crashes and caught errors: exception class, a scrubbed message, the code locations of
 *     the stack (class.method(File.java:line)), app version, Android version, device model.</li>
 * </ul>
 * Never: message text, chat / user ids, usernames, phone numbers, titles, file names,
 * contacts, tokens. Messages and stack lines go through {@link #scrub}: digit runs longer than
 * 5, phone- and @username-like text, URLs, URIs, paths, file names, quoted text and e-mail
 * addresses are replaced.
 *
 * <p>Upload: plain JSON POST over HTTPS to the endpoint set in the settings (empty = never),
 * at most once a day, plus once at the next start after a crash. It follows the app's proxy:
 * through a SOCKS5 proxy (Tor included) when one is on, and not at all when the proxy is an
 * MTProto one, Tor is switched on but not running, or the proxy needs a password (HTTPS can't
 * go through those, and going direct would show this phone's IP). The install id is random
 * and can be reset. Uses only the platform: no SDKs.
 */
public final class HanakoTelemetry {

    private static final String TAG = "HanakoTelemetry";
    private static final String PREFS = "hanako_telemetry";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_SECRET = "upload_key";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_LAST_UPLOAD_DAY = "last_upload_day";
    private static final String DIR = "hanako_telemetry";
    private static final String PENDING = "pending.json";
    private static final String CRASH = "crash.json";

    public static final int SCHEMA = 1;
    private static final int MAX_ERRORS = 50;
    private static final int MAX_FRAMES = 25;
    private static final int MAX_DAYS = 30;
    private static final int MAX_BODY = 256 * 1024;
    private static final long SAVE_DELAY_MS = 5000;

    // ---- feature names (the only strings a counter can have) ----
    public static final String GHOST_TOGGLE = "ghost_toggle";
    public static final String GHOST_OPTION = "ghost_option";
    public static final String GHOST_EXCEPTION = "ghost_exception";
    public static final String BACKUP_CREATE = "backup_create";
    public static final String BACKUP_RESTORE = "backup_restore";
    public static final String SETTINGS_EXPORT = "settings_export";
    public static final String SETTINGS_IMPORT = "settings_import";
    public static final String SETTINGS_UNDO = "settings_undo";
    public static final String CHAT_EXPORT = "chat_export";
    public static final String SETTINGS_SEARCH = "settings_search_open";
    public static final String STREAMER_TOGGLE = "streamer_toggle";
    public static final String PROXY_AUTOSWITCH = "proxy_autoswitch";
    public static final String PROXY_AUTOSWITCH_TOGGLE = "proxy_autoswitch_toggle";
    public static final String TOR_TOGGLE = "tor_toggle";
    public static final String ACTIVITY_LOG_TOGGLE = "activity_log_toggle";
    public static final String CHAT_LOCK_SET = "chat_lock_set";
    public static final String CHAT_LOCK_UNLOCK = "chat_lock_unlock_prompt";
    public static final String SCHEDULED_SEND = "scheduled_send";
    public static final String PEEK = "peek";
    public static final String MESSAGE_SHOT = "message_shot";
    public static final String DELETED_MEDIA_EXPORT = "deleted_media_export";
    public static final String MESSAGE_FILTER_ADD = "message_filter_add";
    public static final String USER_HIDE = "message_filter_user_hide";
    public static final String OPENPGP_DECRYPT = "openpgp_decrypt";
    public static final String HUB_OPEN = "hub_open";
    private static final String[] PAGE_NAMES = {
            "hub", "ghost", "deleted", "activity_log", "streamer", "chats", "tracking_ai", "network",
            "security", "updates",
    };

    private static final Object lock = new Object();
    private static JSONObject pending; // loaded lazily, guarded by lock
    private static boolean saveQueued;
    private static boolean uploading;

    private HanakoTelemetry() {
    }

    // =====================================================================================
    // settings

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        try {
            return prefs().getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Turning it off also deletes whatever was collected and not sent. */
    public static void setEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_ENABLED, value).apply();
        if (!value) clearPending();
    }

    public static String getEndpoint() {
        return prefs().getString(KEY_ENDPOINT, "");
    }

    /** Only https:// URLs are kept; anything else clears the endpoint (= never upload). */
    public static boolean setEndpoint(String url) {
        String u = url == null ? "" : url.trim();
        boolean ok = u.isEmpty() || (u.startsWith("https://") && u.length() <= 300);
        prefs().edit().putString(KEY_ENDPOINT, ok ? u : "").apply();
        return ok;
    }

    public static boolean hasUploadKey() {
        return !TextUtils.isEmpty(prefs().getString(KEY_SECRET, ""));
    }

    /** The receiver's shared secret, sent as the X-Hanako-Key header. Never shown or exported. */
    public static void setUploadKey(String key) {
        prefs().edit().putString(KEY_SECRET, key == null ? "" : key.trim()).apply();
    }

    /** Random 128-bit id, made on first use. */
    public static String installId() {
        SharedPreferences p = prefs();
        String id = p.getString(KEY_INSTALL_ID, "");
        if (id.length() != 32) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
            id = sb.toString();
            p.edit().putString(KEY_INSTALL_ID, id).apply();
        }
        return id;
    }

    public static void resetInstallId() {
        prefs().edit().remove(KEY_INSTALL_ID).apply();
    }

    // =====================================================================================
    // collecting

    /** Counts one use of a feature today. {@code feature} must be one of the constants above. */
    public static void count(String feature) {
        if (feature == null || !isEnabled()) return;
        synchronized (lock) {
            try {
                JSONObject days = load().getJSONObject("days");
                String day = today();
                JSONObject counts = days.optJSONObject(day);
                if (counts == null) {
                    counts = new JSONObject();
                    days.put(day, counts);
                }
                counts.put(feature, counts.optInt(feature, 0) + 1);
            } catch (Throwable t) {
                Log.w(TAG, "count failed");
                return;
            }
        }
        scheduleSave();
        maybeUpload();
    }

    /** Settings page opened (by its MercurygramSettingsActivity page number). */
    public static void countPage(int page) {
        if (page == 0) {
            count(HUB_OPEN);
        } else if (page > 0 && page < PAGE_NAMES.length) {
            count("page_" + PAGE_NAMES[page]);
        }
    }

    /** A Hanako entry of Settings search was opened. */
    public static void searchOpen(BaseFragment from, BaseFragment page) {
        count(SETTINGS_SEARCH);
        from.presentFragment(page);
    }

    /** A caught error (called from FileLog.e). Same fields as a crash, deduplicated per day. */
    public static void caught(Throwable t) {
        if (t == null || !isEnabled()) return;
        try {
            JSONObject e = describe(t, "caught");
            synchronized (lock) {
                addError(load(), e);
            }
            scheduleSave();
        } catch (Throwable ignore) {
            // never let telemetry break the caller
        }
    }

    /**
     * Installs the crash hook. The crash is written synchronously (the process is about to die)
     * and uploaded at the next start; the previous handler still runs.
     */
    public static void init() {
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
                try {
                    if (isEnabled()) {
                        writeFile(CRASH, describe(t, "crash").toString());
                    }
                } catch (Throwable ignore) {
                }
                if (previous != null) previous.uncaughtException(thread, t);
            });
            if (isEnabled()) {
                // a crash file from the last run goes into the batch and is sent right away
                Utilities.globalQueue.postRunnable(() -> {
                    boolean crashed = takeCrash();
                    if (crashed) {
                        save();
                        upload(true);
                    } else {
                        maybeUpload();
                    }
                }, 30_000);
            }
        } catch (Throwable t) {
            Log.w(TAG, "init failed");
        }
    }

    private static boolean takeCrash() {
        File f = file(CRASH);
        if (!f.isFile()) return false;
        try {
            JSONObject e = new JSONObject(readFile(f));
            synchronized (lock) {
                addError(load(), e);
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            f.delete();
        }
    }

    private static void addError(JSONObject root, JSONObject e) throws Exception {
        JSONArray errors = root.getJSONArray("errors");
        String key = e.optString("kind") + "|" + e.optString("class") + "|" + e.optString("date")
                + "|" + e.optJSONArray("stack");
        for (int i = 0; i < errors.length(); i++) {
            JSONObject o = errors.getJSONObject(i);
            String k = o.optString("kind") + "|" + o.optString("class") + "|" + o.optString("date")
                    + "|" + o.optJSONArray("stack");
            if (k.equals(key)) {
                o.put("count", o.optInt("count", 1) + 1);
                return;
            }
        }
        if (errors.length() >= MAX_ERRORS) return;
        e.put("count", 1);
        errors.put(e);
    }

    // =====================================================================================
    // scrubbing

    private static final Pattern URL_LIKE = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
    // segments may contain spaces ("/storage/.../my file.pdf"): over-scrubbing is fine
    private static final Pattern PATH = Pattern.compile("(?:/[^/\\n:]+){2,}/?");
    private static final Pattern FILE_NAME = Pattern.compile("(?i)[^\\s/:]+\\.(?:jpe?g|png|gif|webp|heic|mp4|webm|mov|mkv|ogg|oga|opus|mp3|m4a|wav|pdf|zip|rar|7z|apk|txt|json|html?|tgs|docx?|xlsx?|pptx?|csv)\\b");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[a-zA-Z]{2,}");
    private static final Pattern USERNAME = Pattern.compile("@[A-Za-z][A-Za-z0-9_]{3,31}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d ()\\-]{6,}\\d");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");
    private static final Pattern QUOTED = Pattern.compile("([\"'«]).{1,200}?([\"'»])");

    /**
     * Removes anything that could identify a person, a chat or a file. Applied to every message
     * and every stack line before it is stored.
     */
    public static String scrub(String s) {
        if (s == null) return "";
        String r = s;
        r = URL_LIKE.matcher(r).replaceAll("<uri>");
        r = EMAIL.matcher(r).replaceAll("<email>");
        r = USERNAME.matcher(r).replaceAll("<user>");
        r = PATH.matcher(r).replaceAll("<path>");
        r = FILE_NAME.matcher(r).replaceAll("<file>");
        r = PHONE.matcher(r).replaceAll("<phone>");
        r = LONG_DIGITS.matcher(r).replaceAll("<n>");
        r = QUOTED.matcher(r).replaceAll("$1<text>$2"); // quoted names, titles, file names
        if (r.length() > 300) r = r.substring(0, 300) + "…";
        return r;
    }

    private static JSONObject describe(Throwable t, String kind) throws Exception {
        JSONObject e = new JSONObject();
        e.put("date", today());
        e.put("kind", kind);
        e.put("class", t.getClass().getName());
        e.put("message", scrub(t.getMessage()));
        JSONArray stack = new JSONArray();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4 && stack.length() < MAX_FRAMES) {
            if (depth > 0) stack.put("Caused by: " + cur.getClass().getName() + ": " + scrub(cur.getMessage()));
            for (StackTraceElement el : cur.getStackTrace()) {
                if (stack.length() >= MAX_FRAMES) break;
                stack.put(scrub(el.getClassName() + "." + el.getMethodName()
                        + "(" + (el.getFileName() != null ? el.getFileName() : "?") + ":" + el.getLineNumber() + ")"));
            }
            cur = cur.getCause() != cur ? cur.getCause() : null;
            depth++;
        }
        e.put("stack", stack);
        e.put("app_version", appVersion());
        e.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        e.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        return e;
    }

    // =====================================================================================
    // the batch file

    private static File file(String name) {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, name);
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_BODY)];
            int n = 0;
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(String name, String data) throws Exception {
        File f = file(name);
        File tmp = new File(f.getParentFile(), name + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    /** The batch, loaded from disk once. Call with {@link #lock} held. */
    private static JSONObject load() throws Exception {
        if (pending != null) return pending;
        JSONObject p = null;
        File f = file(PENDING);
        if (f.isFile()) {
            try {
                p = new JSONObject(readFile(f));
            } catch (Throwable t) {
                p = null; // damaged: start over
            }
        }
        if (p == null) p = new JSONObject();
        if (p.optJSONObject("days") == null) p.put("days", new JSONObject());
        if (p.optJSONArray("errors") == null) p.put("errors", new JSONArray());
        pending = p;
        return p;
    }

    private static void scheduleSave() {
        synchronized (lock) {
            if (saveQueued) return;
            saveQueued = true;
        }
        Utilities.globalQueue.postRunnable(HanakoTelemetry::save, SAVE_DELAY_MS);
    }

    private static void save() {
        String data;
        synchronized (lock) {
            saveQueued = false;
            if (pending == null) return;
            data = pending.toString();
        }
        try {
            writeFile(PENDING, data);
        } catch (Throwable t) {
            Log.w(TAG, "save failed");
        }
    }

    public static void clearPending() {
        synchronized (lock) {
            pending = null;
        }
        file(PENDING).delete();
        file(CRASH).delete();
    }

    // =====================================================================================
    // upload

    /**
     * Exactly what the next upload sends (days before today, all errors), or null when there is
     * nothing to send. Shown by "View what would be sent".
     */
    public static JSONObject buildPayload() {
        try {
            JSONObject root;
            synchronized (lock) {
                root = new JSONObject(load().toString());
            }
            String today = today();
            JSONArray days = new JSONArray();
            JSONObject all = root.getJSONObject("days");
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String day = it.next();
                if (day.compareTo(today) >= 0) continue; // today is still being counted
                days.put(new JSONObject().put("date", day).put("counts", all.getJSONObject(day)));
                if (days.length() >= MAX_DAYS) break;
            }
            JSONArray errors = root.getJSONArray("errors");
            JSONObject out = new JSONObject();
            out.put("schema", SCHEMA);
            out.put("install_id", installId());
            out.put("app_version", appVersion());
            out.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            out.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            out.put("sent_day", today);
            out.put("days", days);
            out.put("errors", errors);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void maybeUpload() {
        if (!isEnabled() || TextUtils.isEmpty(getEndpoint())) return;
        if (today().equals(prefs().getString(KEY_LAST_UPLOAD_DAY, ""))) return;
        Utilities.globalQueue.postRunnable(() -> upload(false));
    }

    /** Result of the last upload attempt, for the settings screen. */
    public static volatile String lastResult = "";

    /** @param afterCrash a crash was just added: send even if today's upload already went out */
    private static void upload(boolean afterCrash) {
        if (!isEnabled()) return;
        String endpoint = getEndpoint();
        if (!endpoint.startsWith("https://")) return;
        String today = today();
        if (!afterCrash && today.equals(prefs().getString(KEY_LAST_UPLOAD_DAY, ""))) return;
        synchronized (lock) {
            if (uploading) return;
            uploading = true;
        }
        try {
            JSONObject payload = buildPayload();
            if (payload == null) return;
            if (payload.getJSONArray("days").length() == 0 && payload.getJSONArray("errors").length() == 0) {
                prefs().edit().putString(KEY_LAST_UPLOAD_DAY, today).apply();
                return;
            }
            Proxy proxy = proxyForUpload();
            if (proxy == null) {
                lastResult = "proxy";
                return; // the app's proxy can't carry HTTPS: don't go around it
            }
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_BODY) {
                trimErrors();
                return;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection(proxy);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Hanako");
            String key = prefs().getString(KEY_SECRET, "");
            if (!key.isEmpty()) c.setRequestProperty("X-Hanako-Key", key);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            c.disconnect();
            if (code >= 200 && code < 300) {
                removeSent(payload);
                prefs().edit().putString(KEY_LAST_UPLOAD_DAY, today).apply();
                lastResult = "ok";
            } else {
                lastResult = "http " + code;
            }
        } catch (Throwable t) {
            lastResult = t.getClass().getSimpleName();
            Log.w(TAG, "upload failed: " + t.getClass().getSimpleName());
        } finally {
            synchronized (lock) {
                uploading = false;
            }
        }
    }

    /** Uploads now (from the settings screen), ignoring the once-a-day limit. */
    public static void uploadNow(Runnable done) {
        Utilities.globalQueue.postRunnable(() -> {
            upload(true);
            if (done != null) org.telegram.messenger.AndroidUtilities.runOnUIThread(done);
        });
    }

    private static void removeSent(JSONObject payload) throws Exception {
        synchronized (lock) {
            JSONObject root = load();
            JSONArray days = payload.getJSONArray("days");
            for (int i = 0; i < days.length(); i++) {
                root.getJSONObject("days").remove(days.getJSONObject(i).getString("date"));
            }
            JSONArray errors = root.getJSONArray("errors");
            JSONArray rest = new JSONArray();
            int sent = payload.getJSONArray("errors").length();
            for (int i = sent; i < errors.length(); i++) rest.put(errors.get(i)); // added meanwhile
            root.put("errors", rest);
        }
        save();
    }

    private static void trimErrors() throws Exception {
        synchronized (lock) {
            load().put("errors", new JSONArray());
        }
        save();
    }

    /**
     * How an upload may leave this phone: Proxy.NO_PROXY with no proxy and no Tor, the SOCKS5
     * proxy (Tor's local one included) when one is on, or null when the app's proxy can't carry
     * HTTPS (MTProto, SOCKS with a password, Tor switched on but not running).
     */
    static Proxy proxyForUpload() {
        boolean proxyOn = SharedConfig.isProxyEnabled();
        if (!proxyOn) {
            return SharedConfig.mg_useTor ? null : Proxy.NO_PROXY;
        }
        ProxySettings s = SharedConfig.currentProxy != null ? SharedConfig.currentProxy.settings : null;
        if (s == null || s.getType() != ProxySettings.Type.SOCKS5) return null;
        if (!TextUtils.isEmpty(s.getUser()) || !TextUtils.isEmpty(s.getPassword())) return null;
        if (TextUtils.isEmpty(s.getAddress()) || s.getPort() <= 0) return null;
        return new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(s.getAddress(), s.getPort()));
    }

    // =====================================================================================

    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String appVersion() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }
}
