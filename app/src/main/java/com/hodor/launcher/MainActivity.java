package com.hodor.launcher;

import android.app.Activity;
import com.hodor.launcher.widgets.WeatherWidget;
import com.hodor.launcher.widgets.WidgetHost;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hodor Launcher — a deliberately minimal Android TV home screen.
 *
 * Design rules (why it's fast):
 *  - One activity, no fragments, no third-party libraries, no network,
 *    no background services, no ads/recommendation rows to fetch or render.
 *  - The home screen is a clock plus ONE horizontal bar of app tiles that
 *    side-scrolls. Nothing else competes for the TV's CPU when you press
 *    Home.
 *  - Focus changes swap a background and scale instantly — no animations
 *    to wait on. Focus is tvOS-style: the tile lifts (scale + soft shadow
 *    + frosted sheen), never an outline or glow.
 *
 * Scrolling is deterministic, not stock: both scrollers override the
 * focus-scroll hook so the focused tile always lands at exactly the same
 * offset it rests at. Scrolling away and coming back returns to precisely
 * the rest position — no drift, no half-cut tiles.
 *
 * Long-press a bar tile for its menu:
 *  - Move: pick the tile up, steer with left/right (it swaps with the
 *    neighbour), OK or long-press again to drop, Back cancels.
 *  - Hide from home screen: the app stays installed and moves to the
 *    overflow section — the bar's last entry, a chevron, expands a grid
 *    below the bar holding the hidden apps; long-press there to show an
 *    app on the home screen again.
 *  - Change icon: opens the icon picker — the app's own icon, any
 *    designs bundled for that app, and letter monograms rendered
 *    on-device. The pick is stored per package and survives restarts.
 *  - Uninstall: hands off to the system's own confirmation dialog.
 * TV Settings has no bar tile: it lives as a tile in the overflow
 * section (MENU opens it too).
 */
public class MainActivity extends Activity {

    private static final int COLUMNS = 6;
    private static final String PREFS = "launcher";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";

    private static final class AppEntry {
        final String label;
        final Drawable icon;
        final ComponentName component;

        AppEntry(String label, Drawable icon, ComponentName component) {
            this.label = label;
            this.icon = icon;
            this.component = component;
        }
    }

    /** Packages pinned to the front of the bar, in this order, when installed. */
    private static final String[] PINNED_ORDER = {
        "com.google.android.youtube.tv",       // YouTube (TV)
        "com.google.android.youtube",          // YouTube
        "com.netflix.mediaclient",             // Netflix
        "com.amazon.amazonvideo.livingroom",   // Prime Video
        "com.disney.disneyplus",               // Disney+
        "com.hbo.hbonow",                      // Max (legacy)
        "com.wbd.stream",                      // Max
        "com.apple.android.music",             // Apple Music
        "com.spotify.tv.android",              // Spotify (TV)
        "com.spotify.music",                   // Spotify
        "com.plexapp.android",                 // Plex
        "tv.twitch.android.app",               // Twitch
    };

    /**
     * A horizontal scroller whose focus scrolling is deterministic: the
     * focused tile always lands at (padding + tile margin) from the left
     * edge — the same offset the first tile rests at — instead of the
     * stock "scroll the minimum to reveal it", which drifts.
     */
    private static class LauncherHorizontalScrollView extends HorizontalScrollView {
        LauncherHorizontalScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
            if (getChildCount() == 0) {
                return super.requestChildRectangleOnScreen(child, rect, immediate);
            }
            int left = rect.left;
            View v = child;
            while (v != null && v != this) {
                left += v.getLeft();
                v = (View) v.getParent();
            }
            int rest = getPaddingLeft()
                + Math.round(8 * getResources().getDisplayMetrics().density);
            int range = Math.max(0, getChildAt(0).getWidth()
                - (getWidth() - getPaddingLeft() - getPaddingRight()));
            int target = Math.max(0, Math.min(left - rest, range));
            if (immediate) {
                scrollTo(target, 0);
            } else {
                smoothScrollTo(target, 0);
            }
            return true;
        }
    }

    /** Vertical twin of the above, used by the home column and picker. */
    private static class LauncherScrollView extends ScrollView {
        LauncherScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
            if (getChildCount() == 0) {
                return super.requestChildRectangleOnScreen(child, rect, immediate);
            }
            int top = rect.top;
            View v = child;
            while (v != null && v != this) {
                top += v.getTop();
                v = (View) v.getParent();
            }
            int rest = getPaddingTop()
                + Math.round(8 * getResources().getDisplayMetrics().density);
            int range = Math.max(0, getChildAt(0).getHeight()
                - (getHeight() - getPaddingTop() - getPaddingBottom()));
            int target = Math.max(0, Math.min(top - rest, range));
            if (immediate) {
                scrollTo(0, target);
            } else {
                smoothScrollTo(0, target);
            }
            return true;
        }
    }

    private FrameLayout rootFrame;
    private WidgetHost widgetHost;
    private TextureView bgVideo;
    private MediaPlayer bgPlayer;
    private Surface bgSurface;
    private int bgVideoW;
    private int bgVideoH;
    private LinearLayout bar;
    private HorizontalScrollView barScroll;
    private ScrollView homeScroll;
    private LinearLayout allAppsSection;
    private GridLayout allAppsGrid;
    private TextView allAppsEmpty;
    private ScrollView pickerScroll;
    private GridLayout pickerGrid;
    private TextView pickerTitle;
    private TextView clockView;
    private TextView dateView;

    /** Every launchable app, in saved order. Hidden ones included. */
    private List<AppEntry> currentApps = new ArrayList<>();
    private Set<String> hiddenPkgs = new HashSet<>();
    /** Index into the VISIBLE bar list of the tile being moved, or -1. */
    private int moveIndex = -1;
    /** Order snapshot taken when a move starts, so Back can cancel. */
    private List<AppEntry> moveSnapshot = null;

    /** Whether the bar's overflow section (hidden apps) is expanded. */
    private boolean expanded = false;
    private View expandTileView;
    private ImageView expandIcon;
    private boolean pickerOpen = false;
    private AppEntry pickerApp;
    private boolean pickerFromGrid = false;
    private FrameLayout menuOverlay;
    private View menuReturnFocus;

    // PSP XMB navigation sounds (cursor / decide / cancel) via SoundPool
    // for near-zero latency. Ids stay 0 until each load finishes;
    // playNav() no-ops on 0.
    private SoundPool navPool;
    private int sndCursor;
    private int sndDecide;
    private int sndCancel;

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            clockHandler.postDelayed(this, 15_000);
        }
    };

    private volatile boolean appsDirty = true;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hiddenPkgs = new HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN, Collections.emptySet()));
        setContentView(buildUi());
        initNavSounds();
        // PSP-style: tick whenever focus lands on a new view. The first
        // focus after launch has no predecessor and stays silent.
        getWindow().getDecorView().getViewTreeObserver()
            .addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
                if (oldFocus != null && newFocus != null && oldFocus != newFocus) {
                    playNav(sndCursor);
                }
            });

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);

        reloadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockHandler.post(clockTick);
        if (widgetHost != null) widgetHost.startWidgets();
        if (appsDirty) reloadApps();
        if (bgPlayer != null) {
            try {
                if (!bgPlayer.isPlaying()) bgPlayer.start();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
        if (widgetHost != null) widgetHost.stopWidgets();
        if (bgPlayer != null) {
            try {
                if (bgPlayer.isPlaying()) bgPlayer.pause();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (navPool != null) {
            navPool.release();
            navPool = null;
        }
        if (bgPlayer != null) {
            try {
                bgPlayer.release();
            } catch (Exception ignored) {
            }
            bgPlayer = null;
        }
        if (bgSurface != null) {
            bgSurface.release();
            bgSurface = null;
        }
        try {
            unregisterReceiver(packageReceiver);
        } catch (Exception ignored) {
        }
    }

    /** Back cancels an in-progress move; on a home screen it otherwise does nothing. */
    @Override
    public void onBackPressed() {
        if (moveIndex >= 0) {
            playNav(sndCancel);
            endMove(false);
            return;
        }
        // No-op: you're already home.
    }

    /**
     * Key routing, in priority order: an open app menu (Back closes it),
     * the icon picker (Back closes it), the expanded overflow section
     * (Back collapses it), then move mode.
     * Move steering must be caught here, before any view sees the event:
     * the scroller consumes DPAD keys for scrolling on its own, so by the
     * time onKeyDown would run they are already gone.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // PSP decide blip on any confirm press (repeat-safe).
            int kc0 = event.getKeyCode();
            if (event.getRepeatCount() == 0
                    && (kc0 == KeyEvent.KEYCODE_DPAD_CENTER
                        || kc0 == KeyEvent.KEYCODE_ENTER
                        || kc0 == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                playNav(sndDecide);
            }
            if (menuOverlay != null) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    playNav(sndCancel);
                    closeMenu(true);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            // Widget edit mode owns the keys: BACK exits, everything
            // else goes to the focused widget or ghost untouched.
            if (widgetHost != null && widgetHost.isEditMode()) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    playNav(sndCancel);
                    widgetHost.exitEditMode();
                    return true;
                }
                int kc1 = event.getKeyCode();
                if (kc1 == KeyEvent.KEYCODE_DPAD_LEFT
                        || kc1 == KeyEvent.KEYCODE_DPAD_RIGHT
                        || kc1 == KeyEvent.KEYCODE_DPAD_UP
                        || kc1 == KeyEvent.KEYCODE_DPAD_DOWN) {
                    playNav(sndCursor);
                }
                return super.dispatchKeyEvent(event);
            }
            if (pickerOpen && moveIndex < 0
                    && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                playNav(sndCancel);
                closeIconPicker();
                return true;
            }
            if (expanded && !pickerOpen && moveIndex < 0
                    && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                playNav(sndCancel);
                setExpanded(false);
                return true;
            }
            // Collapsed, the bar is a single row with nothing below it.
            // An unhandled DOWN falls through to the horizontal scroller,
            // which on this TV answers it by scrolling sideways — swallow
            // it while focus is in the bar. Expanded, the overflow grid IS
            // below, so DOWN goes through to focus search. Nothing is
            // above the bar either (the header holds no focusables, and
            // the corner gear is gone), so UP is swallowed the same way.
            if (!pickerOpen && moveIndex < 0) {
                View focus = getCurrentFocus();
                if (focus != null && isDescendantOf(focus, barScroll)) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                            && !expanded) {
                        return true;
                    }
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                        return true;
                    }
                }
            }
            if (moveIndex >= 0) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        moveTile(-1);
                        playNav(sndCursor);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        moveTile(1);
                        playNav(sndCursor);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return true; // one row: nowhere to steer vertically
                    default:
                        break;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isDescendantOf(View view, View ancestor) {
        View v = view;
        while (v != null) {
            if (v == ancestor) return true;
            Object parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // MENU opens Android TV settings — a useful escape hatch that
        // doesn't cost a bar tile.
        if (keyCode == KeyEvent.KEYCODE_MENU && menuOverlay == null) {
            playNav(sndDecide);
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ UI

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    /** Load the PSP XMB sounds (ripped from the PSP's system_plugin.rco). */
    private void initNavSounds() {
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        navPool = new SoundPool.Builder().setMaxStreams(4)
            .setAudioAttributes(attrs).build();
        sndCursor = navPool.load(this, R.raw.snd_cursor, 1);
        sndDecide = navPool.load(this, R.raw.snd_decide, 1);
        sndCancel = navPool.load(this, R.raw.snd_cancel, 1);
    }

    private void playNav(int soundId) {
        if (navPool != null && soundId != 0) {
            navPool.play(soundId, 0.15f, 0.15f, 1, 0, 1.0f);
        }
    }

    /** Start the live-background player once its surface exists. */
    private void startBgPlayer() {
        if (bgPlayer != null || bgSurface == null) return;
        try {
            bgPlayer = new MediaPlayer();
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.bg_live);
            if (afd == null) return;
            bgPlayer.setDataSource(
                afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            bgPlayer.setSurface(bgSurface);
            bgPlayer.setLooping(true);
            bgPlayer.setVolume(0f, 0f);
            bgPlayer.setOnPreparedListener(mp -> {
                bgVideoW = mp.getVideoWidth();
                bgVideoH = mp.getVideoHeight();
                adjustBgTransform();
                mp.start();
            });
            bgPlayer.setOnErrorListener((mp, what, extra) -> true);
            bgPlayer.prepareAsync();
        } catch (Exception e) {
            // The still photo underneath stays as the background.
            bgPlayer = null;
        }
    }

    /** Centre-crop the background video to fill the screen, no stretching. */
    private void adjustBgTransform() {
        if (bgVideo == null || bgVideoW == 0 || bgVideoH == 0) return;
        int viewW = bgVideo.getWidth();
        int viewH = bgVideo.getHeight();
        if (viewW == 0 || viewH == 0) return;
        float videoRatio = (float) bgVideoW / bgVideoH;
        float viewRatio = (float) viewW / viewH;
        Matrix matrix = new Matrix();
        if (videoRatio > viewRatio) {
            matrix.setScale(videoRatio / viewRatio, 1f, viewW / 2f, viewH / 2f);
        } else {
            matrix.setScale(1f, viewRatio / videoRatio, viewW / 2f, viewH / 2f);
        }
        bgVideo.setTransform(matrix);
    }


    private View buildUi() {
        rootFrame = new FrameLayout(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Background layers, back to front: the still photo on rootFrame
        // (fallback + first frame while the video warms up), then the live
        // video — the same dusk mountain-lake scene with the mist drifting,
        // baked as a seamless 8-second loop — then the UI in root.
        Drawable bg = getDrawable(R.drawable.bg_photo);
        if (bg instanceof BitmapDrawable) {
            ((BitmapDrawable) bg).setGravity(Gravity.FILL);
        }
        rootFrame.setBackground(bg);

        bgVideo = new TextureView(this);
        bgVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                bgSurface = new Surface(st);
                startBgPlayer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                adjustBgTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (bgPlayer != null) {
                    try {
                        bgPlayer.setSurface(null);
                    } catch (Exception ignored) {
                    }
                }
                if (bgSurface != null) {
                    bgSurface.release();
                    bgSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        rootFrame.addView(bgVideo, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setClipChildren(false);
        rootFrame.addView(root, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // Header: clock + date on the left. The header IS grid row A
        // (LAYOUT.md): exactly one cell tall (100dp) from y=28, and the
        // clock block spans A1+A2 — time at the top of the row, date
        // pinned to its bottom, nothing hanging into row B.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);
        header.setClipChildren(false);
        header.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(100)));

        LinearLayout clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Bebas Neue (bundled in assets) for the clock + date — tall,
        // condensed display face picked by Adarsh 2026-11-15. Bebas runs
        // narrow, so the clock goes bigger than the old 54sp stock face
        // to keep the same presence.
        Typeface bebas = Typeface.createFromAsset(getAssets(), "fonts/bebas-neue.ttf");

        clockView = new TextView(this);
        clockView.setTextColor(getColor(R.color.text_primary));
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68);
        clockView.setTypeface(bebas);
        clockView.setIncludeFontPadding(false);

        dateView = new TextView(this);
        dateView.setTextColor(getColor(R.color.text_secondary));
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        dateView.setTypeface(bebas);
        dateView.setLetterSpacing(0.06f);
        dateView.setIncludeFontPadding(false);

        clockBlock.addView(clockView);
        // Spacer: pushes the date to the bottom of row A.
        View clockSpacer = new View(this);
        clockSpacer.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        clockBlock.addView(clockSpacer);
        clockBlock.addView(dateView);
        header.addView(clockBlock);

        root.addView(header);

        // Widget layer: self-contained widget apps (see widgets/).
        // Each widget owns its data and refresh; placements persist
        // and are the user's to move, remove and re-add in edit mode
        // (the Widgets tile in the overflow section).
        widgetHost = new WidgetHost(this);
        widgetHost.registerWidget(new WeatherWidget(this));
        widgetHost.setBackgroundView(bgVideo);
        widgetHost.setEditModeListener(this::focusBarFirstTile);
        rootFrame.addView(widgetHost, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // Content area: the home column (bar + overflow), or the icon picker.
        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content);

        // The home bar: one horizontal row of tiles, side-scrolling,
        // sitting low on the screen like a dock. When the row is shorter
        // than the screen it centres horizontally instead of hugging left.
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        // The row carries the screen inset as padding, while the scroller
        // itself spans the full screen width (negative margins below).
        // That gives a focused edge tile's shadow the whole margin to
        // render into, instead of being sliced at the scroller's edge.
        bar.setPadding(dp(28), 0, dp(28), 0);
        bar.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));

        barScroll = new LauncherHorizontalScrollView(this);
        barScroll.setFillViewport(true);
        barScroll.setClipChildren(false);
        barScroll.setClipToPadding(false);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setPadding(0, dp(18), 0, dp(18));
        LinearLayout.LayoutParams barScrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        barScrollParams.setMargins(-dp(28), 0, -dp(28), 0);
        barScroll.setLayoutParams(barScrollParams);
        barScroll.addView(bar);

        // Home column: the bar, plus the overflow section under it (the
        // hidden apps), inside a bottom-anchored vertical scroller. The
        // column hugs the bottom of the screen; expanding the section
        // grows it upward and reveals the grid below the bar.
        LinearLayout homeColumn = new LinearLayout(this);
        homeColumn.setOrientation(LinearLayout.VERTICAL);
        homeColumn.setClipChildren(false);
        homeColumn.setClipToPadding(false);
        homeColumn.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.BOTTOM;
        homeColumn.setLayoutParams(columnParams);
        homeColumn.addView(barScroll);

        allAppsSection = new LinearLayout(this);
        allAppsSection.setOrientation(LinearLayout.VERTICAL);
        allAppsSection.setClipChildren(false);
        allAppsSection.setClipToPadding(false);
        allAppsSection.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        allAppsSection.setVisibility(View.GONE);

        allAppsGrid = new GridLayout(this);
        allAppsGrid.setColumnCount(COLUMNS);
        allAppsGrid.setClipChildren(false);
        allAppsGrid.setClipToPadding(false);
        allAppsGrid.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        allAppsSection.addView(allAppsGrid);

        allAppsEmpty = new TextView(this);
        allAppsEmpty.setText("Nothing here. Long-press any app in the bar and choose “Hide from home screen” to move it down here.");
        allAppsEmpty.setTextColor(getColor(R.color.text_secondary));
        allAppsEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        emptyParams.setMargins(dp(8), dp(6), 0, dp(10));
        allAppsEmpty.setLayoutParams(emptyParams);
        allAppsEmpty.setVisibility(View.GONE);
        allAppsSection.addView(allAppsEmpty);
        homeColumn.addView(allAppsSection);

        FrameLayout homeAnchor = new FrameLayout(this);
        homeAnchor.setClipChildren(false);
        homeAnchor.setClipToPadding(false);
        homeAnchor.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));
        homeAnchor.addView(homeColumn);

        homeScroll = new LauncherScrollView(this);
        homeScroll.setFillViewport(true);
        homeScroll.setClipChildren(false);
        homeScroll.setClipToPadding(false);
        homeScroll.setVerticalScrollBarEnabled(false);
        homeScroll.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        homeScroll.addView(homeAnchor);
        content.addView(homeScroll);

        // Icon picker page: a title plus a grid of icon options, in its
        // own deterministic scroller. Hidden until a "Change icon" menu
        // item opens it.
        LinearLayout pickerPage = new LinearLayout(this);
        pickerPage.setOrientation(LinearLayout.VERTICAL);
        pickerPage.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));

        pickerTitle = new TextView(this);
        pickerTitle.setTextColor(getColor(R.color.text_primary));
        pickerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        pickerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams pickerTitleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        pickerTitleParams.setMargins(dp(8), 0, 0, dp(18));
        pickerTitle.setLayoutParams(pickerTitleParams);
        pickerPage.addView(pickerTitle);

        pickerGrid = new GridLayout(this);
        pickerGrid.setColumnCount(COLUMNS);
        pickerGrid.setClipChildren(false);
        pickerGrid.setClipToPadding(false);
        pickerGrid.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        pickerPage.addView(pickerGrid);

        pickerScroll = new LauncherScrollView(this);
        pickerScroll.setFillViewport(true);
        pickerScroll.setClipToPadding(false);
        pickerScroll.setVerticalScrollBarEnabled(false);
        pickerScroll.setPadding(0, dp(40), 0, dp(24));
        pickerScroll.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        pickerScroll.addView(pickerPage);
        pickerScroll.setVisibility(View.GONE);
        content.addView(pickerScroll);

        updateClock();
        return rootFrame;
    }

    private void updateClock() {
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("h:mm a", Locale.US).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now));
    }

    // -------------------------------------------------------------- app list

    private void reloadApps() {
        appsDirty = false;
        new Thread(() -> {
            List<AppEntry> apps = loadLaunchableApps();
            runOnUiThread(() -> {
                moveIndex = -1;
                moveSnapshot = null;
                populateBar(apps, 0);
                if (expanded) populateAllApps();
            });
        }, "app-scan").start();
    }

    private List<AppEntry> loadLaunchableApps() {
        PackageManager pm = getPackageManager();

        // Packages that handle HOME are launchers (stock TCL/Google, and us).
        // They don't belong in the app list.
        Set<String> homePackages = new HashSet<>();
        for (ResolveInfo info : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY)) {
            homePackages.add(info.activityInfo.packageName);
        }

        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || homePackages.contains(pkg)) continue;
                // First hit wins; LEANBACK_LAUNCHER is queried first, so a
                // TV-specific activity beats the phone one for the same app.
                byPackage.putIfAbsent(pkg, info);
            }
        }

        List<AppEntry> entries = new ArrayList<>();
        for (ResolveInfo info : byPackage.values()) {
            Drawable icon = null;
            try {
                icon = info.loadIcon(pm);
            } catch (Exception ignored) {
            }
            entries.add(new AppEntry(
                info.loadLabel(pm).toString(),
                icon,
                new ComponentName(info.activityInfo.packageName, info.activityInfo.name)));
        }

        Map<String, Integer> pinRank = new HashMap<>();
        for (int i = 0; i < PINNED_ORDER.length; i++) pinRank.put(PINNED_ORDER[i], i);
        Collections.sort(entries, (a, b) -> {
            int ra = pinRank.getOrDefault(a.component.getPackageName(), Integer.MAX_VALUE);
            int rb = pinRank.getOrDefault(b.component.getPackageName(), Integer.MAX_VALUE);
            if (ra != rb) return Integer.compare(ra, rb);
            return a.label.toLowerCase(Locale.US).compareTo(b.label.toLowerCase(Locale.US));
        });

        applySavedOrder(entries);
        return entries;
    }

    /** A saved custom order wins; anything not in it keeps its default place at the end. */
    private void applySavedOrder(List<AppEntry> entries) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_ORDER, null);
        if (saved == null || saved.isEmpty()) return;
        Map<String, Integer> pos = new HashMap<>();
        String[] pkgs = saved.split(",");
        for (int i = 0; i < pkgs.length; i++) pos.put(pkgs[i], i);
        // Collections.sort is stable, so ties keep the pinned/alphabetical order.
        Collections.sort(entries, (a, b) -> {
            int pa = pos.getOrDefault(a.component.getPackageName(), Integer.MAX_VALUE);
            int pb = pos.getOrDefault(b.component.getPackageName(), Integer.MAX_VALUE);
            return Integer.compare(pa, pb);
        });
    }

    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : currentApps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.component.getPackageName());
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    private void saveHidden() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putStringSet(KEY_HIDDEN, new HashSet<>(hiddenPkgs)).apply();
    }

    /** The apps shown in the home bar: everything except the hidden ones. */
    private List<AppEntry> visibleApps() {
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : currentApps) {
            if (!hiddenPkgs.contains(e.component.getPackageName())) out.add(e);
        }
        return out;
    }

    private void populateBar(List<AppEntry> apps, int focusIndex) {
        currentApps = apps;
        List<AppEntry> vis = visibleApps();
        bar.removeAllViews();
        List<View> tiles = new ArrayList<>();

        for (int i = 0; i < vis.size(); i++) {
            FrameLayout shell = makeBarTile(vis.get(i), i);
            bar.addView(shell);
            tiles.add(shell.getChildAt(0));
        }

        // Last in the bar: the expand arrow, toggling the overflow
        // section below the bar. (TV Settings used to sit here too, but
        // it duplicates the header gear — the gear is the one door now.)
        FrameLayout expandShell = makeExpandTile();
        bar.addView(expandShell);
        expandTileView = expandShell.getChildAt(0);
        tiles.add(expandTileView);

        if (!pickerOpen) {
            View target = (focusIndex >= 0 && focusIndex < tiles.size())
                ? tiles.get(focusIndex) : tiles.get(0);
            target.post(target::requestFocus);
        }
    }

    // ---------------------------------------------------- overflow section

    private void populateAllApps() {
        allAppsGrid.removeAllViews();
        // Settings lives here now (Adarsh, 2026-11-16): a tile in the
        // overflow section instead of a floating gear — the top-right
        // corner belongs to the weather widget.
        allAppsGrid.addView(makeSettingsTile());
        allAppsGrid.addView(makeWidgetsTile());
        int shown = 0;
        for (AppEntry app : currentApps) {
            if (!hiddenPkgs.contains(app.component.getPackageName())) continue;
            allAppsGrid.addView(makeTile(app));
            shown++;
        }
        allAppsEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    /** Expand/collapse the overflow section under the bar. */
    private void setExpanded(boolean expand) {
        if (expand == expanded) return;
        expanded = expand;
        if (expanded) populateAllApps();
        allAppsSection.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (expandIcon != null) {
            expandIcon.setImageDrawable(getDrawable(expanded
                ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down));
            expandIcon.setContentDescription(expanded ? "Fewer apps" : "More apps");
        }
        if (!expanded) {
            // If focus was inside the collapsing section, rescue it.
            View f = getCurrentFocus();
            if (f != null && isDescendantOf(f, allAppsSection)
                    && expandTileView != null) {
                expandTileView.post(expandTileView::requestFocus);
            }
        }
    }

    private void focusFirstGridTile() {
        if (allAppsGrid.getChildCount() > 0) {
            View shell = allAppsGrid.getChildAt(0);
            if (shell instanceof FrameLayout) {
                View t = ((FrameLayout) shell).getChildAt(0);
                t.post(t::requestFocus);
            }
        }
    }

    private void focusGridApp(String pkg) {
        for (int i = 0; i < allAppsGrid.getChildCount(); i++) {
            View shell = allAppsGrid.getChildAt(i);
            if (shell instanceof FrameLayout) {
                View t = ((FrameLayout) shell).getChildAt(0);
                Object tag = t.getTag();
                if (tag instanceof AppEntry
                        && ((AppEntry) tag).component.getPackageName().equals(pkg)) {
                    t.post(t::requestFocus);
                    return;
                }
            }
        }
        focusFirstGridTile();
    }

    // --------------------------------------------------------- icon picker

    /** Where a custom icon for a package lives, if the user picked one. */
    private File iconFile(String pkg) {
        return new File(new File(getFilesDir(), "icons"), pkg + ".png");
    }

    /** The icon to show for an app: the user's override, else the app's own. */
    private Drawable displayIcon(AppEntry app) {
        File f = iconFile(app.component.getPackageName());
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return new BitmapDrawable(getResources(), b);
        }
        return app.icon;
    }

    private final Map<String, Drawable> cleanIconCache = new HashMap<>();

    /**
     * The tile icon (Adarsh, 2026-11-15): the app's art rendered
     * full-bleed to a uniform square, original colours kept (a
     * filter-based dark mode was tried and dropped the same night —
     * it mangled the art). Corner rounding lives on the VIEW's outline
     * clip, not in the bitmap: baking it in only clipped the fitted
     * art's corners along a shallow arc segment, which read as a
     * chamfer on the panel. Baking the corners
     * (instead of clipping the view) is what fixed the "cut corners"
     * look — view clipping rounded the art a second time on top of the
     * art's own corners and chamfered them. Cached per package
     * (+ custom-icon mtime).
     */
    private Drawable cleanIcon(AppEntry app) {
        String pkg = app.component.getPackageName();
        File f = iconFile(pkg);
        String key = pkg + "@" + (f.isFile() ? f.lastModified() : 0L);
        Drawable cached = cleanIconCache.get(key);
        if (cached != null) return cached;
        Drawable src = displayIcon(app);
        int canvas = 256;
        Bitmap art = Bitmap.createBitmap(canvas, canvas, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(art);
        src.setBounds(0, 0, canvas, canvas);
        src.draw(c);
        Bitmap out = art;
        BitmapDrawable bd = new BitmapDrawable(getResources(), out);
        cleanIconCache.put(key, bd);
        return bd;
    }

    private void openIconPicker(AppEntry app, boolean fromGrid) {
        pickerApp = app;
        pickerFromGrid = fromGrid;
        pickerOpen = true;
        pickerTitle.setText("Icon — " + app.label);
        buildIconOptions(app);
        homeScroll.setVisibility(View.GONE);
        pickerScroll.setVisibility(View.VISIBLE);
        if (pickerGrid.getChildCount() > 0) {
            View shell = pickerGrid.getChildAt(0);
            if (shell instanceof FrameLayout) {
                View t = ((FrameLayout) shell).getChildAt(0);
                t.post(t::requestFocus);
            }
        }
    }

    private void closeIconPicker() {
        pickerOpen = false;
        pickerScroll.setVisibility(View.GONE);
        homeScroll.setVisibility(View.VISIBLE);
        AppEntry app = pickerApp;
        pickerApp = null;
        if (app == null) {
            populateBar(currentApps, 0);
            return;
        }
        String pkg = app.component.getPackageName();
        if (pickerFromGrid) {
            populateBar(currentApps, 0);
            if (expanded) {
                populateAllApps();
                focusGridApp(pkg);
            }
        } else {
            List<AppEntry> vis = visibleApps();
            int idx = -1;
            for (int i = 0; i < vis.size(); i++) {
                if (vis.get(i).component.getPackageName().equals(pkg)) {
                    idx = i;
                    break;
                }
            }
            // Not in the bar (it's in the section): land on the arrow.
            if (idx < 0) idx = vis.size();
            populateBar(currentApps, idx);
        }
    }

    private void buildIconOptions(AppEntry app) {
        pickerGrid.removeAllViews();
        String pkg = app.component.getPackageName();

        // The app's own icon — picking it clears any override.
        pickerGrid.addView(makeIconOption(app.icon, "Original", () -> {
            File f = iconFile(pkg);
            if (f.exists()) f.delete();
            closeIconPicker();
        }));

        // Bundled designs for this app, if any ship with the launcher
        // (assets/icons/<package>/<name>.png — the caption is the name).
        try {
            String[] names = getAssets().list("icons/" + pkg);
            if (names != null) {
                Arrays.sort(names);
                for (String name : names) {
                    if (!name.endsWith(".png")) continue;
                    String assetPath = "icons/" + pkg + "/" + name;
                    Drawable d = null;
                    try (InputStream in = getAssets().open(assetPath)) {
                        d = Drawable.createFromStream(in, null);
                    } catch (Exception ignored) {
                    }
                    if (d == null) continue;
                    String caption = name.substring(0, name.length() - 4);
                    caption = caption.substring(0, 1).toUpperCase() + caption.substring(1);
                    pickerGrid.addView(makeIconOption(d, caption, () -> {
                        try {
                            File f = iconFile(pkg);
                            f.getParentFile().mkdirs();
                            try (InputStream in = getAssets().open(assetPath);
                                 OutputStream out = new FileOutputStream(f)) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                        } catch (Exception ignored) {
                        }
                        closeIconPicker();
                    }));
                }
            }
        } catch (Exception ignored) {
        }

        // Monograms: the app's initial on a gradient, rendered on-device —
        // available for every app.
        String[][] styles = {
            {"Indigo", "46598A", "1E2A44"},
            {"Crimson", "8A3B4A", "33161F"},
            {"Forest", "3E7A5C", "173527"},
            {"Amber", "B07C2E", "4A2F0E"},
        };
        for (String[] style : styles) {
            Bitmap bmp = renderMonogram(app.label,
                0xFF000000 | Integer.parseInt(style[1], 16),
                0xFF000000 | Integer.parseInt(style[2], 16));
            pickerGrid.addView(makeIconOption(
                new BitmapDrawable(getResources(), bmp), style[0], () -> {
                    try {
                        File f = iconFile(pkg);
                        f.getParentFile().mkdirs();
                        try (OutputStream out = new FileOutputStream(f)) {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                    } catch (Exception ignored) {
                    }
                    closeIconPicker();
                }));
        }
    }

    private FrameLayout makeIconOption(Drawable iconDrawable, String caption,
                                       Runnable onPick) {
        FrameLayout shell = tileShell(false);
        LinearLayout cell = newTileLayout(false);
        cell.setBackground(getDrawable(R.drawable.tile_bg));
        cell.setOnClickListener(v -> onPick.run());
        cell.setOnFocusChangeListener((v, hasFocus) -> {
            v.setElevation(hasFocus ? dp(12) : 0f);
            v.setBackground(getDrawable(
                hasFocus ? R.drawable.tile_bg_focused : R.drawable.tile_bg));
            float scale = hasFocus ? 1.03f : 1.0f;
            shell.setScaleX(scale);
            shell.setScaleY(scale);
        });
        cell.addView(makeIconView(iconDrawable, 77));
        cell.addView(makeLabelView(caption, 15));
        shell.addView(cell);
        return shell;
    }

    /** A letter icon: the app's initial, white on a diagonal gradient. */
    private Bitmap renderMonogram(String label, int colorA, int colorB) {
        int s = 412;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, s, s, colorA, colorB,
            Shader.TileMode.CLAMP));
        float r = s * 0.22f;
        canvas.drawRoundRect(new RectF(0, 0, s, s), r, r, p);
        p.setShader(null);
        p.setColor(0xF2FFFFFF);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(s * 0.46f);
        String letter = (label == null || label.isEmpty())
            ? "?" : label.substring(0, 1).toUpperCase();
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = s / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(letter, s / 2f, y, p);
        return bmp;
    }

    // ------------------------------------------------------------ move mode

    private void startMove(int index) {
        moveIndex = index;
        moveSnapshot = new ArrayList<>(currentApps);
        populateBar(currentApps, index);
    }

    private void endMove(boolean save) {
        int idx = moveIndex;
        if (save) {
            saveOrder();
        } else if (moveSnapshot != null) {
            currentApps = moveSnapshot;
        }
        moveIndex = -1;
        moveSnapshot = null;
        populateBar(currentApps, idx);
    }

    /** Steer the picked-up tile: swap it with the visible neighbour in the master order. */
    private void moveTile(int delta) {
        List<AppEntry> vis = visibleApps();
        int target = moveIndex + delta;
        if (target < 0 || target >= vis.size()) return;
        int ia = currentApps.indexOf(vis.get(moveIndex));
        int ib = currentApps.indexOf(vis.get(target));
        if (ia < 0 || ib < 0) return;
        Collections.swap(currentApps, ia, ib);
        moveIndex = target;
        populateBar(currentApps, target);
    }

    // -------------------------------------------------------------- app menu

    private void showAppMenu(AppEntry app, int visibleIndex, boolean fromGrid) {
        if (menuOverlay != null) return;
        menuReturnFocus = getCurrentFocus();
        boolean hidden = hiddenPkgs.contains(app.component.getPackageName());

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x8C04070A);
        overlay.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.menu_bg));
        card.setPadding(dp(10), dp(12), dp(10), dp(12));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
            dp(320), FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        card.setLayoutParams(cardParams);

        TextView title = new TextView(this);
        title.setText(app.label);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(14), dp(4), dp(14), dp(10));
        card.addView(title);

        List<TextView> items = new ArrayList<>();
        if (fromGrid) {
            items.add(menuItem("Show on home screen", () -> doShow(app)));
        } else {
            items.add(menuItem("Move", () -> {
                closeMenu(false);
                startMove(visibleIndex);
            }));
            items.add(menuItem("Hide from home screen",
                () -> doHide(app, visibleIndex, false)));
        }
        items.add(menuItem("Change icon", () -> {
            closeMenu(false);
            openIconPicker(app, fromGrid);
        }));
        items.add(menuItem("Uninstall", () -> doUninstall(app)));
        for (TextView item : items) card.addView(item);

        overlay.addView(card);
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        menuOverlay = overlay;
        if (!items.isEmpty()) {
            TextView first = items.get(0);
            first.post(first::requestFocus);
        }
    }

    private TextView menuItem(String text, Runnable action) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setTextColor(getColor(R.color.text_primary));
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        item.setFocusable(true);
        item.setClickable(true);
        item.setOnClickListener(v -> action.run());
        item.setOnFocusChangeListener((v, hasFocus) ->
            v.setBackground(hasFocus ? getDrawable(R.drawable.tile_bg_focused) : null));
        return item;
    }

    private void closeMenu(boolean restoreFocus) {
        if (menuOverlay != null) {
            rootFrame.removeView(menuOverlay);
            menuOverlay = null;
        }
        if (restoreFocus && menuReturnFocus != null) {
            View t = menuReturnFocus;
            t.post(t::requestFocus);
        }
        menuReturnFocus = null;
    }

    private void doHide(AppEntry app, int visibleIndex, boolean fromGrid) {
        closeMenu(false);
        hiddenPkgs.add(app.component.getPackageName());
        saveHidden();
        populateBar(currentApps, Math.max(0, visibleIndex));
    }

    private void doShow(AppEntry app) {
        closeMenu(false);
        hiddenPkgs.remove(app.component.getPackageName());
        saveHidden();
        populateBar(currentApps, 0);
        if (expanded) {
            populateAllApps();
            focusFirstGridTile();
        }
    }

    private void doUninstall(AppEntry app) {
        closeMenu(false);
        String pkg = app.component.getPackageName();
        boolean systemApp = false;
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            systemApp = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception ignored) {
        }
        if (!systemApp) {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
                // Fall through to the app info page.
            }
        }
        // System apps can't be uninstalled outright, and some builds
        // swallow the delete intent for them: land on the app's system
        // page instead, which offers Disable / Uninstall updates.
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- tiles

    private void launch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
            .setComponent(app.component)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            // Some apps only declare the phone LAUNCHER category.
            startActivity(new Intent(Intent.ACTION_MAIN)
                .setComponent(app.component)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /**
     * A home-bar tile: the bare app icon, no card, no label — a dock.
     * Hover is a 12% lift with a solid elevation shadow (native elevation,
     * so it can never disturb the layout the way the old shadow view did).
     */
    private FrameLayout makeBarTile(AppEntry app, int index) {
        FrameLayout shell = tileShell(true);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(cleanIcon(app));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconParams =
            new FrameLayout.LayoutParams(dp(112), dp(112));
        iconParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconParams);
        icon.setFocusable(true);
        icon.setClickable(true);
        icon.setContentDescription(app.label);
        icon.setOutlineAmbientShadowColor(0xFF000000);
        icon.setOutlineSpotShadowColor(0xFF000000);
        float radius = dp(34);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        icon.setClipToOutline(true);

        icon.setOnClickListener(v -> {
            if (moveIndex >= 0) {
                // Any press while an icon is picked up drops it where it is.
                endMove(true);
                return;
            }
            launch(app);
        });
        icon.setOnLongClickListener(v -> {
            if (moveIndex < 0) {
                showAppMenu(app, index, false);
            } else if (moveIndex == index) {
                endMove(true);
            }
            return true;
        });
        icon.setOnFocusChangeListener((v, hasFocus) -> {
            boolean moving = index == moveIndex;
            // Scale the icon itself, not the shell: the elevation shadow
            // lives on the icon, and scaling a parent wrapper while the
            // child casts the shadow slices the shadow with a hard edge.
            // Lower elevation than before — a tighter shadow reads
            // darker and fades out faster.
            v.setElevation(moving ? dp(18) : hasFocus ? dp(9) : 0f);
            float scale = moving ? 1.15f : hasFocus ? 1.12f : 1.0f;
            v.setScaleX(scale);
            v.setScaleY(scale);
        });

        shell.addView(icon);
        return shell;
    }

    /** An overflow-grid tile: bare icon + name, no card — the bar's language. */
    private FrameLayout makeTile(AppEntry app) {
        FrameLayout shell = tileShell(false);

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));
        cell.setPadding(dp(6), dp(10), dp(6), dp(10));
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setTag(app);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(cleanIcon(app));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(95), dp(95)));
        icon.setClipToOutline(true);
        icon.setOutlineAmbientShadowColor(0xFF000000);
        icon.setOutlineSpotShadowColor(0xFF000000);
        float radius = dp(24);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        cell.addView(icon);

        View label = makeLabelView(app.label, 15);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(8), 0, 0);
        label.setLayoutParams(labelParams);
        cell.addView(label);

        cell.setOnClickListener(v -> launch(app));
        cell.setOnLongClickListener(v -> {
            showAppMenu(app, -1, true);
            return true;
        });
        cell.setOnFocusChangeListener((v, hasFocus) -> {
            // No elevation shadow here: it traces the icon view's square
            // outline, which looks broken behind icons whose art isn't a
            // full square (Play Store's glyph, wide banners). A soft glow
            // behind the icon reads as focus without a fake shadow box.
            v.setBackground(hasFocus
                ? getDrawable(R.drawable.grid_focus_glow) : null);
            float scale = hasFocus ? 1.10f : 1.0f;
            shell.setScaleX(scale);
            shell.setScaleY(scale);
        });

        shell.addView(cell);
        return shell;
    }

    /**
     * The Settings tile in the overflow grid: the gear glyph + label,
     * styled like the app tiles, but it fires the system Settings
     * intent and has no long-press menu — it isn't an app entry. It
     * replaced the floating corner gear (Adarsh, 2026-11-16), which
     * made room for the weather widget in the top-right corner.
     */
    private FrameLayout makeSettingsTile() {
        FrameLayout shell = tileShell(false);

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));
        cell.setPadding(dp(6), dp(10), dp(6), dp(10));
        cell.setFocusable(true);
        cell.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getDrawable(R.drawable.ic_settings));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(20), dp(20), dp(20), dp(20));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(95), dp(95)));
        cell.addView(icon);

        View label = makeLabelView("Settings", 15);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(8), 0, 0);
        label.setLayoutParams(labelParams);
        cell.addView(label);

        cell.setOnClickListener(v -> startActivity(
            new Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        cell.setOnFocusChangeListener((v, hasFocus) -> {
            icon.setImageDrawable(getDrawable(hasFocus
                ? R.drawable.ic_settings_glow : R.drawable.ic_settings));
            v.setBackground(hasFocus
                ? getDrawable(R.drawable.grid_focus_glow) : null);
            float scale = hasFocus ? 1.10f : 1.0f;
            shell.setScaleX(scale);
            shell.setScaleY(scale);
        });

        shell.addView(cell);
        return shell;
    }

    /**
     * The Widgets tile in the overflow grid, next to Settings: opens
     * the widget host's edit mode, where placed widgets can be moved
     * on the grid, removed to the pool, and pooled widgets added.
     */
    private FrameLayout makeWidgetsTile() {
        FrameLayout shell = tileShell(false);

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));
        cell.setPadding(dp(6), dp(10), dp(6), dp(10));
        cell.setFocusable(true);
        cell.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getDrawable(R.drawable.ic_widgets));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(20), dp(20), dp(20), dp(20));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(95), dp(95)));
        cell.addView(icon);

        View label = makeLabelView("Widgets", 15);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(8), 0, 0);
        label.setLayoutParams(labelParams);
        cell.addView(label);

        cell.setOnClickListener(v -> {
            setExpanded(false);
            widgetHost.post(() -> widgetHost.enterEditMode());
        });
        cell.setOnFocusChangeListener((v, hasFocus) -> {
            icon.setImageDrawable(getDrawable(hasFocus
                ? R.drawable.ic_widgets_glow : R.drawable.ic_widgets));
            v.setBackground(hasFocus
                ? getDrawable(R.drawable.grid_focus_glow) : null);
            float scale = hasFocus ? 1.10f : 1.0f;
            shell.setScaleX(scale);
            shell.setScaleY(scale);
        });

        shell.addView(cell);
        return shell;
    }

    /** After widget edit mode ends: d-pad focus back on the bar. */
    private void focusBarFirstTile() {
        View target = findFocusable(bar);
        if (target != null) target.post(target::requestFocus);
    }

    private static View findFocusable(View v) {
        if (v == null) return null;
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) return v;
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The bar's last entry: the expand arrow. A chevron on a dusk-indigo
     * gradient in the background scene's family — not a grey box. It
     * toggles the overflow section below the bar and flips to point up
     * while the section is open.
     */
    private FrameLayout makeExpandTile() {
        FrameLayout shell = tileShell(true);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getDrawable(expanded
            ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(32), dp(32), dp(32), dp(32));
        icon.setBackground(getDrawable(R.drawable.arrow_backdrop));
        FrameLayout.LayoutParams arrowParams =
            new FrameLayout.LayoutParams(dp(112), dp(112));
        arrowParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(arrowParams);
        icon.setFocusable(true);
        icon.setClickable(true);
        icon.setContentDescription(expanded ? "Fewer apps" : "More apps");
        icon.setOutlineAmbientShadowColor(0xFF000000);
        icon.setOutlineSpotShadowColor(0xFF000000);
        float radius = dp(34);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });

        icon.setOnClickListener(v -> {
            if (moveIndex >= 0) {
                endMove(true);
                return;
            }
            setExpanded(!expanded);
        });
        icon.setOnFocusChangeListener((v, hasFocus) -> {
            // Same rule as the app tiles: scale + shadow on the icon
            // itself, tight elevation (darker core, faster fade).
            v.setElevation(hasFocus ? dp(9) : 0f);
            float scale = hasFocus ? 1.12f : 1.0f;
            v.setScaleX(scale);
            v.setScaleY(scale);
        });

        expandIcon = icon;
        shell.addView(icon);
        return shell;
    }

    /**
     * A tile's outer shell: just a wrapper so focus scale grows the tile
     * around its centre without disturbing the row's layout. The focus
     * shadow is native elevation on the tile itself — an earlier build
     * used a separate shadow view behind the tile, but a MATCH_PARENT
     * child inflates the shell's measured height (and only while visible,
     * i.e. only for the focused tile), which made the focused tile float
     * above the row and the whole bar ride high. Elevation shadows are
     * drawn by the render thread and never affect measurement.
     */
    private FrameLayout tileShell(boolean inBar) {
        FrameLayout shell = new FrameLayout(this);
        shell.setClipChildren(false);
        shell.setClipToPadding(false);

        if (inBar) {
            // Exactly six slots fit the screen: each shell is one slot
            // wide (content width / 6) with the icon centred in it, so
            // the first six fill the screen evenly and the rest scroll.
            int slotPx = (getResources().getDisplayMetrics().widthPixels
                - dp(56)) / 6;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                slotPx, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(8), 0, dp(8));
            shell.setLayoutParams(params);
        } else {
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(8), dp(8), dp(8), dp(8));
            shell.setLayoutParams(params);
        }
        return shell;
    }

    private LinearLayout newTileLayout(boolean inBar) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setClickable(true);
        // The elevation shadow follows this rounded outline (24dp corners,
        // matching the tile backgrounds).
        float corner = dp(24);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        if (inBar) {
            tile.setPadding(dp(12), dp(16), dp(12), dp(12));
            tile.setLayoutParams(new FrameLayout.LayoutParams(dp(148), dp(152)));
        } else {
            tile.setPadding(dp(12), dp(18), dp(12), dp(14));
            tile.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        }
        return tile;
    }

    private View makeIconView(Drawable icon, int sizeDp) {
        ImageView image = new ImageView(this);
        image.setImageDrawable(icon);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        // Round the icon itself (Apple-style squircle clip, ~22% radius).
        float radius = dp(Math.round(sizeDp * 0.22f));
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        image.setClipToOutline(true);
        return image;
    }

    private View makeLabelView(String label, int sizeSp) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(getColor(R.color.text_primary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        text.setGravity(Gravity.CENTER);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, dp(10), 0, 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        return text;
    }
}
