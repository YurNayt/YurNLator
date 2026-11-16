package com.winlator.cmod;

import com.winlator.cmod.ui.UiStrings;
import com.winlator.cmod.R;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.ui.library.GameDetailCallbacks;
import com.winlator.cmod.ui.library.GameDetailComposeHost;
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog;
import com.winlator.cmod.ui.presets.CommunityPresetsDialog;

import java.io.File;

public class GameDetailFragment extends Fragment {
    private final String shortcutPath;
    private Shortcut shortcut;

    public GameDetailFragment() {
        this("");
    }

    public GameDetailFragment(String shortcutPath) {
        this.shortcutPath = shortcutPath;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        ContainerManager manager = new ContainerManager(requireContext());
        for (Shortcut candidate : manager.loadShortcuts()) {
            if (candidate != null && candidate.file != null
                    && candidate.file.getPath().equals(shortcutPath)) {
                shortcut = candidate;
                break;
            }
        }

        if (shortcut == null) {
            getParentFragmentManager().popBackStack();
            return new FrameLayout(requireContext());
        }

        // Portrait: the page covers the top bar, which keeps the library's title for the way back.
        if (isLandscape()) ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(com.winlator.cmod.core.ExeVersionInfo.displayName(shortcut.name, shortcut.path));
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File banner = new File(Environment.getExternalStorageDirectory(),
                "YurNLator/banners/" + baseName + ".png");
        File cover = new File(Environment.getExternalStorageDirectory(),
                "YurNLator/covers/" + baseName + ".png");
        String artworkPath = banner.exists() ? banner.getPath() : cover.exists() ? cover.getPath() : null;
        Bitmap fallback = shortcut.icon;

        View content = GameDetailComposeHost.create(
                requireContext(),
                com.winlator.cmod.core.ExeVersionInfo.displayName(shortcut.name, shortcut.path),
                buildEnvironmentSubtitle(),
                artworkPath,
                fallback,
                "1".equals(shortcut.getExtra("favorite", "0")),
                shortcut,
                new GameDetailCallbacks() {
                    @Override
                    public void onPlay() {
                        runShortcut(false);
                    }

                    @Override
                    public void onPlayAdmin() {
                        runShortcut(true);
                    }

                    @Override
                    public void onConfigure() {
                        ShortcutSettingsComposeDialog.show(GameDetailFragment.this, shortcut);
                    }

                    @Override
                    public void onArguments() {
                        runContainer();
                    }

                    @Override
                    public void onGameFolder() {
                        Toast.makeText(requireContext(), shortcut.container.getDesktopDir().getPath(),
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onFavorite(boolean favorite) {
                        shortcut.putExtra("favorite", favorite ? "1" : "0");
                        shortcut.saveData();
                    }

                    @Override
                    public void onComponents() {
                        com.winlator.cmod.ui.components.WindowsComponentsDialog.showForGame(requireContext(), shortcut.container,
                                com.winlator.cmod.ui.components.WindowsComponentsDialog.exeOf(shortcut));
                    }

                    @Override
                    public void onPresets() {
                        CommunityPresetsDialog.show(requireContext(), shortcut);
                    }

                    @Override
                    public void onRemove() {
                        android.content.Context ctx = requireContext();
                        com.winlator.cmod.store.ShortcutRemoval.ask(ctx, shortcut, () -> {
                            ShortcutsFragment.disableShortcutOnScreen(ctx, shortcut);
                            if (isAdded()) getParentFragmentManager().popBackStack();
                        });
                    }
                }
        );
        content.post(this::applyDetailChrome);
        return content;
    }

    private String buildEnvironmentSubtitle() {
        String runtime = shortcut.container.getWineVersion();
        try {
            ContentsManager contents = new ContentsManager(requireContext());
            contents.syncContents();
            WineInfo info = WineInfo.fromIdentifier(requireContext(), contents, runtime);
            String version = info.fullVersion();
            if (version.endsWith(".0")) version = version.substring(0, version.length() - 2);
            runtime = ("proton".equalsIgnoreCase(info.type) ? "Proton " : "Wine ") + version + " " + info.getArch();
        } catch (Exception ignored) {}
        String renderer = shortcut.getUseDisplayX() ? "DisplayX" : shortcut.getRendererNative() ? "EGL" : "Vulkan";
        return runtime + "  •  " + renderer;
    }

    private void runShortcut(boolean asAdmin) {
        Activity activity = requireActivity();
        if (!XrActivity.isEnabled(requireContext())) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            if (asAdmin) intent.putExtra(com.winlator.cmod.session.AdminRun.EXTRA, true);
            intent.putExtra("container_id", shortcut.container.id);
            intent.putExtra("shortcut_path", shortcut.file.getPath());
            intent.putExtra("shortcut_name", shortcut.name);
            intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
            intent.putExtra("native_rendering", shortcut.getRendererNative());
            activity.startActivity(intent);
        } else {
            XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }
    }

    private void runContainer() {
        Activity activity = requireActivity();
        if (!XrActivity.isEnabled(requireContext())) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            activity.startActivity(intent);
        } else {
            XrActivity.openIntent(activity, shortcut.container.id, null);
        }
    }

    /**
     * Portrait: the page slides in from the right over the library and back out to the right,
     * on the "Game page" motion (ui/Motion, tunable). The XML animations set by the library are
     * only markers that a transition is wanted; landscape keeps them.
     */
    @Override
    public android.view.animation.Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        if (nextAnim == 0 || isLandscape()) return super.onCreateAnimation(transit, enter, nextAnim);
        com.winlator.cmod.ui.MotionCurve c = com.winlator.cmod.ui.Motion.curve(com.winlator.cmod.ui.MotionKind.PAGE);
        android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
        set.setInterpolator(c.interpolator());
        float from = enter ? 0.08f : 0f, to = enter ? 0f : 0.08f;
        set.addAnimation(new android.view.animation.AlphaAnimation(enter ? 0f : 1f, enter ? 1f : 0f));
        set.addAnimation(new android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_SELF, from, android.view.animation.Animation.RELATIVE_TO_SELF, to,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0f, android.view.animation.Animation.RELATIVE_TO_SELF, 0f));
        // Leaving is quicker than arriving: the way back shouldn't make you wait.
        set.setDuration(enter ? c.getMs() : Math.max(1, Math.round(c.getMs() * 0.6f)));
        return set;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void applyDetailChrome() {
        if (!(getActivity() instanceof MainActivity)) return;
        // Portrait: the page is a layer over the top bar and leaves the bars alone (hiding them
        // resized the library under it, which then jumped on the way back).
        if (!isLandscape()) return;
        MainActivity activity = (MainActivity) getActivity();
        activity.setDetailMode(true);
        activity.setBottomNavigationVisible(false);
        activity.setMainToolbarVisible(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        applyDetailChrome();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

}
