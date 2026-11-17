package com.hodor.launcher.widgets;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Clock widget — one grid cell (218x100dp), movable and removable
 * like every other widget. The look (variant A, picked by Adarsh
 * 2026-11-17 over the split-flap tiles): big bare text — the time
 * oversized in Bebas with AM/PM small at its baseline, the date
 * underneath in letterspaced bold sans caps. No card, no tiles.
 * Ticks every 15 seconds while the launcher is in the foreground.
 */
public class ClockWidget extends Widget {

    private final Typeface bebas;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView timeView;
    private TextView amPmView;
    private TextView dateView;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            handler.postDelayed(this, 15_000);
        }
    };

    public ClockWidget(Context ctx) {
        super(ctx);
        bebas = Typeface.createFromAsset(ctx.getAssets(), "fonts/bebas-neue.ttf");
    }

    @Override public String id() { return "clock"; }
    @Override public String description() { return "Time and date"; }
    @Override public int colSpan() { return 1; }
    @Override public int rowSpan() { return 1; }
    // Default home: A1, the top-left cell it has always lived in.
    @Override public int defaultCol() { return 0; }
    @Override public int defaultRow() { return 0; }

    @Override
    protected View buildView() {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(3), dp(2), 0, dp(2));

        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        block.addView(timeRow);

        timeView = new TextView(ctx);
        timeView.setTextColor(Color.WHITE);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 62);
        timeView.setTypeface(bebas);
        timeView.setIncludeFontPadding(false);
        timeRow.addView(timeView);

        amPmView = new TextView(ctx);
        amPmView.setTextColor(Color.argb(210, 255, 255, 255));
        amPmView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        amPmView.setTypeface(bebas);
        amPmView.setIncludeFontPadding(false);
        amPmView.setSingleLine(true);
        amPmView.setGravity(Gravity.BOTTOM);
        amPmView.setPadding(0, 0, 0, dp(5));
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT);
        apLp.setMargins(dp(6), 0, 0, 0);
        amPmView.setLayoutParams(apLp);
        timeRow.addView(amPmView);

        dateView = new TextView(ctx);
        dateView.setTextColor(Color.rgb(205, 212, 226));
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dateView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        dateView.setLetterSpacing(0.18f);
        dateView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.setMargins(dp(1), dp(10), 0, 0);
        dateView.setLayoutParams(dLp);
        block.addView(dateView);

        update();
        return block;
    }

    private void update() {
        if (timeView == null) return;
        Date now = new Date();
        timeView.setText(new SimpleDateFormat("h:mm", Locale.US).format(now));
        amPmView.setText(new SimpleDateFormat("a", Locale.US).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.US)
            .format(now).toUpperCase(Locale.US));
    }

    @Override
    public void onStart() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(tick);
    }
}
