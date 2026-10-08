package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

/**
 * plus f17: "Disable proxy while a VPN is active".
 *
 * While the app's default network is a VPN, every proxy write into native is turned into
 * "no proxy" (hook at the top of ConnectionsManager.setProxySettings and in ConnectionsManager.init),
 * so MTProto goes straight through the VPN. The saved proxy and proxy_enabled are left untouched,
 * so the proxy comes back by itself when the VPN goes down. Tor always wins: nothing is bypassed
 * while mg_useTor is on. A per-app VPN that excludes this app is not seen as active, which is right
 * because our traffic does not go through it.
 */
public final class PlusVpnProxy {

    private static final String PREFS = "plus_f17";
    private static final String KEY_ENABLED = "vpn_disable_proxy";
    private static final long DEBOUNCE_MS = 1000;

    private static boolean registered;
    private static Boolean lastBypass;
    private static final Runnable reapplyRunnable = PlusVpnProxy::reapply;

    private PlusVpnProxy() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            register(ApplicationLoader.applicationContext);
        }
        AndroidUtilities.runOnUIThread(PlusVpnProxy::reapply);
    }

    /** Called once from ApplicationLoader. */
    public static void init(Context context) {
        if (isEnabled()) {
            register(context);
        }
    }

    /** Hook: true when the configured proxy must not be handed to native right now. */
    public static boolean shouldBypass() {
        try {
            return isEnabled() && !SharedConfig.mg_useTor && isVpnActive();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean isVpnActive() {
        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private static synchronized void register(Context context) {
        if (registered) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    schedule();
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                    schedule();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    schedule();
                }
            });
            registered = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void schedule() {
        AndroidUtilities.cancelRunOnUIThread(reapplyRunnable);
        AndroidUtilities.runOnUIThread(reapplyRunnable, DEBOUNCE_MS);
    }

    /** Re-push the user's proxy into native when the bypass decision flipped. UI thread. */
    private static void reapply() {
        if (SharedConfig.mg_useTor) {
            lastBypass = null;
            return; // Tor owns the native proxy slot
        }
        boolean bypass = shouldBypass();
        if (lastBypass != null && lastBypass == bypass) {
            return;
        }
        boolean first = lastBypass == null;
        lastBypass = bypass;
        if (first && !bypass) {
            return; // nothing was bypassed before, native already has the right proxy
        }
        SharedPreferences main = MessagesController.getGlobalMainSettings();
        if (!main.getBoolean("proxy_enabled", false)) {
            return;
        }
        ProxySettings settings = SharedConfig.currentProxy != null
                ? SharedConfig.currentProxy.settings
                : ProxySettings.fromSharedPreferences(main);
        if (settings == null || !settings.isValid()) {
            return;
        }
        // setProxySettings applies shouldBypass() itself, so this either restores or drops the proxy
        ConnectionsManager.setProxySettings(true, settings);
    }
}
