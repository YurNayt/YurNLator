package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Environment;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.math.Mathf;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;

public class LogView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<String> lines = new ArrayList<>();
    private static final int MAX_LINES = 5000;
    private final float rowHeight = UnitUtils.dpToPx(22);
    private final float defaultTextSize = UnitUtils.dpToPx(12.5f);
    private final float padX = UnitUtils.dpToPx(8);
    // The emulator's dark look: near-black rows, light text, the accent for the time, red / amber for
    // errors / warnings.
    private static final int BG = 0xFF0C0E13, BG_ALT = 0xFF12151B, TEXT = 0xFFE4E6EB, MUTED = 0xFF9699A3;
    private static final int ERROR = 0xFFF26672, WARN = 0xFFE5B85C;
    private int accent = 0;
    private final float minScrollThumbSize = UnitUtils.dpToPx(6);
    private final PointF lastPoint = new PointF();
    private final PointF scrollPosition = new PointF();
    private final PointF scrollSize = new PointF();
    private boolean isActionDown = false;
    private static String fileName;
    private boolean scrollingHorizontally = false;
    private boolean scrollingVertically = false;
    private final Object lock = new Object();

    public LogView(Context context) {
        this(context, null);
    }

    public LogView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LogView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public LogView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeScrollSize();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) return;
        
        synchronized (lock) {
            paint.setStyle(Paint.Style.FILL);

            if (lines.isEmpty()) {
                canvas.drawColor(BG);
                paint.setTextSize(UnitUtils.dpToPx(16));
                paint.setColor(MUTED);
                String text = getContext().getString(R.string.no_items_to_display);
                float centerX = (width - paint.measureText(text)) * 0.5f;
                float centerY = (height - paint.getFontSpacing()) * 0.5f - paint.ascent();
                canvas.drawText(text, centerX, centerY, paint);
                return;
            }

            paint.setTextSize(defaultTextSize);
            paint.setTypeface(android.graphics.Typeface.MONOSPACE);
            float textHeight = paint.getFontSpacing();
            if (accent == 0) {
                int a = 0;
                try { a = com.winlator.cmod.ui.theme.WinlatorThemeManager.INSTANCE.accent(getContext()); } catch (Throwable ignored) {}
                accent = a != 0 ? (a | 0xFF000000) : 0xFF7AB8FF;
            }
            canvas.drawColor(BG);

            float rowY = -scrollPosition.y;

            for (int i = 0, count = lines.size(); i < count; i++) {
                if ((rowY + rowHeight) < 0 || rowY >= height) {
                    rowY += rowHeight;
                    continue;
                }

                if ((i % 2) != 0) {
                    paint.setColor(BG_ALT);
                    canvas.drawRect(0, rowY, width, rowY + rowHeight, paint);
                }

                String line = lines.get(i);
                float centerY = (rowY - paint.ascent()) + (rowHeight - textHeight) * 0.5f;
                float x = padX - scrollPosition.x;
                int stamp = line.startsWith("[") ? line.indexOf("]  ") : -1;
                if (stamp > 0) {
                    String time = line.substring(0, stamp + 1);
                    paint.setColor(accent);
                    canvas.drawText(time, x, centerY, paint);
                    x += paint.measureText(time + "  ");
                    line = line.substring(stamp + 3);
                }
                String low = line.toLowerCase(java.util.Locale.ROOT);
                if (low.contains("err:") || low.contains("error") || low.contains("fatal") || low.contains("crash")) paint.setColor(ERROR);
                else if (low.contains("warn") || low.contains("fixme:")) paint.setColor(WARN);
                else paint.setColor(TEXT);
                canvas.drawText(line, x, centerY, paint);
                rowY += rowHeight;
            }
             
            drawScrollThumbs(canvas);
        }
    }

    private void drawScrollThumbs(Canvas canvas) {
        float scrollThumbX = getScrollThumbX();
        float scrollThumbY = getScrollThumbY();
        float scrollThumbWidth = getScrollThumbWidth();
        float scrollThumbHeight = getScrollThumbHeight();

        paint.setColor(0x55FFFFFF);
        float radius = minScrollThumbSize * 0.5f;

        canvas.drawRoundRect(scrollThumbX, getHeight() - minScrollThumbSize, scrollThumbX + scrollThumbWidth, getHeight(), radius, radius, paint);
        canvas.drawRoundRect(getWidth() - minScrollThumbSize, scrollThumbY, getWidth(), scrollThumbY + scrollThumbHeight, radius, radius, paint);
    }

    public float getScrollMaxLeft() {
        return Math.max(0, scrollSize.x - getWidth());
    }

    public float getScrollMaxTop() {
        return Math.max(0, scrollSize.y - getHeight());
    }

    public float getScrollThumbX() {
        float width = getWidth();
        if (scrollSize.x > 0 && scrollSize.x > width) return scrollPosition.x * (width / scrollSize.x);
        return -Float.MAX_VALUE;
    }

    public float getScrollThumbY() {
        float height = getHeight();
        if (scrollSize.y > 0 && scrollSize.y > height) return scrollPosition.y * (height / scrollSize.y);
        return -Float.MAX_VALUE;
    }

    public float getScrollThumbWidth() {
        float width = getWidth();
        if (scrollSize.x > 0 && scrollSize.x > width) {
            return Math.max(width - width * (getScrollMaxLeft() / scrollSize.x), minScrollThumbSize);
        }
        return 0;
    }

    public float getScrollThumbHeight() {
        float height = getHeight();
        if (scrollSize.y > 0 && scrollSize.y > height) {
            return Math.max(height - height * (getScrollMaxTop() / scrollSize.y), minScrollThumbSize);
        }
        return 0;
    }

    private void computeScrollSize() {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        float maxWidth = 0;
        paint.setTextSize(defaultTextSize);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        for (int i = 0, count = lines.size(); i < count; i++) maxWidth = Math.max(paint.measureText(lines.get(i)), maxWidth);
        scrollSize.x = Math.max(maxWidth + padX * 2, width);
        scrollSize.y = Math.max(rowHeight * lines.size(), height);
        scrollPosition.set(0, getScrollMaxTop());
    }

    /** The lines on screen as text (the newest [maxChars] at most), for copying. */
    public String getText(int maxChars) {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.size() - 1; i >= 0; i--) {
                String l = lines.get(i);
                if (sb.length() + l.length() + 1 > maxChars) break;
                sb.insert(0, l + "\n");
            }
            return sb.toString();
        }
    }

    public int getLineCount() {
        synchronized (lock) { return lines.size(); }
    }

    public void clear() {
        synchronized (lock) {
            lines.clear();
        }
        postInvalidate();
    }

    public void append(String line) {
        synchronized (lock) {
            lines.add("["+DateFormat.format("HH:mm:ss", System.currentTimeMillis())+"]  "+line.replace("\n", ""));
            // The view keeps the latest lines only (the log file has them all): +relay logs ran
            // into hundreds of thousands of lines and the app out of memory.
            if (lines.size() > MAX_LINES) lines.subList(0, lines.size() - MAX_LINES + MAX_LINES / 5).clear();
            computeScrollSize();
        }
    }

    public static void setFilename(String file) {
        fileName = file.substring(0, file.lastIndexOf("."));
    }

    public static File getLogFile(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String winlatorPath = sp.getString("winlator_path_uri", null);
        File logsDir;

        if (winlatorPath != null) {
            Uri winlatorUri = Uri.parse(winlatorPath);
            logsDir = new File(FileUtils.getFilePathFromUri(context, winlatorUri), "logs");
        }
        else {
            logsDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "logs");
        }

        if (!logsDir.exists())
            logsDir.mkdirs();

        String logFile = fileName.replaceAll("\\s", "_").toLowerCase() + "_" + DateFormat.format("yyyy-MM-dd_HH-mm-ss", new Date()) + ".txt";
        return new File(logsDir, logFile);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastPoint.set(event.getX(), event.getY());
                isActionDown = true;
                scrollingHorizontally = false;
                scrollingVertically = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (isActionDown) {
                    float dx = event.getX() - lastPoint.x;
                    float dy = event.getY() - lastPoint.y;

                    if (Math.abs(dx) > 10) scrollingHorizontally = true;
                    if (Math.abs(dy) > 10) scrollingVertically = true;

                    if (scrollingHorizontally) {
                        DebugDialog.setPaused(true);
                        scrollPosition.x = Mathf.clamp(scrollPosition.x - dx, 0, getScrollMaxLeft());
                        lastPoint.set(event.getX(), event.getY());
                        invalidate();
                    }

                    if (scrollingVertically) {
                        DebugDialog.setPaused(true);
                        scrollPosition.y = Mathf.clamp(scrollPosition.y - dy, 0, getScrollMaxTop());
                        lastPoint.set(event.getX(), event.getY());
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                DebugDialog.setPaused(false);
                isActionDown = false;
                break;
        }

        return true;
    }
}
