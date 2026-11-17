package com.hodor.launcher.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The widget layer: a full-screen overlay that places widgets on the
 * launcher's grid and forwards the launcher's lifecycle to them.
 *
 * Uniform sizing (LAYOUT.md): every widget is sized ONLY by whole
 * cells of the one grid — cell 218x100dp, gutter 10dp, origin (28, 28),
 * 4 columns x 3 rows, at most 2 cells wide. A widget declares a span
 * (colSpan x rowSpan); the host derives its exact rectangle from the
 * shared constants. No custom sizes, no off-grid offsets: two widgets
 * with the same span are pixel-identical in size, wherever they sit.
 *
 * Placement: every cell is fair game — the clock is a widget like
 * any other. A widget's
 * default placement comes from its anchor (TOP_RIGHT = the top row's
 * rightmost cells, e.g. a 2x1 lands on A3+A4); after that, placements
 * are the user's — they persist across restarts.
 *
 * Edit mode (entered from the Widgets tile in the overflow section):
 * placed widgets become focusable; the d-pad moves the focused
 * widget cell by cell (grid-snapped, collision-checked), ENTER
 * cycles focus to the next widget, MENU removes the widget back
 * to the pool, BACK exits.
 * Widgets not on the home screen appear as a "+ NAME" ghost at the
 * first free cell; steering it and pressing ENTER adds the widget
 * there. Outside edit mode widgets are display-only again.
 */
public class WidgetHost extends FrameLayout {

    public static final int ORIGIN_X_DP = 28;
    public static final int ORIGIN_Y_DP = 28;
    public static final int CELL_W_DP = 218;
    public static final int CELL_H_DP = 100;
    public static final int GUTTER_DP = 10;
    public static final int COLUMNS = 4;
    public static final int ROWS = 3;
    public static final int MAX_COL_SPAN = 2;

    private static final String PREFS = "widget_placements";
    private static final String REMOVED = "none";

    /** Told when edit mode ends, so the launcher can restore focus. */
    public interface EditModeListener {
        void onEditModeExit();
    }

    private final List<Widget> registered = new ArrayList<>();
    private final Map<String, int[]> placements = new LinkedHashMap<>();
    private final SharedPreferences prefs;
    private TextureView backgroundView;
    private boolean running;
    private boolean editMode;
    private EditModeListener editListener;
    private FrameLayout ghost;
    private Widget ghostWidget;
    private int ghostCol;
    private int ghostRow;
    private TextView hint;

