package it.belloworld.mercurygram;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.DocumentsContract;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;

import java.util.ArrayList;

/**
 * hanako: runs a chat export as a foreground service (dataSync) so it survives the screen going
 * off and leaving the app, with a progress notification that has a Cancel action, and a final
 * notification with "Open folder". One export at a time. An export the system kills anyway is
 * not resumed: the partial folder stays readable and the export has to be started again.
 */
public class HanakoChatExportService extends Service {

    private static final int NOTIFICATION_ID = 7310;
    private static final int NOTIFICATION_DONE_ID = 7311;
    private static final String ACTION_CANCEL = "moe.hanako.chatexport.CANCEL";
    private static final long WAKELOCK_MS = 6L * 60 * 60 * 1000;

    private static HanakoChatExport running;
    private static HanakoChatExport.Progress lastProgress;
    private static String chatTitle;
    private static final ArrayList<HanakoChatExport.Listener> listeners = new ArrayList<>();

    private PowerManager.WakeLock wakeLock;

    /** In-app progress UI. Always called on the UI thread. */
    public static void addListener(HanakoChatExport.Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(HanakoChatExport.Listener l) {
        listeners.remove(l);
    }

    public static boolean isRunning() {
        return running != null;
    }

    public static long runningDialogId() {
        return running != null ? running.getDialogId() : 0;
    }

    public static HanakoChatExport.Progress lastProgress() {
        return lastProgress;
    }

    public static void cancelRunning() {
        if (running != null) running.cancel();
    }

    /** Starts an export; false when one is already running. UI thread. */
    public static boolean start(int account, HanakoChatExport.Options options, Uri tree, String title) {
        if (running != null) return false;
        chatTitle = title;
        lastProgress = new HanakoChatExport.Progress();
        final Context ctx = ApplicationLoader.applicationContext;
        running = new HanakoChatExport(account, options, tree, new HanakoChatExport.Listener() {
            @Override
            public void onProgress(HanakoChatExport.Progress p) {
                lastProgress = p;
                updateNotification(ctx, p);
                for (HanakoChatExport.Listener l : new ArrayList<>(listeners)) l.onProgress(p);
            }

            @Override
            public void onFinished(boolean cancelled, Throwable error, HanakoChatExport.Progress p, Uri exportDir) {
                running = null;
                lastProgress = null;
                try {
                    ctx.stopService(new Intent(ctx, HanakoChatExportService.class));
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
                if (!cancelled) postDone(ctx, error, p, exportDir);
                for (HanakoChatExport.Listener l : new ArrayList<>(listeners)) l.onFinished(cancelled, error, p, exportDir);
            }
        });
        try {
            Intent intent = new Intent(ctx, HanakoChatExportService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable t) {
            // the app is in the foreground here, so this should not happen; export anyway
            FileLog.e(t);
        }
        running.start();
        return true;
    }

    // ------------------------------------------------------------------ service

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelRunning();
            return START_NOT_STICKY;
        }
        Notification n = buildProgress(this, lastProgress != null ? lastProgress : new HanakoChatExport.Progress());
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        if (running == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hanako:chatexport");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(WAKELOCK_MS);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId) {
        // Android 15 dataSync time limit: stop cleanly, the partial export stays readable
        cancelRunning();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignore) {
        }
        wakeLock = null;
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------- notifications

    private static PendingIntent launchApp(Context ctx) {
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (launch == null) return null;
        return PendingIntent.getActivity(ctx, 7312, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static String progressText(HanakoChatExport.Progress p) {
        String text;
        if (p.total > 0) {
            text = LocaleController.formatString(R.string.HanakoChatExportProgressTotal,
                    LocaleController.formatNumber(p.messages, ','), LocaleController.formatNumber(p.total, ','),
                    LocaleController.formatPluralString("HanakoFiles", p.media));
        } else {
            text = LocaleController.formatString(R.string.HanakoChatExportProgressCount,
                    LocaleController.formatPluralString("HanakoMessages", p.messages),
                    LocaleController.formatPluralString("HanakoFiles", p.media));
        }
        if (p.waitSeconds > 0) {
            text += "\n" + LocaleController.formatString(R.string.HanakoChatExportFloodWait, p.waitSeconds);
        }
        return text;
    }

    private static Notification buildProgress(Context ctx, HanakoChatExport.Progress p) {
        NotificationsController.checkOtherNotificationsChannel();
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
        b.setSmallIcon(android.R.drawable.stat_sys_download);
        b.setContentTitle(LocaleController.formatString(R.string.HanakoChatExportNotifTitle, chatTitle != null ? chatTitle : ""));
        String text = progressText(p);
        b.setContentText(text.replace('\n', ' '));
        b.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setSilent(true);
        b.setCategory(NotificationCompat.CATEGORY_PROGRESS);
        if (p.total > 0) {
            b.setProgress(p.total, Math.min(p.messages, p.total), false);
        } else {
            b.setProgress(0, 0, true);
        }
        PendingIntent content = launchApp(ctx);
        if (content != null) b.setContentIntent(content);
        Intent cancel = new Intent(ctx, HanakoChatExportService.class).setAction(ACTION_CANCEL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent cancelPi = PendingIntent.getService(ctx, 7313, cancel, flags);
        b.addAction(0, LocaleController.getString(R.string.Cancel), cancelPi);
        return b.build();
    }

    private static long lastNotify;

    private static void updateNotification(Context ctx, HanakoChatExport.Progress p) {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 900 && p.waitSeconds == 0) return; // notification rate limit
        lastNotify = now;
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, buildProgress(ctx, p));
        } catch (Throwable t) {
            // POST_NOTIFICATIONS not granted: the in-app dialog still shows progress
        }
    }

    /** Intent that shows the export folder in a file manager (chooser, so "no app" is handled). */
    public static Intent openFolderIntent(Uri exportDir) {
        if (exportDir == null) return null;
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(exportDir, DocumentsContract.Document.MIME_TYPE_DIR);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooser = Intent.createChooser(view, LocaleController.getString(R.string.HanakoChatExportOpenFolder));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return chooser;
    }

    public static String summary(Throwable error, HanakoChatExport.Progress p) {
        if (error != null) {
            return HanakoBackup.describeError(error);
        }
        String text = LocaleController.formatString(R.string.HanakoChatExportDoneSummary,
                LocaleController.formatPluralString("HanakoMessages", p.messages),
                LocaleController.formatPluralString("HanakoFiles", p.media));
        if (p.failed > 0) {
            text += "\n\n" + LocaleController.formatPluralString("HanakoChatExportFailedFiles", p.failed);
        }
        return text;
    }

    private static void postDone(Context ctx, Throwable error, HanakoChatExport.Progress p, Uri exportDir) {
        try {
            NotificationsController.checkOtherNotificationsChannel();
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            b.setSmallIcon(error != null ? android.R.drawable.stat_notify_error : android.R.drawable.stat_sys_download_done);
            b.setContentTitle(LocaleController.getString(error != null ? R.string.HanakoChatExportFailedTitle : R.string.HanakoChatExportDoneTitle));
            String text = summary(error, p);
            b.setContentText(text.replace('\n', ' '));
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
            b.setAutoCancel(true);
            PendingIntent content = launchApp(ctx);
            Intent open = error == null ? openFolderIntent(exportDir) : null;
            if (open != null) {
                PendingIntent openPi = PendingIntent.getActivity(ctx, 7314, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(0, LocaleController.getString(R.string.HanakoChatExportOpenFolder), openPi);
                b.setContentIntent(openPi);
            } else if (content != null) {
                b.setContentIntent(content);
            }
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_DONE_ID, b.build());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void cancelDoneNotification() {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_DONE_ID);
        } catch (Throwable ignore) {
        }
    }

    /** Deletes a partial export folder (after Cancel → Delete). Off the UI thread. */
    public static void deleteExport(Uri exportDir, Runnable done) {
        if (exportDir == null) {
            if (done != null) AndroidUtilities.runOnUIThread(done);
            return;
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                DocumentsContract.deleteDocument(ApplicationLoader.applicationContext.getContentResolver(), exportDir);
            } catch (Throwable t) {
                FileLog.e(t);
            }
            if (done != null) AndroidUtilities.runOnUIThread(done);
        });
    }
}
