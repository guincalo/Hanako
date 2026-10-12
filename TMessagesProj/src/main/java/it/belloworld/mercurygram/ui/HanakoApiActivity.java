/*
 * hanako: Settings > Hanako > App API ID, also opened from the login screen ("API ID: ...") and
 * by long-pressing "Start Messaging" on the intro, so it works before any account signs in.
 * Picks the api_id / api_hash pair the client connects as (see HanakoApiConfig). Applies at the
 * next start; saving a different pair offers a restart.
 */
package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

import it.belloworld.mercurygram.HanakoApiConfig;
import it.belloworld.mercurygram.HanakoBackup;

public class HanakoApiActivity extends UniversalFragment {

    private static final int ID_PRESET = 100; // + HanakoApiConfig.PRESET_*
    private static final int ID_RESTART = 200;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HanakoApiTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        int saved = HanakoApiConfig.getSavedPreset();
        items.add(UItem.asHeader(LocaleController.getString(R.string.HanakoApiHeader)));
        for (int preset : HanakoApiConfig.UI_ORDER) {
            addPreset(items, preset, presetName(preset), saved);
        }

        StringBuilder info = new StringBuilder();
        info.append(LocaleController.formatString(R.string.HanakoApiActive, HanakoApiConfig.getActiveId()));
        if (HanakoApiConfig.restartPending()) {
            info.append('\n').append(LocaleController.formatString(R.string.HanakoApiPending, HanakoApiConfig.getSavedId()));
        }
        info.append("\n\n").append(LocaleController.getString(R.string.HanakoApiInfo));
        items.add(UItem.asShadow(info.toString()));

        if (HanakoApiConfig.restartPending()) {
            items.add(UItem.asButton(ID_RESTART, LocaleController.getString(R.string.HanakoBackupRestartNow)));
            items.add(UItem.asShadow(null));
        }
    }

    private static int presetName(int preset) {
        switch (preset) {
            case HanakoApiConfig.PRESET_GOREGRAM: return R.string.HanakoApiPresetGoregram;
            case HanakoApiConfig.PRESET_MERCURYGRAM: return R.string.HanakoApiPresetMercurygram;
            case HanakoApiConfig.PRESET_OFFICIAL: return R.string.HanakoApiPresetOfficial;
            case HanakoApiConfig.PRESET_IOS: return R.string.HanakoApiPresetIos;
            case HanakoApiConfig.PRESET_MACOS: return R.string.HanakoApiPresetMacos;
            case HanakoApiConfig.PRESET_DESKTOP_SNAP: return R.string.HanakoApiPresetDesktopSnap;
            case HanakoApiConfig.PRESET_TELEGRAM_X: return R.string.HanakoApiPresetTelegramX;
            case HanakoApiConfig.PRESET_WEB_K: return R.string.HanakoApiPresetWebK;
            case HanakoApiConfig.PRESET_WEB_LEGACY: return R.string.HanakoApiPresetWebLegacy;
            case HanakoApiConfig.PRESET_OCTOGRAM: return R.string.HanakoApiPresetOctogram;
            case HanakoApiConfig.PRESET_OWLGRAM: return R.string.HanakoApiPresetOwlgram;
            case HanakoApiConfig.PRESET_CUSTOM: return R.string.HanakoApiPresetCustom;
            default: return R.string.HanakoApiPresetBuild;
        }
    }

    private void addPreset(ArrayList<UItem> items, int preset, int nameRes, int saved) {
        int id = HanakoApiConfig.idOf(preset);
        String value = id > 0 ? Integer.toString(id) : LocaleController.getString(R.string.HanakoApiCustomNotSet);
        items.add(UItem.asRadio(ID_PRESET + preset, LocaleController.getString(nameRes), value).setChecked(preset == saved));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RESTART) {
            HanakoBackup.restartApp(getParentActivity());
            return;
        }
        int preset = item.id - ID_PRESET;
        if (preset == HanakoApiConfig.PRESET_CUSTOM) {
            askCustom(HanakoApiConfig.hasCustom() ? Integer.toString(HanakoApiConfig.getCustomId()) : "",
                    HanakoApiConfig.getCustomHash());
            return;
        }
        if (preset == HanakoApiConfig.PRESET_BUILD || HanakoApiConfig.isFixedPreset(preset)) {
            if (preset == HanakoApiConfig.getSavedPreset()) return;
            HanakoApiConfig.savePreset(preset);
            afterSave();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    private void toast(String text) {
        Activity a = getParentActivity();
        if (a != null) Toast.makeText(a, text, Toast.LENGTH_LONG).show();
    }

    private void afterSave() {
        refresh();
        if (!HanakoApiConfig.restartPending()) return;
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoApiRestartTitle));
        b.setMessage(LocaleController.formatString(R.string.HanakoApiRestartText, HanakoApiConfig.getSavedId()));
        b.setPositiveButton(LocaleController.getString(R.string.HanakoBackupRestartNow), (d, w) -> HanakoBackup.restartApp(getParentActivity()));
        b.setNegativeButton(LocaleController.getString(R.string.HanakoApiRestartLater), null);
        showDialog(b.create());
    }

    private EditText field(Activity activity, int hintRes, String text, int inputType) {
        EditText f = new EditText(activity);
        f.setSingleLine(true);
        f.setInputType(inputType);
        f.setHint(LocaleController.getString(hintRes));
        f.setText(text);
        f.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        f.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        f.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        return f;
    }

    private void askCustom(String idText, String hashText) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        EditText idField = field(activity, R.string.HanakoApiIdHint, idText, InputType.TYPE_CLASS_NUMBER);
        EditText hashField = field(activity, R.string.HanakoApiHashHint, hashText,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(idField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        layout.addView(hashField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoApiCustomTitle));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            String idIn = idField.getText() != null ? idField.getText().toString().trim() : "";
            String hashIn = hashField.getText() != null ? hashField.getText().toString().trim() : "";
            int id = HanakoApiConfig.parseId(idIn);
            if (!HanakoApiConfig.isValidId(id)) {
                toast(LocaleController.getString(R.string.HanakoApiBadId));
                AndroidUtilities.runOnUIThread(() -> askCustom(idIn, hashIn), 150);
                return;
            }
            if (!HanakoApiConfig.isValidHash(hashIn)) {
                toast(LocaleController.getString(R.string.HanakoApiBadHash));
                AndroidUtilities.runOnUIThread(() -> askCustom(idIn, hashIn), 150);
                return;
            }
            HanakoApiConfig.saveCustom(id, hashIn);
            // after this dialog has closed, so the restart offer is not dismissed with it
            AndroidUtilities.runOnUIThread(this::afterSave, 150);
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }
}
