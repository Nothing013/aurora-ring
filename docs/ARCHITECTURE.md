# How the old apps work, and what Aurora does differently

This document is the reverse-engineering report behind Aurora Ring. It covers the
two projects the hardware protocol was learned from, what each feature actually
did at the code level, the defects that made the ring feel wrong, and the design
that replaces them.

Sources read in full:

- **DashDazz** — `olivia-hye/DashDazz`, Kotlin/Compose camera-ring controller
  (`DashDazzService.kt` 1 725 lines, `AudioReactiveEngine.kt` 1 349 lines,
  `LightsBridge.kt`, `ILightsManager.java`, `MainActivity.kt` 3 417 lines, the
  tile, the notification listener, `Prefs`, `Palettes`, `ConflictDetector`).
- **HaloLight** — `fan-world-me/HaloLight`, protocol research
  (`docs/BINDER.md`, `FINDINGS.md`, `RESEARCH.md`, `API.md`, `TIMELINE.md`,
  `BRIGHTNESS.md`, `RHYTHM.md`).
- The AW21024 driver sources shipped with the onyx kernel tree.

---

## 1. The stack

```
Compose UI
   │  settings / intents
   ▼
Foreground service ──► frame generator (animation or audio)
   │                        │
   │                        ▼
   │                  LedController / LightsBridge
   │                     │            │
   │            root sysfs            │ Binder
   │                │                 ▼
   │                │      miui.lights.ILightsManager
   │                │                 │
   ▼                ▼                 ▼
/sys/class/leds/aw21024_led/reg    MIUI lights service
                    │                 │
                    └───────┬─────────┘
                            ▼
                 Awinic AW21024 (I²C 6-0x30, 24 channels)
                            ▼
                     camera ring LEDs
```

The ring is **24 single-colour LEDs** driven by a 24-channel constant-current
driver. Every "animation" is therefore either

1. a sequence of RGB values written to the chip (software animation), or
2. a hardware pattern (breath/split/spin) configured once and left running.

There is no per-LED addressable strip: the driver exposes whole-ring colour
registers, two split halves, and two spot masks. Everything the UI calls a
"dual ring" or a "spin" is one of those three hardware layouts.

## 2. The Binder interface (both apps)

`miui.lights.ILightsManager` has four transactions, in ABI order:

| # | Method | Arguments |
| --- | --- | --- |
| 1 | `setColorfulLight` | `String pkg, int styleType, int userId` |
| 2 | `setColorCommon` | `int color, String pkg, int styleType, int userId` |
| 3 | `setColorLed` | present but empty on HyperOS |
| 4 | `setCustomLight` | `int color, int flashMode, int onMs, int offMs, int brightnessMode, String pkg, int styleType, int userId` |

Key behaviours:

- The service checks the **package string**, not the caller UID. Passing
  `"com.android.camera"` is accepted, which is how a third-party app drives the
  ring at all.
- `styleType = 3` is the ring animation scene; `-1` resets; `12` is the stock
  camera scene.
- `setColorCommon` is timer-free. `setCustomLight` schedules a Handler auto-off
  for **every call**, so using it at 30–50 fps makes the timers cancel each other
  and the ring flickers. Both old apps learned this the hard way; HaloLight's
  `BINDER.md` documents it, DashDazz's `LightsBridge` avoids it for animation.
- A local AIDL class named `miui.lights.ILightsManager` must **not** be generated:
  it shadows the platform class in the boot class loader and breaks
  `SystemServiceHelper`. Aurora writes the parcels by hand.

## 3. DashDazz, feature by feature

### 3.1 Service and loop

`DashDazzService` is a foreground service. `onStartCommand` reads `Prefs`, then:

- `shouldRunNow()` decides whether the loop is allowed (screen mode, enabled flag);
- `launchAnimationLoop()` starts a coroutine that, every frame, calls the current
  animation function and `LightsBridge.setColor(...)`;
- `startAudioEngineIfNeeded()` starts `AudioReactiveEngine` when audio is on.

Animations are time-parameterised functions of `SystemClock.elapsedRealtime()`,
so they are stateless and resume cleanly after a pause. A frame is a single
`setColor` (Binder) or a register write (root).

### 3.2 Animations

Implemented as `when (mode)` branches over a phase `t = (now % period) / period`:
rainbow hue rotation, palette cross-fade, breathe (`0.5 - 0.5cos`), solid,
dual-half, spin, comet/flow, fire/candle noise, strobe, and audio visualiser.
Colours come from `Palettes` (HSV interpolation for Spectrum, explicit stops for
the rest).

