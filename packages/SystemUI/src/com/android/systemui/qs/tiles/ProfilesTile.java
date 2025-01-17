/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.ListView;

import com.android.systemui.R;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import mokee.app.Profile;
import mokee.app.ProfileManager;
import mokee.providers.MKSettings;
import org.mokee.internal.logging.MKMetricsLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProfilesTile extends QSTile<QSTile.State> implements KeyguardMonitor.Callback {

    private static final Intent PROFILES_SETTINGS =
            new Intent("org.cyanogenmod.cmparts.PROFILES_SETTINGS");

    private boolean mListening;
    private ProfilesObserver mObserver;
    private ProfileManager mProfileManager;
    private QSDetailItemsList mDetails;
    private ProfileAdapter mAdapter;
    private KeyguardMonitor mKeyguardMonitor;
    private final ProfileDetailAdapter mDetailAdapter;
    private final QSTile.State mStateBeforeClick = newTileState();

    public ProfilesTile(Host host) {
        super(host);
        mProfileManager = ProfileManager.getInstance(mContext);
        mObserver = new ProfilesObserver(mHandler);
        mKeyguardMonitor = host.getKeyguardMonitor();
        mKeyguardMonitor.addCallback(this);
        mDetailAdapter = new ProfileDetailAdapter();
    }

    @Override
    protected void handleDestroy() {
        mKeyguardMonitor.removeCallback(this);
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_profiles);
    }

    @Override
    public Intent getLongClickIntent() {
        return PROFILES_SETTINGS;
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    protected void handleLongClick() {
        mHost.startActivityDismissingKeyguard(PROFILES_SETTINGS);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.visible = true;

        state.enabled = !mKeyguardMonitor.isShowing() || !mKeyguardMonitor.isSecure();
        if (profilesEnabled()) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_profiles_on);
            state.label = mProfileManager.getActiveProfile().getName();
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_profiles, state.label);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_profiles_off);
            state.label = mContext.getString(R.string.quick_settings_profiles_off);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_profiles_off);
        }
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (profilesEnabled()) {
            return mContext.getString(R.string.accessibility_quick_settings_profiles_changed,
                    mState.label);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_profiles_changed_off);
        }
    }

    private boolean profilesEnabled() {
        return MKSettings.System.getInt(mContext.getContentResolver(),
                MKSettings.System.SYSTEM_PROFILES_ENABLED, 1) == 1;
    }

    @Override
    public int getMetricsCategory() {
        return MKMetricsLogger.TILE_PROFILES;
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mObserver.startObserving();
            final IntentFilter filter = new IntentFilter();
            filter.addAction(ProfileManager.INTENT_ACTION_PROFILE_SELECTED);
            filter.addAction(ProfileManager.INTENT_ACTION_PROFILE_UPDATED);
            mContext.registerReceiver(mReceiver, filter);
            refreshState();
        } else {
            mObserver.endObserving();
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public void onKeyguardChanged() {
        refreshState();
    }

    private class ProfileAdapter extends ArrayAdapter<Profile> {
        public ProfileAdapter(Context context, List<Profile> profiles) {
            super(context, android.R.layout.simple_list_item_single_choice, profiles);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            CheckedTextView label = (CheckedTextView) inflater.inflate(
                    android.R.layout.simple_list_item_single_choice, parent, false);

            Profile p = getItem(position);
            label.setText(p.getName());

            return label;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ProfileManager.INTENT_ACTION_PROFILE_SELECTED.equals(intent.getAction())
                    || ProfileManager.INTENT_ACTION_PROFILE_UPDATED.equals(intent.getAction())) {
                refreshState();
            }
        }
    };

    public class ProfileDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {

        private List<Profile> mProfilesList;

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_profiles);
        }

        @Override
        public Boolean getToggleState() {
            boolean enabled = profilesEnabled();
            return enabled;
        }

        @Override
        public int getMetricsCategory() {
            return MKMetricsLogger.TILE_PROFILES_DETAIL;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mDetails = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            mProfilesList = new ArrayList<>();
            mDetails.setAdapter(mAdapter = new ProfileAdapter(context, mProfilesList));

            final ListView list = mDetails.getListView();
            list.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
            list.setOnItemClickListener(this);

            mDetails.setEmptyState(R.drawable.ic_qs_profiles_off,
                    R.string.quick_settings_profiles_off);

            rebuildProfilesList(profilesEnabled());

            return mDetails;
        }

        private void rebuildProfilesList(boolean populate) {
            mProfilesList.clear();
            if (populate) {
                int selected = -1;

                final Profile[] profiles = mProfileManager.getProfiles();
                final Profile activeProfile = mProfileManager.getActiveProfile();
                final UUID activeUuid = activeProfile != null ? activeProfile.getUuid() : null;

                for (int i = 0; i < profiles.length; i++) {
                    mProfilesList.add(profiles[i]);
                    if (activeUuid != null && activeUuid.equals(profiles[i].getUuid())) {
                        selected = i;
                    }
                }
                mDetails.getListView().setItemChecked(selected, true);
            }
            mAdapter.notifyDataSetChanged();
        }

        @Override
        public Intent getSettingsIntent() {
            return PROFILES_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            MKSettings.System.putInt(mContext.getContentResolver(),
                    MKSettings.System.SYSTEM_PROFILES_ENABLED, state ? 1 : 0);

            fireToggleStateChanged(state);
            rebuildProfilesList(state);
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Profile selected = (Profile) parent.getItemAtPosition(position);
            mProfileManager.setActiveProfile(selected.getUuid());
        }
    }

    private class ProfilesObserver extends ContentObserver {
        public ProfilesObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }

        public void startObserving() {
            mContext.getContentResolver().registerContentObserver(
                    MKSettings.System.getUriFor(MKSettings.System.SYSTEM_PROFILES_ENABLED),
                    false, this);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }
}
