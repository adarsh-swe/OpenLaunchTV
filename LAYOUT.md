# Hodor Launcher — layout grid

The home screen's spatial contract. Every widget snaps to this grid;
nothing ships off-grid. Set 2026-11-15, revised the same night at
Direction: the whole top is free space, widgets may span
multiple cells — but never 3 wide, and only ever whole cells.
Row heights were spread evenly and the grid confirmed against a
drawn reference on 2026-11-16: see `docs/grid-reference.jpg`.

## Canvas

- 1920×1080 px at density 2.0 → 960×540 dp. All numbers in dp.
- Screen margins: 28 dp left/right.

## Widget grid

- Origin: (28, 28) dp — the grid starts at the top of the screen.
  The whole top is widget space; nothing up there is reserved.
- 4 columns × 3 rows. Cell: 218×100 dp. Gutters: 10 dp.
- Slots: A1–A4 (top row, y 28–128), B1–B4 (y 138–238), C1–C4
  (y 248–348). The three row heights are equal (100 dp each),
  spread evenly over the zone above the app bar.
- Spans: any contiguous rectangle of cells, 1–2 cells wide and
  1–3 cells tall. Max width is 2 (2026-11-16: "Widgets
  cannot be 3 spaces wide") — no 3-wide or 4-wide widgets, ever.
- Whole cells only (2026-11-16): a widget's bounds align
  exactly to cell edges — no partial cells, no off-grid sizes or
  offsets. A multi-cell span absorbs the gutters between its cells
  into one rectangle.
- Uniform sizing (2026-11-16): every widget is sized ONLY
  by whole cells of this grid — the host derives each widget's
  rectangle from the shared constants (cell 218×100, gutter 10,
  origin x 28). No custom sizes or off-grid offsets; same span =
  same size, anywhere on screen. The host rejects off-grid spans.
- A widget may instead anchor TOP_RIGHT (2026-11-16):
  the top row's rightmost cells, computed from the same grid —
  a 2-wide widget lands exactly on A3+A4 (x 484, y 28). The
  weather widget lives there. (An A4 settings-widget reservation
  from earlier the same day is superseded: Settings is a tile in
  the overflow section now, and the floating corner gear is gone.)
- Grid flow (non-anchored widgets) uses the grid's own rows below
  row A: flow row 0 is row B (y=138dp), flow row 1 is row C
  (y=248dp). Columns use the grid's x math unchanged.
- Widgets are display-only (not focusable) in normal use; d-pad
  flow stays bar-only. In edit mode (the Widgets tile in the
  overflow section) they become focusable and user-managed:
  arrows move the focused widget cell by cell (grid-snapped,
  collision-checked, A1+A2 reserved for the clock), ENTER
  finishes, MENU removes the widget to the pool, BACK exits.
  Pooled widgets appear as a "+ NAME" ghost at the first free
  cell — steer it and press ENTER to add the widget there.
  Placements persist across restarts.

## Fixed zones (not widgets)

| Zone | Rect (dp) | Notes |
| --- | --- | --- |
| Background | full screen | Live video loop, full bleed |
| Settings | a tile in the overflow grid | Gear glyph + "Settings" label, styled like the app tiles; fires the system Settings intent. Replaced the floating corner gear 2026-11-16. MENU also opens Settings |
| App bar | y ~360–510, full width | 6 slots + chevron; overflow grid expands above it |

## Current assignments

- Clock + date: spanning A1+A2. The block snaps to the cells
  exactly (100dp tall from y=28): time at the top, the date pinned
  to the bottom of row A (2026-11-16 — the date used to
  hang into row B; that overflow is gone).
- Weather: BUILT 2026-11-16 as a self-contained widget bundle
  (own Open-Meteo fetching, 30-min refresh). Anchored TOP_RIGHT —
  cells A3+A4 — 2×1 (446×100dp). Frosted card — a blurred,
  tinted snapshot of the background video behind it, refreshed
  every 15s, edges feathered; Bebas type throughout, letterspaced
  caps like the date line. Big temperature, place/condition/H-L
  stack, 3 forecast columns.
- Settings: a tile in the overflow grid (see Fixed zones). The
  earlier A4 bare-icons settings-widget idea is shelved.
- Everything else: unassigned.

## Taste rule

The right-center of the background is the scenic focal point
(mountains over the lake). Prefer left-column and edge slots; don't
pave the vista with cards just because slots exist.
