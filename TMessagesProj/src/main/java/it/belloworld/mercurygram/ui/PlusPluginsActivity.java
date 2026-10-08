package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import it.belloworld.mercurygram.PlusPlugins;

/** plus f11: plugin manager (list, enable/disable, import, delete). Row ids 1101-1199. */
public class PlusPluginsActivity extends UniversalFragment {

    private static final int ID_ALLOW_CODE = 1101;
    private static final int ID_IMPORT = 1102;
    private static final int ID_RELOAD = 1103;
    private static final int ID_PLUGIN = 1110; // + index, up to ID_PLUGIN_MAX
    private static final int ID_PLUGIN_MAX = 1189;
    private static final int REQUEST_IMPORT = 1199;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PlusF11Plugins);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        List<PlusPlugins.Info> plugins = PlusPlugins.list();

        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF11Installed)));
        if (plugins.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF11None)));
        } else {
            StringBuilder problems = new StringBuilder();
            int count = Math.min(plugins.size(), ID_PLUGIN_MAX - ID_PLUGIN + 1);
            for (int i = 0; i < count; i++) {
                PlusPlugins.Info info = plugins.get(i);
                String label = info.name + (TextUtils.isEmpty(info.version) ? "" : " " + info.version)
                        + (info.hasCode() ? " · " + LocaleController.getString(R.string.PlusF11CodeTag) : "");
                items.add(UItem.asCheck(ID_PLUGIN + i, label).setChecked(info.enabled && info.loadError == null));
                String err = info.loadError != null ? info.loadError : info.lastError;
                if (err != null) {
                    if (problems.length() > 0) problems.append('\n');
                    problems.append(info.name).append(": ").append(err);
                }
            }
            if (plugins.size() > count) {
                if (problems.length() > 0) problems.append('\n');
                problems.append(plugins.size() - count).append(" more not shown");
            }
            items.add(UItem.asShadow(problems.length() > 0 ? problems.toString() : LocaleController.getString(R.string.PlusF11LongPress)));
        }

        items.add(UItem.asButton(ID_IMPORT, LocaleController.getString(R.string.PlusF11Import)));
        items.add(UItem.asButton(ID_RELOAD, LocaleController.getString(R.string.PlusF11Reload)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF11ImportAbout)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusF11Security)));
        items.add(UItem.asCheck(ID_ALLOW_CODE, LocaleController.getString(R.string.PlusF11AllowCode))
                .setChecked(PlusPlugins.isCodeAllowed()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PlusF11AllowCodeAbout)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ALLOW_CODE) {
            if (PlusPlugins.isCodeAllowed()) {
                PlusPlugins.setCodeAllowed(false);
                refresh();
            } else {
                confirm(LocaleController.getString(R.string.PlusF11AllowCode),
                        LocaleController.getString(R.string.PlusF11AllowCodeWarning),
                        () -> {
                            PlusPlugins.setCodeAllowed(true);
                            refresh();
                        });
            }
        } else if (item.id == ID_IMPORT) {
            openPicker();
        } else if (item.id == ID_RELOAD) {
            PlusPlugins.reload();
            refresh();
        } else if (item.id >= ID_PLUGIN && item.id <= ID_PLUGIN_MAX) {
            PlusPlugins.Info info = pluginAt(item.id - ID_PLUGIN);
            if (info == null) {
                return;
            }
            if (info.enabled) {
                PlusPlugins.setEnabled(info, false);
                refresh();
            } else if (info.hasCode()) {
                confirm(info.name, LocaleController.getString(R.string.PlusF11EnableCodeWarning) + "\n\n" + info.hooksSummary(), () -> {
                    PlusPlugins.setEnabled(info, true);
                    refresh();
                });
            } else {
                PlusPlugins.setEnabled(info, true);
                refresh();
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < ID_PLUGIN || item.id > ID_PLUGIN_MAX || getParentActivity() == null) {
            return false;
        }
        PlusPlugins.Info info = pluginAt(item.id - ID_PLUGIN);
        if (info == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(info.description)) sb.append(info.description).append("\n\n");
        if (info.id != null) sb.append("id: ").append(info.id).append('\n');
        if (!TextUtils.isEmpty(info.author)) sb.append(LocaleController.getString(R.string.PlusF11Author)).append(": ").append(info.author).append('\n');
        sb.append(LocaleController.getString(R.string.PlusF11Hooks)).append(": ").append(info.hooksSummary()).append('\n');
        sb.append(LocaleController.getString(R.string.PlusF11File)).append(": ").append(info.manifest != null ? info.manifest.getName() : "?");
        if (info.loadError != null) sb.append("\n\n").append(info.loadError);
        if (info.lastError != null) sb.append("\n\n").append(info.lastError);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(info.name);
        b.setMessage(sb.toString());
        b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        b.setNegativeButton(LocaleController.getString(R.string.PlusF11Delete), (d, w) -> confirm(info.name,
                LocaleController.getString(R.string.PlusF11DeleteConfirm), () -> {
                    PlusPlugins.delete(info);
                    refresh();
                }));
        b.makeRed(AlertDialog.BUTTON_NEGATIVE);
        showDialog(b.create());
        return true;
    }

    private PlusPlugins.Info pluginAt(int index) {
        List<PlusPlugins.Info> plugins = PlusPlugins.list();
        return index >= 0 && index < plugins.size() ? plugins.get(index) : null;
    }

    private void confirm(CharSequence title, CharSequence message, Runnable onYes) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(title);
        b.setMessage(message);
        b.setPositiveButton(LocaleController.getString(R.string.PlusF11Continue), (d, w) -> onYes.run());
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void openPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Exception e) {
            FileLog.e(e);
            toast(e.getMessage());
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        Utilities.globalQueue.postRunnable(() -> {
            StringBuilder result = new StringBuilder();
            for (Uri u : uris) {
                if (result.length() > 0) result.append('\n');
                result.append(PlusPlugins.importUri(context, u));
            }
            AndroidUtilities.runOnUIThread(() -> {
                toast(result.toString());
                refresh();
            });
        });
    }

    private void toast(String text) {
        if (!TextUtils.isEmpty(text)) {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