### 3.3 Dual and spin

- **Dual**: root path writes `0x5c–0x5e` (half A), `0x53–0x55` (half B) and
  latches `0x49 = 0`; the driver is put in split mode with `0x7a = 1`,
  `0xac = 0x10`, `0xab = 0`, per-channel brightness `0x01–0x18 = 0xff`.
- **Spin**: root path sets `0xa7 = 0xcc` and alternates the `0xab` channel mask
  (`0x40` / `0x08`), which makes the lit spot appear to orbit with zero software
  interpolation.
- Shizuku cannot do either: `setColorCommon` is whole-ring only, so the old app
  blended the two halves into one colour on the fallback path.

### 3.4 Brightness

DashDazz scaled the RGB value before writing. The MIUI global
`lamp_effect_brightness` (80–255) is deliberately not touched: changing it
flashes the ring white for about five seconds and interrupts effects. Aurora
keeps the same rule and applies brightness in software.

### 3.5 Notifications

`DashDazzNotificationListener` receives `onNotificationPosted`, filters ongoing
and group-summary posts, debounces by key, and asks the service to flash. The
flash was implemented by switching the service into a notification mode and
scheduling a return to the previous mode.

### 3.6 Screen modes

`ALWAYS`, `SCREEN_ON`, `SCREEN_OFF`, and `NOTIF_ONLY`. The first three gate the
loop with `PowerManager.isInteractive`. The fourth was intended to keep the ring
dark until something happened — but it was implemented by **stopping the loop**
(see §5.1).

### 3.7 Extras in DashDazz

- Quick Settings tile (`TileService`) toggling the service.
- Boot receiver restoring the last state.
- `ConflictDetector`: warns when the stock camera/music light owns the ring.
- Charging and low-battery behaviours.
- App filter for notifications.
- Root shell wrapper with a persistent `su` process.

## 4. HaloLight, and what it added

HaloLight is primarily a research project. Its `docs/` are the authoritative
notes used here:

- `BINDER.md` — transaction order, signatures, `setColorLed` no-op, the
  package-string check, `setCustomLight` timer behaviour.
- `FINDINGS.md` / `RESEARCH.md` — style types, scene ownership, why writing
  `-1` to a colour register fails, the `0xFFFEFEFE` white workaround.
- `BRIGHTNESS.md` — the global `lamp_effect_brightness` side effects.
- `RHYTHM.md` — the stock music-light parameter API and its race with third-party
  controllers.
- `TIMELINE.md` — boot/service ordering, why a late writer wins.
- `API.md` — the register map used by the root path (solid, dual, spot, breath).

Aurora reuses the protocol facts, not the code.

## 5. The two defects that mattered

### 5.1 "Notification only" killed the whole engine

In DashDazz the mode was handled inside `onStartCommand`:

```kotlin
if (screenMode == SCREEN_NOTIF_ONLY) {
    pauseAnimationForScreenOff()
    return          // <-- no loop, no audio
}
```

and `shouldRunNow()` also returned `false` for that mode, so
`launchAnimationLoop()` and `startAudioEngineIfNeeded()` were never reached.
Consequences:

- audio reactive never started, because the audio engine was only started next
  to the animation loop;
- toggling a notification restarted the pipeline, which is why the ring jumped;
- every feature that depended on a frame (charging, low battery, app filter
  visuals) was dead.

The mode conflated **visibility** with **lifecycle**.

### 5.2 Beat sync measured loudness, not attack

`AudioReactiveEngine` did:

1. FFT → band energy → normalise by a running peak;
2. envelope follower with a slow release (~200 ms);
3. beat when `envelope > baseline × threshold`;
4. two independent tempo estimators (instant BPM + a smoothed one);
5. clamp the inter-beat gap to 150–430 ms;
6. no latency compensation.

Problems:

- the envelope crosses its threshold **after** the transient, so the flash is
  late by construction;
- peak normalisation smears dynamics and makes quiet tracks trigger differently;
- two tempo trackers disagree, and the clamp fights real tempos;
- the LED write and Binder/audio buffers add 20–60 ms that nobody subtracted.

## 6. Aurora Ring's design

### 6.1 Layers instead of modes

`RenderEngine` owns one thread and three concepts:

- **base layer** — the selected animation, or the audio visualiser;
- **transient layers** — notification, charging, low battery;
- **compositor** — blends the topmost transient over the base with a 90 ms
  fade-in and 420 ms fade-out.

