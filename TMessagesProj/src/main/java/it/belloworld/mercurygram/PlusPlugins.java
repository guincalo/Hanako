package it.belloworld.mercurygram;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.system.DexClassLoader;

/**
 * plus f11: minimal plugin engine.
 *
 * Plugins live in app-private storage, {@code files/plus_plugins/}. Every plugin is a JSON manifest
 * ({@code *.json}); it can carry
 * <ul>
 * <li>declarative rules (sandboxed: literal or regex text replacement for outgoing messages and for
 *     message display, and a list of request names to cancel). Rules can't run code, can't send
 *     anything and regexes run under a time budget, so a rule plugin is safe to enable;</li>
 * <li>a code part: a .dex/.jar/.apk file in the same folder plus the class implementing
 *     {@link PlusPlugin}. Code plugins are fully trusted Java code, off unless the user allows code
 *     plugins globally and enables the plugin; the code file is pinned by SHA-256 when enabled.</li>
 * </ul>
 *
 * Hook points (one marked line each in upstream code):
 * <ul>
 * <li>{@link #onSendMessage} - SendMessagesHelper.sendMessage (outgoing text / caption);</li>
 * <li>{@link #onMessageText} - MessageObject.updateMessageText (display of plain text messages);</li>
 * <li>{@link #interceptRequest} - ConnectionsManager.sendRequestInternal, before PlusGhost.</li>
 * </ul>
 *
 * Ghost-safe: the engine never sends anything itself; a cancelled request is completed locally with
 * an error. The request hook runs before PlusGhost.intercept, so ghost mode still has the last word
 * on anything a plugin let through or modified, and requests a code plugin sends itself also pass
 * through PlusGhost.
 */
public final class PlusPlugins {

    public static final String DIR = "plus_plugins";
    public static final String DATA_DIR = "plus_plugins_data";
    public static final int MAX_FAILURES = 3;
    /** Error text a cancelled request's callback receives. */
    public static final String BLOCKED_ERROR = "PLUS_PLUGIN_BLOCKED";

