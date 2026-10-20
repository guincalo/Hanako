package it.belloworld.mercurygram;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;

/**
 * plus f13: "Message shot". Renders the selected messages (real chat bubbles, names, avatars,
 * replies, reactions, current chat theme and wallpaper) into one PNG that can be shared or saved.
 *
 * The messages are laid out with fresh ChatMessageCell instances inside a preview bottom sheet, so
 * the cells are attached to a window and load their media/avatars exactly like in the chat.
 * Save/Share draws that preview view into a bitmap, so the image is what the preview shows.
 *
 * Purely local: the cells get copies of the MessageObjects with viewsReloaded=true, so binding
 * them does not queue a messages.getMessagesViews (view counter) request. Nothing else is sent.
 */
public final class PlusMessageShot {

    /** ChatActivity action-mode item id (plus f13 range 1300..1399). */
    public static final int MENU_ID = 1300;

    private static final String PREFS = "plus_f13";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BACKGROUND = "background";
    /** Upper bound for the output bitmap (about 96 MB ARGB); taller shots are scaled down. */
    private static final long MAX_PIXELS = 24_000_000L;

    private PlusMessageShot() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the "Message shot" button is shown in the chat selection bar. */
    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Whether the chat wallpaper is drawn behind the bubbles (otherwise the plain theme background). */
    public static boolean isBackgroundEnabled() {
        return prefs().getBoolean(KEY_BACKGROUND, true);
    }

