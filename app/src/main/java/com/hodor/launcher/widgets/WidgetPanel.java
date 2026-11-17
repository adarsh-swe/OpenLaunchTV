package com.hodor.launcher.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Widgets panel: a centred glass card over the dimmed home
 * screen, opened from the Widgets tile in the overflow grid. One
 * row per widget with an ON/OFF switch — off takes the widget off
 * the home screen (its spot is remembered), on brings it back to
 * that spot. A final row hands over to the host's edit mode for
 * rearranging. BACK closes the panel (the launcher routes it here).
 */
public class WidgetPanel extends FrameLayout {

    public interface Listener {
        /** The user chose "Rearrange widgets": close and open edit mode. */
        void onRearrange();
    }

    private final WidgetHost host;
    private final Listener listener;
    private final List<View> rows = new ArrayList<>();

    public WidgetPanel(Context ctx, WidgetHost host, Listener listener) {
        super(ctx);
        this.host = host;
        this.listener = listener;
        setBackgroundColor(Color.argb(110, 4, 6, 12));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(24), dp(28), dp(20));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(228, 13, 18, 30));
        cardBg.setCornerRadius(dp(22));
        cardBg.setStroke(dp(1), Color.argb(45, 255, 255, 255));
        card.setBackground(cardBg);
        LayoutParams cardLp = new LayoutParams(dp(460), LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);
        addView(card);

        Typeface bebas = Typeface.createFromAsset(
            ctx.getAssets(), "fonts/bebas-neue.ttf");
        TextView title = new TextView(ctx);
        title.setText("WIDGETS");
        title.setTypeface(bebas);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        title.setTextColor(Color.WHITE);
        title.setIncludeFontPadding(false);
        card.addView(title);

        card.addView(hintLine("Turn a widget off and it leaves the home screen."));
        card.addView(hintLine("Its spot is remembered — turn it back on and it returns there."));

        for (Widget w : host.widgets()) {
            card.addView(widgetRow(w));
        }
        card.addView(rearrangeRow());

        // Chain d-pad focus strictly through the rows, so focus can
        // never escape into the home screen behind the panel.
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            row.setId(View.generateViewId());
            row.setNextFocusUpId(i == 0 ? row.getId() : rows.get(i - 1).getId());
            row.setNextFocusDownId(i == rows.size() - 1
                ? row.getId() : rows.get(i + 1).getId());
            row.setNextFocusLeftId(row.getId());
            row.setNextFocusRightId(row.getId());
        }
    }

    /** Focus the first row; call once the panel is attached. */
    public void focusFirstRow() {
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    private TextView hintLine(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setTextColor(Color.argb(170, 255, 255, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout rowShell() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(lp);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.argb(28, 255, 255, 255));
                bg.setCornerRadius(dp(12));
                v.setBackground(bg);
            } else {
                v.setBackground(null);
            }
        });
        rows.add(row);
        return row;
    }

    private LinearLayout nameBlock(String name, String sub) {
        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(LinearLayout.VERTICAL);
        block.setLayoutParams(new LinearLayout.LayoutParams(
            0, LayoutParams.WRAP_CONTENT, 1f));
        TextView nameView = new TextView(getContext());
        nameView.setText(name.toUpperCase(Locale.US));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        nameView.setLetterSpacing(0.04f);
        nameView.setTextColor(Color.WHITE);
        block.addView(nameView);
        if (sub != null && !sub.isEmpty()) {
            TextView subView = new TextView(getContext());
            subView.setText(sub);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            subView.setTextColor(Color.argb(160, 255, 255, 255));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            slp.setMargins(0, dp(2), 0, 0);
            subView.setLayoutParams(slp);
            block.addView(subView);
        }
        return block;
    }

    private View widgetRow(Widget w) {
        LinearLayout row = rowShell();
        row.addView(nameBlock(w.displayName(), w.description()));

        TextView state = new TextView(getContext());
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        state.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        state.setTextColor(Color.argb(220, 255, 255, 255));
        row.addView(state);

        TogglePill pill = new TogglePill(getContext());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(59), dp(26));
        plp.setMargins(dp(9), 0, 0, 0);
        pill.setLayoutParams(plp);
        row.addView(pill);

        Runnable refresh = () -> {
            boolean on = host.isPlaced(w);
            pill.setOn(on);
            state.setText(on ? "ON" : "OFF");
        };
        refresh.run();
        row.setOnClickListener(v -> {
            host.setWidgetEnabled(w, !host.isPlaced(w));
            refresh.run();
        });
        return row;
    }

    private View rearrangeRow() {
        LinearLayout row = rowShell();
        row.addView(nameBlock("Rearrange widgets", "Move widgets around the grid"));
        TextView chevron = new TextView(getContext());
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        chevron.setTextColor(Color.argb(180, 255, 255, 255));
        row.addView(chevron);
        row.setOnClickListener(v -> listener.onRearrange());
        return row;
    }

    /** An iOS-style switch: green track and knob right when on. */
    private static class TogglePill extends View {
        private boolean on;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TogglePill(Context ctx) {
            super(ctx);
        }

        void setOn(boolean on) {
            this.on = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setColor(on ? Color.rgb(52, 199, 89)
                              : Color.argb(90, 255, 255, 255));
            canvas.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, paint);
            float r = h / 2 - dp(2.5f);
            float cx = on ? w - h / 2 : h / 2;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, h / 2, r, paint);
        }

        private int dp(float value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
