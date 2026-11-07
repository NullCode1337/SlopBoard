# GLASS_NOTES

Design decisions, defaults and the phase log for the iOS 26 "Clear" liquid-glass
launcher.

## Library choice

**Chosen: `QWEA0/Liquid-Glass-Android` v2.0.11 (MIT), vendored under `libs/liquidglass/`.**
It is a `FrameLayout`-based `LiquidGlassView`, so it drops into Launcher3's View
hierarchy without a Compose migration. It has the API 33+ single-pass AGSL lens
pipeline (SDF refraction, dispersion, two-lobe rim specular, adaptive tint),
`REGULAR`/`CLEAR` materials, `backdropSource`, and the API 36+ polish needed for the
API 37 target.

**Not chosen: `QmDeve/AndroidLiquidGlassView`.** It is also View-based, but its
shader is a single simpler AGSL pass with no `CLEAR`/`REGULAR` material model, no
adaptive tint, no sensor/rim model, and no sub-API-33 pipeline. It is a good demo,
but the QWEA0 core matches the reference material and gives us the dock/search
quality we need.

## Defaults chosen (no question asked)

* Grid: **4 columns × 6 rows**, 4 hotseat icons, 3×3 folders, 66 dp tiles, no labels.
  Added as the `4_by_6` grid in `res/xml/device_profiles.xml`; it is selected for the
  myron display class (365×793 dp) by distance.
* Tile material: **CLEAR**, with a per-package dark tone for dialer/Facebook/Settings/
  Maps/Recorder and `hash & 7 == 0`. Corner radius = 22.4 % of tile side.
* Search pill replaces the page dots. Tapping opens All Apps with the search field
  focused.
* Wallpaper: the launcher rasterises the static system wallpaper into a screen-space
  bitmap and paints it in-window, because the system wallpaper is in another window
  and cannot be sampled. Live wallpapers are shown as their current static frame (or
  a dark gradient if the provider returns nothing).
* Dock: one glass pill (93 % width) behind the four dock plates.
* Recents: existing horizontal paging + a glass plate and two ghost plates per task
  (stacked look), rather than replacing Quickstep's paging model.

## Phase log

### Phase 1 — vendor, wallpaper, build plumbing
Vendored the MIT View core into `libs/liquidglass` as Soong `android_library` + `libnativegauss`.
Added `GlassBackdrop`, `GlassWallpaperView`, `GlassSurface`, `GlassBadge`, `GlassPageInvalidator`.
`GlassWallpaperView` is a full-window (`layout_ignoreInsets`) first child of `LauncherRootView`.
Build: `m Launcher3QuickStep` (Soong), `jni_libs: ["libnativegauss"]`; details in AGENTS.md.
Default: wallpaper is a static in-window bitmap; parallax and live animation are not sampled.

### Phase 2 — home screen clear tiles, dock, search, folders
4×6 grid (`4_by_6`, 66 dp, no labels), 4 hotseat icons, 3×3 folders.
`CellLayout.drawGlassTiles()` draws plates for `BubbleTextView`/`FolderIcon`; white glyphs, 1 dp underlay, white numeric badges.
`Workspace.onScrollChanged` invalidates visible pages so the wallpaper sampling matrix follows the page.
Dock is a `LiquidGlassView` (93 % width) behind the hotseat; `LiquidGlassButton` replaces the page dots as the Search pill.
Tile tone: dialer/Facebook/Settings/Maps/Recorder + `hash & 7 == 0` use the dark Clear tone.

### Phase 3 — stacked glass Recents
`TaskView.dispatchDraw()` draws a `GlassSurface.Style.TASK` plate around `getThumbnailBounds()`.
Two offset ghost plates peek below the card for the stacked look (opacity 0.31/0.22).
Quickstep's horizontal paging, gestures and task lifecycle are untouched.
Plates reuse the same wallpaper bitmap; Recents' dim/blur is approximated by `blur = 0.62`.
No per-task offscreen capture is added.

