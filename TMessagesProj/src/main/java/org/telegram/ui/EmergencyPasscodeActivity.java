/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.duress.EmergencyPasscode;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

// FOLDOGRAM-DURESS: Normal-mode settings screen for emergency passcode configuration.
public class EmergencyPasscodeActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private int setPasscodeRow;
    private int chooseChatsRow;
    private int infoRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        if (EmergencyPasscode.emergencyModeActive) {
            return false;
        }
        updateRows();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(LocaleController.getString(R.string.EmergencyPasscode));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == setPasscodeRow) {
                // FOLDOGRAM-DURESS: Reuse passcode setup while writing only the emergency credential.
                presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE).setSettingEmergencyPasscode(true));
            } else if (position == chooseChatsRow) {
                // FOLDOGRAM-DURESS: Reuse the existing seeded multi-chat picker.
                ArrayList<Long> ids = new ArrayList<>(EmergencyPasscode.emergencyHiddenChats);
                UsersSelectActivity fragment = new UsersSelectActivity(false, ids, 0);
                fragment.setDelegate((selectedIds, flags) -> {
                    EmergencyPasscode.setHiddenChats(currentAccount, selectedIds);
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                });
                presentFragment(fragment);
            }
        });
        fragmentView = listView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        return fragmentView;
    }

    // FOLDOGRAM-DURESS: Fixed two-action settings layout.
    private void updateRows() {
        rowCount = 0;
        setPasscodeRow = rowCount++;
        chooseChatsRow = rowCount++;
        infoRow = rowCount++;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == setPasscodeRow || position == chooseChatsRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new TextSettingsCell(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (position == setPasscodeRow) {
                    cell.setTextAndValue(LocaleController.getString(R.string.EmergencyPasscode), LocaleController.getString(EmergencyPasscode.hasEmergency() ? R.string.NotificationsOn : R.string.NotificationsOff), true);
                } else if (position == chooseChatsRow) {
                    cell.setTextAndValue(LocaleController.getString(R.string.EmergencyChooseChats), String.valueOf(EmergencyPasscode.emergencyHiddenChats.size()), false);
                }
            } else {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(LocaleController.getString(R.string.EmergencyPasscodeInfo));
            }
        }

        @Override
        public int getItemViewType(int position) {
            return position == infoRow ? 1 : 0;
        }
    }
}
