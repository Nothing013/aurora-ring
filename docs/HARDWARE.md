# Hardware notes

Everything below was verified on a retail **Redmi Turbo 4 Pro (onyx)**, HyperOS,
kernel `6.6.118-android15`, or taken from the AW21024 driver sources shipped with
the device kernel tree. Register meanings marked *(inferred)* come from working
write sequences rather than a public datasheet.

## 1. Device probe

```
/sys/class/leds/aw21024_led/            (symlink to ../../../6-0030)
├── brightness        125
├── color             read → EIO
├── effect            cfg[0..5] list + "current cfg"
├── hwen              1
├── max_brightness    255
├── period            read → EIO
├── reg               read/write register dump
├── repeat            read → EIO
├── rgbcolor          write-only
└── run               read → EIO
```

- Driver module: `leds_awrgb21024`
- I²C: bus 6, address `0x30` (`6-0030`)
- Chip: **Awinic AW21024**, 24 constant-current channels
- Device tree: `reset-gpio`, `apse_mode`, `clk_pwm = <1>` (8 MHz),
  `max_brightness = <255>`, `led_current = <125>`, `brightness = <125>`

The driver exposes six canned effects through `effect`:

```
cfg[0] = aw21024_cfg_led_off
cfg[1] = aw21024_all_leds_on
cfg[2] = aw21024_red_leds_on
cfg[3] = aw21024_green_leds_on
cfg[4] = aw21024_blue_leds_on
cfg[5] = aw21024_breath_leds_on
```

Writing a number selects a canned pattern; it is not fast enough for animation,
but it is a useful "is the hardware alive" test.

## 2. The Binder service

HyperOS publishes `miui.lights.ILightsManager`. The interface has exactly four
transactions (order is ABI, not documentation):

| # | Method | Signature |
| --- | --- | --- |
| 1 | `setColorfulLight` | `(String pkg, int styleType, int userId)` |
| 2 | `setColorCommon` | `(int color, String pkg, int styleType, int userId)` |
| 3 | `setColorLed` | empty implementation |
| 4 | `setCustomLight` | `(int color, int flashMode, int onMs, int offMs, int brightNessMode, String pkg, int styleType, int userId)` |

`styleType` selects the scene. The values that matter:

| style | Meaning |
| --- | --- |
| `-1` | reset / release the scene |
| `2` | dual-colour scene (firmware-dependent) |
| `3` | ring animation zone around the camera |
| `12` | camera scene, used by the stock camera app |

The service checks the **caller package string**, not the caller UID, so
`pkg = "com.android.camera"` is accepted from any app that holds
`com.miui.permission.ACCESS_SET_LIGHTS` (or reaches the service through
Shizuku). This is why both DashDazz and Aurora can drive the ring without being
the camera.

### Why `setColorCommon` and not `setCustomLight`

`setCustomLight` schedules a `Handler` auto-off timer for every call. At 30–50
frames per second the timers overlap, cancel each other and produce visible
flicker; it is designed for "flash N times then stop" notifications.

`setColorCommon(color, pkg, 3, 0)` is timer-free: it sets the colour and leaves
it. It is the only Binder call suitable for animation, and it is the Shizuku
backend in Aurora.

The AIDL class is deliberately **not** generated into the app. A local class
named `miui.lights.ILightsManager` would shadow the platform class inside the
boot class loader and break `SystemServiceHelper`. Aurora writes the parcels by
hand instead (`led/LightsApi.kt`).

## 3. Root path: the AW21024 register map

With root, Aurora opens one persistent `su` shell and writes the sysfs `reg`
node. Each frame is a single line:

```
echo '0xa8 0xff' > /sys/class/leds/aw21024_led/reg; ...
```

The node accepts `0xRR 0xVV` pairs. Write `0xFFFEFEFE` instead of `-1` for white
(the driver rejects negative input).

### Single colour (whole ring)

