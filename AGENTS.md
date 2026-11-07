# SlopBoard — iOS 26 Liquid Glass Launcher3

This repository is a fork of `Evolution-X/packages_apps_Launcher3` (branch `cnb`) with a
system-wide iOS 26 "Clear" liquid-glass treatment for the home screen, dock, search pill,
folders and Quickstep Recents.

It builds with **Soong** (`Android.bp`), not Gradle, and is normally dropped into an AOSP /
Evolution X tree at `packages/apps/Launcher3`. A KernelSU module template lives in
`kernelsu/` so the same APK can be flashed without rebuilding the ROM.

## 1. Build

From the ROM tree (the one that contains `build/envsetup.sh`):

```bash
# This checkout must be at packages/apps/Launcher3 (real directory, not a symlink:
# kati/soong does not walk symlinked source directories).
source build/envsetup.sh
lunch lineage_myron-userdebug        # or your product
m Launcher3QuickStep -j8
```

Output:

```
out/target/product/<product>/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk
```

`-j8` is deliberate: Soong's module-graph step is memory-hungry (about 24 GB RSS on a
17k-`Android.bp` tree). Higher parallelism mainly adds swap pressure.

### KernelSU module

```bash
./kernelsu/build-module.sh \
    /path/to/out/target/product/myron/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk
# -> dist/SlopBoard-Glass-KernelSU.zip
```

Flash in KernelSU, reboot. The module overlays
`/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk` and whiteouts the
ROM's `oat/` directory. The APK **must** be signed with the same platform key as the
installed ROM, otherwise PackageManager rejects it; the normal Soong build already
does this.

## 2. Repository layout

| Path | Contents |
|---|---|
| `src/` | Launcher3 Java/Kotlin sources. Glass code is in `src/com/android/launcher3/glass/`. |
| `quickstep/` | Quickstep (gestures, Recents). Glass Recents changes are in `quickstep/src/com/android/quickstep/views/TaskView.kt`. |
| `res/` | Launcher resources. `res/xml/device_profiles.xml` defines the 4×6 grid; `res/layout/launcher.xml` hosts the wallpaper view, dock glass and search pill. |
| `libs/liquidglass/` | Vendored `QWEA0/Liquid-Glass-Android` v2.0.11 View core (MIT, `libs/liquidglass/LICENSE`), converted to Soong. |
| `kernelsu/` | Flashable-module template + packaging script. |
| `AGENTS.md` | This file. |
| `GLASS_NOTES.md` | Phase log, design defaults, measured numbers. |

## 3. Glass architecture

Three rendering paths, chosen by surface size and motion:

1. **`com.example.liquidglass.LiquidGlassView`** (vendored library) — used for the
   dock plate and the search pill (`LiquidGlassButton`). These are few, large, static
   surfaces, so the library's full lens pipeline (offscreen backdrop capture + AGSL)
   is affordable and gives the best result. Its `backdropSource` is the
   `GlassWallpaperView`; `enableDynamicBackground` stays `false`.
2. **`com.android.launcher3.glass.GlassSurface`** — one AGSL pass per icon tile,
   sampling the rasterised wallpaper directly. Used for workspace icons, hotseat
   icons, folder plates and Recents task plates. 24 workspace + 4 dock tiles per page
   stay inside the frame budget because there is no per-tile offscreen capture.
3. **Fallback** — API < 33 or AGSL compile failure: translucent rounded rect + rim.

### Why a wallpaper bitmap?

Launcher3 uses `android:windowShowWallpaper="true"`: the wallpaper lives in a
different window. No view in the launcher hierarchy can be captured with the system
wallpaper. `GlassWallpaperView` (first child of `LauncherRootView`) paints a
screen-space rasterisation of the wallpaper from `WallpaperManager.getDrawable()`,
center-cropped to the current window bounds. `GlassBackdrop` keeps the sharp bitmap
plus a 1/8-scale blurred copy; the shader samples both.

