package com.hodor.launcher.widgets;

import android.content.Context;
import android.view.TextureView;
import android.view.View;

/**
 * A widget is a self-contained mini-app that lives on the launcher's
 * widget grid. It owns its data, its refresh cycle and its rendering.
 * The launcher's side of the contract is deliberately tiny: the host
 * gives the widget a rectangle (see {@link WidgetHost}) and start/stop
 * signals as the launcher comes and goes. Everything else — where the
 * data comes from, how often it refreshes, how it draws — is the
 * widget's own business, inside its own class.
 *
 * Widgets are display-only in normal use: the host keeps their views
 * non-focusable, so the d-pad never lands on them. In the host's edit
 * mode they become focusable so they can be moved, removed and added.
 */
public abstract class Widget {

    /** Placed on the host's grid at the (col, row) given to the host. */
    public static final int ANCHOR_GRID = 0;
    /** Pinned to the top-right: the top row's rightmost cells. */
    public static final int ANCHOR_TOP_RIGHT = 1;

    protected final Context ctx;
    private View view;

    /** The launcher's live background, shared by the host for widgets
     *  that blend into it (e.g. a frosted-glass card). May be null. */
    protected TextureView backgroundView;

    protected Widget(Context ctx) {
        this.ctx = ctx;
    }

    /** Stable identifier, e.g. "weather". */
    public abstract String id();

    /** Width on the widget grid, in cells (1 or 2 — never wider). */
    public abstract int colSpan();

    /** Height on the widget grid, in cells. */
    public abstract int rowSpan();

    /** Where the host places this widget. Default: the grid. */
    public int anchor() {
        return ANCHOR_GRID;
    }

    void bindBackground(TextureView tv) {
        backgroundView = tv;
    }

    /** Build the widget's root view. Called once, lazily. */
    protected abstract View buildView();

    public final View view() {
        if (view == null) view = buildView();
        return view;
    }

    /** The launcher is in the foreground: start or resume work. */
    public void onStart() {}

    /** The launcher went to the background: pause timers and fetches. */
    public void onStop() {}

    protected int dp(int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
