package com.hodor.launcher.widgets;

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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Weather widget — a self-contained bundle. It queries Open-Meteo
 * itself (no API key), on its own thread, every 30 minutes while the
 * launcher is in the foreground, and renders a frosted card: big
 * temperature, place / condition / hi-lo, then three forecast
 * columns. On any fetch failure it simply keeps the last good data
 * on screen.
 *
 * Design (2026-11-16): anchored in the top-right corner
 * itself (the settings gear that used to sit there is now a tile in
 * the overflow section), balancing the clock across the top of the
 * screen.
 * Type is Bebas Neue throughout — the launcher's own face — with the
 * date line's letterspaced caps, so the card reads as part of the
 * launcher. The frost is real: the card's background is a blurred,
 * tinted snapshot of the live background video behind it, refreshed
 * every 15 seconds, edges feathered so the card melts into the
 * scene while staying clearly present.
 *
 * Occupies 2x1 cells (446x100dp).
 */
public class WeatherWidget extends Widget {

    // ---- widget configuration -------------------------------------
    private static final String PLACE = "Menlo Park";
    private static final double LAT = 37.4530;
    private static final double LON = -122.1817;
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long FROST_MS = 15 * 1000L;
    private static final String API =
        "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
        + "&current=temperature_2m,weather_code"
        + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
        + "&forecast_days=3&timezone=America%2FLos_Angeles";

    // ---- palette ----------------------------------------------------
    private static final int TEXT = Color.rgb(255, 255, 255);
    private static final int TEXT_DIM = Color.rgb(205, 212, 226);
    private static final int TEXT_FAINT = Color.rgb(160, 170, 190);
    /** The frost tint: a faint dark veil over the blurred scene. */
    private static final int TINT = Color.argb(26, 15, 21, 38);

    private final Typeface bebas;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private boolean running;
    private volatile boolean frostBusy;

    private LinearLayout cardView;
    private TextView tempView, placeView, condView, hiloView;
    private final TextView[] dayViews = new TextView[3];
    private final TextView[] glyphViews = new TextView[3];
    private final TextView[] rangeViews = new TextView[3];

    public WeatherWidget(Context ctx) {
        super(ctx);
        bebas = Typeface.createFromAsset(ctx.getAssets(), "fonts/bebas-neue.ttf");
    }

    @Override public String id() { return "weather"; }
    @Override public int colSpan() { return 2; }
    @Override public int rowSpan() { return 1; }
    @Override public int anchor() { return ANCHOR_TOP_RIGHT; }

    // ---- view ------------------------------------------------------

    @Override
    protected View buildView() {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(8), dp(18), dp(8));
        // Flat tint until the first frost snapshot lands.
        GradientDrawable flat = new GradientDrawable();
        flat.setColor(TINT);
        flat.setCornerRadius(dp(22));
        card.setBackground(flat);
        cardView = card;

        tempView = text("––°", 59, bebas, TEXT);
        card.addView(tempView);

        LinearLayout stack = new LinearLayout(ctx);
        stack.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stackLp.setMargins(dp(16), 0, 0, 0);
        stack.setLayoutParams(stackLp);
        placeView = text(PLACE.toUpperCase(Locale.US), 18, bebas, TEXT);
        placeView.setLetterSpacing(0.06f);
        condView = text(" ", 15, bebas, TEXT_DIM);
        condView.setLetterSpacing(0.06f);
        hiloView = text(" ", 15, bebas, TEXT_DIM);
        hiloView.setLetterSpacing(0.06f);
        stack.addView(placeView);
        stack.addView(condView);
        stack.addView(hiloView);
        card.addView(stack);

        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        card.addView(spacer);

