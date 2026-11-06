package com.winlator.cmod.ui.ingame;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.SeekBar;

import java.util.ArrayList;

/**
 * YurNLator base, part 11: the sidebar's on-screen controls panel: the controls profile, show /
 * hide, auto-hide, haptics, opacity, and the button to the controls editor.
 */
public final class SidebarInputPanel {
    public interface Host {
        Activity activity();
        InputControlsView controls();
        InputControlsManager manager();
        SharedPreferences prefs();
        TouchControls touch();
        ArrayAdapter<String> spinnerAdapter(String[] items);
        /** Opens the controls editor on [profileId]; [onBack] runs when it returns. */
        void openEditor(int profileId, Runnable onBack);
        /** Plain mouse on the touchpad again (no auto-hide listener). */
        void clearTouchpadListener();
    }

    private SidebarInputPanel() {}

    public static void setup(Host host) {
        Activity a = host.activity();
        InputControlsView controls = host.controls();
        InputControlsManager manager = host.manager();
        SharedPreferences prefs = host.prefs();
        if (controls == null || manager == null) return;

        Spinner spProfile = a.findViewById(R.id.SPInputControlsProfile);
        Switch swShow = a.findViewById(R.id.SWShowTouchscreenControls);
        Switch swTimeout = a.findViewById(R.id.SWEnableTouchscreenTimeout);
        Switch swHaptics = a.findViewById(R.id.SWEnableTouchscreenHaptics);
        View btSettings = a.findViewById(R.id.BTInputControlsSettings);
        SeekBar sbOpacity = a.findViewById(R.id.SBControlsOpacity);
        if (spProfile == null) return;

        Runnable loadProfiles = () -> {
            ArrayList<ControlsProfile> profiles = manager.getProfiles(true);
            ArrayList<String> items = new ArrayList<>();
            int selected = 0;
            items.add("-- " + a.getString(R.string.disabled) + " --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (controls.getProfile() != null && profile.id == controls.getProfile().id) selected = i + 1;
                items.add(profile.getName());
            }
            ArrayAdapter<String> adapter = host.spinnerAdapter(items.toArray(new String[0]));
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spProfile.setAdapter(adapter);
            spProfile.setSelection(selected, false);
        };
        loadProfiles.run();

        if (swShow != null) swShow.setChecked(controls.isShowTouchscreenControls());
        if (swTimeout != null) swTimeout.setChecked(prefs.getBoolean("touchscreen_timeout_enabled", false));
        if (swHaptics != null) swHaptics.setChecked(prefs.getBoolean("touchscreen_haptics_enabled", false));

        if (sbOpacity != null) {
            sbOpacity.setValue(prefs.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY) * 100f);
            sbOpacity.setOnValueChangeListener((sb, v) -> {
                float opacity = v / 100f;
                prefs.edit().putFloat("overlay_opacity", opacity).apply();
                controls.setOverlayOpacity(opacity);
                controls.invalidate();
            });
        }

        Runnable apply = () -> {
            if (swShow != null) {
                boolean show = swShow.isChecked();
                controls.setShowTouchscreenControls(show);
                prefs.edit().putBoolean("show_touchscreen_controls_enabled", show).apply();
            }
            boolean timeout = swTimeout != null && swTimeout.isChecked();
            boolean haptics = swHaptics != null && swHaptics.isChecked();
            prefs.edit()
                    .putBoolean("touchscreen_timeout_enabled", timeout)
                    .putBoolean("touchscreen_haptics_enabled", haptics)
                    .putInt("selected_profile_index", spProfile.getSelectedItemPosition() - 1)
                    .apply();

            int position = spProfile.getSelectedItemPosition();
            ArrayList<ControlsProfile> profiles = manager.getProfiles();
            if (position > 0 && position - 1 < profiles.size()) host.touch().show(profiles.get(position - 1));
            else host.touch().hide();

            if (timeout && controls.getVisibility() == View.VISIBLE) host.touch().startTimeout();
            else host.clearTouchpadListener();
        };

        spProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { apply.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        if (swShow != null) swShow.setOnCheckedChangeListener((b, c) -> apply.run());
        if (swTimeout != null) swTimeout.setOnCheckedChangeListener((b, c) -> apply.run());
        if (swHaptics != null) swHaptics.setOnCheckedChangeListener((b, c) -> apply.run());

        if (btSettings != null) {
            btSettings.setOnClickListener(v -> {
                int position = spProfile.getSelectedItemPosition();
                int profileId = position > 0 ? manager.getProfiles().get(position - 1).id : 0;
                host.openEditor(profileId, () -> {
                    host.touch().hide();
                    manager.loadProfiles(true);
                    loadProfiles.run();
                    apply.run();
                });
            });
        }
    }
}
