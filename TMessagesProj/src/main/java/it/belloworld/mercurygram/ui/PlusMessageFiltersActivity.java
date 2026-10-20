package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

import it.belloworld.mercurygram.PlusMessageFilters;

/** plus f04: settings screen for regex filters and hidden ("shadow-banned") users. */
public class PlusMessageFiltersActivity extends UniversalFragment {

    private static final int ID_ENABLED = 1;
    private static final int ID_HIDE_BLOCKED = 2;
    private static final int ID_USERS_COLLAPSE = 3;
    private static final int ID_ADD_FILTER = 4;
    private static final int ID_ADD_USER = 5;
    private static final int ID_FILTER_BASE = 1000;
    private static final int ID_USER_BASE = 100000;

    private ArrayList<PlusMessageFilters.Filter> shownFilters = new ArrayList<>();
    private ArrayList<PlusMessageFilters.BannedUser> shownUsers = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PlusF04Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean on = PlusMessageFilters.isEnabled();
        items.add(UItem.asCheck(ID_ENABLED, LocaleController.getString(R.string.PlusF04Enable)).setChecked(on));
        items.add(UItem.asCheck(ID_HIDE_BLOCKED, LocaleController.getString(R.string.PlusF04HideBlocked))
                .setChecked(PlusMessageFilters.isHideBlocked()).setEnabled(on));
        items.add(UItem.asCheck(ID_USERS_COLLAPSE, LocaleController.getString(R.string.PlusF04UsersCollapse))
                .setChecked(PlusMessageFilters.isUsersCollapse()).setEnabled(on));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(LocaleController.getString(R.string.PlusF04EnableInfo))));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF04RegexHeader)));
        shownFilters = PlusMessageFilters.getFilters();
        for (int i = 0; i < shownFilters.size(); i++) {
            PlusMessageFilters.Filter f = shownFilters.get(i);
            items.add(UItem.asButton(ID_FILTER_BASE + i, f.pattern, describeFilter(f)).setEnabled(on));
        }
        items.add(UItem.asButton(ID_ADD_FILTER, R.drawable.msg_add, LocaleController.getString(R.string.PlusF04AddFilter)).setEnabled(on));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF04RegexInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF04UsersHeader)));
        shownUsers = PlusMessageFilters.getBanned();
        for (int i = 0; i < shownUsers.size(); i++) {
            PlusMessageFilters.BannedUser b = shownUsers.get(i);
            items.add(UItem.asButton(ID_USER_BASE + i, peerName(b.userId), scopeName(b.dialogId)).setEnabled(on));
        }
        items.add(UItem.asButton(ID_ADD_USER, R.drawable.msg_add, LocaleController.getString(R.string.PlusF04AddUser)).setEnabled(on));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF04UsersInfo)));
    }

    private static String peerName(long id) {
        String name = DialogObject.getName(UserConfig.selectedAccount, id);
        return TextUtils.isEmpty(name) ? String.valueOf(id) : name;
    }

    private static String scopeName(long dialogId) {
        if (dialogId == 0) {
            return LocaleController.getString(R.string.PlusF04ScopeAll);
        }
        return peerName(dialogId);
    }

    private static String describeFilter(PlusMessageFilters.Filter f) {
        StringBuilder sb = new StringBuilder();
        if (!f.enabled) {
            sb.append(LocaleController.getString(R.string.PlusF04Off)).append(" · ");
        }
        sb.append(LocaleController.getString(f.collapse ? R.string.PlusF04ModeCollapse : R.string.PlusF04ModeHide));
        sb.append(" · ").append(scopeName(f.dialogId));
        return sb.toString();
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENABLED) {
            PlusMessageFilters.setEnabled(!PlusMessageFilters.isEnabled());
        } else if (item.id == ID_HIDE_BLOCKED) {
            PlusMessageFilters.setHideBlocked(!PlusMessageFilters.isHideBlocked());
        } else if (item.id == ID_USERS_COLLAPSE) {
            PlusMessageFilters.setUsersCollapse(!PlusMessageFilters.isUsersCollapse());
        } else if (item.id == ID_ADD_FILTER) {
            showEditDialog(this, null, 0, this::refreshList);
            return;
        } else if (item.id == ID_ADD_USER) {
            showAddUserDialog();
            return;
        } else if (item.id >= ID_USER_BASE) {
            int index = item.id - ID_USER_BASE;
            if (index < shownUsers.size()) {
                confirmRemoveUser(shownUsers.get(index));
            }
            return;
        } else if (item.id >= ID_FILTER_BASE) {
            int index = item.id - ID_FILTER_BASE;
            if (index < shownFilters.size()) {
                showEditDialog(this, shownFilters.get(index), 0, this::refreshList);
            }
            return;
        } else {
            return;
        }
        refreshList();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_USER_BASE) {
            int index = item.id - ID_USER_BASE;
            if (index < shownUsers.size()) {
                confirmRemoveUser(shownUsers.get(index));
                return true;
            }
        } else if (item.id >= ID_FILTER_BASE) {
            int index = item.id - ID_FILTER_BASE;
            if (index < shownFilters.size()) {
                PlusMessageFilters.Filter f = shownFilters.get(index);
                confirm(LocaleController.getString(R.string.PlusF04DeleteFilterConfirm), () -> {
                    PlusMessageFilters.removeFilter(f.id);
                    refreshList();
                });
                return true;
            }
        }
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

    private void confirmRemoveUser(PlusMessageFilters.BannedUser b) {
        confirm(LocaleController.formatString(R.string.PlusF04RemoveUserConfirm, peerName(b.userId)), () -> {
            PlusMessageFilters.removeBanned(b.userId, b.dialogId);
            refreshList();
        });
    }

    private void confirm(CharSequence message, Runnable onYes) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.PlusF04Title))
                .setMessage(message)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> onYes.run())
                .create();
        showDialog(dialog);
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void showAddUserDialog() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        EditTextBoldCursor idField = createField(activity, LocaleController.getString(R.string.PlusF04UserIdHint), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        layout.addView(idField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.FILL_HORIZONTAL, 0, 8, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.PlusF04AddUser))
                .setView(layout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Save), null)
                .create();
        dialog.setOnShowListener(d -> {
            idField.requestFocus();
            AndroidUtilities.showKeyboard(idField);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                long id = parseLong(idField.getText().toString());
                if (id == 0) {
                    AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.PlusF04InvalidId));
                    return;
                }
                PlusMessageFilters.addBanned(id, 0);
                dialog.dismiss();
                refreshList();
            });
        });
        showDialog(dialog);
    }

    private static long parseLong(String s) {
        if (s == null) {
            return 0;
        }
        s = s.trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static EditTextBoldCursor createField(Context context, CharSequence hint, int inputType) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint(hint);
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setInputType(inputType);
        editText.setSingleLine(true);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        return editText;
    }

    private static TextCheckCell createCheck(Context context, CharSequence text, boolean checked) {
        TextCheckCell cell = new TextCheckCell(context);
        cell.setTextAndCheck(text, checked, false);
        cell.setOnClickListener(v -> cell.setChecked(!cell.isChecked()));
        return cell;
    }

    /**
     * Add/edit dialog for one regex filter. {@code existing == null} adds a new filter scoped to
     * {@code presetDialogId} (0 = all chats). Also used from the chat long-press menu.
     */
    public static void showEditDialog(BaseFragment fragment, PlusMessageFilters.Filter existing, long presetDialogId, Runnable onSaved) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final PlusMessageFilters.Filter filter = existing != null ? existing.copy() : new PlusMessageFilters.Filter();
        if (existing == null) {
            filter.dialogId = presetDialogId;
        }

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor patternField = createField(activity, LocaleController.getString(R.string.PlusF04PatternHint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        patternField.setText(filter.pattern);
        layout.addView(patternField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.FILL_HORIZONTAL, 24, 8, 24, 0));

        EditTextBoldCursor chatField = createField(activity, LocaleController.getString(R.string.PlusF04ChatIdHint), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (filter.dialogId != 0) {
            chatField.setText(String.valueOf(filter.dialogId));
        }
        layout.addView(chatField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.FILL_HORIZONTAL, 24, 12, 24, 0));
        if (filter.dialogId != 0) {
            TextView scopeView = new TextView(activity);
            scopeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            scopeView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            scopeView.setText(scopeName(filter.dialogId));
            layout.addView(scopeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));
        }

        TextCheckCell enabledCell = createCheck(activity, LocaleController.getString(R.string.PlusF04FilterEnabled), filter.enabled);
        TextCheckCell caseCell = createCheck(activity, LocaleController.getString(R.string.PlusF04CaseInsensitive), filter.caseInsensitive);
        TextCheckCell collapseCell = createCheck(activity, LocaleController.getString(R.string.PlusF04CollapseInstead), filter.collapse);
        layout.addView(enabledCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 8, 0, 0));
        layout.addView(caseCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        layout.addView(collapseCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(existing != null ? R.string.PlusF04EditFilter : R.string.PlusF04AddFilter))
                .setView(layout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Save), null);
        if (existing != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                PlusMessageFilters.removeFilter(existing.id);
                if (onSaved != null) {
                    onSaved.run();
                }
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            patternField.requestFocus();
            AndroidUtilities.showKeyboard(patternField);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String pattern = patternField.getText().toString();
                String error = PlusMessageFilters.validatePattern(pattern);
                if (error != null) {
                    AlertsCreator.showSimpleAlert(fragment, LocaleController.formatString(R.string.PlusF04InvalidPattern, error));
                    return;
                }
                String chatText = chatField.getText().toString().trim();
                long dialogId = 0;
                if (!chatText.isEmpty()) {
                    dialogId = parseLong(chatText);
                    if (dialogId == 0) {
                        AlertsCreator.showSimpleAlert(fragment, LocaleController.getString(R.string.PlusF04InvalidId));
                        return;
                    }
                }
                filter.pattern = pattern;
                filter.dialogId = dialogId;
                filter.enabled = enabledCell.isChecked();
                filter.caseInsensitive = caseCell.isChecked();
                filter.collapse = collapseCell.isChecked();
                PlusMessageFilters.putFilter(filter);
                dialog.dismiss();
                if (onSaved != null) {
                    onSaved.run();
                }
            });
        });
        fragment.showDialog(dialog);
        if (existing != null) {
            View neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (neutral instanceof TextView) {
                ((TextView) neutral).setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
        }
    }
}
