package it.belloworld.mercurygram;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.SparseArray;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

/**
 * plus f18: invisible trampoline that runs an OpenPGP provider PendingIntent
 * (permission grant, key picker, passphrase) with startIntentSenderForResult
 * and hands the returned Intent back to PlusOpenPgp, so no upstream
 * onActivityResult needs a hook. Callback gets null on cancel or failure.
 */
public class PlusOpenPgpProxyActivity extends Activity {

    private static final String EXTRA_PENDING = "plus_f18_pending";
    private static final String EXTRA_TOKEN = "plus_f18_token";
    private static final int REQUEST = 1820; // f18 range

    // Main thread only.
    private static final SparseArray<Utilities.Callback<Intent>> callbacks = new SparseArray<>();
    private static int nextToken = 1;

    static void launch(Activity from, PendingIntent pending, Utilities.Callback<Intent> callback) {
        int token = nextToken++;
        callbacks.put(token, callback);
        try {
            Intent i = new Intent(from, PlusOpenPgpProxyActivity.class)
                    .putExtra(EXTRA_PENDING, pending)
                    .putExtra(EXTRA_TOKEN, token);
            from.startActivity(i);
            from.overridePendingTransition(0, 0);
        } catch (Throwable t) {
            FileLog.e(t);
            deliver(token, null);
        }
    }

    private static void deliver(int token, Intent data) {
        Utilities.Callback<Intent> cb = callbacks.get(token);
        callbacks.remove(token);
        if (cb != null) {
            try {
                cb.run(data);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    private boolean delivered;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            return; // recreated while the provider screen is up: the result still arrives here
        }
        PendingIntent pending;
        try {
            pending = getIntent().getParcelableExtra(EXTRA_PENDING);
        } catch (Throwable t) {
            pending = null;
        }
        if (pending == null) {
            finishWith(null);
            return;
        }
        Bundle options = null;
        if (Build.VERSION.SDK_INT >= 34) {
            // The provider's PendingIntent opens its own activity: grant our
            // (foreground) activity-start privilege to it.
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            options = o.toBundle();
        }
        try {
            startIntentSenderForResult(pending.getIntentSender(), REQUEST, null, 0, 0, 0, options);
        } catch (Throwable t) {
            FileLog.e(t);
            finishWith(null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST) {
            finishWith(resultCode == RESULT_OK ? data : null);
        }
    }

    private void finishWith(Intent data) {
        if (!delivered) {
            delivered = true;
            deliver(getIntent().getIntExtra(EXTRA_TOKEN, 0), data);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && !delivered) {
            delivered = true;
            deliver(getIntent().getIntExtra(EXTRA_TOKEN, 0), null);
        }
        super.onDestroy();
    }
}
