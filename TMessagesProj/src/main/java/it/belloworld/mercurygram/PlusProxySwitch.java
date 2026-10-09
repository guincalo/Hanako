package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;

/**
 * plus f17: proxy auto-switch.
 *
 * When the selected account has been stuck in "Connecting to proxy" for {@link #FAIL_DELAY_MS},
 * every other saved proxy is pinged (results newer than {@link #RECHECK_AFTER_MS} are reused),
 * and once all answers are in (or {@link #CHECK_DEADLINE_MS} passed) the app switches to the
 * working proxy with the lowest ping. Unlike the stock "proxy rotation", which switches to the first
 * proxy that answers, this waits for the whole round and picks the fastest; while this option is on
 * the stock rotation's switch step is suppressed (hook in ProxyRotationController) so the two never
 * fight. Never runs while Tor owns the proxy slot or while the VPN bypass (PlusVpnProxy) is active.
 * Proxy pings are unauthenticated checks against the proxy, the same ones the proxy list makes.
 */
public final class PlusProxySwitch implements NotificationCenter.NotificationCenterDelegate {

    private static final String PREFS = "plus_f17";
    private static final String KEY_ENABLED = "proxy_auto_switch";

    private static final long FAIL_DELAY_MS = 10_000;
    private static final long CHECK_DEADLINE_MS = 12_000;
    private static final long RECHECK_AFTER_MS = 60_000;
    private static final long MIN_SWITCH_INTERVAL_MS = 30_000;

    private static final PlusProxySwitch INSTANCE = new PlusProxySwitch();

    private boolean failScheduled;
    private boolean roundRunning;
    private int round;
    private int pending;
    private long lastSwitchTime;

    private final Runnable failRunnable = () -> {
        failScheduled = false;
        startRound();
    };

    private PlusProxySwitch() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        HanakoTelemetry.count(HanakoTelemetry.PROXY_AUTOSWITCH_TOGGLE); // hanako: usage statistics (off by default)
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            AndroidUtilities.runOnUIThread(() -> {
                AndroidUtilities.cancelRunOnUIThread(INSTANCE.failRunnable);
                INSTANCE.failScheduled = false;
            });
        }
    }

    /** Called once from ApplicationLoader. */
    public static void init() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(INSTANCE, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(INSTANCE, NotificationCenter.proxySettingsChanged);
    }

    private static boolean canRun() {
        return isEnabled()
                && SharedConfig.isProxyEnabled()
                && !SharedConfig.mg_useTor
                && !PlusVpnProxy.shouldBypass()
                && SharedConfig.proxyList.size() > 1;
    }

    private static boolean stuckOnProxy() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState()
                == ConnectionsManager.ConnectionStateConnectingToProxy;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged) {
            // user (or we) picked a proxy: restart the failure timer from scratch
            AndroidUtilities.cancelRunOnUIThread(failRunnable);
            failScheduled = false;
            if (canRun() && stuckOnProxy()) {
                failScheduled = true;
                AndroidUtilities.runOnUIThread(failRunnable, FAIL_DELAY_MS);
            }
            return;
        }
        if (id != NotificationCenter.didUpdateConnectionState || account != UserConfig.selectedAccount) {
            return;
        }
        if (canRun() && stuckOnProxy()) {
            if (!failScheduled && !roundRunning) {
                failScheduled = true;
                AndroidUtilities.runOnUIThread(failRunnable, FAIL_DELAY_MS);
            }
        } else {
            AndroidUtilities.cancelRunOnUIThread(failRunnable);
            failScheduled = false;
        }
    }

    private void startRound() {
        if (roundRunning || !canRun() || !stuckOnProxy()) {
            return;
        }
        if (SystemClock.elapsedRealtime() - lastSwitchTime < MIN_SWITCH_INTERVAL_MS) {
            failScheduled = true;
            AndroidUtilities.runOnUIThread(failRunnable, MIN_SWITCH_INTERVAL_MS);
            return;
        }
        roundRunning = true;
        final int thisRound = ++round;
        pending = 0;
        final int currentAccount = UserConfig.selectedAccount;
        long now = SystemClock.elapsedRealtime();
        ArrayList<SharedConfig.ProxyInfo> toCheck = new ArrayList<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info == SharedConfig.currentProxy || info.mgInternal || info.checking || !info.settings.isValid()) {
                continue;
            }
            if (info.availableCheckTime != 0 && now - info.availableCheckTime < RECHECK_AFTER_MS) {
                continue; // fresh enough, reuse
            }
            toCheck.add(info);
        }
        pending = toCheck.size();
        for (SharedConfig.ProxyInfo info : toCheck) {
            info.checking = true;
            ConnectionsManager.getInstance(currentAccount).checkProxy(info.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                info.availableCheckTime = SystemClock.elapsedRealtime();
                info.checking = false;
                if (time == -1) {
                    info.available = false;
                    info.ping = 0;
                } else {
                    info.available = true;
                    info.ping = time;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info);
                if (thisRound == round && roundRunning && --pending <= 0) {
                    finishRound(thisRound);
                }
            }));
        }
        if (pending <= 0) {
            finishRound(thisRound);
        } else {
            AndroidUtilities.runOnUIThread(() -> finishRound(thisRound), CHECK_DEADLINE_MS);
        }
    }

    private void finishRound(int thisRound) {
        if (thisRound != round || !roundRunning) {
            return;
        }
        roundRunning = false;
        if (!canRun() || !stuckOnProxy()) {
            return; // recovered meanwhile, or option turned off
        }
        SharedConfig.ProxyInfo best = null;
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info == SharedConfig.currentProxy || info.mgInternal || info.checking || !info.available || !info.settings.isValid()) {
                continue;
            }
            if (best == null || info.ping < best.ping) {
                best = info;
            }
        }
        if (best == null) {
            // nothing works right now; try again after the normal delay if still stuck
            failScheduled = true;
            AndroidUtilities.runOnUIThread(failRunnable, MIN_SWITCH_INTERVAL_MS);
            return;
        }
        lastSwitchTime = SystemClock.elapsedRealtime();
        HanakoTelemetry.count(HanakoTelemetry.PROXY_AUTOSWITCH); // hanako: usage statistics (off by default)
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", true);
        best.settings.toSharedPreferences(editor);
        editor.apply();
        SharedConfig.currentProxy = best;
        ConnectionsManager.setProxySettings(true, best.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
        // hanako: say what happened instead of switching silently (P2-7)
        final String address = best.settings.getAddress();
        final long ping = best.ping;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                org.telegram.ui.Components.BulletinFactory.global().createSimpleBulletin(org.telegram.messenger.R.raw.contact_check,
                        org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.PlusProxySwitched, address, ping)).show();
            } catch (Throwable t) {
                org.telegram.messenger.FileLog.e(t);
            }
        });
    }
}
