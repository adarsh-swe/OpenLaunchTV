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
 * launcher is in the foreground, and renders a frosted card: a
 * pictogram and big temperature, place / condition / feels-like,
 * then three forecast columns. On any fetch failure it simply
 * keeps the last good data on screen.
 *
 * Design (2026-11-16): anchored in the top-right corner itself
 * (the settings gear that used to sit there is now a tile in the
 * overflow section), balancing the clock across the top of the
 * screen. The content follows the iOS Weather widget's clarity
 * (reference photo, 2026-11-16): filled white pictograms drawn
 * on canvas instead of unicode glyphs, the condition in a bold
 * sans sentence case, and a Feels-like line. Temperature and
 * place stay Bebas Neue — the launcher's own face — so the card
 * still reads as part of the launcher. The frost is real: the
 * card's background is a blurred, tinted snapshot of the live
 * background video behind it, refreshed ~7 times a second so the
 * scene keeps flowing behind the glass, edges
 * feathered so the card melts into the scene while staying
 * clearly present.
 *
 * Occupies 2x1 cells (446x100dp).
 */
public class WeatherWidget extends Widget {

    // ---- widget configuration -------------------------------------
    private static final String PLACE = "Menlo Park";
    private static final double LAT = 37.4530;
    private static final double LON = -122.1817;
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long FROST_MS = 150L;
    private static final String API =
        "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
        + "&current=temperature_2m,apparent_temperature,weather_code"
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
    private WeatherIcon iconView;
    private TextView tempView, placeView, condView, hiloView;
    private final TextView[] dayViews = new TextView[3];
    private final WeatherIcon[] dayIcons = new WeatherIcon[3];
    private final TextView[] rangeViews = new TextView[3];

    public WeatherWidget(Context ctx) {
        super(ctx);
        bebas = Typeface.createFromAsset(ctx.getAssets(), "fonts/bebas-neue.ttf");
    }

    @Override public String id() { return "weather"; }
    @Override public int colSpan() { return 2; }
    @Override public int rowSpan() { return 1; }
    @Override public int anchor() { return ANCHOR_TOP_RIGHT; }

    // The drawn card frames the clock block's measured glyph
    // extent (TV frame, 2026-11-16: time glyphs 40.5dp, date
    // glyphs end 133dp) with ~4dp of air on each side, so the two
    // header blocks read as one uniform band. (An exact-extent
    // 93dp card read as shorter than the clock — the frost's
    // feathered edge eats ~2dp of visible body per side.)
    // Placement stays cell-based. Re-measure if the clock's
    // type sizes change.
    @Override public int topInsetDp() { return 8; }
    @Override public int heightDp() { return 101; }

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

