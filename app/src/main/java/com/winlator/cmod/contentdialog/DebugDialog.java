package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.widget.LogView;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class DebugDialog extends ContentDialog implements Callback<String> {
    private final LogView logView;
    private static boolean paused = false;
    private BufferedWriter writer;
    private int unflushed = 0;
    private long lastFlushMs = 0;

    public DebugDialog(@NonNull Context context) {
        super(context, R.layout.debug_dialog);
        setIcon(R.drawable.icon_debug);
        setTitle(context.getString(R.string.logs));
        logView = findViewById(R.id.LogView);
        
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        logView.getLayoutParams().width = (int)(dm.widthPixels * 0.86f);
        logView.getLayoutParams().height = (int)(dm.heightPixels * (dm.widthPixels > dm.heightPixels ? 0.58f : 0.62f));

       // findViewById(R.id.BTCancel).setVisibility(View.GONE);

        LinearLayout llBottomBarPanel = findViewById(R.id.LLBottomBarPanel);
        llBottomBarPanel.setVisibility(View.VISIBLE);

        View toolbarView = LayoutInflater.from(context).inflate(R.layout.debug_toolbar, llBottomBarPanel, false);
        toolbarView.findViewById(R.id.BTCopy).setOnClickListener((v) -> {
            String text = logView.getText(900_000);
            android.content.ClipboardManager cm = (android.content.ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && !text.isEmpty()) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("YurNLator log", text));
                android.widget.Toast.makeText(context, com.winlator.cmod.ui.UiStrings.get(R.string.l_log_copied, logView.getLineCount()), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        toolbarView.findViewById(R.id.BTClear).setOnClickListener((v) -> logView.clear());
        toolbarView.findViewById(R.id.BTPause).setOnClickListener((v) -> {
            setPaused(!paused);
            ((ImageButton)v).setImageResource(getPaused() ? R.drawable.icon_play : R.drawable.icon_pause);
        });
        llBottomBarPanel.addView(toolbarView);
        try {
            writer = new BufferedWriter(new FileWriter(logView.getLogFile(context)));
            // The tail (what the game printed before it closed) reaches the file within 0.5 s.
            java.util.concurrent.ScheduledExecutorService flusher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LogFlush"); t.setDaemon(true); return t;
            });
            final BufferedWriter w = writer;
            flusher.scheduleWithFixedDelay(() -> { try { w.flush(); } catch (IOException ignored) {} }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void call(final String line) {
        if (!getPaused()) logView.append(line+"\n");
        try {
            writer.write(line + "\n");
            // Flushing every line made heavy logs (+relay) crawl; flush in batches and often enough
            // that a crash loses at most a fraction of a second.
            long now = android.os.SystemClock.uptimeMillis();
            if (++unflushed >= 256 || now - lastFlushMs > 300) {
                writer.flush();
                unflushed = 0;
                lastFlushMs = now;
            }
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static void setPaused(boolean cond) {
        paused = cond;
    }
    
    public static boolean getPaused() {
        return paused;
    }
}