Static wallpapers work exactly. Live wallpapers are rasterised to their current
`getDrawable()` frame (or the fallback gradient if the provider returns nothing), so
they do not animate behind the glass. This is a deliberate default; see
`GLASS_NOTES.md`.

### Home screen

* Grid is `4_by_6` (`res/xml/device_profiles.xml`), 4 hotseat icons, 3×3 folder grid,
  icon image size 66 dp, no labels.
* `CellLayout.drawGlassTiles()` draws one plate per `BubbleTextView`/`FolderIcon`
  before children. Plates live in the page's display list so a page scroll only
  invalidates the visible pages (`Workspace.onScrollChanged` →
  `GlassPageInvalidator`).
* `BubbleTextView` glass mode (`DISPLAY_WORKSPACE`): skips `super.onDraw()` (no
  label), draws the icon drawable as a near-white glyph with a 1 dp dark underlay,
  and draws a white numeric badge with a dark count.
* Folder plates are drawn by `CellLayout` via `FolderIcon.getGlassBounds()`;
  `PreviewBackground` fill is made transparent and the folder name is hidden.
* Dark/clear tile tone is deterministic per package: known "heavy" packages
  (dialer, Facebook, Settings, Maps, Recorder) plus `package.hashCode() & 7 == 0`.
* Dock: `LiquidGlassView` behind the `Hotseat`, 93 % width, 34 % height corner
  radius, sized from `DeviceProfile.getHotseatProfile()`.
* Search pill: `LiquidGlassButton` replaces the page dots; taps open All Apps with
  the search field focused.

### Recents

`TaskView.dispatchDraw()` draws a glass plate around `getThumbnailBounds()` plus two
offset ghost plates, producing the stacked-card look while keeping Quickstep's
existing horizontal paging. `GlassSurface.Style.TASK` uses a stronger blur mix so the
plate matches the dimmed/recents backdrop.

## 4. Vendored library rules

* MIT licence and copyright notice are kept in `libs/liquidglass/LICENSE`.
* Only the View-system core is vendored. Widgets requiring
  `appcompat`/`material`/`viewpager2` (dialog, toast, chip, tab bar, list item) are
  omitted; add them back only with the matching Soong deps.
* API pitfalls from the upstream docs still apply:
  * set `backdropSource` in code (never `glass.apply { findViewById(...) }` — that
    resolves to `View.findViewById` and returns null);
  * `enableDynamicBackground = false` for static surfaces; invalidate manually when
    the wallpaper changes;
  * window animations freeze refraction, so do not animate glass through a window
    transform;
  * `offsetTopAndBottom`/`translation` on glass hosts do not re-record children —
    invalidate the host.
* The shader path requires API 33+; the target is API 37. The vendored JNI
  `libnativegauss` is still built and packaged for the library's classic fallback.

## 5. Performance rules

* Do not add a `LiquidGlassView` per icon: that is one offscreen backdrop capture per
  tile per frame.
* Keep glass plates in the same display list as their host page/hotseat so they are
  re-recorded once per frame, not once per icon.
* Avoid `WallpaperManager` IPC from `onDraw`; `GlassBackdrop` caches by wallpaper id.
* Measure with:

```bash
adb shell dumpsys gfxinfo com.android.launcher3 reset
# swipe home, then
adb shell dumpsys gfxinfo com.android.launcher3 framestats
```

Budget: ≥ 58 fps average and no p95 frame over 16.6 ms on a full 4×6 page + dock.

## 6. Coding conventions

* Launcher3 Java style (4 spaces, braces on the same line) in `src/`; Kotlin style for
  `.kt`.
* New glass code goes in `com.android.launcher3.glass` or the surface's existing
  class. Do not invent parallel icon/folder abstractions.
* Keep the Apache 2.0 header on new Launcher files; keep the MIT header on vendored
  files.
* Commits: one author only — `NullCode1337 <contact.nullcode@proton.me>`. No
  co-authorship trailers, no agent/harness mentions.
