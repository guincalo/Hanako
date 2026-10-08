/*
 * plus f08: settings screen for per-chat lock and hidden chats.
 * Opened from MercurygramSettingsActivity behind a biometric unlock
 * (PlusChatLock.openSettings).
 */
package it.belloworld.mercurygram.ui;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.PasscodeActivity;

import java.util.ArrayList;

import it.belloworld.mercurygram.PlusChatLock;

public class PlusChatLockSettingsActivity extends UniversalFragment {

    private static final int ID_APP_PASSCODE = 1;
    private static final int ID_LOCK_ARCHIVE = 2;
    private static final int ID_LOCK_SECRET = 3;
    private static final int ID_HIDE_LOCKED = 4;
    private static final int ID_MASK_NOTIFICATIONS = 5;
    private static final int ID_ALLOW_DEVICE_CREDENTIAL = 6;
    private static final int ID_CHAT_OFFSET = 1000;

    private final ArrayList<Long> lockedShown = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PlusF08Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // Upstream Telegram already has a whole-app lock with fingerprint unlock; link to it.
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF08AppLockHeader)));
        items.add(UItem.asButton(ID_APP_PASSCODE, R.drawable.msg2_secret, LocaleController.getString(R.string.Passcode)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF08AppLockInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF08LockHeader)));
        if (!PlusChatLock.canAuthenticate()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF08NoBiometrics)));
        }
        items.add(UItem.asCheck(ID_LOCK_ARCHIVE, LocaleController.getString(R.string.PlusF08LockArchive))
                .setChecked(PlusChatLock.getBool(PlusChatLock.KEY_LOCK_ARCHIVE)));
        items.add(UItem.asCheck(ID_LOCK_SECRET, LocaleController.getString(R.string.PlusF08LockSecret))
                .setChecked(PlusChatLock.getBool(PlusChatLock.KEY_LOCK_SECRET)));
        items.add(UItem.asCheck(ID_HIDE_LOCKED, LocaleController.getString(R.string.PlusF08HideLocked))
                .setChecked(PlusChatLock.getBool(PlusChatLock.KEY_HIDE_LOCKED)));
        items.add(UItem.asCheck(ID_MASK_NOTIFICATIONS, LocaleController.getString(R.string.PlusF08MaskNotifications))
                .setChecked(PlusChatLock.getBool(PlusChatLock.KEY_MASK_NOTIFICATIONS)));
        items.add(UItem.asCheck(ID_ALLOW_DEVICE_CREDENTIAL, LocaleController.getString(R.string.PlusF08AllowDeviceCredential))
                .setChecked(PlusChatLock.getBool(PlusChatLock.KEY_ALLOW_DEVICE_CREDENTIAL)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF08LockInfo)));

        lockedShown.clear();
        lockedShown.addAll(PlusChatLock.getLocked(currentAccount));
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF08LockedChatsHeader)));
        if (lockedShown.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF08LockedChatsEmpty)));
        } else {
            for (int i = 0; i < lockedShown.size(); i++) {
                long did = lockedShown.get(i);
                items.add(UItem.asButton(ID_CHAT_OFFSET + i, R.drawable.msg_secret, PlusChatLock.dialogName(currentAccount, did),
                        LocaleController.getString(R.string.PlusF08UnlockChat)));
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF08LockedChatsInfo)));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_APP_PASSCODE:
                presentFragment(PasscodeActivity.determineOpenFragment());
                return;
            case ID_LOCK_ARCHIVE:
                toggle(PlusChatLock.KEY_LOCK_ARCHIVE, true);
                return;
            case ID_LOCK_SECRET:
                toggle(PlusChatLock.KEY_LOCK_SECRET, true);
                return;
            case ID_HIDE_LOCKED:
                toggle(PlusChatLock.KEY_HIDE_LOCKED, true);
                return;
            case ID_MASK_NOTIFICATIONS:
                toggle(PlusChatLock.KEY_MASK_NOTIFICATIONS, false);
                return;
            case ID_ALLOW_DEVICE_CREDENTIAL:
                toggle(PlusChatLock.KEY_ALLOW_DEVICE_CREDENTIAL, false);
                return;
        }
        int idx = item.id - ID_CHAT_OFFSET;
        if (idx >= 0 && idx < lockedShown.size()) {
            final long did = lockedShown.get(idx);
            AlertDialog.Builder b = new AlertDialog.Builder(getContext(), getResourceProvider());
            b.setTitle(PlusChatLock.dialogName(currentAccount, did));
            b.setMessage(LocaleController.getString(R.string.PlusF08UnlockConfirm));
            b.setPositiveButton(LocaleController.getString(R.string.PlusF08UnlockChat), (d, w) -> {
                PlusChatLock.setLocked(currentAccount, did, false);
                refreshList();
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(b.create());
        }
    }

    private void toggle(String key, boolean needsAuthToEnable) {
        boolean newValue = !PlusChatLock.getBool(key);
        if (newValue && needsAuthToEnable && !PlusChatLock.canAuthenticate()) {
            AlertDialog.Builder b = new AlertDialog.Builder(getContext(), getResourceProvider());
            b.setMessage(LocaleController.getString(R.string.PlusF08NoBiometrics));
            b.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(b.create());
            return;
        }
        PlusChatLock.setBool(key, newValue);
        refreshList();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