    private static final String PREFS = "plus_f11";
    private static final String KEY_ALLOW_CODE = "allow_code";
    private static final long REGEX_BUDGET_MS = 30;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final long MAX_CODE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_RULES = 200;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final Pattern FILE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}");

    /** One text replacement rule. */
    private static final class Rule {
        final String find;
        final Pattern pattern; // null = literal
        final String replace;

        Rule(String find, Pattern pattern, String replace) {
            this.find = find;
            this.pattern = pattern;
            this.replace = replace;
        }
    }

    /** A plugin as read from its manifest. Immutable after load, except the failure bookkeeping. */
    public static final class Info {
        public String id;
        public String name;
        public String version = "";
        public String author = "";
        public String description = "";
        public File manifest;
        public File codeFile;
        public String mainClass;
        public boolean enabled;
        /** Why the plugin is not running (bad manifest, code not allowed, load failure...), or null. */
        public String loadError;
        /** Last exception thrown by a hook, or null. */
        public volatile String lastError;

        Rule[] outgoing = new Rule[0];
        Rule[] display = new Rule[0];
        Set<String> blockRequests = Collections.emptySet();
        PlusPlugin instance;
        volatile int failures;

        public boolean hasCode() {
            return mainClass != null;
        }

        public boolean hasRules() {
            return outgoing.length > 0 || display.length > 0 || !blockRequests.isEmpty();
        }

        /** Short human-readable list of what the plugin hooks. */
        public String hooksSummary() {
            ArrayList<String> parts = new ArrayList<>();
            if (outgoing.length > 0) parts.add(outgoing.length + " outgoing rule(s)");
            if (display.length > 0) parts.add(display.length + " display rule(s)");
            if (!blockRequests.isEmpty()) parts.add("blocks " + TextUtils.join(", ", blockRequests));
            if (hasCode()) parts.add("code: " + mainClass + (codeFile != null ? " (" + codeFile.getName() + ")" : ""));
            return parts.isEmpty() ? "nothing" : TextUtils.join("; ", parts);
        }

        boolean running() {
            return enabled && loadError == null && (hasRules() || instance != null);
        }
    }

    private static final Object lock = new Object();
    private static volatile boolean initialized;
    private static volatile List<Info> all = Collections.emptyList();
    private static volatile Info[] active = new Info[0];
    private static final ConcurrentHashMap<Class<?>, String> requestNames = new ConcurrentHashMap<>();

    private PlusPlugins() {
    }

    // ---- storage / prefs ----

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static File pluginDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    static File dataRoot() {
        return new File(ApplicationLoader.getFilesDirFixed(), DATA_DIR);
    }

    public static boolean isCodeAllowed() {
        return prefs().getBoolean(KEY_ALLOW_CODE, false);
    }

    public static void setCodeAllowed(boolean value) {
        prefs().edit().putBoolean(KEY_ALLOW_CODE, value).apply();
        reload();
    }

    /** All plugins found in the folder, including disabled and broken ones. */
    public static List<Info> list() {
        ensureLoaded();
        return all;
    }

    /** "2 of 3 on" style summary for the settings row; cheap, never loads code. */
    public static String summary() {
        if (!initialized) {
            String[] names = pluginDir().list();
            int n = 0;
            if (names != null) {
                for (String s : names) {
                    if (s.endsWith(".json")) n++;
                }
            }
            return n == 0 ? "" : Integer.toString(n);
        }
        int on = 0;
        for (Info info : all) {
            if (info.running()) on++;
        }
        return all.isEmpty() ? "" : on + "/" + all.size();
    }

    // ---- loading ----

    public static void ensureLoaded() {
        if (!initialized) {
            synchronized (lock) {
                if (!initialized) {
                    loadLocked();
                    initialized = true;
                }
            }
        }
    }

    /** Unload everything and read the folder again. */
    public static void reload() {
        synchronized (lock) {
            unloadLocked();
            loadLocked();
            initialized = true;
        }
    }

    private static void unloadLocked() {
        Info[] old = active;
        active = new Info[0];
        for (Info info : old) {
            if (info.instance != null) {
                try {
                    info.instance.onUnload();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        }
        all = Collections.emptyList();
    }

    private static void loadLocked() {
        File dir = pluginDir();
        File[] files = dir.listFiles();
        ArrayList<Info> infos = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            HashSet<String> seen = new HashSet<>();
            SharedPreferences p = prefs();
            boolean allowCode = p.getBoolean(KEY_ALLOW_CODE, false);
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".json")) {
                    continue;
                }
                Info info = parseManifest(f);
                if (info.loadError == null && !seen.add(info.id)) {
                    info.loadError = "duplicate id \"" + info.id + "\"";
                }
                info.enabled = info.id != null && p.getBoolean("on_" + info.id, false);
                if (info.enabled && info.loadError == null && info.hasCode()) {
                    loadCode(info, allowCode, p);
                }
                infos.add(info);
            }
        }
        ArrayList<Info> run = new ArrayList<>();
        for (Info info : infos) {
            if (info.running()) run.add(info);
        }
        all = Collections.unmodifiableList(infos);
        active = run.toArray(new Info[0]);
    }

    private static Info parseManifest(File f) {
        Info info = new Info();
        info.manifest = f;
        info.name = f.getName();
        try {
            if (f.length() > MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("manifest too large");
            }
            JSONObject o = new JSONObject(new String(readAll(new FileInputStream(f), MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
            String id = o.optString("id", "");
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("missing or invalid \"id\" (a-z 0-9 _ . -, max 64)");
            }
            info.id = id;
            info.name = o.optString("name", id);
            info.version = o.optString("version", "");
            info.author = o.optString("author", "");
            info.description = o.optString("description", "");
            int api = o.optInt("api", PlusPlugin.API_VERSION);
            if (api > PlusPlugin.API_VERSION) {
                throw new IllegalArgumentException("needs plugin API " + api + ", this build has " + PlusPlugin.API_VERSION);
            }
            info.outgoing = parseRules(o.optJSONArray("outgoing"));
            info.display = parseRules(o.optJSONArray("display"));
            JSONArray block = o.optJSONArray("block_requests");
            if (block != null) {
                HashSet<String> set = new HashSet<>();
                for (int i = 0; i < block.length() && i < MAX_RULES; i++) {
                    String s = block.optString(i, "").trim();
                    if (!s.isEmpty()) set.add(s);
                }
                info.blockRequests = Collections.unmodifiableSet(set);
            }
            String code = o.optString("code", "");
            String main = o.optString("main", "");
            if (!code.isEmpty() || !main.isEmpty()) {
                if (code.isEmpty() || main.isEmpty()) {
                    throw new IllegalArgumentException("\"code\" and \"main\" must both be set");
                }
                if (!FILE_PATTERN.matcher(code).matches() || !isCodeName(code)) {
                    throw new IllegalArgumentException("\"code\" must be a .dex, .jar, .apk or .zip file name in the plugin folder");
                }
                info.codeFile = new File(f.getParentFile(), code);
                info.mainClass = main;
            }
            if (!info.hasRules() && !info.hasCode()) {
                info.loadError = "no rules and no code";
            }
        } catch (Throwable t) {
            info.loadError = "bad manifest: " + t.getMessage();
        }
        return info;
    }

    private static Rule[] parseRules(JSONArray arr) throws Exception {
        if (arr == null) {
            return new Rule[0];
        }
        ArrayList<Rule> rules = new ArrayList<>();
        for (int i = 0; i < arr.length() && i < MAX_RULES; i++) {
            JSONObject r = arr.getJSONObject(i);
            String find = r.optString("find", "");
            if (find.isEmpty()) {
                continue;
            }
            String replace = r.optString("replace", "");
            Pattern pattern = null;
            if (r.optBoolean("regex", false)) {
                pattern = Pattern.compile(find); // throws on invalid syntax -> bad manifest
            }
            rules.add(new Rule(find, pattern, replace));
        }
        return rules.toArray(new Rule[0]);
    }

    private static boolean isCodeName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".dex") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".zip");
    }

    private static void loadCode(Info info, boolean allowCode, SharedPreferences p) {
        if (!allowCode) {
            info.loadError = "code plugins are not allowed (see the switch above)";
            return;
        }
        try {
            File code = info.codeFile;
            if (code == null || !code.isFile()) {
                info.loadError = "code file missing: " + (code != null ? code.getName() : "?");
                return;
            }
            if (code.length() > MAX_CODE_BYTES) {
                info.loadError = "code file too large";
                return;
            }
            String hash = sha256(code);
            String pinned = p.getString("hash_" + info.id, null);
            if (pinned == null || !pinned.equals(hash)) {
                // the file changed since the user enabled it: don't run unreviewed code
                p.edit().putBoolean("on_" + info.id, false).remove("hash_" + info.id).apply();
                info.enabled = false;
                info.loadError = "code file changed since it was enabled; enable it again to trust the new file";
                return;
            }
            // Android 14+ refuses writable dex files: load a read-only copy named by its hash.
            File cacheDir = new File(ApplicationLoader.applicationContext.getCodeCacheDir(), DIR);
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            String ext = code.getName().substring(code.getName().lastIndexOf('.')).toLowerCase(Locale.ROOT);
            File copy = new File(cacheDir, hash + ext);
            if (!copy.isFile() || !hash.equals(sha256(copy))) {
                if (copy.exists()) {
                    copy.setWritable(true);
                    copy.delete();
                }
                copyFile(code, copy);
            }
            copy.setReadOnly();
            DexClassLoader loader = new DexClassLoader(copy.getAbsolutePath(), cacheDir.getAbsolutePath(), null, PlusPlugins.class.getClassLoader());
            Class<?> cls = loader.loadClass(info.mainClass);
            if (!PlusPlugin.class.isAssignableFrom(cls)) {
                info.loadError = info.mainClass + " does not implement " + PlusPlugin.class.getName();
                return;
            }
            PlusPlugin instance = (PlusPlugin) cls.getDeclaredConstructor().newInstance();
            instance.onLoad(new PlusPluginHost(info.id));
            info.instance = instance;
        } catch (Throwable t) {
            FileLog.e(t);
            info.instance = null;
            info.loadError = "load failed: " + t;
        }
    }

    // ---- enable / delete / import (UI thread) ----

    /** Enable or disable one plugin. Enabling a code plugin pins the current code file hash. */
    public static void setEnabled(Info info, boolean value) {
        if (info == null || info.id == null) {
            return;
        }
        SharedPreferences.Editor e = prefs().edit().putBoolean("on_" + info.id, value);
        if (value && info.hasCode() && info.codeFile != null && info.codeFile.isFile()) {
            try {
                e.putString("hash_" + info.id, sha256(info.codeFile));
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        } else if (!value) {
            e.remove("hash_" + info.id);
        }
        e.apply();
        reload();
    }

    /** Delete the manifest, its code file, its data folder and its settings. */
    public static void delete(Info info) {
        if (info == null) {
            return;
        }
        synchronized (lock) {
            unloadLocked();
            if (info.manifest != null) info.manifest.delete();
            if (info.codeFile != null) info.codeFile.delete();
            if (info.id != null) {
                prefs().edit().remove("on_" + info.id).remove("hash_" + info.id).apply();
                deleteRecursive(new File(dataRoot(), info.id));
                ApplicationLoader.applicationContext.getSharedPreferences("plus_f11_p_" + info.id, Context.MODE_PRIVATE).edit().clear().apply();
            }
            loadLocked();
            initialized = true;
        }
    }

    /**
     * Copy a file picked by the user into the plugin folder. Call off the UI thread.
     * Manifests are validated first; a replaced code file disables its plugin (hash pin).
     *
     * @return a short result line for a toast.
     */
    public static String importUri(Context context, Uri uri) {
        try {
            ContentResolver cr = context.getContentResolver();
            String name = null;
            try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0);
                }
            }
            if (name == null) {
                name = uri.getLastPathSegment();
            }
            name = sanitizeFileName(name);
            boolean json = name.toLowerCase(Locale.ROOT).endsWith(".json");
            if (!json && !isCodeName(name)) {
                return name + ": not a plugin file (.json, .dex, .jar, .apk, .zip)";
            }
            byte[] data;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) {
                    return name + ": can't open";
                }
                data = readAll(in, json ? MAX_MANIFEST_BYTES : (int) MAX_CODE_BYTES);
            }
            File target = new File(pluginDir(), name);
            if (json) {
                File tmp = new File(pluginDir(), ".import.tmp");
                writeFile(tmp, data);
                Info parsed = parseManifest(tmp);
                tmp.delete();
                if (parsed.loadError != null && parsed.id == null) {
                    return name + ": " + parsed.loadError;
                }
            }
            if (target.exists()) {
                target.setWritable(true);
            }
            writeFile(target, data);
            reload();
            return name + ": imported";
        } catch (Throwable t) {
            FileLog.e(t);
            return "import failed: " + t.getMessage();
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null) {
            name = "plugin";
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        while (name.startsWith(".") || name.startsWith("_") || name.startsWith("-")) {
            name = name.substring(1);
        }
        if (name.length() > 96) {
            name = name.substring(name.length() - 96);
        }
        return name.isEmpty() ? "plugin" : name;
    }

    // ---- failure bookkeeping ----

    private static void ok(Info info) {
        if (info.failures != 0) {
            info.failures = 0;
        }
    }

    private static void fail(Info info, Throwable t) {
        FileLog.e("plus f11: plugin " + info.id + " failed", t);
        info.lastError = String.valueOf(t);
        if (++info.failures >= MAX_FAILURES) {
            info.failures = 0;
            Utilities.globalQueue.postRunnable(() -> {
                prefs().edit().putBoolean("on_" + info.id, false).remove("hash_" + info.id).apply();
                reload();
            });
        }
    }

    // ---- hooks ----

    private static Info[] snapshot() {
        ensureLoaded();
        return active;
    }

    /**
     * Hook in SendMessagesHelper.sendMessage.
     *
     * @return true when a plugin cancelled the send.
     */
    public static boolean onSendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        Info[] list = snapshot();
        if (list.length == 0 || params == null || params.retryMessageObject != null) {
            return false;
        }
        for (Info info : list) {
            if (info.outgoing.length > 0) {
                try {
                    boolean hasEntities = params.entities != null && !params.entities.isEmpty();
                    if (!TextUtils.isEmpty(params.message) && PlusOpenPgp.findArmor(params.message) == null) { // plus f18: never rewrite an armored (encrypted/signed) text
                        params.message = keepLengthIfEntities(params.message, applyRules(info.outgoing, params.message), hasEntities);
                    }
                    if (!TextUtils.isEmpty(params.caption)) {
                        params.caption = keepLengthIfEntities(params.caption, applyRules(info.outgoing, params.caption), hasEntities);
                    }
                    ok(info);
                } catch (Throwable t) {
                    fail(info, t);
                }
            }
            if (info.instance != null) {
                try {
                    boolean cancel = info.instance.onSendMessage(account, params);
                    ok(info);
                    if (cancel) {
                        return true;
                    }
                } catch (Throwable t) {
                    fail(info, t);
                }
            }
        }
        return false;
    }

    /** Hook in MessageObject.updateMessageText for plain text messages. Display only. */
    public static CharSequence onMessageText(int account, MessageObject message, CharSequence text) {
        Info[] list = snapshot();
        if (list.length == 0 || message == null || TextUtils.isEmpty(text)) {
            return text;
        }
        boolean hasEntities = message.messageOwner != null && message.messageOwner.entities != null && !message.messageOwner.entities.isEmpty();
        CharSequence cur = text;
        for (Info info : list) {
            if (info.display.length > 0) {
                try {
                    String in = cur.toString();
                    String out = applyRules(info.display, in);
                    if (!out.equals(in) && (!hasEntities || out.length() == in.length())) {
                        cur = out;
                    }
                    ok(info);
                } catch (Throwable t) {
                    fail(info, t);
                }
            }
            if (info.instance != null) {
                try {
                    CharSequence out = info.instance.onMessageText(account, message, cur);
                    if (out != null && out != cur && (!hasEntities || out.length() == cur.length())) {
                        cur = out;
                    }
                    ok(info);
                } catch (Throwable t) {
                    fail(info, t);
                }
            }
        }
        return cur;
    }

    /**
     * Hook in ConnectionsManager.sendRequestInternal (stage queue), before PlusGhost.
     *
     * @return true when a plugin cancelled the request; its callback is completed with an error.
     */
    public static boolean interceptRequest(int account, TLObject request, RequestDelegate onComplete) {
        Info[] list = snapshot();
        if (list.length == 0 || request == null) {
            return false;
        }
        String name = requestName(request);
        for (Info info : list) {
            if (info.blockRequests.contains(name)) {
                return cancel(onComplete);
            }
            if (info.instance != null) {
                try {
                    int r = info.instance.onRequest(account, name, request);
                    ok(info);
                    if (r == PlusPlugin.CANCEL) {
                        return cancel(onComplete);
                    }
                } catch (Throwable t) {
                    fail(info, t);
                }
            }
        }
        return false;
    }

    private static boolean cancel(RequestDelegate onComplete) {
        if (onComplete != null) {
            final TLRPC.TL_error error = new TLRPC.TL_error();
            error.code = 400;
            error.text = BLOCKED_ERROR;
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    onComplete.run(null, error);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        }
        return true;
    }

    /**
     * exteraGram-style request name: TLRPC.TL_messages_setTyping -> "TL_messages_setTyping",
     * TL_account.updateStatus -> "TL_account_updateStatus".
     */
    public static String requestName(TLObject request) {
        Class<?> cls = request.getClass();
        String name = requestNames.get(cls);
        if (name == null) {
            name = cls.getSimpleName();
            Class<?> outer = cls.getEnclosingClass();
            if (outer != null && !name.startsWith("TL_") && outer.getSimpleName().startsWith("TL_")) {
                name = outer.getSimpleName() + "_" + name;
            }
            requestNames.put(cls, name);
        }
        return name;
    }

    // ---- rules ----

    private static String keepLengthIfEntities(String before, String after, boolean hasEntities) {
        if (hasEntities && after.length() != before.length()) {
            return before; // entity offsets would break; skip this change
        }
        return after;
    }

    private static String applyRules(Rule[] rules, String text) {
        String cur = text;
        for (Rule rule : rules) {
            if (rule.pattern == null) {
                if (cur.contains(rule.find)) {
                    cur = cur.replace(rule.find, rule.replace);
                }
            } else {
                Matcher m = rule.pattern.matcher(new BudgetCharSequence(cur, SystemClock.uptimeMillis() + REGEX_BUDGET_MS));
                cur = m.replaceAll(rule.replace);
            }
        }
        return cur;
    }

    /** Throws when a regex keeps reading past its time budget (catastrophic backtracking guard). */
    private static final class BudgetCharSequence implements CharSequence {
        private final CharSequence s;
        private final long deadline;
        private int reads;

        BudgetCharSequence(CharSequence s, long deadline) {
            this.s = s;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return s.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0x3FF) == 0 && SystemClock.uptimeMillis() > deadline) {
                throw new IllegalStateException("regex time budget exceeded");
            }
            return s.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetCharSequence(s.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return s.toString();
        }
    }

    // ---- io ----

    private static byte[] readAll(InputStream in, int max) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    throw new IllegalArgumentException("file too large");
                }
            }
            return out.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
