package it.belloworld.mercurygram.ui;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Collections;

import it.belloworld.mercurygram.PlusGhostExceptions;

/** plus f01: list of per-chat ghost exceptions for the current account. */
public class PlusGhostExceptionsActivity extends UniversalFragment {

    private static final int ID_BASE = 1000;

    /** Dialog ids in the order shown; row id = ID_BASE + index. */
    private final ArrayList<Long> shown = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PlusGhostExcTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final int acc = getCurrentAccount();
        shown.clear();
        ArrayList<Long> ids = PlusGhostExceptions.list(acc);
        ArrayList<String> titles = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            titles.add(PlusGhostExceptions.dialogTitle(acc, ids.get(i)));
        }
        // sort by title
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> titles.get(a).compareToIgnoreCase(titles.get(b)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PlusGhostExcHeader)));
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            long did = ids.get(i);
            shown.add(did);
            items.add(UItem.asButton(ID_BASE + k, titles.get(i),
                    PlusGhostExceptions.flagsSummary(PlusGhostExceptions.getFlags(acc, did))));
        }
        items.add(UItem.asShadow(LocaleController.getString(shown.isEmpty()
                ? R.string.PlusGhostExcEmpty : R.string.PlusGhostExcListInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        int idx = item.id - ID_BASE;
        if (idx >= 0 && idx < shown.size()) {
            PlusGhostExceptions.showDialog(this, shown.get(idx), this::refreshList);
        }
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