        for (int i = 0; i < 3; i++) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                dp(52), LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) colLp.setMargins(dp(10), 0, 0, 0);
            col.setLayoutParams(colLp);
            dayViews[i] = text(" ", 13, bebas, TEXT_DIM);
            dayViews[i].setGravity(Gravity.CENTER);
            dayViews[i].setLetterSpacing(0.06f);
            glyphViews[i] = text(" ", 19, Typeface.SANS_SERIF, TEXT);
            glyphViews[i].setGravity(Gravity.CENTER);
            rangeViews[i] = text(" ", 13, bebas, TEXT_FAINT);
            rangeViews[i].setGravity(Gravity.CENTER);
            rangeViews[i].setLetterSpacing(0.06f);
            col.addView(dayViews[i]);
            col.addView(glyphViews[i]);
            col.addView(rangeViews[i]);
            card.addView(col);
        }
        return card;
    }

    private TextView text(String s, int sp, Typeface face, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ---- lifecycle / fetching --------------------------------------

    @Override
    public void onStart() {
        if (thread == null) {
            thread = new HandlerThread("weather-widget");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        running = true;
        worker.post(fetchLoop);
        main.removeCallbacks(frostLoop);
        main.postDelayed(frostLoop, 600);
    }

    @Override
    public void onStop() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        main.removeCallbacks(frostLoop);
    }

    private final Runnable fetchLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            fetchOnce();
            if (worker != null) worker.postDelayed(this, REFRESH_MS);
        }
    };

    private void fetchOnce() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject cur = root.getJSONObject("current");
            JSONObject daily = root.getJSONObject("daily");
            JSONArray dates = daily.getJSONArray("time");
            JSONArray max = daily.getJSONArray("temperature_2m_max");
            JSONArray min = daily.getJSONArray("temperature_2m_min");
            JSONArray codes = daily.getJSONArray("weather_code");
            final int temp = (int) Math.round(cur.getDouble("temperature_2m"));
            final int code = cur.getInt("weather_code");
            final String[] days = new String[3];
            final int[] his = new int[3], los = new int[3], dayCodes = new int[3];
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.US);
            for (int i = 0; i < 3; i++) {
                Date d = in.parse(dates.getString(i));
                days[i] = out.format(d).toUpperCase(Locale.US);
                his[i] = (int) Math.round(max.getDouble(i));
                los[i] = (int) Math.round(min.getDouble(i));
                dayCodes[i] = codes.getInt(i);
            }
            main.post(() -> {
                tempView.setText(temp + "°");
                condView.setText(condition(code).toUpperCase(Locale.US));
                hiloView.setText("H:" + his[0] + "°  L:" + los[0] + "°");
                for (int i = 0; i < 3; i++) {
                    dayViews[i].setText(days[i]);
                    glyphViews[i].setText(glyph(dayCodes[i]));
                    rangeViews[i].setText(his[i] + "° " + los[i] + "°");
                }
            });
        } catch (Exception ignored) {
            // Keep the last good data on screen.
        }
    }

    // ---- frost ------------------------------------------------------
    // The card background is a snapshot of the background video taken
    // from exactly the card's rectangle: downscaled hard (the
    // downscale itself does most of the softening), box-blurred,
    // tinted, then cut out with a feathered rounded-rect alpha mask.

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
        if (w == 0 || h == 0) return;
        final Bitmap frame;
        try {
            frame = backgroundView.getBitmap();
        } catch (Exception e) {
            return;
        }
        if (frame == null) return;
        int[] loc = new int[2];
        cardView.getLocationOnScreen(loc);
        final int x = loc[0], y = loc[1];
        if (x < 0 || y < 0 || x + w > frame.getWidth() || y + h > frame.getHeight()
                || isDark(frame, x, y, w, h)) {
            // Video not producing frames yet — keep the flat tint.
            frame.recycle();
            return;
        }
        frostBusy = true;
        new Thread(() -> {
            Bitmap result = null;
            try {
                result = makeFrost(frame, x, y, w, h);
            } catch (Exception ignored) {
            }
            frame.recycle();
            final Bitmap out = result;
            main.post(() -> {
                frostBusy = false;
                if (out == null) return;
                if (running && cardView != null) {
                    cardView.setBackground(
                        new BitmapDrawable(ctx.getResources(), out));
                } else {
                    out.recycle();
                }
            });
        }, "weather-frost").start();
    }

    private Bitmap makeFrost(Bitmap frame, int x, int y, int w, int h) {
        Bitmap crop = Bitmap.createBitmap(frame, x, y, w, h);
        // Recipe B (picked 2026-11-16): a lighter hand — /3
        // downscale with saturation lifted 1.25, one blur pass, tint
        // alpha 26 — so the blurred scene reads through the card
        // instead of flattening into a tinted slab.
        int sw = Math.max(2, w / 3), sh = Math.max(2, h / 3);
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas smallCanvas = new Canvas(small);
        Paint satPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.25f);
        satPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        smallCanvas.drawBitmap(crop, null, new RectF(0, 0, sw, sh), satPaint);
        crop.recycle();
        boxBlur(small, 5);
        Bitmap big = Bitmap.createScaledBitmap(small, w, h, true);
        small.recycle();

        Canvas canvas = new Canvas(big);
        canvas.drawColor(TINT);

        Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(mask);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        float r = dp(22);
        maskCanvas.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        boxBlur(mask, 4);
        Paint maskPaint = new Paint();
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(mask, 0, 0, maskPaint);
        mask.recycle();
        return big;
    }

    /** True when the sampled points are all near-black (no video yet). */
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

    /** Separable box blur, two passes (horizontal then vertical). */
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

    // ---- WMO weather-code mapping -----------------------------------

    private static String condition(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mainly Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing Drizzle";
            case 61: case 63: case 65: return "Rain";
            case 66: case 67: return "Freezing Rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Rain Showers";
            case 85: case 86: return "Snow Showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, Hail";
            default: return " ";
        }
    }

    private static String glyph(int code) {
        switch (code) {
            case 0: case 1: return "☀";
            case 95: case 96: case 99: return "⚡";
            case 71: case 73: case 75: case 77: case 85: case 86: return "❄";
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return "☂";
            default: return "☁";
        }
    }
}
