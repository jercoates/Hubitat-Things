# Hubitat Things

Hubitat Elevation drivers and apps by Jeremy Coates (namespace `jercoates`).

| What | Files | Status |
|---|---|---|
| [Aeotec HEM Gen2 Dual Clamp](#aeotec-hem-gen2-dual-clamp) | `drivers/aeotec-hem-gen2-dual.groovy` | Per-leg readings verified on hardware; rollups (1.1.0) not yet run |
| [LinknLink eMotion Ultra + IR remotes](#linknlink-emotion-ultra--ir-remotes) | `apps/linknlink-device-service.groovy`, `drivers/linknlink-mqtt-listener.groovy`, `drivers/linknlink-emotion-ultra.groovy`, `drivers/linknlink-ir-remote.groovy` | Tested on a C-5 test hub; not yet on a production hub |

## Installing code (any of these)

**Drivers:** Drivers Code → New Driver → paste the file → Save.
**Apps:** Apps Code → New App → paste the file → Save.

---

# Aeotec HEM Gen2 Dual Clamp

Driver for the Aeotec / Aeon Labs Home Energy Meter **Gen2 (DSB28)**, 2-clamp version. It reports each leg separately, plus daily and monthly kWh totals.

## Install
**Manual:** Drivers Code → New Driver → paste `drivers/aeotec-hem-gen2-dual.groovy` → Save. On the meter's device page set Type to **Aeotec HEM Gen2 Dual Clamp** → Save Device → Save Preferences → Configure.

**Hubitat Package Manager:** Install → From a URL → `https://raw.githubusercontent.com/jercoates/Hubitat-Things/main/packageManifest-aeotec.json`

Recommended: Device Info → **Event history size** ≈ 400 so a full year of daily totals is kept.

## Attributes
| Attribute | Meaning |
|---|---|
| `power`, `energy` | Whole house W / kWh (meter counter) |
| `power1`, `power2` | Leg 1 / leg 2 W |
| `energy1`, `energy2` | Leg 1 / leg 2 kWh (meter counter) |
| `energyToday`, `energyYesterday`, `energyThisMonth`, `energyLastMonth` | Whole-house rollups |
| `energy1…`, `energy2…` (same four) | Per-leg rollups |

## Commands
- **Configure**: sends report settings to the meter
- **Refresh**: polls all readings
- **Reset Energy**: zeroes the meter's kWh counters and the Today/ThisMonth rollups
- **Create Children / Remove Children**: optional per-leg child devices (Generic Component Energy Meter)

## Preferences
- Group 1/2/3 report masks (params 101–103) and intervals (111–113). Defaults: watts every 30 s (mask 772), kWh every 300 s (mask 6152).
- Change-triggered reporting (params 3–6), off by default.
- Max watts filter (drops Gen2 garbage readings).
- Child devices, description text and debug logging.

### Report mask bits (Gen2)
4 = W whole · 8 = kWh whole · 256 = W clamp 1 · 512 = W clamp 2 · 2048 = kWh clamp 1 · 4096 = kWh clamp 2

## Notes
- Gen2 does not report voltage or current.
- Rollups count the change between readings, so meter resets don't corrupt totals. The day rolls at 00:00:05, the month on the 1st.

## Changelog
- **1.1.0** (2026-11-22): daily/monthly kWh rollups, Reset Energy clears rollups, `updated()` clears stale scheduled jobs.
- **1.0.0** (2026-11-22): initial version with per-leg W/kWh, configurable report groups, spike filter and optional child devices.

---

# LinknLink eMotion Ultra + IR remotes

Connects the **LinknLink eMotion Ultra** (60 GHz mmWave presence sensor with temperature, humidity, light and an IR blaster) to Hubitat:

- **Sensors over MQTT:** presence, 4 zones, people counts, temperature, humidity, lux, Wi-Fi signal, plus the Ultra's settings.
- **Full IR remotes:** each remote (TV, soundbar, fan, AC…) becomes its own Hubitat device. **Every button is a command**, usable from dashboards, Rule Machine and webCoRE.
- Before a remote is created, you test its codes against your real device, because brands and models use different codes.

Structure follows Albert Mulder's InvisOutlet Device Service ([almulder/Hubitat-Drivers](https://github.com/almulder/Hubitat-Drivers)).

## The four pieces (install all four)

| File | Type | What it does |
|---|---|---|
| `apps/linknlink-device-service.groovy` | App | Broker settings, picks which Ultras become devices, and the **Add a remote** wizard |
| `drivers/linknlink-mqtt-listener.groovy` | Driver | The one MQTT connection to the broker. The app creates it automatically. |
| `drivers/linknlink-emotion-ultra.groovy` | Driver | One device per eMotion Ultra: sensors, settings and the IR blaster |
| `drivers/linknlink-ir-remote.groovy` | Driver | One device per IR remote: every button is a command |

## What you need
- An MQTT broker the Ultra publishes to. In the LinknLink app, turn on the Ultra's MQTT/Home Assistant option and point it at the broker.
- For IR: the Ultra must be **unlocked** in the LinknLink app (device settings → unlock / local control), and it needs a fixed IP address (a DHCP reservation).
- The Ultra and Hubitat must be able to reach each other on the network: MQTT to the broker, and UDP port 80 from Hubitat to the Ultra for IR.

## Setup
1. Install the code, either way:
   - **Hubitat Package Manager:** Install → From a URL → `https://raw.githubusercontent.com/jercoates/Hubitat-Things/main/packageManifest-linknlink.json`
   - **Manual:** add the 3 drivers (Drivers Code) and the app (Apps Code).
2. Apps → Add User App → **LinknLink Device Service**.
3. Enter the broker IP, port, username and password, then press **Done** and open the app again. The status line should say connected.
4. **Choose sensors:** tick your Ultra(s) and type each one's IP address (needed for IR). Done. A device named after the Ultra is created.
   - Ultras are found from their MQTT messages. If none is listed, wait a minute or restart the Ultra so it announces itself.

## eMotion Ultra device

| Attribute | Meaning |
|---|---|
| `motion` / `presence` | Anyone in the sensor's area |
| `zone1`…`zone4` | Each drawn zone: active / inactive / not configured |
| `targetCount`, `fencedCount`, `zone1Count`…`zone4Count` | People counts |
| `temperature`, `humidity`, `illuminance`, `rssi` | Environment and Wi-Fi signal |
| `healthStatus` | online / offline (no messages for a while) |
| `irStatus`, `lastIr`, `learnStatus` | IR blaster status |
| `xMin`…`zMax`, `reverseXAxis`, thresholds, intervals | The Ultra's current settings |

**Commands:** Refresh (re-reads current values from the Ultra), `sendIrCode(hex)`, `learnIr`, `setDetectionRange(axis, min, max)`, `setReverseXAxis`, `setTargetCoordinatesUpload`, `setTempThreshold`, `setHumidityThreshold`, `setLuxThreshold`, `setTempHumidityInterval`, `setLuxInterval`.

**Preferences:** Ultra IP address (for IR), info logging, debug logging (turns off after 30 min).

Zones are drawn in the LinknLink phone app. Hubitat shows them but can't draw them.

## Adding a remote

In the app: **➕ Add a remote**. It has 4 pages, and **Next** always goes forward:

1. **What is it:** device type and brand. The codes come from [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB). If your brand isn't there, switch to the second database, [IRDB](https://github.com/probonopd/irdb). If your device isn't in either, choose "I'll teach the buttons from its own remote".
2. **One test:** the brand's code files are grouped by the codes they actually use (for LG TVs, 35 of 37 models share Group A). Press **Test Power** on a group. If your device reacts, pick that group. **Nothing is sent unless you press a Test button.**
3. **Your buttons:** every button of that group is already included. Test any of them, switch on **leave out** for any that don't work, and add missing buttons by:
   - **Teach it:** type a name, press Teach, then within 30 s press that button on the real remote, aimed at the Ultra.
   - **Paste a code:** Broadlink hex, Pronto hex, or raw timings in µs.
4. **Name it:** **Next** creates the remote and shows a link to it.

To change a remote later, open it from the app's main page. That opens the same button page, and Next updates the existing remote instead of making a new one.

## IR Remote device

**Using it:**
- **Device page:** every button is a command tile. Click it to send.
  `On`, `Off`, `Power`, `Volume Up/Down`, `Mute`, `Channel Up/Down`, `Input Source`, `Up`, `Down`, `Left`, `Right`, `Ok`, `Back`, `Exit`, `Home`, `Menu`, `Guide`, `Show Info`, `Play`, `Pause`, `Play Pause`, `Stop`, `Fast Forward`, `Rewind`, `Next`, `Previous`, `Record`, `Digit` (0–9).
- **Any other button** (Netflix, HDMI 1, Red, …): **Push Button** with its name, exactly as listed in the `buttons` attribute.
- **By number:** **Push** with the number from the `buttonMap` attribute, which also makes button tiles work.
- **Rule Machine / webCoRE:** pick the remote and run any command, e.g. `volumeUp`, `ok`, `pushButton("Netflix")`, `push(66)`.
- **As a switch:** `on`/`off` use the discrete Power On/Off codes when the remote has them. With only a Power toggle, on/off skip sending if the switch already shows that state. The state is then a best guess, because IR can't report back.

| Attribute | Meaning |
|---|---|
| `switch` | on / off (see above) |
| `buttons` | Every button this remote has |
| `buttonMap` | Button number → name, for `push(n)` |
| `numberOfButtons`, `pushed` | PushableButton |
| `lastButton` | Last button sent |
| `blaster` | Which Ultra sends it |

A standard command the remote has no code for logs a warning and does nothing.

## Safeguards (no log spam, no loops)
- MQTT reconnects back off from 30 s to 10 min. The backoff only resets after 5 minutes of stable connection, so a broker that keeps dropping the connection can't cause a reconnect loop.
- Duplicate MQTT messages are dropped. Events only fire when a value changes.
- IR: one retry for login and one resend at most. Commands older than 15 s are dropped instead of sent late. Error handlers never retry by themselves.
- Repeating errors and warnings are limited to one every 10 minutes (or once until things recover).
- Code downloads run at most 4 at a time, and a failed download is retried only when you press "Check again".
- Debug and raw-message logging turn themselves off after 30 minutes.

## Known limits
- RC5/RC6 codes always use toggle bit 0. A few devices ignore a repeated press because of it; teach that button from the real remote instead.
- The Test page shows at most 6 code groups per brand.
- Flipper-IRDB browsing uses GitHub's API, which allows 60 requests an hour without login. Browsing many brands quickly can hit that limit for an hour.
- The Ultra only publishes presence, zone and count changes, so a newly made device reads its current values from the Ultra's web page (needs the IP).
- Remotes learned in the LinknLink phone app live in LinknLink's cloud and can't be imported. Use the database, teach the buttons, or paste codes.

## Changelog
- **1.0.0** (2026-11-24): initial version. App, MQTT Listener, eMotion Ultra and IR Remote.
