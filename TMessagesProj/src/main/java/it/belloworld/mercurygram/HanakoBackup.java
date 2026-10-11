package it.belloworld.mercurygram;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * hanako: session backup. Copies the login of every visible account (its tgnet.dat and the
 * serialized current user, which is all UserConfig needs to call a slot signed in) into one
 * password-encrypted file, and restores it into free account slots of any Hanako build,
 * whatever its package id or phone. No settings, no messages: the cache re-syncs.
 *
 * <p>File: {@code "HNKBAK01" | u8 version | u8 kdf | i32 iterations | u8 n | salt[n] | u8 n |
 * nonce[n] | AES-256-GCM(payload)}, the header authenticated as AAD, the key from
 * PBKDF2-HMAC-SHA256 over the UTF-8 password. Payload: per account {@code u8 1 | i32 n |
 * TL user[n] | i32 n | tgnet.dat[n]}, then {@code u8 0}.
 *
 * <p>Hidden accounts are never written. A restore only fills free slots: an account that is
 * already signed in here (hidden or not) is skipped, so a working login is never replaced. It
 * is staged under files/hanako_restore and applied by {@link #applyPendingAtStartup} at the next
 * process start, before UserConfig or the native ConnectionsManager read anything.
 *
 * <p>Passwords, keys and sessions are kept in arrays and wiped where this code owns the copy
 * (not the text field's, the cipher's, or the staged file until the next start).
 */
public final class HanakoBackup {

    private static final String TAG = "HanakoBackup";

    private static final byte[] MAGIC = {'H', 'N', 'K', 'B', 'A', 'K', '0', '1'};
    /** 1 was the zip format of the full backup (settings + sessions), never released. */
    private static final int FILE_VERSION = 2;
    private static final int KDF_PBKDF2_HMAC_SHA256 = 1;
    /** OWASP 2023 figure for PBKDF2-HMAC-SHA256. */
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int MIN_ITERATIONS = 310_000;
    /** More would freeze a restore for minutes. */
    private static final int MAX_ITERATIONS = 2_000_000;
    private static final int SALT_LEN = 32;
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    /** tgnet.dat is a few KB, a TL user well under one. */
    private static final int MAX_ENTRY_BYTES = 1024 * 1024;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    public static final int MIN_PASSWORD_LENGTH = 12;
    /** A staged restore older than this is dropped instead of applied without the user. */
    private static final long PLAN_MAX_AGE_MS = 30L * 60 * 1000;

    public static final int ERR_DAMAGED = 0;
    public static final int ERR_NOT_BACKUP = 1;
    public static final int ERR_NEWER = 2;
    public static final int ERR_NO_ACCOUNTS = 3;

    private static final String TGNET = "tgnet.dat";
    /** Rebuilt by the app: other DC configs, CDN keys and the message cache of the old slot. */
    private static final String[] SLOT_FILES_TO_DROP = {
            "dc1conf.dat", "dc2conf.dat", "dc3conf.dat", "dc4conf.dat", "dc5conf.dat",
            "cdnkeys.dat", "cdnkeys.dat.bak", "cache4.db", "cache4.db-wal", "cache4.db-shm",
    };
    private static final String STAGE_DIR = "hanako_restore";
    private static final String STAGE_PLAN = "plan.json";
    private static final String RESULT_FILE = "hanako_restore_result.json";
    /** Mercurygram HiddenAccountHelper's keys, written here before HiddenAccountHelper can run. */
    private static final String HIDDEN_PREFS = "mainconfig";
    private static final String HIDDEN_HASH_PREFIX = "mg_hiddenAccountHash_";
    private static final String HIDDEN_SALT_PREFIX = "mg_hiddenAccountSalt_";

    private HanakoBackup() {
    }

    public static class WrongPasswordException extends Exception {
        public WrongPasswordException() {
            super("wrong password");
        }
    }

    public static class InvalidBackupException extends Exception {
        public final int code;

        public InvalidBackupException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** One account of a backup. */
    public static final class Account {
        public final TLRPC.User user;
        final byte[] userBytes;
        final byte[] tgnet;

        Account(TLRPC.User user, byte[] userBytes, byte[] tgnet) {
            this.user = user;
            this.userBytes = userBytes;
            this.tgnet = tgnet;
        }
    }

    public static final class Backup {
        public final ArrayList<Account> accounts = new ArrayList<>();

        public void wipe() {
            for (Account a : accounts) Arrays.fill(a.tgnet, (byte) 0);
        }
    }

    // =====================================================================================
    // Errors
    // =====================================================================================

    /** Raw exception text, for the "Details" button only. */
    public static String rawError(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (TextUtils.isEmpty(m) ? "" : ": " + m);
    }

    /** Maps a backup or chat export failure to a short localized message that says what to do. */
    public static String describeError(Throwable t) {
        if (t instanceof HanakoChatExport.FloodWaitTooLongException) {
            int sec = ((HanakoChatExport.FloodWaitTooLongException) t).seconds;
            String wait = sec >= 3600 ? LocaleController.formatPluralString("Hours", (sec + 3599) / 3600)
                    : LocaleController.formatPluralString("Minutes", (sec + 59) / 60);
            return LocaleController.formatString(R.string.HanakoErrFloodWaitLong, wait);
        }
        if (t instanceof InvalidBackupException) {
            switch (((InvalidBackupException) t).code) {
                case ERR_NOT_BACKUP:
                    return LocaleController.getString(R.string.HanakoErrNotBackup);
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
        if (raw.contains("cannot open input") || t instanceof FileNotFoundException) {
            return LocaleController.getString(R.string.HanakoErrCannotRead);
        }
        if (raw.contains("timeout") || raw.contains("network")) {
            return LocaleController.getString(R.string.HanakoErrNetwork);
        }
        return LocaleController.getString(R.string.HanakoErrGeneric);
    }

    // =====================================================================================
    // Backup
    // =====================================================================================

    private static File filesDir(Context ctx) {
        File files = ctx.getFilesDir();
        return files != null ? files : ApplicationLoader.getFilesDirFixed();
    }

    private static File slotDir(Context ctx, int slot) {
        return slot == 0 ? filesDir(ctx) : new File(filesDir(ctx), "account" + slot);
    }

    private static String userConfigName(int slot) {
        return slot == 0 ? "userconfing" : "userconfig" + slot;
    }

    /**
     * A slot's session as tgnet last completed it. Config.cpp writes by renaming the live file
     * to .bak, writing a new one, then deleting the .bak, and at start trusts the .bak while it
     * exists: so the .bak is read first, and a file only counts when its length header says it
     * is whole.
     */
    private static byte[] readSession(File dir) throws IOException {
        for (String name : new String[]{TGNET + ".bak", TGNET}) {
            byte[] data;
            try (InputStream in = new FileInputStream(new File(dir, name))) {
                data = readStream(in, MAX_ENTRY_BYTES);
            } catch (FileNotFoundException e) {
                continue; // none, or tgnet just dropped the .bak
            }
            if (isWholeConfig(data)) return data;
            Arrays.fill(data, (byte) 0);
        }
        return null;
    }

    /** Config.cpp layout: little-endian u32 size, then that many bytes. */
    private static boolean isWholeConfig(byte[] d) {
        if (d == null || d.length < 8) return false;
        long size = (d[0] & 0xffL) | (d[1] & 0xffL) << 8 | (d[2] & 0xffL) << 16 | (d[3] & 0xffL) << 24;
        return size > 0 && size + 4 <= d.length;
    }

    /**
     * Writes an encrypted backup of every visible signed-in account to {@code uri}. Wipes
     * {@code password}. Returns [accounts written, accounts whose session could not be read].
     * Call off the UI thread: the key derivation takes a second or two.
     */
    public static int[] write(Context context, Uri uri, char[] password) throws Exception {
        HanakoTelemetry.count(HanakoTelemetry.BACKUP_CREATE); // hanako: usage statistics (off by default)
        WipeableBytes plain = new WipeableBytes();
        byte[] key = null;
        try {
            if (password == null || password.length < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("password too short");
            }
            int written = 0;
            int skipped = 0;
            DataOutputStream out = new DataOutputStream(plain);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig uc = UserConfig.getInstance(a);
                if (!uc.isClientActivated() || HiddenAccountHelper.isAccountHidden(a)) continue;
                TLRPC.User user = uc.getCurrentUser();
                byte[] session = readSession(slotDir(context, a));
                if (user == null || session == null) {
                    FileLog.d("hanako backup: no session for slot " + a);
                    skipped++;
                    continue;
                }
                SerializedData data = new SerializedData(user.getObjectSize());
                user.serializeToStream(data);
                byte[] userBytes = data.toByteArray();
                data.cleanup();
                out.writeByte(1);
                out.writeInt(userBytes.length);
                out.write(userBytes);
                out.writeInt(session.length);
                out.write(session);
                Arrays.fill(session, (byte) 0);
                written++;
            }
            out.writeByte(0);
            if (written == 0) {
                throw new InvalidBackupException(ERR_NO_ACCOUNTS, "no accounts");
            }
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_LEN];
            byte[] nonce = new byte[NONCE_LEN];
            random.nextBytes(salt);
            random.nextBytes(nonce);
            byte[] header = header(PBKDF2_ITERATIONS, salt, nonce);
            key = pbkdf2(password, salt, PBKDF2_ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] ct = cipher.doFinal(plain.buffer(), 0, plain.size());
            try (OutputStream os = openTruncated(context, uri)) {
                if (os == null) throw new IOException("cannot open output");
                os.write(header);
                os.write(ct);
            }
            return new int[]{written, skipped};
        } finally {
            plain.wipe();
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
        return bos.toByteArray();
    }

    // =====================================================================================
    // Restore
    // =====================================================================================

    /** Reads, decrypts and checks a backup. Wipes {@code password}. Call off the UI thread. */
    public static Backup read(Context context, Uri uri, char[] password) throws Exception {
        byte[] key = null;
        byte[] plain = null;
        try {
            byte[] file = readAll(context.getContentResolver(), uri, MAX_FILE_BYTES);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
            byte[] magic = new byte[MAGIC.length];
            int version;
            try {
                in.readFully(magic);
                version = in.readUnsignedByte();
            } catch (EOFException e) {
                throw new InvalidBackupException(ERR_NOT_BACKUP, "not a Hanako backup");
            }
            if (!Arrays.equals(magic, MAGIC) || version < FILE_VERSION) {
                throw new InvalidBackupException(ERR_NOT_BACKUP, "not a Hanako session backup (version " + version + ")");
            }
            if (version > FILE_VERSION) {
                throw new InvalidBackupException(ERR_NEWER, "backup file version " + version);
            }
            int headerLen;
            byte[] salt;
            byte[] nonce;
            int iterations;
            try {
                if (in.readUnsignedByte() != KDF_PBKDF2_HMAC_SHA256) throw new InvalidBackupException(ERR_DAMAGED, "unknown key derivation");
                iterations = in.readInt();
                if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw new InvalidBackupException(ERR_DAMAGED, "bad iteration count");
                salt = new byte[in.readUnsignedByte()];
                if (salt.length < 16) throw new InvalidBackupException(ERR_DAMAGED, "bad salt");
                in.readFully(salt);
                nonce = new byte[in.readUnsignedByte()];
                if (nonce.length != NONCE_LEN) throw new InvalidBackupException(ERR_DAMAGED, "bad nonce");
                in.readFully(nonce);
                headerLen = file.length - in.available();
            } catch (EOFException e) {
                throw new InvalidBackupException(ERR_DAMAGED, "truncated header");
            }
            int ctLen = file.length - headerLen;
            if (ctLen < GCM_TAG_BITS / 8) throw new InvalidBackupException(ERR_DAMAGED, "truncated backup");

            key = pbkdf2(password, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(file, 0, headerLen);
            try {
                plain = cipher.doFinal(file, headerLen, ctLen);
            } catch (AEADBadTagException e) {
                throw new WrongPasswordException();
            }
            return parse(plain);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private static Backup parse(byte[] plain) throws InvalidBackupException {
        Backup b = new Backup();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
        try {
            int tag;
            while ((tag = in.readUnsignedByte()) == 1) {
                if (b.accounts.size() >= 64) throw new InvalidBackupException(ERR_DAMAGED, "too many accounts");
                byte[] userBytes = readEntry(in);
                byte[] tgnet = readEntry(in);
                SerializedData data = new SerializedData(userBytes);
                TLRPC.User user = TLRPC.User.TLdeserialize(data, data.readInt32(false), false);
                data.cleanup();
                if (user == null || user.id == 0 || !isWholeConfig(tgnet)) {
                    Arrays.fill(tgnet, (byte) 0);
                    throw new InvalidBackupException(ERR_DAMAGED, "bad account entry");
                }
                b.accounts.add(new Account(user, userBytes, tgnet));
            }
            if (tag != 0) throw new InvalidBackupException(ERR_DAMAGED, "bad entry tag " + tag);
        } catch (IOException | RuntimeException e) {
            b.wipe();
            throw new InvalidBackupException(ERR_DAMAGED, "bad payload: " + rawError(e));
        } catch (InvalidBackupException e) {
            b.wipe();
            throw e;
        }
        if (b.accounts.isEmpty()) throw new InvalidBackupException(ERR_NO_ACCOUNTS, "no accounts");
        return b;
    }

    private static byte[] readEntry(DataInputStream in) throws IOException, InvalidBackupException {
        int n = in.readInt();
        if (n <= 0 || n > MAX_ENTRY_BYTES) throw new InvalidBackupException(ERR_DAMAGED, "bad entry size");
        byte[] out = new byte[n];
        in.readFully(out);
        return out;
    }

    /** Slot of a signed-in account here (hidden ones included), or -1. */
    public static int slotOf(long userId) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (uc.isClientActivated() && uc.getClientUserId() == userId) return a;
        }
        return -1;
    }

    /** Signed-out account slots, lowest first. */
    public static ArrayList<Integer> freeSlots() {
        ArrayList<Integer> free = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) free.add(a);
        }
        return free;
    }

    /**
     * Stages the picked accounts that are not signed in here into free slots, in order; applied
     * at the next process start. Returns the number of staged accounts.
     */
    public static int stage(Backup b, boolean[] picked) throws Exception {
        HanakoTelemetry.count(HanakoTelemetry.BACKUP_RESTORE); // hanako: usage statistics (off by default)
        File dir = new File(filesDir(ApplicationLoader.applicationContext), STAGE_DIR);
        deleteRecursive(dir);
        if (!dir.mkdirs()) throw new IOException("cannot create staging dir");
        ArrayList<Integer> free = freeSlots();
        JSONArray accounts = new JSONArray();
        for (int i = 0; i < b.accounts.size() && i < picked.length && !free.isEmpty(); i++) {
            Account e = b.accounts.get(i);
            if (!picked[i] || slotOf(e.user.id) >= 0) continue;
            int slot = free.remove(0);
            try (FileOutputStream fos = new FileOutputStream(new File(dir, "slot" + slot + ".tgnet"))) {
                fos.write(e.tgnet);
                fos.getFD().sync();
            }
            accounts.put(new JSONObject()
                    .put("slot", slot)
                    .put("user", Base64.encodeToString(e.userBytes, Base64.DEFAULT)));
        }
        if (accounts.length() == 0) {
            deleteRecursive(dir);
            return 0;
        }
        // written last and renamed into place: the commit marker of the staging
        JSONObject plan = new JSONObject().put("created_ms", System.currentTimeMillis()).put("accounts", accounts);
        File tmp = new File(dir, STAGE_PLAN + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(plan.toString().getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
        if (!tmp.renameTo(new File(dir, STAGE_PLAN))) throw new IOException("cannot commit staging");
        return accounts.length();
    }

    public static void discardStaged() {
        deleteRecursive(new File(filesDir(ApplicationLoader.applicationContext), STAGE_DIR));
    }

    // =====================================================================================
    // Startup apply
    // =====================================================================================

    /**
     * Applies a staged restore. Called from ApplicationLoader.onCreate before anything reads
     * UserConfig or starts the native ConnectionsManager. Never throws; the staging is always
     * removed afterwards.
     */
    public static void applyPendingAtStartup(Context ctx) {
        File dir;
        try {
            if (ctx == null || ctx.getFilesDir() == null) return;
            dir = new File(ctx.getFilesDir(), STAGE_DIR);
            if (!dir.exists()) return;
            String proc = processName();
            if (proc != null && !proc.equals(ctx.getPackageName())) return; // only the main process applies it
        } catch (Throwable t) {
            return;
        }
        int planned = 0;
        int restored = 0;
        String error = null;
        try {
            File planFile = new File(dir, STAGE_PLAN);
            if (!planFile.isFile()) return; // incomplete staging: dropped below
            byte[] data;
            try (InputStream in = new FileInputStream(planFile)) {
                data = readStream(in, MAX_FILE_BYTES);
            }
            JSONObject plan = new JSONObject(new String(data, StandardCharsets.UTF_8));
            // a restore the user never restarted into must not change logins much later,
            // for example at a background push start
            if (Math.abs(System.currentTimeMillis() - plan.optLong("created_ms", 0)) > PLAN_MAX_AGE_MS) {
                error = "expired";
                return;
            }
            JSONArray accounts = plan.optJSONArray("accounts");
            planned = accounts != null ? accounts.length() : 0;
            int first = -1;
            for (int i = 0; i < planned; i++) {
                JSONObject acc = accounts.optJSONObject(i);
                int slot = acc != null ? acc.optInt("slot", -1) : -1;
                try {
                    applyAccount(ctx, dir, slot, acc != null ? acc.optString("user", "") : "");
                    restored++;
                    if (first < 0) first = slot;
                } catch (Throwable t) {
                    Log.e(TAG, "restore of one account failed", t);
                    if (error == null) error = rawError(t);
                }
            }
            if (first >= 0) {
                // open on an account that exists
                SharedPreferences main = ctx.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
                int selected = main.getInt("selectedAccount", 0);
                if (selected < 0 || selected >= UserConfig.MAX_ACCOUNT_COUNT
                        || !ctx.getSharedPreferences(userConfigName(selected), Context.MODE_PRIVATE).contains("user")) {
                    main.edit().putInt("selectedAccount", first).commit();
                }
            }
            Log.i(TAG, "applied staged restore: " + restored + "/" + planned);
        } catch (Throwable t) {
            Log.e(TAG, "staged restore failed", t);
            error = rawError(t);
        } finally {
            writeResult(ctx, planned, restored, error);
            deleteRecursive(dir);
        }
    }

    /**
     * Puts one staged session into a signed-out slot. The session is copied next to the live
     * file first, and the slot is refused if it got signed in since the staging, so a failure
     * at any step leaves a working login as it was.
     */
    private static void applyAccount(Context ctx, File dir, int slot, String user) throws IOException {
        File staged = new File(dir, "slot" + slot + ".tgnet");
        if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT || TextUtils.isEmpty(user) || !staged.isFile()) {
            throw new IOException("bad staged account");
        }
        SharedPreferences sp = ctx.getSharedPreferences(userConfigName(slot), Context.MODE_PRIVATE);
        if (sp.contains("user")) throw new IOException("slot " + slot + " is signed in");
        File target = slotDir(ctx, slot);
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create account dir");
        File live = new File(target, TGNET);
        File tmp = copyToTemp(staged, live);
        // tgnet would put a left-over .bak back over the restored file at start
        File bak = new File(target, TGNET + ".bak");
        if (bak.exists() && !bak.delete()) {
            tmp.delete();
            throw new IOException("cannot delete " + bak.getName());
        }
        if (!tmp.renameTo(live)) {
            tmp.delete();
            throw new IOException("cannot move " + TGNET);
        }
        for (String name : SLOT_FILES_TO_DROP) {
            File f = new File(target, name);
            if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + name);
        }
        // slot 0's file also holds global keys; logout already cleared its account keys
        SharedPreferences.Editor e = sp.edit();
        if (slot != 0) e.clear();
        if (!e.putString("user", user).commit()) {
            live.delete();
            throw new IOException("cannot write account config");
        }
        // a stale hidden marker on the slot must not hide the restored account
        ctx.getSharedPreferences(HIDDEN_PREFS, Context.MODE_PRIVATE).edit()
                .remove(HIDDEN_HASH_PREFIX + slot).remove(HIDDEN_SALT_PREFIX + slot).commit();
        dropSlotKeys(ctx, slot);
    }

    /*
     * Old slot-numbered feature keys ("on_3", "u_3_<user>") of the slot belonged to the account
     * that was there before; left alone they would move onto the restored account the first
     * time it reads them (PlusUtil.accountKey).
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

    private static void writeResult(Context ctx, int planned, int restored, String error) {
        if (planned == 0 && error == null) return; // incomplete staging, nothing was tried
        try (FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), RESULT_FILE))) {
            JSONObject r = new JSONObject().put("planned", planned).put("restored", restored);
            if (error != null) r.put("error", error.length() > 500 ? error.substring(0, 500) : error);
            fos.write(r.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.e(TAG, "cannot write restore result", t);
        }
    }

    /** Shows, once, what the last restore did. Call on the UI thread from a visible screen. */
    public static void showPendingResult(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        JSONObject r;
        try {
            File f = new File(ApplicationLoader.applicationContext.getFilesDir(), RESULT_FILE);
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
        final String error = r.optString("error", "");
        final boolean expired = "expired".equals(error);
        String accounts = LocaleController.formatPluralString("HanakoAccounts", restored);
        String msg;
        if (expired) {
            msg = LocaleController.getString(R.string.HanakoResultExpired);
        } else if (restored == 0) {
            msg = LocaleController.getString(R.string.HanakoResultRestoreFailed);
        } else if (restored < planned) {
            msg = LocaleController.formatString(R.string.HanakoResultRestorePartial, accounts, planned - restored);
        } else {
            msg = LocaleController.formatString(R.string.HanakoResultRestored, accounts);
        }
        if (restored > 0) {
            msg += "\n\n" + LocaleController.getString(R.string.HanakoResultPasscodeNote);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoBackupRestoreTitle));
        b.setMessage(msg);
        b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        if (!expired && !TextUtils.isEmpty(error)) {
            b.setNeutralButton(LocaleController.getString(R.string.HanakoErrDetails), (d, w) -> {
                AlertDialog.Builder details = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
                details.setTitle(LocaleController.getString(R.string.HanakoErrDetails));
                details.setMessage(error);
                details.setPositiveButton(LocaleController.getString(R.string.OK), null);
                fragment.showDialog(details.create());
            });
        }
        fragment.showDialog(b.create());
    }

    // =====================================================================================
    // Restart / login entry
    // =====================================================================================

    /** Same restart as the debug "tablet mode" toggle: relaunch the launcher activity and exit. */
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
        int pad = AndroidUtilities.dp(8);
        button.setPadding(pad, pad, pad, pad);
        button.setMinHeight(AndroidUtilities.dp(48)); // touch target
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(v -> fragment.presentFragment(new it.belloworld.mercurygram.ui.HanakoBackupActivity(true)));
        // a restore that ended back here (nothing restored) explains itself once
        AndroidUtilities.runOnUIThread(() -> showPendingResult(fragment), 600);
        parent.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
        return 52;
    }

    /** Name, @username and masked phone of a backup account. */
    public static String label(Account a) {
        StringBuilder sb = new StringBuilder(UserObject.getUserName(a.user));
        String username = UserObject.getPublicUsername(a.user);
        if (!TextUtils.isEmpty(username)) sb.append(" @").append(username);
        String phone = a.user.phone;
        if (!TextUtils.isEmpty(phone)) {
            sb.append(" +");
            for (int i = 0; i < phone.length() - 4; i++) sb.append('•');
            sb.append(phone.substring(Math.max(0, phone.length() - 4)));
        }
        return sb.toString();
    }

    // =====================================================================================
    // Crypto / IO helpers
    // =====================================================================================

    /** ByteArrayOutputStream whose buffer can be read in place and zeroed. */
    private static final class WipeableBytes extends ByteArrayOutputStream {
        byte[] buffer() {
            return buf;
        }

        void wipe() {
            Arrays.fill(buf, (byte) 0);
        }
    }

    /**
     * Opens a picked file for writing from the start. "w" alone does not truncate on some
     * providers, so overwriting a longer old file would leave its tail and break the GCM tag.
     */
    private static OutputStream openTruncated(Context context, Uri uri) throws IOException {
        try {
            return context.getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException e) {
            return context.getContentResolver().openOutputStream(uri, "w");
        }
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018), 32 bytes, over the UTF-8 bytes of the password. Same on every device. */
    static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        if (password == null || password.length == 0) throw new GeneralSecurityException("empty password");
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] pw = new byte[bb.remaining()];
        bb.get(pw);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pw, "HmacSHA256"));
            byte[] u = new byte[32];
            byte[] t = new byte[32];
            mac.update(salt);
            mac.update(new byte[]{0, 0, 0, 1}); // block 1 is all of a 32-byte key
            mac.doFinal(u, 0);
            System.arraycopy(u, 0, t, 0, 32);
            for (int i = 1; i < iterations; i++) {
                mac.update(u);
                mac.doFinal(u, 0);
                for (int k = 0; k < 32; k++) t[k] ^= u[k];
            }
            Arrays.fill(u, (byte) 0);
            return t;
        } finally {
            Arrays.fill(pw, (byte) 0);
        }
    }

    private static byte[] readAll(ContentResolver resolver, Uri uri, int max) throws IOException {
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
}
