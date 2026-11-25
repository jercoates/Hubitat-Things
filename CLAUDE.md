# Aeotec HEM Gen2 Dual Clamp — Hubitat driver

Hubitat Elevation (C-8) Groovy driver for the Aeotec / Aeon Labs Home Energy Meter **Gen2 (DSB28-ZWUS), 2-clamp**. Purpose: expose each leg (clamp) separately instead of the single whole-house value the built-in driver gives, plus daily/monthly kWh rollups for future solar sizing.

Owner has no formal programming background and verifies behaviorally on real hardware. Explain findings plainly; flag anything that needs a hardware test rather than guessing.

## Files
- `drivers/aeotec-hem-gen2-dual.groovy` — the driver (single file, no libraries)
- `packageManifest-aeotec.json` — Hubitat Package Manager manifest for this driver only (repo `jercoates/Hubitat-Things`, branch `main`)
- `README.md` — user-facing install/usage

## Status
- **Verified on hardware (2026-11-22):** per-leg power/energy reports arrive and populate `power1`/`power2`/`energy1`/`energy2` (e.g. 968.0 W / 530.7 W, whole 1547.9 W). Config via params 101/102/111/112 works.
- **NOT yet verified:** v1.1.0 rollup code (trackEnergy / rollDay / scheduling) has never been compiled or run. Written without a Groovy toolchain.

## Device facts (sourced)
- Gen2 reports **W and kWh only** — no V or A. Do not add voltage/current capabilities.
- Param 101/102/103 bitmask (Gen2): 4 = W whole, 8 = kWh whole, 256 = W clamp 1, 512 = W clamp 2, 2048 = kWh clamp 1, 4096 = kWh clamp 2. (Bits 1/2 = battery/multisensor reports — not wanted.) Gen5 (ZW095) uses a DIFFERENT mask — don't mix them up.
- Params 111/112/113 = group intervals (seconds, 4 bytes). Param 3 = selective reporting enable (1 byte). Params 4/5/6 = W change thresholds whole/clamp1/clamp2 (2 bytes).
- Clamp reports arrive as `MultiChannelCmdEncap` with `sourceEndPoint` 1/2; whole-house reports are unencapsulated (endpoint 0).
- Gen2 is known to occasionally emit garbage W values (huge/negative) — filtered by `maxWatts` preference and `< 0` check.
- Fingerprint `0086 / 0002 / 001C` came from a community driver; not independently confirmed.

## Design decisions (keep unless there's a real bug)
- Rollups accumulate **deltas between reports** rather than diffing the meter's cumulative counter, so meter resets and Reset Energy don't corrupt totals.
- Delta sanity: reject jump > 50 kWh or a drop to anything other than < 1 kWh (true reset). After 3 consecutive rejects, accept the new value as baseline with zero delta (prevents getting stuck forever).
- `refresh()` at 23:59:00 to capture end-of-day reading; `rollDay()` at 00:00:05. Month rolls when local day-of-month == 1 at that time.
- `updated()` calls `unschedule()` first — intentionally clears jobs left over from the previously-used viertaxa Gen5 driver.
- Optional child devices use built-in `Generic Component Energy Meter`; component callbacks `componentRefresh` / `componentResetEnergy` implemented.

## Review checklist (please check these specifically)
1. Compiles in Hubitat's sandbox (no disallowed classes/methods).
2. `BigDecimal` values stored in `state` maps round-trip correctly (they come back as Number/String from JSON) — `bd()` helper converts via `toString()`; confirm no type errors in `val >= prev`, `+ delta`.
3. Groovy default-parameter overloads: `zwaveEvent(MeterReport cmd, Integer ep = 0)` and `zwaveEvent(Command cmd, Integer ep = 0)` — confirm dispatch picks MeterReport for both 1-arg (unencapsulated) and 2-arg (encapsulated) calls and doesn't fall to the generic handler.
4. `new Date().format("d", location.timeZone)` is allowed in the sandbox and returns local day.
5. `schedule()` cron strings valid for Hubitat (Quartz, 6–7 fields).
6. `isStateChange: true` on Yesterday/LastMonth so identical consecutive values still record a daily event.
7. Commands from `configure()`/`refresh()`/`resetEnergy()` are sent via `sendHubCommand(HubMultiAction)` — required because `updated()` discards return values. Don't "simplify" to returning command lists.
8. `zwaveSecureEncap` on a non-secure classic device should be a pass-through — confirm.
9. Any logic error in trackEnergy reset/strike handling.

## Testing on hardware
- Turn on debug logging; Save Preferences; Configure. Expect readback `param 101 = 772`, `param 102 = 6152`, `param 111 = 30`, `param 112 = 300`.
- `energyToday`/`energy1Today`/`energy2Today` should start at 0 and climb every ~5 min (group 2 interval).
- To test rollDay without waiting for midnight: temporarily add `command "rollDay"` to metadata, run it, confirm Yesterday attributes populate and Today resets to 0. Remove afterward.
- Reset Energy should zero meter counters and Today/ThisMonth; next report should NOT produce a huge delta.

## Conventions
- Namespace `jercoates`. Bump the version in the driver header AND `packageManifest-aeotec.json` together; note changes in README changelog.
- Keep it a single file. No external libraries.
