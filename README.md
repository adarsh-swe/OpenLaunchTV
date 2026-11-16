# OpenLaunchTV

A minimal Android TV home screen. One activity, zero dependencies, no ads, no recommendations — just a clock, your apps, widgets, and a background that moves.

The frosted weather card is live — the background video keeps flowing behind the glass:

![Live frost demo](docs/live-frost.gif)

## Features

- **Live background** — a seamless looping video (a still photo shows while it loads, and on pause)
- **Icons-only dock** — your pinned apps in a row; a chevron expands the full app grid below
- **Bebas Neue clock + date** in the top-left; Settings lives in the overflow grid (MENU key also opens Settings)
- **Long-press any tile:**
  - **Move** — reorder the dock
  - **Hide / Show on home screen** — keep the dock to what you actually use
  - **Change icon** — bundled alternate designs where provided, plus generated letter monograms
  - **Uninstall** — system apps open their App Info page instead
- Focus is the icon itself: it scales up with a soft shadow. No cards, no rails, no "sponsored" anything.

## Widgets

Widgets are self-contained mini-apps that live on a 4×3 grid over the home screen (see [LAYOUT.md](LAYOUT.md)). Each widget owns its data, its refresh cycle and its rendering; the launcher only gives it whole grid cells and start/stop signals.

- **Weather** — current conditions plus a 3-day forecast for your location, fetched from [Open-Meteo](https://open-meteo.com) (free, no API key — set your coordinates in `WeatherWidget`). The card is frosted glass: a blurred, lightly tinted cutout of the live background behind it, re-rendered several times a second so the scene keeps flowing behind the glass. This is the launcher's only network call.
- **Move / remove / add** — open the overflow grid and pick the **Widgets** tile. In edit mode the d-pad moves the focused widget cell by cell (grid-snapped, collision-checked; the clock's cells are reserved), ENTER finishes, MENU removes the widget, BACK exits. Removed widgets wait in a pool as a "+ NAME" outline — steer it to a free cell and press ENTER to put the widget back. Placements persist across restarts.

## Requirements

- Android TV / Google TV, API 28 (Android 9) or newer
- The launcher registers as a `HOME` / `LEANBACK_LAUNCHER` app. Your stock launcher stays installed — switching back is one settings change. Nothing is uninstalled or hidden; the grid is built from whatever apps are installed.

## Build

Two ways:

**Script (no Gradle):** needs a JDK 17 and the Android SDK (platform 34, build-tools 34.0.0) —

```sh
./build.sh   # produces openlaunch-tv.apk, debug-signed and sideloadable
```

`build.sh` runs the toolchain directly (aapt2 → javac → d8 → zipalign → apksigner) and honours `JAVA_HOME` / `ANDROID_SDK` if set.

**Android Studio:** open the project — the Gradle files are included and work normally there.

## Install

```sh
adb install -r openlaunch-tv.apk
```

Then press Home and pick OpenLaunchTV (or set it under Settings → Apps → Default apps → Home app).

## Branches

- **`main`** — the stable checkout. Only code that has been built and verified running on a real TV gets promoted here. Build from this one.
- **`latest`** — the newest work: verified builds that have not been promoted to main yet.

## License

MIT — see [LICENSE](LICENSE).

The bundled Bebas Neue font is © its respective authors and licensed under the SIL Open Font License 1.1.