| Register | Value | Purpose |
| --- | --- | --- |
| `0xa8` | R | red *(inferred)* |
| `0xa9` | G | green *(inferred)* |
| `0xaa` | B | blue *(inferred)* |
| `0xab` | `0xff` | enable all 24 channels |

Initialisation before the first frame (once per session):

```
hwen = 1
effect = 2
0x00 = 0x11      chip config
0x6e = 0xff      global current
0x7a = 0x00      single-colour mode
0xa0 = 0x00      breath off
0xa1 = 0x00      breath engine off
0xac = 0x00      pattern off
0xab = 0xff
```

### Dual half-ring

```
hwen = 1
effect = 2
0x00 = 0x11
0x6e = 0xff
0x7a = 0x01      split mode
0xa0 = 0x00
0xa1 = 0x00
0xab = 0x00
0xac = 0x10      split/pattern enable
0x01..0x18 = 0xff   per-channel brightness for all 24 LEDs
```

Per frame:

| Register | Purpose |
| --- | --- |
| `0x5c/0x5d/0x5e` | RGB of half A |
| `0x53/0x54/0x55` | RGB of half B |
| `0x49 = 0x00` | latch |

### Hardware spin (two spots)

Initialisation:

```
0x7a = 0x00
0xac = 0x00
0xa7 = 0xcc
0xab = 0xff
```

Per frame:

| Register | Value |
| --- | --- |
| `0xa8/0xa9/0xaa` | RGB |
| `0xab` | `0x40` = spot A, `0x08` = spot B |

Alternating the mask makes one light appear to orbit the ring at the frame rate,
with no software interpolation.

### Hardware breath

```
0xa1 = 0x00        disable while configuring
0x00 = 0x11
0x6e = 0xff
0x7a = 0x00
0xa0 = 0x07        breath period
0xa2 = 0x44
0xa3 = 0x44
0xa6 = 0x66
0xa7 = 0x00
0xa8/0xa9/0xaa     RGB
0xab = 0xff
0xac = 0x00
0xa1 = 0x01        start
```

Aurora does not use hardware breath for its main animations (software frames
give exact control), but the sequence is kept for repair/diagnostics.

## 4. Global ring brightness

`Settings.Global lamp_effect_brightness` (80–255) is applied as an alpha by the
MIUI lights service. Changing it flashes the ring white for ~5 seconds and
interrupts effects, so Aurora never touches it. Brightness is applied in
software by scaling the RGB values, which is instant and flicker-free.

## 5. Stock music light

HyperOS has its own per-app music effect. Its state is readable and writable
through `AudioManager` parameters:

```
getParameters("Get_MusicLight_Package_State=")
setParameters("Set_MusicLight_Package_State=<pkg>:true|false")
```

`setParameters` throws `SecurityException` on some builds but the value is still
applied. Aurora does not depend on it; if the stock effect fights the app, turn
it off in the stock camera settings.

## 6. Backend selection and write cost

`LedController.refreshPath()` probes root first:

1. `su -c id` → contains `uid=0` → **ROOT**
2. else Shizuku alive and granted → **SHIZUKU**
3. else **NONE**

| Backend | Per-frame cost | Capabilities |
| --- | --- | --- |
| Root | one `write()` to a persistent shell (~100 bytes) | solid, dual split, spin spots, hardware breath |
| Shizuku | one Binder transaction, rate-limited to ~33 fps | one solid colour for the whole ring |

Both paths de-duplicate identical frames, so a static colour produces zero
writes.

## 7. Safety and recovery

- `Reset to stock` replays the camera-scene reset (`setCustomLight` style 12 +
  `setColorCommon` style -1) and restores the register defaults.
- `Repair LED` re-probes the backend and re-runs the init sequence.
- Stopping the service always releases the scene, so the stock camera effects
  come back.
- No register is written outside `0x00–0xac`, and no persistent setting is
  changed.
