<img src="docs/logo.png" width="160" align="right" alt="Inkverse">

# Inkverse

Night-mode colour inversion for BOOX e-ink tablets, for people who read scanned
books and want white-paper-black-ink pages to become black-paper-white-ink after
dark — without giving up the reader they already use.

Built and tested on a **BOOX Note Air5 C** (Android 15, Onyx 4.2.1, SM6350).

## Why this exists

Every standard way of inverting an Android screen is inert on this device. Each of
these was tested against the physical panel, not just read back from a settings value:

| Method | Result |
| --- | --- |
| Accessibility "Colour inversion" | Setting persists, panel unchanged |
| SurfaceFlinger colour matrix (txn 1015) | Matrix stored as `-1.000`, panel unchanged |
| SurfaceFlinger daltonizer (txn 1014) | Call accepted, panel unchanged |
| `cmd color_display` | Exposes saturation only; no inversion |
| Onyx EAC per-app config | No invert field exists anywhere in it |

SurfaceFlinger hands the colour transform to the composer rather than folding it into
its own GPU pass, and nothing downstream on the E-Ink path applies it. Hardware
composition is disabled device-wide (`h/w composer disabled`, every layer
`Client/Client`), so the transform has a GPU path available and still is not taken.

So the pixels have to be produced already inverted, as ordinary app content.

## Why it works the way it does

Two non-obvious constraints shape the whole design.

**A normal overlay cannot be both opaque and non-touchable.** `TYPE_APPLICATION_OVERLAY`
plus `FLAG_NOT_TOUCHABLE` gets its alpha clamped to 0.80 by WindowManager as
tapjacking protection. At 0.8 the inverted image blends back into the original
underneath and the screen lands on flat grey. A window owned by a running
**AccessibilityService** is trusted and exempt, so it can be fully opaque *and* let
pen and touch through.

**MediaProjection cannot feed it.** An opaque fullscreen overlay is part of the
display it is capturing, so the capture feeds on its own output; the loop converges
to uniform grey within a couple of seconds. `FLAG_SECURE` breaks the loop but blanks
the entire capture to black. And this firmware's capture dialog offers no single-app
option — only "cancel" and "start recording".

`AccessibilityService.takeScreenshotOfWindow()` (API 34) captures **one window**
rather than the display. The overlay is a different window, so it cannot appear in
its own input. No recursion by construction, and no consent dialog either.

```
foreground app window
        │
        │  takeScreenshotOfWindow()
        ▼
     Bitmap ──► ColorMatrixColorFilter ──► trusted opaque overlay
                                                   │
                          pen / touch ─────────────┘ passes through
```

The platform rate-limits these screenshots to roughly one per 333 ms. On E-Ink that
is not a limitation: one capture per page turn is all a reader needs, and drawing
more often just makes the panel flash.

## Modes

- **Grayscale invert** — default, and the right choice on Kaleido colour e-ink, where
  inverting RGB produces muddy casts.
- **RGB invert** — keeps hue; photographs become negatives.
- **Grayscale invert + levels** — adds a black/white-point stretch, for scans with a
  heavy grey background.
- **Passthrough** — control mode, for checking the pipeline is neutral.

## Install

Grab the APK from [Releases](../../releases), sideload it, then enable **Inkverse**
under Settings → Accessibility. Open the app and press START; leave the app and the
inverted view appears. The overlay hides itself whenever you are in Inkverse, so its
own controls stay readable.

Requires Android 14+ (API 34) for `takeScreenshotOfWindow`.

### Updating

Inkverse updates itself from GitHub Releases: it checks on launch and offers the new
version, installing through `PackageInstaller` rather than a `FileProvider` intent so
the app keeps its zero dependencies. Turn the check off in the app if you would
rather not have it phone GitHub.

If you would rather a store handled it, [Obtainium](https://github.com/ImranR98/Obtainium)
tracks this repo's releases with no extra work on either side.

## Notes for BOOX devices

- Onyx **auto-freeze** disables sideloaded apps, which shows up as
  `Activity class ... does not exist`. Exclude Inkverse from auto-freeze in the BOOX
  app manager, or run `adb shell pm enable com.bayanasar.inkverse`.
- Onyx's own EAC post-processing still runs underneath. Its per-app
  `ditherThreshold` pushes anything above the threshold to pure white, so very light
  inverted text can disappear; the levels mode exists partly to work around that.
- Kaleido's colour filter array means a dark background reflects as mid-grey rather
  than true black. That is the panel, not the software.

## Debug channel

The overlay is opaque, so when a mode renders badly its on-screen controls are
exactly what you cannot see. Everything is therefore also driveable over adb:

```sh
adb shell am broadcast -a com.bayanasar.inkverse.DIAG -f 0x01000020 --es cmd start --es pkg '*'
adb shell am broadcast -a com.bayanasar.inkverse.DIAG -f 0x01000020 --es cmd mode --ei shader 2
adb shell am broadcast -a com.bayanasar.inkverse.DIAG -f 0x01000020 --es cmd probe
adb shell am broadcast -a com.bayanasar.inkverse.DIAG -f 0x01000020 --es cmd stop
```

`probe` logs the luminance of the captured frame at five points, which is how you
tell "the shader is wrong" from "the capture is empty" without trusting your eyes on
a washed-out panel.

## Building

```sh
gradle :app:assembleRelease
```

Kotlin, no dependencies at all — not even AndroidX. R8 is on for release builds
because Kotlin's stdlib is otherwise most of the APK (2 MB before, 38 KB after).

Flutter is not an option here and it is worth saying why: the app *is* an
`AccessibilityService`, a platform class the system instantiates from the manifest,
and the overlay is a `TYPE_ACCESSIBILITY_OVERLAY` window owned by it. Neither can be
expressed in Dart; a Flutter build would still need this same Kotlin underneath, plus
a platform channel shuttling every captured bitmap in and out of the Dart VM.

Tagging `v*` builds and publishes a signed APK. Releases from v0.1.2 on are signed
with a stable key held in the repository secrets, so they install over one another;
v0.1.0 and v0.1.1 were each signed with a throwaway key and have to be uninstalled
first.

## Licence

MIT