    public static void setBackgroundEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_BACKGROUND, enabled).apply();
    }

    /**
     * Opens the preview sheet for the given (selected) messages. Call on the UI thread.
     * Service messages and date separators are skipped; album parts render as separate bubbles.
     */
    public static void show(ChatActivity fragment, ArrayList<MessageObject> selected) {
        HanakoTelemetry.count(HanakoTelemetry.MESSAGE_SHOT); // hanako: usage statistics (off by default)
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || selected == null) {
            return;
        }
        final ArrayList<MessageObject> messages = new ArrayList<>();
        for (MessageObject m : selected) {
            if (m != null && m.messageOwner != null && !m.isDateObject && m.contentType == 0) {
                messages.add(m);
            }
        }
        if (messages.isEmpty()) {
            return;
        }
        Collections.sort(messages, (a, b) -> {
            int c = Integer.compare(a.messageOwner.date, b.messageOwner.date);
            // secret chats use negative, decreasing ids; abs() keeps send order for both kinds
            return c != 0 ? c : Integer.compare(Math.abs(a.getId()), Math.abs(b.getId()));
        });

        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final int account = fragment.getCurrentAccount();

        // Chat-level cell flags (isChat, isMegagroup, isForum, ...) are copied from a cell the chat
        // already bound, so names/avatars follow the same rules as the chat itself.
        ChatMessageCell template = null;
        if (fragment.getChatListView() != null) {
            for (int i = 0; i < fragment.getChatListView().getChildCount(); i++) {
                View child = fragment.getChatListView().getChildAt(i);
                if (child instanceof ChatMessageCell) {
                    template = (ChatMessageCell) child;
                    break;
                }
            }
        }

        final Drawable wallpaper = currentWallpaper(fragment);
        final ShotLayout shot = new ShotLayout(activity, wallpaper, resourcesProvider);
        shot.drawWallpaper = isBackgroundEnabled();
        final int listWidth = fragment.getChatListView() != null ? fragment.getChatListView().getMeasuredWidth() : 0;

        for (int i = 0; i < messages.size(); i++) {
            MessageObject original = messages.get(i);
            boolean pinnedTop = i > 0 && sameRun(messages.get(i - 1), original);
            boolean pinnedBottom = i < messages.size() - 1 && sameRun(original, messages.get(i + 1));

            ChatMessageCell cell = new ChatMessageCell(activity, account, false, null, resourcesProvider);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return false;
                }
            });
            if (template != null) {
                template.copyParamsTo(cell);
            } else {
                cell.isChat = DialogObject.isChatDialog(fragment.getDialogId());
            }
            cell.isPinned = false;
            cell.plusShot = true; // names and titles follow streamer mode (f10)
            cell.setFullyDraw(true);
            cell.setMessageObject(copyOf(account, original), null, pinnedBottom, pinnedTop, false);
            shot.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.PlusMessageShot), true);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.6f);
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        if (listWidth > 0 && listWidth < AndroidUtilities.displaySize.x) {
            // tablets/side panes: keep the chat column width so bubbles wrap like in the chat
            scroll.addView(shot, new ScrollView.LayoutParams(listWidth, ScrollView.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL));
        } else {
            scroll.addView(shot, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell backgroundCheck = new TextCheckCell(activity, resourcesProvider);
        backgroundCheck.setTextAndCheck(LocaleController.getString(R.string.PlusMessageShotBackground), shot.drawWallpaper, false);
        backgroundCheck.setOnClickListener(v -> {
            boolean value = !backgroundCheck.isChecked();
            backgroundCheck.setChecked(value);
            setBackgroundEnabled(value);
            shot.drawWallpaper = value;
            shot.invalidate();
        });
        root.addView(backgroundCheck, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        ButtonWithCounterView saveButton = new ButtonWithCounterView(activity, false, resourcesProvider);
        saveButton.setText(LocaleController.getString(R.string.PlusMessageShotSave), false);
        ButtonWithCounterView shareButton = new ButtonWithCounterView(activity, resourcesProvider);
        shareButton.setText(LocaleController.getString(R.string.PlusMessageShotShare), false);
        buttons.addView(saveButton, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 5, 0));
        buttons.addView(shareButton, LayoutHelper.createLinear(0, 48, 1f, 5, 0, 0, 0));
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 14f, 8f, 14f, 10f));

        builder.setCustomView(root);
        BottomSheet sheet = builder.create();

        saveButton.setOnClickListener(v -> {
            if (needsStoragePermission(activity)) {
                return;
            }
            export(shot, file -> {
                sheet.dismiss();
                MediaController.saveFile(file.getAbsolutePath(), activity, 0, null, null, uri -> {
                    if (fragment.getParentActivity() != null) {
                        BulletinFactory.createSaveToGalleryBulletin(fragment, false, resourcesProvider).show();
                    }
                });
            });
        });
        shareButton.setOnClickListener(v -> export(shot, file -> {
            sheet.dismiss();
            share(activity, file);
        }));

        fragment.showDialog(sheet);
    }

    /** True (and asks for it) when Android 6-9 still needs the storage permission to save to the gallery. */
    private static boolean needsStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT < 29
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4); // BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE
            return true;
        }
        return false;
    }

    private static Drawable currentWallpaper(ChatActivity fragment) {
        Drawable drawable = null;
        SizeNotifierFrameLayout content = fragment.getContentView();
        if (content != null) {
            drawable = content.getBackgroundImage();
        }
        if (drawable == null) {
            drawable = Theme.getCachedWallpaperNonBlocking();
        }
        return drawable;
    }

    /**
     * Same grouping rule as the chat (simplified): consecutive messages from one sender, same
     * direction, at most 5 minutes apart, and no inline keyboard in between.
     */
    private static boolean sameRun(MessageObject upper, MessageObject lower) {
        if (upper.isOutOwner() != lower.isOutOwner()) {
            return false;
        }
        if (upper.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            return false;
        }
        if (Math.abs(lower.messageOwner.date - upper.messageOwner.date) > 5 * 60) {
            return false;
        }
        return upper.getFromChatId() == lower.getFromChatId();
    }

    /**
     * A private MessageObject for the preview cell, so binding/layout in the sheet cannot disturb
     * the chat's own objects (selection, animations, text layout). viewsReloaded = true keeps
     * ChatMessageCell from queueing a getMessagesViews request (it would bump channel views).
     */
    private static MessageObject copyOf(int account, MessageObject original) {
        MessageObject copy;
        try {
            copy = new MessageObject(account, original.messageOwner, original.replyMessageObject, true, true);
        } catch (Exception e) {
            FileLog.e(e);
            copy = original;
        }
        copy.viewsReloaded = true;
        if (copy != original) {
            copy.deleted = original.deleted;
            copy.forceAvatar = original.forceAvatar;
        }
        return copy;
    }

    private static void export(ShotLayout shot, Utilities.Callback<File> onReady) {
        final Bitmap bitmap = render(shot);
        if (bitmap == null) {
            Toast.makeText(shot.getContext(), LocaleController.getString(R.string.PlusMessageShotFailed), Toast.LENGTH_SHORT).show();
            return;
        }
        final Context context = shot.getContext();
        Utilities.globalQueue.postRunnable(() -> {
            File file = null;
            try {
                File dir = new File(AndroidUtilities.getCacheDir(), "message_shots");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                file = new File(dir, "message_shot_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } catch (Exception e) {
                FileLog.e(e);
                file = null;
            } finally {
                bitmap.recycle();
            }
            final File result = file;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) {
                    Toast.makeText(context, LocaleController.getString(R.string.PlusMessageShotFailed), Toast.LENGTH_SHORT).show();
                } else {
                    onReady.run(result);
                }
            });
        });
    }

    private static Bitmap render(View view) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        float scale = 1f;
        long pixels = (long) w * h;
        if (pixels > MAX_PIXELS) {
            scale = (float) Math.sqrt((double) MAX_PIXELS / pixels);
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            view.draw(canvas);
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void share(Activity activity, File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file); // minSdk 24
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            activity.startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.PlusMessageShotShare)));
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(activity, LocaleController.getString(R.string.PlusMessageShotFailed), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Vertical stack of message cells over the chat background. Avatars are not drawn by
     * ChatMessageCell itself (the chat list draws them), so this layout draws them after the
     * cells, at the bottom of each sender run, the same way ChatActivity does.
     */
    private static final class ShotLayout extends LinearLayout {

        private final Drawable wallpaper;
        private final Theme.ResourcesProvider resourcesProvider;
        boolean drawWallpaper = true;

        ShotLayout(Context context, Drawable wallpaper, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.wallpaper = wallpaper;
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return true; // preview only: no taps into links, media or buttons
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawWallpaper && wallpaper != null) {
                // drawBackgroundDrawable restores bounds and callback: the drawable is shared with the chat
                StoryEntry.drawBackgroundDrawable(canvas, wallpaper, getWidth(), getHeight());
            } else {
                canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child instanceof ChatMessageCell) {
                    ((ChatMessageCell) child).setParentViewSize(getMeasuredWidth(), getMeasuredHeight());
                }
            }
            super.dispatchDraw(canvas);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!(child instanceof ChatMessageCell)) {
                    continue;
                }
                ChatMessageCell cell = (ChatMessageCell) child;
                ImageReceiver avatar = cell.getAvatarImage();
                if (avatar == null || !cell.isAvatarVisible || cell.drawPinnedBottom()) {
                    continue;
                }
                float y = cell.getTop() + cell.getPaddingTop() + cell.getLayoutHeight() - AndroidUtilities.dp(44);
                canvas.save();
                canvas.translate(cell.getLeft(), 0);
                avatar.setImageY(y);
                avatar.setVisible(true, false);
                avatar.draw(canvas);
                canvas.restore();
            }
        }
    }
}
