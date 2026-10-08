package it.belloworld.mercurygram;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;

import java.util.concurrent.atomic.AtomicReference;

/**
 * plus f19: upload / download speed boost.
 *
 * Three levels per direction, global (all accounts), default NORMAL = stock behaviour:
 *
 * Downloads (FileLoadOperation, files >= 10 MB, stories, and the small-file path):
 *   NORMAL  : stock (big 128 KB x 4 requests, small 32 KB x 4; 512 KB x 8 when the server's
 *             experimental file params or a preload prefix are on).
 *   FAST    : big 512 KB x 8, small 128 KB x 6   (= Telegram's own "experimental" params).
 *   EXTREME : big 512 KB x 16, small 128 KB x 8.
 * Chunk sizes stay at 512 KB at most: MTProto upload.getFile rejects a part that crosses a
 * 1 MB boundary, and resumed/streamed downloads restart at offsets aligned to older, smaller
 * chunks, so 1 MB parts would hit LIMIT_INVALID and drop the file to the 32 KB fallback.
 * Streaming (isStream) keeps its stock 128 KB x 4 so seeking stays snappy.
 *
 * Uploads (FileUploadOperation):
 *   NORMAL  : stock (part >= 128 KB, at most 2 MB in flight).
 *   FAST    : part >= 256 KB, at most 4 MB in flight.
 *   EXTREME : part = 512 KB (protocol maximum), at most 8 MB in flight.
 * Never applied on a slow network (upstream's 2G/slow mode stays as is). A boosted upload uses
 * a different resume key, so a half-finished upload is never resumed with another part size.
 *
 * Ghost-safe: only changes the size / count of upload.getFile / upload.saveFilePart requests
 * that would be sent anyway; no extra requests, nothing that touches online or read state.
 */
public final class PlusTransferBoost {

    public static final int MODE_NORMAL = 0;
    public static final int MODE_FAST = 1;
    public static final int MODE_EXTREME = 2;

    private static final String PREFS = "plus_f19";
    private static final String KEY_DOWNLOAD = "download_mode";
    private static final String KEY_UPLOAD = "upload_mode";

    private static volatile int downloadMode = -1;
    private static volatile int uploadMode = -1;

    private PlusTransferBoost() {
    }

    // ---------------------------------------------------------------- settings

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clamp(int mode) {
        return mode < MODE_NORMAL || mode > MODE_EXTREME ? MODE_NORMAL : mode;
    }

    public static int getDownloadMode() {
        int m = downloadMode;
        if (m < 0) {
            try {
                m = clamp(prefs().getInt(KEY_DOWNLOAD, MODE_NORMAL));
            } catch (Throwable e) {
                return MODE_NORMAL;
            }
            downloadMode = m;
        }
        return m;
    }

    public static int getUploadMode() {
        int m = uploadMode;
        if (m < 0) {
            try {
                m = clamp(prefs().getInt(KEY_UPLOAD, MODE_NORMAL));
            } catch (Throwable e) {
                return MODE_NORMAL;
            }
            uploadMode = m;
        }
        return m;
    }

    public static void setDownloadMode(int mode) {
        mode = clamp(mode);
        downloadMode = mode;
        prefs().edit().putInt(KEY_DOWNLOAD, mode).apply();
    }

    public static void setUploadMode(int mode) {
        mode = clamp(mode);
        uploadMode = mode;
        prefs().edit().putInt(KEY_UPLOAD, mode).apply();
    }

    // ---------------------------------------------------------------- downloads

    /** True when FileLoadOperation should replace its stock chunk / request numbers. */
    public static boolean downloadBoosted() {
        return getDownloadMode() != MODE_NORMAL;
    }

    /** Part size for files >= 10 MB and stories. Must divide 1 MB and be a multiple of 4 KB. */
    public static int downloadChunkSizeBig() {
        return 512 * 1024;
    }

    /** Part size for files under 10 MB. Must divide 1 MB and be a multiple of 4 KB. */
    public static int downloadChunkSizeSmall() {
        return 128 * 1024;
    }

    public static int maxDownloadRequestsBig() {
        return getDownloadMode() == MODE_EXTREME ? 16 : 8;
    }

    public static int maxDownloadRequestsSmall() {
        return getDownloadMode() == MODE_EXTREME ? 8 : 6;
    }

    // ---------------------------------------------------------------- uploads

    /** True when FileUploadOperation should raise its part size / in-flight budget. */
    public static boolean uploadBoosted(boolean slowNetwork) {
        return !slowNetwork && getUploadMode() != MODE_NORMAL;
    }

    /**
     * Upload part size in KB. {@code stockKb} is what upstream computed (a power of two, at least
     * 128 unless on a slow network). Never shrinks it; the result is a power of two that divides
     * 512, i.e. a legal upload.saveFilePart / saveBigFilePart size.
     */
    public static int uploadChunkKb(int stockKb, boolean slowNetwork) {
        if (!uploadBoosted(slowNetwork)) {
            return stockKb;
        }
        int min = getUploadMode() == MODE_EXTREME ? 512 : 256;
        return Math.max(stockKb, min);
    }

    /** KB allowed in flight at once; upstream uses 2048 (32 on a slow network). */
    public static int uploadInFlightKb(int stockKb, boolean slowNetwork) {
        if (!uploadBoosted(slowNetwork)) {
            return stockKb;
        }
        return Math.max(stockKb, getUploadMode() == MODE_EXTREME ? 8192 : 4096);
    }

    /**
     * Appended to the upload resume key. Empty for stock uploads (so their resume data is
     * untouched); otherwise per mode, because resuming an upload with a different part size
     * would number parts differently and corrupt the file on the server.
     */
    public static String uploadKeySuffix(boolean slowNetwork) {
        return uploadBoosted(slowNetwork) ? "_plusf19m" + getUploadMode() : "";
    }

    // ---------------------------------------------------------------- UI

    public static String modeLabel(int mode) {
        switch (mode) {
            case MODE_FAST:
                return LocaleController.getString(R.string.PlusF19ModeFast);
            case MODE_EXTREME:
                return LocaleController.getString(R.string.PlusF19ModeExtreme);
            default:
                return LocaleController.getString(R.string.PlusF19ModeNormal);
        }
    }

    /** Radio picker for one direction; {@code onChanged} runs after a new value is saved. */
    public static void showModePicker(BaseFragment fragment, boolean upload, Runnable onChanged) {
        if (fragment == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int current = upload ? getUploadMode() : getDownloadMode();
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        for (int mode = MODE_NORMAL; mode <= MODE_EXTREME; mode++) {
            final int chosen = mode;
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(modeLabel(mode), mode == current);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                if (upload) {
                    setUploadMode(chosen);
                } else {
                    setDownloadMode(chosen);
                }
                Dialog d = dialogRef.get();
                if (d != null) {
                    d.dismiss();
                }
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(upload ? R.string.PlusF19UploadBoost : R.string.PlusF19DownloadBoost))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        fragment.showDialog(dialog);
    }
}
