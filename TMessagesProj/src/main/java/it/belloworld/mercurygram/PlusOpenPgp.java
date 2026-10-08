package it.belloworld.mercurygram;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.widget.Toast;

import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.OpenPgpDecryptionResult;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpMetadata;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.UItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * plus f18: OpenPGP sign / encrypt / decrypt for text messages through an
 * OpenPGP provider app (OpenKeychain) over the org.openintents.openpgp AIDL
 * service, the same API NekoX/Nagram used. No PGP code and no openpgp-api
 * library are bundled: the service AIDL and its four result Parcelables are
 * mirrored in org.openintents.openpgp, everything else lives here.
 *
 * - Global: enable switch, provider app, default signing key (settings).
 * - Per chat (header menu "OpenPGP"): Off / Sign / Encrypt / Sign and encrypt,
 *   recipient keys, optional signing-key override. When a mode is on, typed
 *   text is transformed by the provider before it is sent; plaintext is never
 *   sent as a fallback when that fails.
 * - Incoming: a message whose text holds an ASCII-armored PGP block gets a
 *   "Decrypt / verify" item in its tap menu; the result opens in a dialog.
 *
 * Everything is local IPC to the provider app: no Telegram requests are made,
 * so ghost mode is unaffected. The encrypted or signed text is sent through
 * the normal send path exactly as typed text would be.
 *
 * Prefs file "plus_f18". ids 1800..1899.
 */
public final class PlusOpenPgp {

    private PlusOpenPgp() {}

    // ids (f18 range 1800..1899)
    public static final int CHAT_MENU_ID = 1800;   // ChatActivity header menu item
    public static final int OPTION_DECRYPT = 1801; // ChatActivity message menu option
    public static final int ROW_ENABLED = 1810;    // settings rows
    public static final int ROW_PROVIDER = 1811;
    public static final int ROW_SIGN_KEY = 1812;

    public static final int MODE_OFF = 0;
    public static final int MODE_SIGN = 1;
    public static final int MODE_ENCRYPT = 2;
    public static final int MODE_SIGN_ENCRYPT = 3;

    public static final String DEFAULT_PROVIDER = "org.sufficientlysecure.keychain";
    private static final String SERVICE_ACTION = "org.openintents.openpgp.IOpenPgpService2";

    // OpenPgpApi wire constants (openpgp-api 12, API_VERSION 11)
    private static final int API_VERSION = 11;
    private static final String EXTRA_API_VERSION = "api_version";
    private static final String ACTION_CLEARTEXT_SIGN = "org.openintents.openpgp.action.CLEARTEXT_SIGN";
    private static final String ACTION_ENCRYPT = "org.openintents.openpgp.action.ENCRYPT";
    private static final String ACTION_SIGN_AND_ENCRYPT = "org.openintents.openpgp.action.SIGN_AND_ENCRYPT";
    private static final String ACTION_DECRYPT_VERIFY = "org.openintents.openpgp.action.DECRYPT_VERIFY";
    private static final String ACTION_GET_SIGN_KEY_ID = "org.openintents.openpgp.action.GET_SIGN_KEY_ID";
    private static final String ACTION_GET_KEY_IDS = "org.openintents.openpgp.action.GET_KEY_IDS";
    private static final String EXTRA_REQUEST_ASCII_ARMOR = "ascii_armor";
    private static final String EXTRA_KEY_IDS = "key_ids";
    private static final String EXTRA_SIGN_KEY_ID = "sign_key_id";
    private static final String RESULT_SIGN_KEY_ID = "sign_key_id";
    private static final String RESULT_PRIMARY_USER_ID = "primary_user_id";
    private static final String RESULT_KEY_IDS = "key_ids";
    private static final String RESULT_CODE = "result_code";
    private static final int RESULT_CODE_ERROR = 0;
    private static final int RESULT_CODE_SUCCESS = 1;
    private static final int RESULT_CODE_USER_INTERACTION_REQUIRED = 2;
    private static final String RESULT_ERROR = "error";
    private static final String RESULT_INTENT = "intent";
    private static final String RESULT_SIGNATURE = "signature";
    private static final String RESULT_DECRYPTION = "decryption";
    private static final String RESULT_METADATA = "metadata";
    private static final String RESULT_CHARSET = "charset";