    public WidgetHost(Context ctx) {
        super(ctx);
        setClipChildren(false);
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Share the launcher's live background with every widget. */
    public void setBackgroundView(TextureView tv) {
        backgroundView = tv;
        for (Widget w : registered) w.bindBackground(tv);
    }

    public void setEditModeListener(EditModeListener l) {
        editListener = l;
    }

    public boolean isEditMode() {
        return editMode;
    }

    // ---- registration and placement --------------------------------

    /**
     * Make a widget known to the host. It is placed at its saved
     * position if it has one, at its default position on first run,
     * or left in the pool if the user removed it (or it doesn't fit).
     */
    public void registerWidget(Widget w) {
        if (w.colSpan() < 1 || w.colSpan() > MAX_COL_SPAN
                || w.rowSpan() < 1 || w.rowSpan() > ROWS) {
            throw new IllegalArgumentException(
                "Widget " + w.id() + " has off-grid span "
                    + w.colSpan() + "x" + w.rowSpan()
                    + " (cells only, max " + MAX_COL_SPAN + " wide)");
        }
        registered.add(w);
        if (backgroundView != null) w.bindBackground(backgroundView);

        String saved = prefs.getString(w.id(), null);
        if (REMOVED.equals(saved)) return; // user took it off: pool
        int savedVersion = prefs.getInt(w.id() + "#v", 1);
        if (saved != null && savedVersion >= w.placementVersion()) {
            String[] parts = saved.split(",");
            int c = Integer.parseInt(parts[0]);
            int r = Integer.parseInt(parts[1]);
            if (cellsFree(w, c, r)) {
                place(w, c, r);
                return;
            }
        }
        int[] spot = defaultSpot(w);
        if (spot != null) {
            place(w, spot[0], spot[1]);
            save(w);
        }
    }

    /** The widget's preferred/anchor cell if free, else first free fit. */
    private int[] defaultSpot(Widget w) {
        if (w.defaultCol() >= 0 && w.defaultRow() >= 0
                && cellsFree(w, w.defaultCol(), w.defaultRow())) {
            return new int[]{w.defaultCol(), w.defaultRow()};
        }
        if (w.anchor() == Widget.ANCHOR_TOP_RIGHT) {
            int c = COLUMNS - w.colSpan();
            if (cellsFree(w, c, 0)) return new int[]{c, 0};
        }
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                if (cellsFree(w, c, r)) return new int[]{c, r};
            }
        }
        return null;
    }

    private void place(Widget w, int col, int row) {
        View v = w.view();
        v.setLayoutParams(cellParams(w, col, row));
        v.setFocusable(false);
        addView(v);
        placements.put(w.id(), new int[]{col, row});
        if (backgroundView != null) w.bindBackground(backgroundView);
        if (running) w.onStart();
    }

    private void unplace(Widget w, boolean markRemoved) {
        if (running) w.onStop();
        removeView(w.view());
        int[] p = placements.remove(w.id());
        if (markRemoved) {
            SharedPreferences.Editor e = prefs.edit()
                .putString(w.id(), REMOVED);
            // Remember the spot, so re-enabling (panel toggle or a
            // later edit-mode add) can offer the widget its home back.
            if (p != null) e.putString(w.id() + "#last", p[0] + "," + p[1]);
            e.apply();
        }
    }

    // ---- enable / disable (the Widgets panel) -----------------------

    /** Every registered widget, in registration order. */
    public List<Widget> widgets() {
        return new ArrayList<>(registered);
    }

    /** True if the widget is currently on the home screen. */
    public boolean isPlaced(Widget w) {
        return placements.containsKey(w.id());
    }

    /**
     * Turn a widget on or off outside edit mode. Off removes it from
     * the home screen (its spot is remembered). On restores it to its
     * remembered spot if that is free, else to its default spot, else
     * to the first free fit. Returns false only when turning on and
     * no cell on the grid can take the widget.
     */
    public boolean setWidgetEnabled(Widget w, boolean on) {
        if (!on) {
            if (placements.containsKey(w.id())) unplace(w, true);
            return true;
        }
        if (placements.containsKey(w.id())) return true;
        int[] spot = null;
        String last = prefs.getString(w.id() + "#last", null);
        if (last != null) {
            String[] parts = last.split(",");
            int c = Integer.parseInt(parts[0]);
            int r = Integer.parseInt(parts[1]);
            if (cellsFree(w, c, r)) spot = new int[]{c, r};
        }
        if (spot == null) spot = defaultSpot(w);
        if (spot == null) return false;
        place(w, spot[0], spot[1]);
        save(w);
        return true;
    }

    private void save(Widget w) {
        int[] p = placements.get(w.id());
        if (p != null) {
            prefs.edit().putString(w.id(), p[0] + "," + p[1])
                .putInt(w.id() + "#v", w.placementVersion()).apply();
        }
    }

    private LayoutParams cellParams(Widget w, int col, int row) {
        int spanW = w.colSpan(), spanH = w.rowSpan();
        int width = spanW * CELL_W_DP + (spanW - 1) * GUTTER_DP;
        int height = w.heightDp() > 0
            ? w.heightDp()
            : spanH * CELL_H_DP + (spanH - 1) * GUTTER_DP;
        LayoutParams lp = new LayoutParams(dp(width), dp(height));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(
            dp(ORIGIN_X_DP + col * (CELL_W_DP + GUTTER_DP)),
            dp(ORIGIN_Y_DP + row * (CELL_H_DP + GUTTER_DP) + w.topInsetDp()), 0, 0);
        return lp;
    }

    /**
     * True if the widget's span fits at (col, row): inside the grid
     * and clear of every placed widget except {@code self}.
     */
    private boolean cellsFree(Widget self, int col, int row) {
        int spanW = self.colSpan(), spanH = self.rowSpan();
        if (col < 0 || row < 0
                || col + spanW > COLUMNS || row + spanH > ROWS) {
            return false;
        }
        for (Map.Entry<String, int[]> e : placements.entrySet()) {
            if (e.getKey().equals(self.id())) continue;
            Widget other = byId(e.getKey());
            int[] p = e.getValue();
            boolean overlap = col < p[0] + other.colSpan()
                && p[0] < col + spanW
                && row < p[1] + other.rowSpan()
                && p[1] < row + spanH;
            if (overlap) return false;
        }
        return true;
    }

    private Widget byId(String id) {
        for (Widget w : registered) {
            if (w.id().equals(id)) return w;
        }
        return null;
    }

    // ---- lifecycle --------------------------------------------------

    public void startWidgets() {
        running = true;
        for (String id : placements.keySet()) byId(id).onStart();
    }

    public void stopWidgets() {
        running = false;
        for (String id : placements.keySet()) byId(id).onStop();
    }

    // ---- edit mode --------------------------------------------------

    public void enterEditMode() {
        if (editMode) return;
        if (placements.isEmpty() && poolWidget() == null) return;
        editMode = true;
        for (String id : placements.keySet()) arm(byId(id).view());
        spawnGhost();
        showHint();
        View target = !placements.isEmpty()
            ? byId(placements.keySet().iterator().next()).view()
            : ghost;
        if (target != null) target.post(target::requestFocus);
    }

    public void exitEditMode() {
        if (!editMode) return;
        editMode = false;
        for (String id : placements.keySet()) disarm(byId(id).view());
        if (ghost != null) {
            removeView(ghost);
            ghost = null;
            ghostWidget = null;
        }
        if (hint != null) {
            removeView(hint);
            hint = null;
        }
        if (editListener != null) editListener.onEditModeExit();
    }

    private void arm(View v) {
        v.setFocusable(true);
        v.setOnKeyListener(editKeys);
        v.setOnFocusChangeListener((view, hasFocus) ->
            view.setForeground(hasFocus ? outline() : null));
    }

    private void disarm(View v) {
        v.setFocusable(false);
        v.setOnKeyListener(null);
        v.setOnFocusChangeListener(null);
        v.setForeground(null);
    }

    private GradientDrawable outline() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dp(2), Color.argb(235, 255, 255, 255));
        d.setCornerRadius(dp(22));
        return d;
    }

    private final OnKeyListener editKeys = (v, keyCode, event) -> {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                nudge(v, -1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                nudge(v, 1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                nudge(v, 0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                nudge(v, 0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (v == ghost) {
                    placeGhost();
                } else {
                    focusNext(v);
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (v != ghost) removeFocused(v);
                return true;
            default:
                return false;
        }
    };

    /**
     * Cycle edit-mode focus through the placed widgets and the
     * ghost. The arrow keys steer the focused widget, so without
     * this the first widget to take focus would trap it — with a
     * single widget that never showed; with two it made the second
     * one unreachable.
     */
    private void focusNext(View current) {
        List<View> order = new ArrayList<>();
        for (String id : placements.keySet()) order.add(byId(id).view());
        if (ghost != null) order.add(ghost);
        if (order.size() < 2) return;
        int i = order.indexOf(current);
        View next = order.get((i + 1) % order.size());
        next.post(next::requestFocus);
    }

    private void nudge(View v, int dx, int dy) {
        if (v == ghost) {
            int nc = ghostCol + dx, nr = ghostRow + dy;
            if (cellsFree(ghostWidget, nc, nr)) {
                ghostCol = nc;
                ghostRow = nr;
                ghost.setLayoutParams(cellParams(ghostWidget, nc, nr));
            }
            return;
        }
        Widget w = widgetFor(v);
        if (w == null) return;
        int[] p = placements.get(w.id());
        int nc = p[0] + dx, nr = p[1] + dy;
        if (cellsFree(w, nc, nr)) {
            p[0] = nc;
            p[1] = nr;
            v.setLayoutParams(cellParams(w, nc, nr));
            save(w);
        }
    }

    private void removeFocused(View v) {
        Widget w = widgetFor(v);
        if (w == null) return;
        unplace(w, true);
        disarm(v);
        spawnGhost();
        View target = ghost;
        if (target == null && !placements.isEmpty()) {
            target = byId(placements.keySet().iterator().next()).view();
        }
        if (target != null) {
            target.post(target::requestFocus);
        } else {
            exitEditMode();
        }
    }

    private void placeGhost() {
        Widget w = ghostWidget;
        removeView(ghost);
        ghost = null;
        ghostWidget = null;
        place(w, ghostCol, ghostRow);
        save(w);
        arm(w.view());
        w.view().post(w.view()::requestFocus);
        spawnGhost();
    }

    private Widget poolWidget() {
        for (Widget w : registered) {
            if (!placements.containsKey(w.id())) return w;
        }
        return null;
    }

    /** Show the next pooled widget as a "+ NAME" ghost, if it fits. */
    private void spawnGhost() {
        if (ghost != null) return;
        Widget w = poolWidget();
        if (w == null) return;
        int[] spot = null;
        for (int r = 0; r < ROWS && spot == null; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                if (cellsFree(w, c, r)) {
                    spot = new int[]{c, r};
                    break;
                }
            }
        }
        if (spot == null) return;
        ghostWidget = w;
        ghostCol = spot[0];
        ghostRow = spot[1];
        FrameLayout g = new FrameLayout(getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setStroke(dp(2), Color.argb(150, 255, 255, 255));
        bg.setCornerRadius(dp(22));
        g.setBackground(bg);
        TextView label = new TextView(getContext());
        label.setText("+ " + w.id().toUpperCase(Locale.US));
        label.setTextColor(Color.argb(225, 255, 255, 255));
        label.setTextSize(15);
        label.setLetterSpacing(0.1f);
        LayoutParams labelLp = new LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelLp.gravity = Gravity.CENTER;
        g.addView(label, labelLp);
        g.setLayoutParams(cellParams(w, ghostCol, ghostRow));
        addView(g);
        ghost = g;
        arm(g);
    }

    private void showHint() {
        hint = new TextView(getContext());
        hint.setText("ARROWS MOVE · ENTER NEXT WIDGET · MENU REMOVE · BACK DONE");
        hint.setTextColor(Color.rgb(205, 212, 226));
        hint.setTextSize(13);
        hint.setLetterSpacing(0.08f);
        hint.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(150, 8, 11, 20));
        bg.setCornerRadius(dp(14));
        hint.setBackground(bg);
        LayoutParams lp = new LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, 0, 0, dp(188));
        hint.setLayoutParams(lp);
        addView(hint);
    }

    private Widget widgetFor(View v) {
        for (Widget w : registered) {
            if (placements.containsKey(w.id()) && w.view() == v) return w;
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
