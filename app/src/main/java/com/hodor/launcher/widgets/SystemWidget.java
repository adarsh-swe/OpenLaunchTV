package com.hodor.launcher.widgets;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * System widget — a self-contained bundle in the weather widget's
 * frosted-glass language. It reads the TV's own vitals on-device
 * (no server, no permissions): memory from ActivityManager,
 * storage from StatFs, network throughput from TrafficStats
 * deltas. Samples every 2 seconds while the launcher is in the
 * foreground. (No CPU: Android blocks apps from /proc/stat and
 * /proc/loadavg, so a CPU figure would be permanently dead.)
 *
 * Layout (1x1 cell, 218x100dp): MEMORY and STORAGE as a label +
 * bold percentage over a thin bar, then a NET row with live
 * down/up rates. Labels are small, bold, letter-spaced sans,
 * dimmed — the iOS-widget hierarchy the weather card uses.
 */
public class SystemWidget extends Widget {

    private static final long SAMPLE_MS = 2000L;
    private static final long FROST_MS = 150L;

    private static final int TEXT = Color.rgb(255, 255, 255);
    private static final int TEXT_DIM = Color.rgb(205, 212, 226);
    private static final int TEXT_FAINT = Color.rgb(160, 170, 190);
    private static final int TINT = Color.argb(26, 15, 21, 38);

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private boolean running;
    private volatile boolean frostBusy;

    private LinearLayout cardView;
    private TextView memView, diskView, netView;
    private BarView memBar, diskBar;

    // Previous samples for delta math.
    private long prevRx = -1, prevTx = -1, prevNetAt = 0;

    public SystemWidget(Context ctx) {
        super(ctx);
    }

    @Override public String id() { return "system"; }
    @Override public String description() { return "Memory, storage, network"; }
    @Override public int colSpan() { return 1; }
    @Override public int rowSpan() { return 1; }
    // Default home: A2, between the clock (A1) and the weather (A3+A4).
    @Override public int defaultCol() { return 1; }
    @Override public int defaultRow() { return 0; }
    @Override public int placementVersion() { return 3; }

    // ---- view ------------------------------------------------------

    @Override
    protected View buildView() {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(8), dp(15), dp(8));
        GradientDrawable flat = new GradientDrawable();
        flat.setColor(TINT);
        flat.setCornerRadius(dp(22));
        card.setBackground(flat);
        cardView = card;

        memView = valueText();
        memBar = new BarView(ctx);
        card.addView(statRow("MEMORY", memView, memBar, false));
        diskView = valueText();
        diskBar = new BarView(ctx);
        card.addView(statRow("STORAGE", diskView, diskBar, true));