    private static final int MAX_INTERACTIONS = 4;      // permission + key pick + passphrase + spare
    private static final int MAX_OUTPUT = 1024 * 1024;  // provider output cap
    private static final long PUMP_JOIN_MS = 10_000;
    private static final long IDLE_UNBIND_MS = 60_000;

    private static final Pattern ARMOR = Pattern.compile(
            "-----BEGIN PGP MESSAGE-----[\\s\\S]*?-----END PGP MESSAGE-----"
                    + "|-----BEGIN PGP SIGNED MESSAGE-----[\\s\\S]*?-----END PGP SIGNATURE-----");

    // ---------------------------------------------------------------- prefs

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_f18", Context.MODE_PRIVATE);
    }

    private static String chatKey(String what, int account, long dialogId) {
        return what + "_" + account + "_" + dialogId;
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", false);
    }

    public static String getProvider() {
        String p = prefs().getString("provider", null);
        return TextUtils.isEmpty(p) ? DEFAULT_PROVIDER : p;
    }

    public static int getMode(int account, long dialogId) {
        return prefs().getInt(chatKey("mode", account, dialogId), MODE_OFF);
    }

    private static void setMode(int account, long dialogId, int mode) {
        SharedPreferences.Editor e = prefs().edit();
        if (mode == MODE_OFF) {
            e.remove(chatKey("mode", account, dialogId));
        } else {
            e.putInt(chatKey("mode", account, dialogId), mode);
        }
        e.apply();
    }

    private static long[] getRecipients(int account, long dialogId) {
        String s = prefs().getString(chatKey("keys", account, dialogId), "");
        if (TextUtils.isEmpty(s)) {
            return new long[0];
        }
        String[] parts = s.split(",");
        long[] out = new long[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                long v = Long.parseLong(p.trim());
                out[n++] = v;
            } catch (NumberFormatException ignored) {
            }
        }
        if (n == out.length) {
            return out;
        }
        long[] trimmed = new long[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static void setRecipients(int account, long dialogId, long[] keys) {
        StringBuilder sb = new StringBuilder();
        if (keys != null) {
            for (long k : keys) {
                if (k == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(k); // signed decimal: Long.parseUnsignedLong needs API 26
            }
        }
        prefs().edit().putString(chatKey("keys", account, dialogId), sb.toString()).apply();
    }

    private static long getDefaultSignKey() {
        return prefs().getLong("sign_key", 0);
    }

    /** Chat override when set, otherwise the default signing key; 0 = none. */
    private static long getSignKey(int account, long dialogId) {
        long k = prefs().getLong(chatKey("sign", account, dialogId), 0);
        return k != 0 ? k : getDefaultSignKey();
    }

    private static String keyLabel(long keyId, String uid) {
        if (keyId == 0) {
            return LocaleController.getString(R.string.PlusF18None);
        }
        String hex = String.format(Locale.US, "%016X", keyId);
        return TextUtils.isEmpty(uid) ? hex : uid + " (" + hex.substring(8) + ")";
    }

    // ------------------------------------------------------------ detection

    /** First ASCII-armored PGP message or cleartext-signed block in text, or null. */
    public static String findArmor(CharSequence text) {
        if (text == null || text.length() == 0 || TextUtils.indexOf(text, "-----BEGIN PGP ") < 0) {
            return null;
        }
        Matcher m = ARMOR.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** plus f18 hook: show "Decrypt / verify" for this message? */
    public static boolean hasArmor(MessageObject m) {
        return isEnabled() && m != null && m.messageOwner != null && findArmor(m.messageOwner.message) != null;
    }

    // ------------------------------------------------------- service binding
    // All binding state is touched on the main thread only.

    private static volatile IOpenPgpService2 service;
    private static String boundPackage;
    private static boolean bound;
    private static int inflight;
    private static final ArrayList<Runnable> waiting = new ArrayList<>();
    private static final AtomicInteger pipeIds = new AtomicInteger();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plus-openpgp");
        t.setDaemon(true);
        return t;
    });

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IOpenPgpService2.Stub.asInterface(binder);
            drainWaiting();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            unbind();
        }
    };

    private static final Runnable idleUnbind = () -> {
        if (inflight == 0) {
            unbind();
        }
    };

    private static void drainWaiting() {
        ArrayList<Runnable> drain = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable r : drain) {
            try {
                r.run();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    private static void unbind() {
        if (bound) {
            try {
                ApplicationLoader.applicationContext.unbindService(connection);
            } catch (Throwable ignored) {
            }
        }
        bound = false;
        boundPackage = null;
        service = null;
        drainWaiting(); // waiters see service == null and fail cleanly
    }

    private static void withService(Runnable r) {
        String pkg = getProvider();
        if (bound && !pkg.equals(boundPackage)) {
            unbind();
        }
        AndroidUtilities.cancelRunOnUIThread(idleUnbind);
        if (service != null) {
            r.run();
            return;
        }
        waiting.add(r);
        if (bound) {
            return;
        }
        Intent intent = new Intent(SERVICE_ACTION).setPackage(pkg);
        boolean ok;
        try {
            ok = ApplicationLoader.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            FileLog.e(t);
            ok = false;
        }
        if (ok) {
            bound = true;
            boundPackage = pkg;
        } else {
            try {
                ApplicationLoader.applicationContext.unbindService(connection);
            } catch (Throwable ignored) {
            }
            drainWaiting();
        }
    }

    // ------------------------------------------------------------- execute

    private interface RawCallback {
        void done(Intent result, byte[] output);
    }

    private interface Result {
        void onSuccess(Intent result, byte[] output);

        void onError(String message);
    }

    private static Intent errorIntent(String message) {
        Intent i = new Intent();
        i.putExtra(RESULT_CODE, RESULT_CODE_ERROR);
        i.putExtra(RESULT_ERROR, new OpenPgpError(OpenPgpError.CLIENT_SIDE_ERROR, message));
        return i;
    }

    private static void finish(RawCallback cb, Intent result, byte[] output) {
        inflight--;
        if (inflight <= 0) {
            inflight = 0;
            AndroidUtilities.runOnUIThread(idleUnbind, IDLE_UNBIND_MS);
        }
        try {
            cb.done(result, output);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** One raw provider call. Main thread in, main thread out. */
    private static void execute(Intent data, byte[] input, boolean wantOutput, RawCallback cb) {
        inflight++;
        withService(() -> {
            final IOpenPgpService2 s = service;
            if (s == null) {
                finish(cb, errorIntent(LocaleController.getString(R.string.PlusF18NoProvider)), null);
                return;
            }
            worker.execute(() -> {
                Intent result;
                byte[] output = null;
                ParcelFileDescriptor inRead = null;
                ParcelFileDescriptor outRead = null;
                try {
                    data.putExtra(EXTRA_API_VERSION, API_VERSION);
                    if (input != null) {
                        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                        inRead = pipe[0];
                        final ParcelFileDescriptor inWrite = pipe[1];
                        Thread writer = new Thread(() -> {
                            try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(inWrite)) {
                                os.write(input);
                            } catch (IOException e) {
                                FileLog.e(e);
                            }
                        }, "plus-openpgp-in");
                        writer.setDaemon(true);
                        writer.start();
                    }
                    int pipeId = 0;
                    Thread pump = null;
                    final ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    if (wantOutput) {
                        pipeId = pipeIds.incrementAndGet();
                        outRead = s.createOutputPipe(pipeId);
                        final ParcelFileDescriptor src = outRead;
                        pump = new Thread(() -> {
                            try (InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(src)) {
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = is.read(buf)) > 0) {
                                    synchronized (sink) {
                                        if (sink.size() + n > MAX_OUTPUT) {
                                            break;
                                        }
                                        sink.write(buf, 0, n);
                                    }
                                }
                            } catch (IOException e) {
                                FileLog.e(e);
                            }
                        }, "plus-openpgp-out");
                        pump.setDaemon(true);
                        pump.start();
                    }
                    result = s.execute(data, inRead, pipeId); // blocks until the provider is done
                    if (result == null) {
                        result = errorIntent(LocaleController.getString(R.string.PlusF18ErrorGeneric));
                    }
                    result.setExtrasClassLoader(PlusOpenPgp.class.getClassLoader());
                    if (pump != null) {
                        pump.join(PUMP_JOIN_MS);
                        synchronized (sink) {
                            output = sink.toByteArray();
                        }
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                    result = errorIntent(t.getMessage());
                } finally {
                    closeQuietly(inRead);
                    closeQuietly(outRead);
                }
                final Intent r = result;
                final byte[] o = output;
                AndroidUtilities.runOnUIThread(() -> finish(cb, r, o));
            });
        });
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private static <T> T parcelableExtra(Intent i, String key) {
        try {
            return (T) i.getParcelableExtra(key);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static int resultCode(Intent i) {
        try {
            return i.getIntExtra(RESULT_CODE, RESULT_CODE_ERROR);
        } catch (Throwable t) { // unknown Parcelable in the result Bundle
            FileLog.e(t);
            return -1;
        }
    }

    /** Provider call with the user-interaction loop (permission, key pick, passphrase). */
    private static void run(Activity activity, Intent data, byte[] input, boolean wantOutput, int depth, Result cb) {
        data.setExtrasClassLoader(PlusOpenPgp.class.getClassLoader());
        execute(data, input, wantOutput, (res, out) -> {
            int code = resultCode(res);
            if (code == RESULT_CODE_SUCCESS) {
                cb.onSuccess(res, out);
                return;
            }
            if (code == RESULT_CODE_USER_INTERACTION_REQUIRED) {
                PendingIntent pi = parcelableExtra(res, RESULT_INTENT);
                if (pi == null || activity == null || activity.isFinishing() || depth >= MAX_INTERACTIONS) {
                    cb.onError(LocaleController.getString(R.string.PlusF18ErrorInteraction));
                    return;
                }
                PlusOpenPgpProxyActivity.launch(activity, pi, returned -> {
                    if (returned == null) {
                        cb.onError(LocaleController.getString(R.string.PlusF18Cancelled));
                    } else {
                        run(activity, returned, input, wantOutput, depth + 1, cb);
                    }
                });
                return;
            }
            if (code == -1) {
                cb.onError(LocaleController.getString(R.string.PlusF18ErrorResponse));
                return;
            }
            OpenPgpError err = parcelableExtra(res, RESULT_ERROR);
            cb.onError(err != null && !TextUtils.isEmpty(err.message) ? err.message
                    : LocaleController.getString(R.string.PlusF18ErrorGeneric));
        });
    }

    private static void toast(CharSequence text) {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static boolean isProviderInstalled(String pkg) {
        try {
            Intent probe = new Intent(SERVICE_ACTION).setPackage(pkg);
            return ApplicationLoader.applicationContext.getPackageManager().resolveService(probe, 0) != null;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    // --------------------------------------------------------- key choosers

    private static void chooseSignKey(Activity activity, Utilities.Callback2<Long, String> done) {
        run(activity, new Intent(ACTION_GET_SIGN_KEY_ID), null, false, 0, new Result() {
            @Override
            public void onSuccess(Intent result, byte[] output) {
                long id = result.getLongExtra(RESULT_SIGN_KEY_ID, 0);
                if (id == 0) {
                    toast(LocaleController.getString(R.string.PlusF18Cancelled));
                    return;
                }
                done.run(id, result.getStringExtra(RESULT_PRIMARY_USER_ID));
            }

            @Override
            public void onError(String message) {
                toast(message);
            }
        });
    }

    private static void chooseRecipients(Activity activity, Utilities.Callback<long[]> done) {
        // No user ids: the provider answers with its public-key picker.
        run(activity, new Intent(ACTION_GET_KEY_IDS), null, false, 0, new Result() {
            @Override
            public void onSuccess(Intent result, byte[] output) {
                long[] ids = result.getLongArrayExtra(RESULT_KEY_IDS);
                if (ids == null || ids.length == 0) {
                    toast(LocaleController.getString(R.string.PlusF18NoKeysPicked));
                    return;
                }
                done.run(ids);
            }

            @Override
            public void onError(String message) {
                toast(message);
            }
        });
    }

    // --------------------------------------------------------------- settings

    /** plus f18 hook: settings rows for MercurygramSettingsActivity. */
    public static void addSettingsRows(ArrayList<UItem> items) {
        boolean on = isEnabled();
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF18Title)));
        items.add(UItem.asCheck(ROW_ENABLED, LocaleController.getString(R.string.PlusF18Enable)).setChecked(on));
        if (on) {
            items.add(UItem.asButton(ROW_PROVIDER, LocaleController.getString(R.string.PlusF18Provider), providerLabel()));
            items.add(UItem.asButton(ROW_SIGN_KEY, LocaleController.getString(R.string.PlusF18DefaultSignKey),
                    keyLabel(getDefaultSignKey(), prefs().getString("sign_uid", null))));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF18About)));
    }

    private static String providerLabel() {
        String pkg = getProvider();
        if (!isProviderInstalled(pkg)) {
            return LocaleController.getString(R.string.PlusF18NotInstalled);
        }
        try {
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** plus f18 hook: settings row clicks; true when handled. refresh re-fills the list. */
    public static boolean onSettingsClick(BaseFragment fragment, int id, Runnable refresh) {
        if (id == ROW_ENABLED) {
            boolean on = !isEnabled();
            prefs().edit().putBoolean("enabled", on).apply();
            if (on && !isProviderInstalled(getProvider())) {
                toast(LocaleController.getString(R.string.PlusF18NoProvider));
            }
            if (!on) {
                unbind();
            }
            refresh.run();
            return true;
        }
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return id == ROW_PROVIDER || id == ROW_SIGN_KEY;
        }
        if (id == ROW_PROVIDER) {
            pickProvider(fragment, refresh);
            return true;
        }
        if (id == ROW_SIGN_KEY) {
            CharSequence[] labels = {
                    LocaleController.getString(R.string.PlusF18ChooseKey),
                    LocaleController.getString(R.string.PlusF18ClearKey)
            };
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle(LocaleController.getString(R.string.PlusF18DefaultSignKey));
            b.setItems(labels, (dialog, which) -> {
                if (which == 0) {
                    chooseSignKey(activity, (keyId, uid) -> {
                        prefs().edit().putLong("sign_key", keyId).putString("sign_uid", uid).apply();
                        refresh.run();
                    });
                } else {
                    prefs().edit().remove("sign_key").remove("sign_uid").apply();
                    refresh.run();
                }
            });
            fragment.showDialog(b.create());
            return true;
        }
        return false;
    }

    private static void pickProvider(BaseFragment fragment, Runnable refresh) {
        Activity activity = fragment.getParentActivity();
        PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentServices(new Intent(SERVICE_ACTION), 0);
        } catch (Throwable t) {
            FileLog.e(t);
            found = null;
        }
        final ArrayList<String> pkgs = new ArrayList<>();
        final ArrayList<CharSequence> labels = new ArrayList<>();
        if (found != null) {
            for (ResolveInfo ri : found) {
                if (ri.serviceInfo == null || pkgs.contains(ri.serviceInfo.packageName)) {
                    continue;
                }
                pkgs.add(ri.serviceInfo.packageName);
                CharSequence l = ri.loadLabel(pm);
                labels.add(TextUtils.isEmpty(l) ? ri.serviceInfo.packageName : l);
            }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.PlusF18Provider));
        if (pkgs.isEmpty()) {
            b.setMessage(LocaleController.getString(R.string.PlusF18NoProvider));
            b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        } else {
            b.setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                prefs().edit().putString("provider", pkgs.get(which)).apply();
                unbind();
                refresh.run();
            });
        }
        fragment.showDialog(b.create());
    }

    // ------------------------------------------------------------ chat menu

    /** plus f18 hook: ChatActivity header menu item. */
    public static void addChatMenuItem(ActionBarMenuItem headerItem) {
        if (headerItem != null && isEnabled()) {
            headerItem.lazilyAddSubItem(CHAT_MENU_ID, R.drawable.msg_secret, LocaleController.getString(R.string.PlusF18ChatMenu));
        }
    }

    private static String modeLabel(int mode) {
        switch (mode) {
            case MODE_SIGN:
                return LocaleController.getString(R.string.PlusF18ModeSign);
            case MODE_ENCRYPT:
                return LocaleController.getString(R.string.PlusF18ModeEncrypt);
            case MODE_SIGN_ENCRYPT:
                return LocaleController.getString(R.string.PlusF18ModeSignEncrypt);
            default:
                return LocaleController.getString(R.string.PlusF18ModeOff);
        }
    }

    /** plus f18 hook: ChatActivity header menu click — per-chat mode and keys. */
    public static void openChatSettings(BaseFragment fragment, int account, long dialogId) {
        Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final int mode = getMode(account, dialogId);
        final long chatSign = prefs().getLong(chatKey("sign", account, dialogId), 0);
        CharSequence[] labels = new CharSequence[6];
        for (int m = MODE_OFF; m <= MODE_SIGN_ENCRYPT; m++) {
            labels[m] = (m == mode ? "\u2713  " : "      ") + modeLabel(m);
        }
        labels[4] = LocaleController.formatString(R.string.PlusF18RecipientsRow, getRecipients(account, dialogId).length);
        labels[5] = LocaleController.formatString(R.string.PlusF18ChatSignRow, chatSign != 0
                ? keyLabel(chatSign, prefs().getString(chatKey("sign_uid", account, dialogId), null))
                : LocaleController.getString(R.string.PlusF18UseDefault));
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.PlusF18Title));
        b.setItems(labels, (dialog, which) -> {
            if (which <= MODE_SIGN_ENCRYPT) {
                applyMode(activity, account, dialogId, which);
            } else if (which == 4) {
                chooseRecipients(activity, ids -> {
                    setRecipients(account, dialogId, ids);
                    toast(LocaleController.formatString(R.string.PlusF18RecipientsRow, ids.length));
                });
            } else {
                chooseChatSignKey(fragment, activity, account, dialogId);
            }
        });
        fragment.showDialog(b.create());
    }

    private static void chooseChatSignKey(BaseFragment fragment, Activity activity, int account, long dialogId) {
        CharSequence[] labels = {
                LocaleController.getString(R.string.PlusF18ChooseKey),
                LocaleController.formatString(R.string.PlusF18UseDefaultKey,
                        keyLabel(getDefaultSignKey(), prefs().getString("sign_uid", null)))
        };
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.PlusF18SignKey));
        b.setItems(labels, (dialog, which) -> {
            if (which == 0) {
                chooseSignKey(activity, (keyId, uid) -> prefs().edit()
                        .putLong(chatKey("sign", account, dialogId), keyId)
                        .putString(chatKey("sign_uid", account, dialogId), uid).apply());
            } else {
                prefs().edit().remove(chatKey("sign", account, dialogId))
                        .remove(chatKey("sign_uid", account, dialogId)).apply();
            }
        });
        fragment.showDialog(b.create());
    }

    /** Set a mode, first asking the provider for whatever keys it needs. */
    private static void applyMode(Activity activity, int account, long dialogId, int mode) {
        boolean needsRecipients = mode == MODE_ENCRYPT || mode == MODE_SIGN_ENCRYPT;
        boolean needsSign = mode == MODE_SIGN || mode == MODE_SIGN_ENCRYPT;
        if (needsRecipients && getRecipients(account, dialogId).length == 0) {
            chooseRecipients(activity, ids -> {
                setRecipients(account, dialogId, ids);
                applyMode(activity, account, dialogId, mode);
            });
            return;
        }
        if (needsSign && getSignKey(account, dialogId) == 0) {
            chooseSignKey(activity, (keyId, uid) -> {
                // No default yet: the first key picked becomes the default.
                prefs().edit().putLong("sign_key", keyId).putString("sign_uid", uid).apply();
                applyMode(activity, account, dialogId, mode);
            });
            return;
        }
        setMode(account, dialogId, mode);
        toast(LocaleController.formatString(R.string.PlusF18ModeSet, modeLabel(mode)));
    }

    // ----------------------------------------------------------------- send

    // Main thread only. pendingArmored lives for the duration of one resend.
    private static CharSequence pendingArmored;
    private static boolean bypassOnce;
    private static boolean busy;
    private static long busySince;
    private static final long BUSY_STALE_MS = 120_000; // a provider that never answers must not block sending forever

    /**
     * plus f18 hook (ChatActivityEnterView send path, where the field text is
     * read): during the resend after a successful transform, swaps in the
     * armored text and lets that one send through interceptSend.
     */
    public static CharSequence takeArmored(CharSequence text) {
        if (pendingArmored == null) {
            return text;
        }
        CharSequence a = pendingArmored;
        pendingArmored = null;
        bypassOnce = true;
        return a;
    }

    /**
     * plus f18 hook (ChatActivityEnterView send path, before the text is
     * processed): when this chat has a mode on, transforms the text through the
     * provider and returns true (the caller stops); resend re-enters the send
     * path, which then sends the armored text. On failure nothing is sent and
     * the typed text stays in the field.
     */
    public static boolean interceptSend(ChatActivityEnterView view, BaseFragment fragment, int account, long dialogId,
                                        CharSequence text, Runnable resend) {
        if (bypassOnce) {
            bypassOnce = false;
            return false;
        }
        if (!isEnabled() || view == null || view.isEditingMessage()) {
            return false;
        }
        final int mode = getMode(account, dialogId);
        if (mode == MODE_OFF || TextUtils.isEmpty(text)) {
            return false;
        }
        // The text Telegram would send: trimmed, markdown markers turned into
        // entities and removed. Formatting is dropped; PGP carries plain text.
        CharSequence[] normalized = {AndroidUtilities.getTrimmedString(text)};
        MediaDataController.getInstance(account).getEntities(normalized, true);
        final String plain = normalized[0] == null ? "" : normalized[0].toString();
        if (plain.trim().isEmpty() || findArmor(plain) != null) {
            return false; // nothing to protect, or already a PGP block
        }
        if (busy && android.os.SystemClock.elapsedRealtime() - busySince < BUSY_STALE_MS) {
            toast(LocaleController.getString(R.string.PlusF18Busy));
            return true;
        }
        Activity activity = fragment == null ? null : fragment.getParentActivity();
        Intent data = buildSendIntent(mode, account, dialogId);
        if (activity == null || data == null) {
            toast(LocaleController.getString(R.string.PlusF18KeysMissing));
            if (fragment != null && data == null) {
                openChatSettings(fragment, account, dialogId);
            }
            return true;
        }
        busy = true;
        busySince = android.os.SystemClock.elapsedRealtime();
        run(activity, data, plain.getBytes(StandardCharsets.UTF_8), true, 0, new Result() {
            @Override
            public void onSuccess(Intent result, byte[] output) {
                busy = false;
                String armored = output == null ? "" : new String(output, StandardCharsets.UTF_8).trim();
                if (findArmor(armored) == null) {
                    toast(LocaleController.getString(R.string.PlusF18ErrorGeneric));
                    return;
                }
                if (armored.length() > MessagesController.getInstance(account).getMaxMessageLength()) {
                    // The send path would split it into several messages and break the block.
                    toast(LocaleController.getString(R.string.PlusF18TooLong));
                    return;
                }
                pendingArmored = armored;
                try {
                    resend.run();
                } finally {
                    pendingArmored = null;
                    bypassOnce = false;
                }
            }

            @Override
            public void onError(String message) {
                busy = false;
                toast(LocaleController.formatString(R.string.PlusF18SendFailed, message));
            }
        });
        return true;
    }

    private static Intent buildSendIntent(int mode, int account, long dialogId) {
        long sign = getSignKey(account, dialogId);
        long[] recipients = getRecipients(account, dialogId);
        Intent data;
        switch (mode) {
            case MODE_SIGN:
                if (sign == 0) {
                    return null;
                }
                data = new Intent(ACTION_CLEARTEXT_SIGN);
                data.putExtra(EXTRA_SIGN_KEY_ID, sign);
                break;
            case MODE_ENCRYPT:
                if (recipients.length == 0) {
                    return null;
                }
                data = new Intent(ACTION_ENCRYPT);
                // Also encrypt to our own key (when one is set) so our sent copy stays readable.
                data.putExtra(EXTRA_KEY_IDS, withKey(recipients, sign));
                break;
            case MODE_SIGN_ENCRYPT:
                if (recipients.length == 0 || sign == 0) {
                    return null;
                }
                // The provider adds the signing key as a recipient itself.
                data = new Intent(ACTION_SIGN_AND_ENCRYPT);
                data.putExtra(EXTRA_SIGN_KEY_ID, sign);
                data.putExtra(EXTRA_KEY_IDS, recipients);
                break;
            default:
                return null;
        }
        data.putExtra(EXTRA_REQUEST_ASCII_ARMOR, true);
        return data;
    }

    private static long[] withKey(long[] keys, long extra) {
        if (extra == 0) {
            return keys;
        }
        for (long k : keys) {
            if (k == extra) {
                return keys;
            }
        }
        long[] out = new long[keys.length + 1];
        System.arraycopy(keys, 0, out, 0, keys.length);
        out[keys.length] = extra;
        return out;
    }

    // -------------------------------------------------------------- decrypt

    /** plus f18 hook: ChatActivity message option "Decrypt / verify". */
    public static void decrypt(BaseFragment fragment, MessageObject message) {
        Activity activity = fragment == null ? null : fragment.getParentActivity();
        String block = message == null || message.messageOwner == null ? null : findArmor(message.messageOwner.message);
        if (activity == null || block == null) {
            return;
        }
        run(activity, new Intent(ACTION_DECRYPT_VERIFY), block.getBytes(StandardCharsets.UTF_8), true, 0, new Result() {
            @Override
            public void onSuccess(Intent result, byte[] output) {
                showDecrypted(fragment, result, output);
            }

            @Override
            public void onError(String msg) {
                toast(LocaleController.formatString(R.string.PlusF18DecryptFailed, msg));
            }
        });
    }

    private static void showDecrypted(BaseFragment fragment, Intent result, byte[] output) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        String charsetName = result.getStringExtra(RESULT_CHARSET);
        OpenPgpMetadata meta = parcelableExtra(result, RESULT_METADATA);
        if (TextUtils.isEmpty(charsetName) && meta != null) {
            charsetName = meta.charset;
        }
        Charset cs = StandardCharsets.UTF_8;
        try {
            if (!TextUtils.isEmpty(charsetName)) {
                cs = Charset.forName(charsetName);
            }
        } catch (Throwable ignored) {
        }
        final String plain = output == null ? "" : new String(output, cs);

        OpenPgpSignatureResult sig = parcelableExtra(result, RESULT_SIGNATURE);
        OpenPgpDecryptionResult dec = parcelableExtra(result, RESULT_DECRYPTION);
        boolean encrypted = dec != null && dec.result != OpenPgpDecryptionResult.RESULT_NOT_ENCRYPTED;

        StringBuilder status = new StringBuilder(signatureStatus(sig));
        if (dec != null && dec.result == OpenPgpDecryptionResult.RESULT_INSECURE) {
            status.append('\n').append(LocaleController.getString(R.string.PlusF18InsecureEncryption));
        }
        final PendingIntent detail = parcelableExtra(result, RESULT_INTENT); // key details / fetch missing key

        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(encrypted ? R.string.PlusF18Decrypted : R.string.PlusF18Verified));
        b.setMessage(plain + "\n\n\u2014\n" + status);
        b.setPositiveButton(LocaleController.getString(R.string.Copy), (dialog, which) -> {
            AndroidUtilities.addToClipboard(plain);
            toast(LocaleController.getString(R.string.TextCopied));
        });
        b.setNegativeButton(LocaleController.getString(R.string.Close), null);
        if (detail != null) {
            b.setNeutralButton(LocaleController.getString(R.string.PlusF18KeyDetails), (dialog, which) ->
                    PlusOpenPgpProxyActivity.launch(activity, detail, ignored -> { }));
        }
        fragment.showDialog(b.create());
    }

    private static String signatureStatus(OpenPgpSignatureResult sig) {
        if (sig == null || sig.result == OpenPgpSignatureResult.RESULT_NO_SIGNATURE) {
            return LocaleController.getString(R.string.PlusF18SigNone);
        }
        String who = keyLabel(sig.keyId, sig.primaryUserId);
        switch (sig.result) {
            case OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED:
                return LocaleController.formatString(R.string.PlusF18SigValidConfirmed, who);
            case OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED:
                return LocaleController.formatString(R.string.PlusF18SigValidUnconfirmed, who);
            case OpenPgpSignatureResult.RESULT_KEY_MISSING:
                return LocaleController.formatString(R.string.PlusF18SigKeyMissing, who);
            case OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED:
                return LocaleController.formatString(R.string.PlusF18SigRevoked, who);
            case OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED:
                return LocaleController.formatString(R.string.PlusF18SigExpired, who);
            case OpenPgpSignatureResult.RESULT_INVALID_KEY_INSECURE:
                return LocaleController.formatString(R.string.PlusF18SigInsecure, who);
            case OpenPgpSignatureResult.RESULT_INVALID_NOT_INTENDED_RECIPIENT:
                return LocaleController.formatString(R.string.PlusF18SigNotForYou, who);
            default:
                return LocaleController.getString(R.string.PlusF18SigInvalid);
        }
    }
}
