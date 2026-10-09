/*
 * hanako: Settings > Hanako > Usage statistics. Off by default. The switch, where uploads go
 * (empty = never), the receiver's key, a preview of exactly what would be sent, send now, and
 * a new random install id. See HanakoTelemetry for what is (and is never) collected.
 */
package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

import it.belloworld.mercurygram.HanakoTelemetry;

public class HanakoTelemetryActivity extends UniversalFragment {

    private static final int ID_ENABLED = 1;
    private static final int ID_ENDPOINT = 2;
    private static final int ID_KEY = 3;
    private static final int ID_VIEW = 4;
    private static final int ID_SEND_NOW = 5;
    private static final int ID_RESET_ID = 6;
    private static final int ID_CLEAR = 7;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HanakoTelemetryTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean on = HanakoTelemetry.isEnabled();
        items.add(UItem.asCheck(ID_ENABLED, LocaleController.getString(R.string.HanakoTelemetryEnable)).setChecked(on));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoTelemetryInfo)));

        String endpoint = HanakoTelemetry.getEndpoint();
        items.add(UItem.asButton(ID_ENDPOINT, LocaleController.getString(R.string.HanakoTelemetryEndpoint),
                TextUtils.isEmpty(endpoint) ? LocaleController.getString(R.string.HanakoTelemetryEndpointNone) : shortHost(endpoint)));
        items.add(UItem.asButton(ID_KEY, LocaleController.getString(R.string.HanakoTelemetryKey),
                LocaleController.getString(HanakoTelemetry.hasUploadKey() ? R.string.HanakoTelemetryKeySet : R.string.HanakoTelemetryKeyNotSet)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HanakoTelemetryEndpointInfo)));

        items.add(UItem.asButton(ID_VIEW, LocaleController.getString(R.string.HanakoTelemetryView)));
        if (on && !TextUtils.isEmpty(endpoint)) {
            items.add(UItem.asButton(ID_SEND_NOW, LocaleController.getString(R.string.HanakoTelemetrySendNow)));
        }
        items.add(UItem.asButton(ID_CLEAR, LocaleController.getString(R.string.HanakoTelemetryClear)));
        items.add(UItem.asButton(ID_RESET_ID, LocaleController.getString(R.string.HanakoTelemetryResetId)));
        String last = HanakoTelemetry.lastResult;
        items.add(UItem.asShadow(TextUtils.isEmpty(last) ? null
                : LocaleController.formatString(R.string.HanakoTelemetryLastResult, resultText(last))));
    }

    private static String shortHost(String url) {
        String s = url.replaceFirst("^https://", "");
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : s;
    }

    private static String resultText(String r) {
        if ("ok".equals(r)) return LocaleController.getString(R.string.HanakoTelemetryResultOk);
        if ("proxy".equals(r)) return LocaleController.getString(R.string.HanakoTelemetryResultProxy);
        return r;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ENABLED:
                HanakoTelemetry.setEnabled(!HanakoTelemetry.isEnabled());
                refresh();
                break;
            case ID_ENDPOINT:
                askText(R.string.HanakoTelemetryEndpoint, R.string.HanakoTelemetryEndpointHint, HanakoTelemetry.getEndpoint(), false, value -> {
                    if (!HanakoTelemetry.setEndpoint(value)) toast(LocaleController.getString(R.string.HanakoTelemetryEndpointBad));
                    refresh();
                });
                break;
            case ID_KEY:
                askText(R.string.HanakoTelemetryKey, R.string.HanakoTelemetryKeyHint, "", true, value -> {
                    HanakoTelemetry.setUploadKey(value);
                    refresh();
                });
                break;
            case ID_VIEW:
                showPreview();
                break;
            case ID_SEND_NOW:
                toast(LocaleController.getString(R.string.HanakoTelemetrySending));
                HanakoTelemetry.uploadNow(this::refresh);
                break;
            case ID_CLEAR:
                HanakoTelemetry.clearPending();
                toast(LocaleController.getString(R.string.HanakoTelemetryCleared));
                break;
            case ID_RESET_ID:
                HanakoTelemetry.resetInstallId();
                toast(LocaleController.getString(R.string.HanakoTelemetryIdReset));
                break;
            default:
                break;
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
        if (a != null) Toast.makeText(a, text, Toast.LENGTH_SHORT).show();
    }

    private interface TextCallback {
        void run(String value);
    }

    private void askText(int titleRes, int hintRes, String current, boolean secret, TextCallback callback) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        field.setHint(LocaleController.getString(hintRes));
        field.setText(current);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        LinearLayout layout = new LinearLayout(activity);
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(titleRes));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) ->
                callback.run(field.getText() != null ? field.getText().toString() : ""));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    /** The exact JSON body the next upload would POST. */
    private void showPreview() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        JSONObject payload = HanakoTelemetry.buildPayload();
        String text;
        try {
            text = payload != null ? payload.toString(2) : "";
        } catch (Exception e) {
            text = "";
        }
        TextView tv = new TextView(activity);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        tv.setTextIsSelectable(true);
        tv.setText(text);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(tv);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView note = new TextView(activity);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, getResourceProvider()));
        note.setText(LocaleController.getString(R.string.HanakoTelemetryViewInfo));
        layout.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        layout.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 360, 24, 0, 24, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(activity, getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.HanakoTelemetryView));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(b.create());
    }
}
