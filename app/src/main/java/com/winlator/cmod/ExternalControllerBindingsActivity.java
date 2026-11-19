package com.winlator.cmod;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;

import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.ExternalControllerBinding;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;

public class ExternalControllerBindingsActivity extends AppCompatActivity {
    private TextView emptyTextView;
    private ControlsProfile profile;
    private ExternalController controller;
    private RecyclerView recyclerView;
    private ControllerBindingsAdapter adapter;

    // Track trigger state to only register on rising edge
    private boolean l2WasPressed = false;
    private boolean r2WasPressed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.external_controller_bindings_activity);

        Intent intent = getIntent();
        int profileId = intent.getIntExtra("profile_id", 0);
        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        String controllerId = intent.getStringExtra("controller_id");

        controller = profile.getController(controllerId);
        if (controller == null) {
            controller = profile.addController(controllerId);
            profile.save();
        }

        Toolbar toolbar = findViewById(R.id.Toolbar);
        toolbar.setTitle(controller.getName());
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
        // The bar in the app's colours: it was a light bar with a white title nobody could read.
        int barText = com.winlator.cmod.ui.theme.WinlatorLegacyTheme.onSurface(this);
        toolbar.setBackgroundColor(com.winlator.cmod.ui.theme.WinlatorLegacyTheme.surface(this));
        toolbar.setTitleTextColor(barText);
        if (toolbar.getNavigationIcon() != null) toolbar.getNavigationIcon().setTint(barText);
        getWindow().getDecorView().setBackgroundColor(com.winlator.cmod.ui.theme.WinlatorLegacyTheme.surface(this));

        setupStickSettings();

        emptyTextView = findViewById(R.id.TVEmptyText);
        recyclerView = findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recyclerView.setAdapter(adapter = new ControllerBindingsAdapter());
        updateEmptyTextView();
    }

    // private void updateControllerBinding(int keyCode, Binding binding) {
    // if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return;
    //
    // ExternalControllerBinding controllerBinding =
    // controller.getControllerBinding(keyCode);
    // int position;
    // if (controllerBinding == null) {
    // controllerBinding = new ExternalControllerBinding();
    // controllerBinding.setKeyCode(keyCode);
    // controllerBinding.setBinding(binding);
    //
    // controller.addControllerBinding(controllerBinding);
    // profile.save();
    // adapter.notifyDataSetChanged();
    // updateEmptyTextView();
    // position = controller.getPosition(controllerBinding);
    // }
    // else animateItemView(position = controller.getPosition(controllerBinding));
    // recyclerView.scrollToPosition(position);
    // }

    private void updateControllerBinding(int keyCode, Binding binding) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN)
            return;

        ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
        int position;
        if (controllerBinding == null) {
            controllerBinding = new ExternalControllerBinding();
            controllerBinding.setKeyCode(keyCode);
            controllerBinding.setBinding(binding);

            controller.addControllerBinding(controllerBinding);
            profile.save();
            adapter.notifyDataSetChanged();
            updateEmptyTextView();
            position = controller.getPosition(controllerBinding);
        } else {
            animateItemView(position = controller.getPosition(controllerBinding));
        }
        recyclerView.scrollToPosition(position);
    }

    // private void processJoystickInput() {
    // int keyCode = KeyEvent.KEYCODE_UNKNOWN;
    // Binding binding = Binding.NONE;
    // final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
    // MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X,
    // MotionEvent.AXIS_HAT_Y};
    // final float[] values = {controller.state.thumbLX, controller.state.thumbLY,
    // controller.state.thumbRX, controller.state.thumbRY,
    // controller.state.getDPadX(), controller.state.getDPadY()};
    //
    // byte sign;
    // for (int i = 0; i < axes.length; i++) {
    // if ((sign = Mathf.sign(values[i])) != 0) {
    // if (axes[i] == MotionEvent.AXIS_X || axes[i] == MotionEvent.AXIS_Z) {
    // binding = sign > 0 ? Binding.MOUSE_MOVE_RIGHT : Binding.MOUSE_MOVE_LEFT;
    // }
    // else if (axes[i] == MotionEvent.AXIS_Y || axes[i] == MotionEvent.AXIS_RZ) {
    // binding = sign > 0 ? Binding.MOUSE_MOVE_DOWN : Binding.MOUSE_MOVE_UP;
    // }
    // else if (axes[i] == MotionEvent.AXIS_HAT_X) {
    // binding = sign > 0 ? Binding.KEY_D : Binding.KEY_A;
    // }
    // else if (axes[i] == MotionEvent.AXIS_HAT_Y) {
    // binding = sign > 0 ? Binding.KEY_S : Binding.KEY_W;
    // }
    //
    // keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
    // updateControllerBinding(keyCode, binding);
    // }
    // }
    // }

    private void processJoystickInput() {
        final int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        final float[] values = {
                controller.state.thumbLX, controller.state.thumbLY,
                controller.state.thumbRX, controller.state.thumbRY,
                controller.state.getDPadX(), controller.state.getDPadY()
        };

        for (int i = 0; i < axes.length; i++) {
            float value = values[i];
            byte sign = Mathf.sign(value);
            if (sign != 0) {
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                updateControllerBinding(keyCode, Binding.NONE); // Or prompt the user to select a binding
            }
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Accept motion events from any game controller for binding registration
        InputDevice device = event.getDevice();
        if (device != null && ExternalController.isGameController(device)
                && controller.updateStateFromMotionEvent(event)) {

            // Use a higher threshold for binding registration to avoid false triggers
            float l2Value = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_BRAKE));
            float r2Value = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_GAS));

            // Only register L2/R2 on rising edge (first press) past 80% threshold
            boolean l2Pressed = l2Value > 0.8f;
            if (l2Pressed && !l2WasPressed) {
                updateControllerBinding(KeyEvent.KEYCODE_BUTTON_L2, Binding.NONE);
            }
            l2WasPressed = l2Pressed;

            boolean r2Pressed = r2Value > 0.8f;
            if (r2Pressed && !r2WasPressed) {
                updateControllerBinding(KeyEvent.KEYCODE_BUTTON_R2, Binding.NONE);
            }
            r2WasPressed = r2Pressed;

            processJoystickInput();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Any gamepad button or D-pad keycode, and any other button of a controller (back paddles,
        // C / Z, BUTTON_1..16: what extra buttons are reported as).
        if (isControllerKey(keyCode, event)) {
            updateControllerBinding(keyCode, Binding.NONE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Consume the key up for gamepad buttons
        if (isControllerKey(keyCode, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void setupStickSettings() {
        com.winlator.cmod.ui.theme.AccentButtons.card(findViewById(R.id.LLStickSettings));
        int text = com.winlator.cmod.ui.theme.WinlatorLegacyTheme.onSurface(this);
        int accent = com.winlator.cmod.ui.theme.WinlatorLegacyTheme.primary(this);

        TextView tvSpeed = findViewById(R.id.TVMouseSensitivity);
        TextView tvDead = findViewById(R.id.TVDeadZone);
        android.widget.SeekBar sbSpeed = findViewById(R.id.SBMouseSensitivity);
        android.widget.SeekBar sbDead = findViewById(R.id.SBDeadZone);
        tvSpeed.setTextColor(text);
        tvDead.setTextColor(text);
        for (android.widget.SeekBar sb : new android.widget.SeekBar[]{sbSpeed, sbDead}) {
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        }

        // Speed 0.2..5 in steps of 0.1; dead zone 0..50 %.
        sbSpeed.setProgress(Math.round((controller.getMouseSensitivity() - 0.2f) * 10f));
        sbDead.setProgress(Math.round(controller.getDeadZone() * 100f));
        Runnable labels = () -> {
            tvSpeed.setText(getString(R.string.stick_mouse_sensitivity,
                    String.format(java.util.Locale.ROOT, "%.1f", controller.getMouseSensitivity())));
            tvDead.setText(getString(R.string.stick_dead_zone, Math.round(controller.getDeadZone() * 100f)));
        };
        labels.run();
        android.widget.SeekBar.OnSeekBarChangeListener listener = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                if (sb == sbSpeed) controller.setMouseSensitivity(0.2f + progress / 10f);
                else controller.setDeadZone(progress / 100f);
                labels.run();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { profile.save(); }
        };
        sbSpeed.setOnSeekBarChangeListener(listener);
        sbDead.setOnSeekBarChangeListener(listener);

        TextView chord = findViewById(R.id.BTAddChord);
        com.winlator.cmod.ui.theme.AccentButtons.filled(chord);
        chord.setOnClickListener((v) -> captureChord());

        // The "PC" gamepad mode's layout as a starting point to change.
        TextView fillPc = findViewById(R.id.BTFillPc);
        com.winlator.cmod.ui.theme.AccentButtons.outlined(fillPc);
        fillPc.setOnClickListener((v) -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.pc_layout_fill_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    com.winlator.cmod.inputcontrols.PcInput.fill(controller);
                    controller.setMouseSensitivity(2.0f);
                    profile.save();
                    sbSpeed.setProgress(Math.round((controller.getMouseSensitivity() - 0.2f) * 10f));
                    adapter.notifyDataSetChanged();
                    updateEmptyTextView();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    /** A button chord: hold the first button, press the second. Asked for one press at a time. */
    private void captureChord() {
        final int[] first = {0};
        final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.chord_add)
                .setMessage(R.string.chord_press_first)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (!isControllerKey(keyCode, event)) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (first[0] == 0) {
                    first[0] = keyCode;
                    dialog.setMessage(getString(R.string.chord_press_second, ExternalControllerBinding.keyLabel(keyCode)));
                }
                else if (keyCode != first[0]) {
                    ExternalControllerBinding b = controller.getControllerBinding(keyCode, first[0]);
                    if (b == null) {
                        b = new ExternalControllerBinding();
                        b.setKeyCode(keyCode);
                        b.setModifier(first[0]);
                        b.setBinding(Binding.NONE);
                        controller.addControllerBinding(b);
                        profile.save();
                        adapter.notifyDataSetChanged();
                        updateEmptyTextView();
                    }
                    int position = controller.getPosition(b);
                    recyclerView.scrollToPosition(position);
                    dialog.dismiss();
                }
            }
            return true;
        });
        dialog.show();
    }

    private boolean isControllerKey(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN || keyCode == KeyEvent.KEYCODE_BACK) return false;
        if (isGamepadKeyCode(keyCode) || KeyEvent.isGamepadButton(keyCode)) return true;
        return event != null && ExternalController.isGameController(event.getDevice());
    }

    private boolean isGamepadKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                keyCode == KeyEvent.KEYCODE_BUTTON_X ||
                keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
                keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_L2 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_R2 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL ||
                keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR ||
                keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
                keyCode == KeyEvent.KEYCODE_BUTTON_MODE ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        finish();
        return true;
    }

    private class ControllerBindingsAdapter extends RecyclerView.Adapter<ControllerBindingsAdapter.ViewHolder> {
        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton removeButton;
            private final TextView title;
            private final Spinner bindingType;
            private final Spinner binding;
            private final ImageButton addKeyButton;
            private final TextView combo;

            private ViewHolder(View view) {
                super(view);
                this.title = view.findViewById(R.id.TVTitle);
                this.bindingType = view.findViewById(R.id.SBindingType);
                this.binding = view.findViewById(R.id.SBinding);
                this.removeButton = view.findViewById(R.id.BTRemove);
                this.addKeyButton = view.findViewById(R.id.BTAddKey);
                this.combo = view.findViewById(R.id.TVCombo);
            }
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.external_controller_binding_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ExternalControllerBinding item = controller.getControllerBindingAt(position);
            holder.title.setText(item.toString());
            com.winlator.cmod.ui.theme.AccentButtons.square(holder.addKeyButton);
            com.winlator.cmod.ui.theme.AccentButtons.square(holder.removeButton);
            loadBindingSpinner(holder, item);
            showCombo(holder, item);
            holder.addKeyButton.setOnClickListener((view) -> addComboKey(holder, item));
            holder.combo.setOnClickListener((view) ->
                    new androidx.appcompat.app.AlertDialog.Builder(ExternalControllerBindingsActivity.this)
                            .setMessage(R.string.combo_clear)
                            .setPositiveButton(android.R.string.ok, (d, w) -> {
                                item.clearExtras();
                                profile.save();
                                showCombo(holder, item);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show());
            holder.removeButton.setOnClickListener((view) -> {
                controller.removeControllerBinding(item);
                profile.save();
                notifyDataSetChanged();
                updateEmptyTextView();
            });
        }

        @Override
        public final int getItemCount() {
            return controller.getControllerBindingCount();
        }

        private void showCombo(ViewHolder holder, ExternalControllerBinding item) {
            String label = item.comboLabel();
            holder.combo.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
            holder.combo.setText(getString(R.string.combo_keys, label));
        }

        /** One more key (or mouse button) pressed together with this button's binding: Ctrl + S, Alt + Tab... */
        private void addComboKey(ViewHolder holder, ExternalControllerBinding item) {
            Context ctx = ExternalControllerBindingsActivity.this;
            if (item.getExtras().size() >= ExternalControllerBinding.MAX_EXTRAS) {
                android.widget.Toast.makeText(ctx, getString(R.string.combo_full, ExternalControllerBinding.MAX_EXTRAS),
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            java.util.ArrayList<Binding> values = new java.util.ArrayList<>();
            for (Binding b : Binding.keyboardBindingValues()) if (b != Binding.NONE) values.add(b);
            for (Binding b : Binding.mouseBindingValues()) if (!b.isMouseMove()) values.add(b);
            String[] labels = new String[values.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = values.get(i).toString();
            new androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.add_key_to_combo)
                    .setItems(labels, (d, which) -> {
                        item.addExtra(values.get(which));
                        profile.save();
                        showCombo(holder, item);
                    })
                    .show();
        }

        private void loadBindingSpinner(ViewHolder holder, final ExternalControllerBinding item) {
            final Context $this = ExternalControllerBindingsActivity.this;

            Runnable update = () -> {
                String[] bindingEntries = null;
                switch (holder.bindingType.getSelectedItemPosition()) {
                    case 0:
                        bindingEntries = Binding.keyboardBindingLabels();
                        break;
                    case 1:
                        bindingEntries = Binding.mouseBindingLabels();
                        break;
                    case 2:
                        bindingEntries = Binding.gamepadBindingLabels();
                        break;
                }

                holder.binding.setAdapter(
                        new ArrayAdapter<>($this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
                AppUtils.setSpinnerSelectionFromValue(holder.binding, item.getBinding().toString());
            };

            holder.bindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    update.run();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            Binding selectedBinding = item.getBinding();
            if (selectedBinding.isKeyboard()) {
                holder.bindingType.setSelection(0, false);
            } else if (selectedBinding.isMouse()) {
                holder.bindingType.setSelection(1, false);
            } else if (selectedBinding.isGamepad()) {
                holder.bindingType.setSelection(2, false);
            }

            holder.binding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    Binding binding = Binding.NONE;
                    switch (holder.bindingType.getSelectedItemPosition()) {
                        case 0:
                            binding = Binding.keyboardBindingValues()[position];
                            break;
                        case 1:
                            binding = Binding.mouseBindingValues()[position];
                            break;
                        case 2:
                            binding = Binding.gamepadBindingValues()[position];
                            break;
                    }

                    if (binding != item.getBinding()) {
                        item.setBinding(binding);
                        profile.save();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            update.run();

        }
    }

    private void updateEmptyTextView() {
        emptyTextView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void animateItemView(int position) {
        final ControllerBindingsAdapter.ViewHolder holder = (ControllerBindingsAdapter.ViewHolder) recyclerView
                .findViewHolderForAdapterPosition(position);
        if (holder != null) {
            final int color = ContextCompat.getColor(this, R.color.colorAccent);
            final ValueAnimator animator = ValueAnimator.ofFloat(0.4f, 0.0f);
            animator.setDuration(200);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.addUpdateListener((animation) -> {
                float alpha = (float) animation.getAnimatedValue();
                holder.itemView.setBackgroundColor(
                        Color.argb((int) (alpha * 255), Color.red(color), Color.green(color), Color.blue(color)));
            });
            animator.start();
        }
    }
}