        iconView = new WeatherIcon(ctx);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(54), dp(54)));
        card.addView(iconView);

        tempView = text("––°", 59, bebas, TEXT);
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tempLp.setMargins(dp(6), 0, 0, 0);
        tempView.setLayoutParams(tempLp);
        card.addView(tempView);

        LinearLayout stack = new LinearLayout(ctx);
        stack.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stackLp.setMargins(dp(16), 0, 0, 0);
        stack.setLayoutParams(stackLp);
        placeView = text(PLACE.toUpperCase(Locale.US), 18, bebas, TEXT);
        placeView.setLetterSpacing(0.06f);
        condView = text(" ", 15, Typeface.SANS_SERIF, TEXT);
        condView.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        hiloView = text(" ", 11, Typeface.SANS_SERIF, TEXT_DIM);
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
            dayIcons[i] = new WeatherIcon(ctx);
            dayIcons[i].setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            rangeViews[i] = text(" ", 13, bebas, TEXT_FAINT);
            rangeViews[i].setGravity(Gravity.CENTER);
            rangeViews[i].setLetterSpacing(0.06f);
            col.addView(dayViews[i]);
            col.addView(dayIcons[i]);
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
            final int feels = (int) Math.round(cur.getDouble("apparent_temperature"));
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
                iconView.setCode(code);
                tempView.setText(temp + "°");
                condView.setText(sentence(condition(code)));
                hiloView.setText("Feels " + feels + "° · H:" + his[0] + "° L:" + los[0] + "°");
                for (int i = 0; i < 3; i++) {
                    dayViews[i].setText(days[i]);
                    dayIcons[i].setCode(dayCodes[i]);
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
    // Snapshots retake ~7x/second so the scene keeps flowing behind
    // the glass instead of freezing into a uniform wash. The readback
    // is done at 1/3 scale (the frost's working size anyway), the
    // feather mask is cached, and the previous frame is recycled on
    // swap, so the faster cycle stays cheap.

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
            // Read the surface back at the frost's 1/3 working scale:
            // same pixels the pipeline would downscale to, a ninth
            // of the readback cost of a full frame.
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
            // Video not producing frames yet — keep the flat tint.
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
        }, "weather-frost").start();
    }

    private Bitmap makeFrost(Bitmap frame, int cx, int cy, int cw, int ch,
                             int w, int h) {
        Bitmap crop = Bitmap.createBitmap(frame, cx, cy, cw, ch);
        // Recipe B (picked 2026-11-16): a lighter hand — /3
        // downscale with saturation lifted 1.25, one blur pass, tint
        // alpha 26 — so the blurred scene reads through the card
        // instead of flattening into a tinted slab. The frame already
        // arrives at the /3 scale, so the crop is the working size.
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

    /** The feathered rounded-rect alpha mask, built once per size. */
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

    /** "Mainly Clear" -> "Mainly clear" (iOS-widget sentence case). */
    private static String sentence(String s) {
        if (s == null || s.length() < 2) return s;
        return s.substring(0, 1) + s.substring(1).toLowerCase(Locale.US);
    }

    /**
     * Filled white weather pictogram, drawn on canvas — the iOS
     * Weather widget's icon language (sun, cloud, rain, snow,
     * thunder, fog) instead of unicode glyphs, which rendered thin
     * and inconsistent at TV distance. Same shapes as the preview
     * harness's draw_icon.
     */
    static class WeatherIcon extends View {
        private int code = -1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        WeatherIcon(Context ctx) {
            super(ctx);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        void setCode(int c) {
            if (c != code) {
                code = c;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (code < 0) return;
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            switch (code) {
                case 0:
                    sun(canvas, cx, cy, s * 0.30f);
                    break;
                case 1: case 2:
                    sun(canvas, cx - s * 0.20f, cy - s * 0.20f, s * 0.20f);
                    cloud(canvas, cx + s * 0.06f, cy + s * 0.14f, s * 0.78f);
                    break;
                case 45: case 48:
                    cloud(canvas, cx, cy - s * 0.12f, s * 0.92f);
                    stroke.setStrokeWidth(Math.max(2f, s * 0.07f));
                    canvas.drawLine(cx - s * 0.30f, cy + s * 0.30f,
                        cx + s * 0.30f, cy + s * 0.30f, stroke);
                    canvas.drawLine(cx - s * 0.30f, cy + s * 0.46f,
                        cx + s * 0.30f, cy + s * 0.46f, stroke);
                    break;
                case 71: case 73: case 75: case 77: case 85: case 86:
                    cloud(canvas, cx, cy - s * 0.16f, s * 0.92f);
                    for (float dx : new float[]{-0.24f, 0f, 0.24f}) {
                        canvas.drawCircle(cx + s * dx, cy + s * 0.36f, s * 0.055f, fill);
                    }
                    break;
                case 95: case 96: case 99: {
                    cloud(canvas, cx, cy - s * 0.18f, s * 0.92f);
                    float bx = cx + s * 0.02f, by = cy + s * 0.16f;
                    android.graphics.Path bolt = new android.graphics.Path();
                    bolt.moveTo(bx + s * 0.10f, by);
                    bolt.lineTo(bx - s * 0.12f, by + s * 0.30f);
                    bolt.lineTo(bx - s * 0.01f, by + s * 0.30f);
                    bolt.lineTo(bx - s * 0.10f, by + s * 0.52f);
                    bolt.lineTo(bx + s * 0.14f, by + s * 0.20f);
                    bolt.lineTo(bx + s * 0.03f, by + s * 0.20f);
                    bolt.close();
                    canvas.drawPath(bolt, fill);
                    break;
                }
                case 51: case 53: case 55: case 56: case 57:
                case 61: case 63: case 65: case 66: case 67:
                case 80: case 81: case 82:
                    cloud(canvas, cx, cy - s * 0.16f, s * 0.92f);
                    stroke.setStrokeWidth(Math.max(2f, s * 0.075f));
                    for (float dx : new float[]{-0.24f, 0f, 0.24f}) {
                        canvas.drawLine(cx + s * dx + s * 0.05f, cy + s * 0.26f,
                            cx + s * dx - s * 0.03f, cy + s * 0.46f, stroke);
                    }
                    break;
                default:
                    cloud(canvas, cx, cy, s * 0.98f);
            }
        }

        private void sun(Canvas canvas, float cx, float cy, float r) {
            stroke.setStrokeWidth(Math.max(2f, r * 0.34f));
            for (int k = 0; k < 8; k++) {
                double a = Math.toRadians(k * 45);
                canvas.drawLine(
                    cx + (float) Math.cos(a) * r * 1.3f, cy + (float) Math.sin(a) * r * 1.3f,
                    cx + (float) Math.cos(a) * r * 1.78f, cy + (float) Math.sin(a) * r * 1.78f,
                    stroke);
            }
            canvas.drawCircle(cx, cy, r, fill);
        }

        private void cloud(Canvas canvas, float cx, float cy, float w) {
            float h = w * 0.62f;
            canvas.drawCircle(cx - w * 0.12f, cy - h * 0.16f, w * 0.20f, fill);
            canvas.drawCircle(cx + w * 0.19f, cy - h * 0.30f, w * 0.24f, fill);
            RectF base = new RectF(cx - w * 0.5f, cy - h * 0.10f,
                cx + w * 0.5f, cy + h * 0.42f);
            canvas.drawRoundRect(base, h * 0.30f, h * 0.30f, fill);
        }
    }
}