        LinearLayout net = new LinearLayout(ctx);
        net.setOrientation(LinearLayout.HORIZONTAL);
        net.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams netLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        netLp.setMargins(0, dp(8), 0, 0);
        net.setLayoutParams(netLp);
        net.addView(label("NET"));
        netView = new TextView(ctx);
        netView.setText("↓ –––   ↑ –––");
        netView.setTextSize(13);
        netView.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        netView.setTextColor(TEXT);
        netView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nvLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        nvLp.setMargins(dp(10), 0, 0, 0);
        netView.setLayoutParams(nvLp);
        net.addView(netView);
        card.addView(net);
        return card;
    }

    /** One stat: label + value on a row, thin bar underneath. */
    private LinearLayout statRow(String name, TextView value, BarView bar,
                                 boolean spaced) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (spaced) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(8), 0, 0);
            wrap.setLayoutParams(lp);
        }
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(name));
        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(spacer);
        value.setText("––%");
        row.addView(value);
        wrap.addView(row);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        barLp.setMargins(0, dp(4), 0, 0);
        bar.setLayoutParams(barLp);
        wrap.addView(bar);
        return wrap;
    }

    private TextView valueText() {
        TextView t = new TextView(ctx);
        t.setTextSize(15);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        t.setTextColor(TEXT);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(10);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        t.setLetterSpacing(0.18f);
        t.setTextColor(TEXT_DIM);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Thin rounded bar: translucent track, white fill. */
    private class BarView extends View {
        private float fraction = 0f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarView(Context c) { super(c); }

        void setFraction(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), w = getWidth(), r = h / 2f;
            paint.setColor(Color.argb(55, 255, 255, 255));
            canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, paint);
            if (fraction > 0.01f) {
                paint.setColor(Color.argb(235, 255, 255, 255));
                canvas.drawRoundRect(new RectF(0, 0, Math.max(h, w * fraction), h), r, r, paint);
            }
        }
    }

    // ---- lifecycle / sampling ---------------------------------------

    @Override
    public void onStart() {
        if (thread == null) {
            thread = new HandlerThread("system-widget");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        running = true;
        prevRx = -1;
        worker.post(sampleLoop);
        main.removeCallbacks(frostLoop);
        main.postDelayed(frostLoop, 600);
    }

    @Override
    public void onStop() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        main.removeCallbacks(frostLoop);
    }

    private final Runnable sampleLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            sampleOnce();
            if (worker != null) worker.postDelayed(this, SAMPLE_MS);
        }
    };

    private void sampleOnce() {
        int memPct = -1;
        try {
            ActivityManager am = (ActivityManager)
                ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0) {
                memPct = (int) Math.round(
                    100.0 * (mi.totalMem - mi.availMem) / mi.totalMem);
            }
        } catch (Exception ignored) {
        }

        int diskPct = -1;
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long total = fs.getTotalBytes(), avail = fs.getAvailableBytes();
            if (total > 0) {
                diskPct = (int) Math.round(100.0 * (total - avail) / total);
            }
        } catch (Exception ignored) {
        }

        String net = null;
        try {
            long rx = TrafficStats.getTotalRxBytes();
            long tx = TrafficStats.getTotalTxBytes();
            long now = android.os.SystemClock.elapsedRealtime();
            if (rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED) {
                if (prevRx >= 0 && now > prevNetAt) {
                    double secs = (now - prevNetAt) / 1000.0;
                    net = "↓ " + formatRate((rx - prevRx) / secs)
                        + "   ↑ " + formatRate((tx - prevTx) / secs);
                }
                prevRx = rx; prevTx = tx; prevNetAt = now;
            }
        } catch (Exception ignored) {
        }

        final int fMem = memPct, fDisk = diskPct;
        final String fNet = net;
        main.post(() -> {
            if (fMem >= 0) {
                memView.setText(fMem + "%");
                memBar.setFraction(fMem / 100f);
            }
            if (fDisk >= 0) {
                diskView.setText(fDisk + "%");
                diskBar.setFraction(fDisk / 100f);
            }
            if (fNet != null) netView.setText(fNet);
        });
    }

    private static String formatRate(double bytesPerSec) {
        if (bytesPerSec < 1000) return String.format(Locale.US, "%.0f B/s", bytesPerSec);
        if (bytesPerSec < 1000 * 1000) return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1000);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1000 / 1000);
    }

    // ---- frost ------------------------------------------------------
    // Same recipe as the weather widget: a blurred, tinted snapshot
    // of the live background behind the card, retaken ~7x/second,
    // edges feathered. Kept inside this bundle — widgets own their
    // rendering end to end.

    private Bitmap frostMask;
    private int frostMaskW, frostMaskH;
    private Bitmap frostBitmap;

    private final Runnable frostLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshFrost();
            main.postDelayed(this, FROST_MS);
        }
    };

    private void refreshFrost() {
        if (frostBusy || cardView == null || backgroundView == null) return;
        final int w = cardView.getWidth(), h = cardView.getHeight();
        final int vw = backgroundView.getWidth(), vh = backgroundView.getHeight();
        if (w == 0 || h == 0 || vw == 0 || vh == 0) return;
        final Bitmap frame;
        try {
            frame = backgroundView.getBitmap(
                Math.max(2, vw / 3), Math.max(2, vh / 3));
        } catch (Exception e) {
            return;
        }
        if (frame == null) return;
        int[] loc = new int[2];
        cardView.getLocationOnScreen(loc);
        float sx = frame.getWidth() / (float) vw;
        float sy = frame.getHeight() / (float) vh;
        final int cx = Math.round(loc[0] * sx), cy = Math.round(loc[1] * sy);
        final int cw = Math.round(w * sx), ch = Math.round(h * sy);
        if (cx < 0 || cy < 0 || cx + cw > frame.getWidth()
                || cy + ch > frame.getHeight()
                || isDark(frame, cx, cy, cw, ch)) {
            frame.recycle();
            return;
        }
        frostBusy = true;
        new Thread(() -> {
            Bitmap result = null;
            try {
                result = makeFrost(frame, cx, cy, cw, ch, w, h);
            } catch (Exception ignored) {
            }
            frame.recycle();
            final Bitmap out = result;
            main.post(() -> {
                frostBusy = false;
                if (out == null) return;
                if (running && cardView != null) {
                    Bitmap prev = frostBitmap;
                    cardView.setBackground(
                        new BitmapDrawable(ctx.getResources(), out));
                    frostBitmap = out;
                    if (prev != null && prev != out) prev.recycle();
                } else {
                    out.recycle();
                }
            });
        }, "system-frost").start();
    }

    private Bitmap makeFrost(Bitmap frame, int cx, int cy, int cw, int ch,
                             int w, int h) {
        Bitmap crop = Bitmap.createBitmap(frame, cx, cy, cw, ch);
        Bitmap small = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas smallCanvas = new Canvas(small);
        Paint satPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.25f);
        satPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        smallCanvas.drawBitmap(crop, 0, 0, satPaint);
        crop.recycle();
        boxBlur(small, 5);
        Bitmap big = Bitmap.createScaledBitmap(small, w, h, true);
        small.recycle();

        Canvas canvas = new Canvas(big);
        canvas.drawColor(TINT);
        Paint maskPaint = new Paint();
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(frostMask(w, h), 0, 0, maskPaint);
        return big;
    }

    private Bitmap frostMask(int w, int h) {
        if (frostMask != null && frostMaskW == w && frostMaskH == h) {
            return frostMask;
        }
        if (frostMask != null) frostMask.recycle();
        Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(mask);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        float r = dp(22);
        maskCanvas.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        boxBlur(mask, 4);
        frostMask = mask;
        frostMaskW = w;
        frostMaskH = h;
        return mask;
    }

    private static boolean isDark(Bitmap bmp, int x, int y, int w, int h) {
        int[][] pts = {
            {x + w / 2, y + h / 2},
            {x + w / 4, y + h / 2},
            {x + 3 * w / 4, y + h / 2},
            {x + w / 2, y + h / 4},
            {x + w / 2, y + 3 * h / 4},
        };
        for (int[] pt : pts) {
            int px = bmp.getPixel(pt[0], pt[1]);
            if (Color.red(px) > 12 || Color.green(px) > 12 || Color.blue(px) > 12) {
                return false;
            }
        }
        return true;
    }

    private static void boxBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (radius <= 0 || w < 2 || h < 2) return;
        int[] src = new int[w * h];
        int[] dst = new int[w * h];
        bmp.getPixels(src, 0, w, 0, 0, w, h);
        blurPass(src, dst, w, h, radius, true);
        blurPass(dst, src, w, h, radius, false);
        bmp.setPixels(src, 0, w, 0, 0, w, h);
    }

    private static void blurPass(int[] src, int[] dst, int w, int h,
                                 int radius, boolean horizontal) {
        int n = 2 * radius + 1;
        int outer = horizontal ? h : w;
        int inner = horizontal ? w : h;
        for (int o = 0; o < outer; o++) {
            long a = 0, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = src[pixIdx(o, clamp(i, inner), w, horizontal)];
                a += p >>> 24;
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int i = 0; i < inner; i++) {
                dst[pixIdx(o, i, w, horizontal)] = ((int) (a / n) << 24)
                    | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
                int outP = src[pixIdx(o, clamp(i - radius, inner), w, horizontal)];
                int inP = src[pixIdx(o, clamp(i + radius + 1, inner), w, horizontal)];
                a += (inP >>> 24) - (outP >>> 24);
                r += ((inP >> 16) & 0xFF) - ((outP >> 16) & 0xFF);
                g += ((inP >> 8) & 0xFF) - ((outP >> 8) & 0xFF);
                b += (inP & 0xFF) - (outP & 0xFF);
            }
        }
    }

    private static int pixIdx(int outer, int inner, int w, boolean horizontal) {
        return horizontal ? outer * w + inner : inner * w + outer;
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : (v >= size ? size - 1 : v);
    }
}
