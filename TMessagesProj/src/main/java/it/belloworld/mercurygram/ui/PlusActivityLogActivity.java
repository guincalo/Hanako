package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import it.belloworld.mercurygram.PlusActivityLog;

/** plus f05: activity log of one user, opened from the profile menu. */
public class PlusActivityLogActivity extends UniversalFragment {

    private static final int ID_TRACK = 1;
    private static final int ID_CLEAR = 2;
    private static final int ID_ENTRY = 3;
    private static final int LIMIT = 1000;

    private final long userId;
    private ArrayList<PlusActivityLog.Entry> entries;

    public PlusActivityLogActivity(long userId) {
        super();
        this.userId = userId;
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        TLRPC.User user = getMessagesController().getUser(userId);
        if (user != null) {
            actionBar.setSubtitle(UserObject.getUserName(user));
        }
        reload();
        return v;
    }

    private void reload() {
        PlusActivityLog.load(currentAccount, userId, LIMIT, result -> {
            entries = result;
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PlusF05ActivityLog);
    }

    private static String scopeName(int scope) {
        switch (scope) {
            case PlusActivityLog.SCOPE_ALL:
                return LocaleController.getString(R.string.PlusF05ScopeAll);
            case PlusActivityLog.SCOPE_CONTACTS:
                return LocaleController.getString(R.string.PlusF05ScopeContacts);
            default:
                return LocaleController.getString(R.string.PlusF05ScopeSelected);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = PlusActivityLog.isEnabled(currentAccount);
        if (enabled) {
            items.add(UItem.asCheck(ID_TRACK, LocaleController.getString(R.string.PlusF05LogThisUser))
                    .setChecked(PlusActivityLog.isTracked(currentAccount, userId)));
            items.add(UItem.asShadow(LocaleController.formatString(R.string.PlusF05LogThisUserAbout,
                    scopeName(PlusActivityLog.getScope(currentAccount)))));
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF05DisabledAbout)));
        }