The service always runs while `running = true`. `ScreenModes.NOTIF_ONLY` only
sets `baseAllowed = false`; it never stops the loop or the audio engine. If
audio is enabled and the mode is `AUDIO` (or beat sync is on), the base layer is
the audio visual, so notification-only still reacts to music. Alerts composite
over black when there is no base. Stopping an alert does not restart anything.

Mode changes cross-fade over 380 ms by rendering the previous settings and
blending, instead of snapping.

### 6.2 One writer, two backends

`LedController` is the only hardware writer:

| Backend | How | Cost | Features |
| --- | --- | --- | --- |
| ROOT | persistent `su` shell, `echo 'reg val' > reg` | one `write()`/frame | solid, dual, spin, breath |
| SHIZUKU | `setColorCommon` via `ShizukuBinderWrapper` | one Binder tx, ≥30 ms apart | solid |
| NONE | – | – | – |

It de-duplicates identical frames (a static colour writes zero times), caches
the per-layout init sequence, and reopens the shell if it dies. `RootShell.probe`
runs `su -c id`; `refreshPath()` chooses root first, then Shizuku.

### 6.3 The new beat engine

`BeatEngine` replaces the envelope detector:

1. **Spectral flux** — `Σ max(0, mag[k] − prev[k]) / Σ mag[k]` per FFT frame.
   Level-independent, attack-sensitive.
2. **Adaptive threshold** — `median + 2.6·MAD + 0.012` over a 96-frame ring
   buffer, divided by the sensitivity. Median/MAD resist a loud drop.
3. **Onset** — novelty above threshold, above 1.05× the previous frame, above a
   noise floor, with a 105 ms refractory period.
4. **Tempo histogram** — each inter-onset interval folded into 60–180 BPM with
   ±1-bucket blur and 0.985 decay; the strongest bucket (>2.5) sets the period.
5. **Phase-locked loop** — `nextBeat += 0.35 × (onset − grid)`; the grid
   free-runs between onsets.
6. **Latency compensation** — the pulse is emitted `latencyMs` (default 45 ms,
   slider 0–200) *before* the predicted beat, so the LED peak lands on the kick.
7. **Envelope** — 8 ms attack, 125 ms exponential decay.

Silence for 8 s drops the tempo lock; a 6 s gap clears the pulse. The engine
also exposes per-band levels, confidence, and a throttled 20 Hz snapshot for the
UI, and the UI preview calls the *same* `Animator` as the service.

### 6.4 Everything else, kept or improved

| Feature | DashDazz | Aurora |
| --- | --- | --- |
| Animations | 10+ | 12, plus dual styles |
| Palettes | 8 | 8, same interpolation, shared with preview |
| Dual ring | yes | yes, 6 dual styles |
| Spin | yes | yes, hardware masks |
| Audio reactive | envelope | spectral flux + PLL + latency |
| Notifications | mode switch | transient overlay |
| Notification-only | broke the engine | base layer only, engine runs |
| App filter | notifications | notifications **and** audio gate |
| Per-app colour | yes | yes, plus per-app duration |
| Screen rules | yes | yes, plus audio screen-off switch |
| Charging / low battery | yes | yes, as transient/idle layers |
| Tile / boot | yes | yes |
| Conflict warning | yes | not needed: release/reset actions |
| Brightness | software | software |
| Root / Shizuku | yes | yes, root first |
| Frame rate | fixed | Smooth / Balanced / Eco |
| Latency calibration | no | 0–200 ms |
| Tempo reset | no | yes |
| Setup wizard | partial | full permission wizard |

### 6.5 Extra customisation

- six dual-ring behaviours (split, duo spin, alternate, crossfade, fire & ice,
  rainbow duo);
- per-app alert colour cycling and 3/4/7/10/15 s durations;
- band focus (auto/bass/mid/treble/full) and pulse strength;
- HSV colour editor with presets and randomiser;
- notification-only that is actually useful;
- LED repair/reset, backend display, Shizuku grant from inside the app;
- background survival (Doze whitelist, active standby bucket, auto-start) when
  root is available.

## 7. Safety

- Registers written are limited to `0x00–0xac`; no persistent system setting is
  changed except the optional Doze/app-op tweaks the user explicitly enables.
- `Reset to stock` replays the camera-scene reset and the driver defaults.
- `-1` is never written to a colour register (`0xFFFEFEFE` is used for white).
- The service releases the scene on stop, so the stock camera effect returns.