        if (entries == null) {
            return;
        }
        if (entries.isEmpty() || it.belloworld.mercurygram.PlusChatLock.isHiddenFromLists(currentAccount, userId)) { // plus f08: hidden locked chat shows no log
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF05Empty)));
            return;
        }

        SimpleDateFormat dayFormat = new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        SimpleDateFormat seenFormat = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
        String lastDay = null;
        for (int i = 0; i < entries.size(); i++) {
            PlusActivityLog.Entry e = entries.get(i);
            Date date = new Date(e.date * 1000L);
            String day = dayFormat.format(date);
            if (!day.equals(lastDay)) {
                if (lastDay != null) {
                    items.add(UItem.asShadow(null));
                }
                items.add(UItem.asHeader(day));
                lastDay = day;
            }
            int icon;
            String text;
            switch (e.type) {
                case PlusActivityLog.TYPE_ONLINE:
                    icon = R.drawable.msg_online;
                    text = LocaleController.getString(R.string.PlusF05Online);
                    break;
                case PlusActivityLog.TYPE_OFFLINE:
                    icon = R.drawable.msg_recent;
                    if (e.extra > 0 && e.extra != e.date) {
                        text = LocaleController.formatString(R.string.PlusF05OfflineAt, seenFormat.format(new Date(e.extra * 1000L)));
                    } else {
                        text = LocaleController.getString(R.string.PlusF05Offline);
                    }
                    break;
                case PlusActivityLog.TYPE_READ:
                    icon = R.drawable.msg_seen;
                    text = LocaleController.getString(e.approx ? R.string.PlusF05ReadApprox : R.string.PlusF05Read);
                    break;
                default:
                    icon = R.drawable.msg_log;
                    if (e.extra == -100) {
                        text = LocaleController.getString(R.string.PlusF05Recently);
                    } else if (e.extra == -101) {
                        text = LocaleController.getString(R.string.PlusF05LastWeek);
                    } else if (e.extra == -102) {
                        text = LocaleController.getString(R.string.PlusF05LastMonth);
                    } else {
                        text = LocaleController.getString(R.string.PlusF05Hidden);
                    }
                    break;
            }
            items.add(UItem.asButton(ID_ENTRY, icon, text, timeFormat.format(date)));
        }
        items.add(UItem.asShadow(entries.size() >= LIMIT
                ? LocaleController.formatString(R.string.PlusF05ShowingLatest, LIMIT) : null));
        items.add(UItem.asButton(ID_CLEAR, R.drawable.msg_delete, LocaleController.getString(R.string.PlusF05Clear)).red());
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_TRACK) {
            boolean now = PlusActivityLog.isTracked(currentAccount, userId);
            // store an explicit override only when it differs from what the scope would do
            PlusActivityLog.setUserOverride(currentAccount, userId, 0);
            boolean byScope = PlusActivityLog.isTracked(currentAccount, userId);
            boolean wanted = !now;
            PlusActivityLog.setUserOverride(currentAccount, userId, wanted == byScope ? 0 : (wanted ? 1 : -1));
            listView.adapter.update(true);
        } else if (item.id == ID_CLEAR) {
            Context context = getParentActivity();
            if (context == null) {
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle(LocaleController.getString(R.string.PlusF05Clear))
                    .setMessage(LocaleController.getString(R.string.PlusF05ClearConfirm))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) ->
                            PlusActivityLog.clearUser(currentAccount, userId, this::reload))
                    .show();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // ---------------------------------------------------------------- settings rows (MercurygramSettingsActivity)

    public static final int SETTINGS_ID_ENABLED = 7050;
    public static final int SETTINGS_ID_SCOPE = 7051;
    public static final int SETTINGS_ID_RETENTION = 7052;
    public static final int SETTINGS_ID_CLEAR = 7053;

    public static void fillSettings(ArrayList<UItem> items, int account) {
        final boolean on = PlusActivityLog.isEnabled(account);
        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF05ActivityLog)));
        items.add(UItem.asCheck(SETTINGS_ID_ENABLED, LocaleController.getString(R.string.PlusF05Record)).setChecked(on));
        if (on) {
            items.add(UItem.asButton(SETTINGS_ID_SCOPE, LocaleController.getString(R.string.PlusF05Scope), scopeName(PlusActivityLog.getScope(account))));
        }
        items.add(UItem.asButton(SETTINGS_ID_RETENTION, LocaleController.getString(R.string.PlusF05Retention),
                LocaleController.formatString(R.string.PlusF05RetentionDays, PlusActivityLog.getRetentionDays())));
        items.add(UItem.asButton(SETTINGS_ID_CLEAR, LocaleController.getString(R.string.PlusF05ClearAll)).red());
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF05SettingsAbout)));
    }

    /** Returns true when the row belongs to this feature. */
    public static boolean onSettingsClick(org.telegram.ui.ActionBar.BaseFragment fragment, UItem item, Runnable refresh) {
        final int account = fragment.getCurrentAccount();
        final Context context = fragment.getParentActivity();
        switch (item.id) {
            case SETTINGS_ID_ENABLED:
                PlusActivityLog.setEnabled(account, !PlusActivityLog.isEnabled(account));
                refresh.run();
                return true;
            case SETTINGS_ID_SCOPE: {
                if (context == null) {
                    return true;
                }
                CharSequence[] names = {scopeName(PlusActivityLog.SCOPE_ALL), scopeName(PlusActivityLog.SCOPE_CONTACTS), scopeName(PlusActivityLog.SCOPE_SELECTED)};
                new AlertDialog.Builder(context)
                        .setTitle(LocaleController.getString(R.string.PlusF05Scope))
                        .setItems(names, (dialog, which) -> {
                            PlusActivityLog.setScope(account, which);
                            refresh.run();
                        })
                        .show();
                return true;
            }
            case SETTINGS_ID_RETENTION: {
                if (context == null) {
                    return true;
                }
                CharSequence[] names = new CharSequence[PlusActivityLog.RETENTION_DAYS.length];
                for (int i = 0; i < names.length; i++) {
                    names[i] = LocaleController.formatString(R.string.PlusF05RetentionDays, PlusActivityLog.RETENTION_DAYS[i]);
                }
                new AlertDialog.Builder(context)
                        .setTitle(LocaleController.getString(R.string.PlusF05Retention))
                        .setItems(names, (dialog, which) -> {
                            PlusActivityLog.setRetentionDays(PlusActivityLog.RETENTION_DAYS[which]);
                            refresh.run();
                        })
                        .show();
                return true;
            }
            case SETTINGS_ID_CLEAR: {
                if (context == null) {
                    return true;
                }
                new AlertDialog.Builder(context)
                        .setTitle(LocaleController.getString(R.string.PlusF05ClearAll))
                        .setMessage(LocaleController.getString(R.string.PlusF05ClearAllConfirm))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> PlusActivityLog.clearAll(null))
                        .show();
                return true;
            }
        }
        return false;
    }
}
