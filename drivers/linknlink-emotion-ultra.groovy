/**
 *  LinknLink eMotion Ultra
 *
 *  One LinknLink eMotion Ultra 60GHz mmWave presence sensor, created by the "LinknLink Device Service"
 *  app (don't add it by hand). Sensor data arrives over MQTT through the app; IR is sent straight to
 *  the Ultra on the LAN (UDP port 80, LinknLink/Broadlink local protocol).
 *
 *  SENSORS: motion/presence (whole area), zone1-zone4 (active / inactive / not configured), target
 *  counts, temperature, humidity, illuminance, rssi, healthStatus. Sensor settings (detection range,
 *  thresholds, update intervals, coordinates upload) can be read and changed here.
 *
 *  IR: this device is the IR blaster for the "LinknLink IR Remote" devices the app creates. The
 *  Ultra must be UNLOCKED in the LinknLink app (device settings -> lock off) for IR to work.
 *  sendIrCode / learnIr are here for testing; normal use is through the remote devices.
 *
 *  1.1.0 - 2026-11-25 - Works for the eMotion Pro too (model set in the app). "model" and "parts"
 *          attributes (parts = the sensors this unit has actually reported). IR commands do nothing on a
 *          model without IR. Zones drawn after the unit connected now show up when someone is in them.
 *  1.0.0 - 2026-11-24 - Initial version (split out of the single-file v1 driver). Topic map, payloads
 *          and IR protocol verified on a live eMotion Ultra 2 (radar firmware V3.18.2).
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import hubitat.helper.HexUtils

def driverVersion() { "1.1.0" }

@Field static ConcurrentHashMap<String, Long> lastUltraMsg = new ConcurrentHashMap<String, Long>()
@Field static ConcurrentHashMap<String, List> irQueue = new ConcurrentHashMap<String, List>()
@Field static final long OFFLINE_MS = 5L * 60L * 1000L
@Field static final String INIT_KEY = "097628343fe99e23765c1513accf8b02"
@Field static final String INIT_IV  = "562e17996d093d28ddb3ba695a2e6f58"
@Field static final int DEVTYPE = 0xACD7
@Field static final int LEARN_SECONDS = 30
@Field static final long STALE_IR_MS = 15000L      // queued IR older than this is dropped, never sent late
// Log throttling: key -> last time that message was logged (memory only)
@Field static ConcurrentHashMap<String, Long> logGate = new ConcurrentHashMap<String, Long>()
@Field static final long UNHANDLED_LOG_MS = 60L * 60L * 1000L     // each unhandled topic: once an hour
@Field static final long IR_ERROR_LOG_MS = 10L * 60L * 1000L      // IR "no answer": error once per 10 min
// Which module id carries which settings (ids come from the Ultra's topics: <mac><module>...)
@Field static final Map CONFIG_MODULE = [radar_x: "radar", radar_tar_up: "radar", radar_x_min: "radar", radar_x_max: "radar",
    radar_y_min: "radar", radar_y_max: "radar", radar_z_min: "radar", radar_z_max: "radar",
    t: "sht", h: "sht", sht_i: "sht", l: "opt", opt_i: "opt"]
// Current values from the Ultra's own web API (ESPHome), used to fill a new device right away
@Field static final Map WEB_VALUES = [
    "binary_sensor/zone_any_presence": "zone0", "binary_sensor/zone_1_presence": "zone1",
    "binary_sensor/Zone_2_Presence": "zone2", "binary_sensor/Zone_3_Presence": "zone3", "binary_sensor/Zone_4_Presence": "zone4",
    "sensor/all_target_counts": "AllArea", "sensor/persons_in_fenced_zones": "SetZone",
    "sensor/zone_1_target_counts": "SepZone_1", "sensor/zone_2_target_counts": "SepZone_2",
    "sensor/zone_3_target_counts": "SepZone_3", "sensor/zone_4_target_counts": "SepZone_4"]

@Field static final Map CONFIG_ATTRS = [
    radar_x: "reverseXAxis", radar_tar_up: "targetCoordinatesUpload",
    radar_x_min: "xMin", radar_x_max: "xMax", radar_y_min: "yMin", radar_y_max: "yMax",
    radar_z_min: "zMin", radar_z_max: "zMax",
    t: "tempThreshold", h: "humidityThreshold", l: "luxThreshold",
    opt_i: "luxInterval", sht_i: "tempHumidityInterval"
]

metadata {
    definition(name: "LinknLink eMotion Ultra", namespace: "jercoates", author: "Jeremy Coates", singleThreaded: true) {
        capability "Sensor"
        capability "Actuator"
        capability "MotionSensor"
        capability "PresenceSensor"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "IlluminanceMeasurement"
        capability "SignalStrength"
        capability "Refresh"

        attribute "zone1", "enum", ["active", "inactive", "not configured"]
        attribute "zone2", "enum", ["active", "inactive", "not configured"]
        attribute "zone3", "enum", ["active", "inactive", "not configured"]
        attribute "zone4", "enum", ["active", "inactive", "not configured"]
        attribute "targetCount", "number"
        attribute "fencedCount", "number"
        attribute "zone1Count", "number"
        attribute "zone2Count", "number"
        attribute "zone3Count", "number"
        attribute "zone4Count", "number"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "irStatus", "string"
        attribute "lastIr", "string"
        attribute "learnStatus", "string"
        attribute "reverseXAxis", "string"
        attribute "targetCoordinatesUpload", "string"
        attribute "xMin", "number"
        attribute "xMax", "number"
        attribute "yMin", "number"
        attribute "yMax", "number"
        attribute "zMin", "number"
        attribute "zMax", "number"
        attribute "tempThreshold", "number"
        attribute "humidityThreshold", "number"
        attribute "luxThreshold", "number"
        attribute "luxInterval", "number"
        attribute "tempHumidityInterval", "number"
        attribute "lastUnhandled", "string"
        attribute "model", "string"
        attribute "parts", "string"             // sensors this unit has reported: radar, light, temperature/humidity

        command "sendIrCode", [[name: "hex*", type: "STRING", description: "LinknLink/Broadlink IR code in hex (starts with 26)"]]
        command "learnIr"
        command "setTargetCoordinatesUpload", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"]]]
        command "setReverseXAxis", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"]]]
        command "setDetectionRange", [[name: "axis*", type: "ENUM", constraints: ["x", "y", "z"]],
                                      [name: "min*", type: "NUMBER", description: "metres (x/z: -3..3, y: 0..6)"],
                                      [name: "max*", type: "NUMBER", description: "metres (x/z: -3..3, y: 0..6)"]]
        command "setTempHumidityInterval", [[name: "seconds*", type: "NUMBER", description: "10-600"]]
        command "setLuxInterval", [[name: "seconds*", type: "NUMBER", description: "10-600"]]
        command "setTempThreshold", [[name: "degreesC*", type: "NUMBER", description: "0.1-5.0"]]
        command "setHumidityThreshold", [[name: "percent*", type: "NUMBER", description: "1-100"]]
        command "setLuxThreshold", [[name: "lux*", type: "NUMBER", description: "1-100"]]
    }
    preferences {
        input name: "deviceIp", type: "text", title: "Ultra IP address (needed for IR)", required: false
        input name: "txtEnable", type: "bool", title: "Log presence, zone and IR changes", defaultValue: true
        input name: "logEnable", type: "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false
    }
}

/* ============================== Lifecycle ============================== */

def installed() {
    log.info "${device.displayName}: installed"
    runEvery1Minute("healthCheck")
    runIn(5, "refresh")
}

def updated() {
    if (logEnable) runIn(1800, "logsOff")
    state.remove("irId"); state.remove("irKey")
    unschedule("healthCheck")
    runEvery1Minute("healthCheck")
    runIn(2, "refresh")
}

def logsOff() {
    log.warn "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// The Ultra only publishes presence/zones/counts when they change, so a new device (or one after a
// long gap) asks the Ultra's web API for the current values. Needs the IP in Preferences.
def refresh() {
    parent?.refreshListener()
    if (!settings.deviceIp) return
    WEB_VALUES.each { path, entity ->
        try {
            asynchttpGet("webValue", [uri: "http://${settings.deviceIp}/${path}", timeout: 5], [entity: entity])
        } catch (e) { logDebug "refresh ${path}: ${e.message}" }
    }
}

def webValue(resp, data) {
    if (resp?.status != 200) { logDebug "web value ${data?.entity}: HTTP ${resp?.status}"; return }
    def j
    try { j = resp.getJson() } catch (ignored) { return }
    if (j?.value == null) return
    String entity = data.entity
    if (entity.startsWith("zone")) {
        if (entity != "zone0" && device.currentValue(entity) == "not configured" && j.value != true) return
        handleEntity(entity, "status", "{\"pir_detected\":${j.value == true ? 1 : 0}}")
    } else {
        try { handleEntity(entity, "status", "${(j.value as BigDecimal).intValue()}") } catch (ignored) { logDebug "web value ${entity}: not a number" }
    }
}

def healthCheck() {
    String id = device.id as String
    Long lu = lastUltraMsg[id]
    if (lu == null) { lastUltraMsg[id] = now(); return }     // after a reboot: start the clock
    if (now() - lu > OFFLINE_MS) setAttr("healthStatus", "offline")
}

private String macLower() { (device.getDataValue("mac") ?: "").toLowerCase() }

/* =========================== MQTT (from the app) ========================== */

def mqttUpdate(String topic, String payload) {
    String mac = macLower()
    if (!mac || topic.endsWith("/set")) return          // /set = our own setting changes echoed back
    if (topic.endsWith("/status")) {                      // only the Ultra's own reports count as "alive"
        lastUltraMsg[device.id as String] = now()
        if (device.currentValue("healthStatus") != "online") setAttr("healthStatus", "online")
    }

    def m = (topic =~ /^home\/config\/${mac}[0-9a-f]*_config_([A-Za-z0-9_]+)\/(status|available)$/)
    if (m.matches()) { handleConfig(m[0][1], m[0][2], payload, topic); return }
    m = (topic =~ /^home\/(${mac}[0-9a-f]*)\/([A-Za-z0-9_]+)\/(status|available)$/)
    if (m.matches()) { noteModule(m[0][2], m[0][1]); handleEntity(m[0][2], m[0][3], payload); return }
    noteUnhandled(topic, payload)
}

// A zone marked "not configured" that reports someone in it (or a count > 0) does exist - the Ultra
// only announces which zones are drawn when it connects, so zones drawn later would otherwise stay hidden.
private void zoneSeen(String zn) {
    Map avail = (state.zoneAvail ?: [:]) as Map
    avail[zn] = true
    state.zoneAvail = avail
    if (device.currentValue(zn) == "not configured") setAttr(zn, "inactive", "${device.displayName} ${zn} is configured")
}

private void handleEntity(String entity, String kind, String payload) {
    if (kind == "available") {
        // zone1-4 report "offline" while that zone isn't drawn in the LinknLink app
        def z = (entity =~ /^zone([1-4])$/)
        if (z.matches()) {
            String zn = "zone${z[0][1]}"
            Map avail = (state.zoneAvail ?: [:]) as Map
            boolean on = payload == "online"
            if (avail[zn] != on) { avail[zn] = on; state.zoneAvail = avail }
            if (!on) setAttr(zn, "not configured", "${device.displayName} ${zn} is not configured in the LinknLink app")
            else if (device.currentValue(zn) == "not configured" || device.currentValue(zn) == null) setAttr(zn, "inactive")
        }
        return
    }
    switch (entity) {
        case "zone0":
            Integer pir = jsonInt(payload, "pir_detected")
            if (pir == null) break
            setAttr("motion", pir ? "active" : "inactive", "${device.displayName} motion is ${pir ? 'active' : 'inactive'}")
            setAttr("presence", pir ? "present" : "not present", "${device.displayName} is ${pir ? 'occupied' : 'empty'}")
            break
        case ~/zone[1-4]/:
            Integer zp = jsonInt(payload, "pir_detected")
            if (zp == null) break
            if (((state.zoneAvail ?: [:]) as Map)[entity] == false) {
                if (!zp) break              // not drawn: ignore its idle reports
                zoneSeen(entity)            // someone is in it, so it was drawn after the Ultra last announced its zones
            }
            setAttr(entity, zp ? "active" : "inactive", "${device.displayName} ${entity} is ${zp ? 'active' : 'inactive'}")
            break
        case "AllArea": setNum("targetCount", payload); break
        case "SetZone": setNum("fencedCount", payload); break
        case ~/SepZone_[1-4]/:
            setNum("zone${entity[-1]}Count", payload)
            if (payload?.trim()?.isInteger() && (payload.trim() as int) > 0 && ((state.zoneAvail ?: [:]) as Map)["zone${entity[-1]}"] == false) zoneSeen("zone${entity[-1]}")
            break
        case "temp":
            Integer t = jsonInt(payload, "envtemp")
            if (t == null) break
            BigDecimal c = (t / 100.0)
            String scale = location?.temperatureScale ?: "F"
            BigDecimal v = (scale == "F") ? (c * 9 / 5 + 32) : c
            setAttr("temperature", v.setScale(1, BigDecimal.ROUND_HALF_UP), null, "°${scale}")
            break
        case "humidty":            // sic - the Ultra's own spelling
        case "humidity":
            Integer h = jsonInt(payload, "envhumid")
            if (h != null) setAttr("humidity", (h / 100.0).setScale(1, BigDecimal.ROUND_HALF_UP), null, "%")
            break
        case "lux":
            Integer lx = jsonInt(payload, "illuminance")
            if (lx != null) setAttr("illuminance", lx, null, "lx")
            break
        case "wifi_rssi":
            if (payload?.trim()?.isInteger()) setAttr("rssi", payload.trim() as Integer, null, "dBm")
            break
        default:
            noteUnhandled("home/<ultra>/${entity}/${kind}", payload)
    }
}

private void handleConfig(String key, String kind, String payload, String topic) {
    if (kind != "status") return
    Map topics = (state.cfgTopics ?: [:]) as Map
    String setTopic = topic.replaceAll(/\/status$/, "/set")
    if (topics[key] != setTopic) { topics[key] = setTopic; state.cfgTopics = topics }
    String attr = CONFIG_ATTRS[key]
    if (!attr) { noteUnhandled(topic, payload); return }
    String p = payload?.trim()
    if (key in ["radar_x", "radar_tar_up"]) setAttr(attr, p == "1" ? "on" : "off")
    else if (p?.isNumber()) setAttr(attr, new BigDecimal(p))
}

// Remember which module id belongs to which sensor, so settings can be changed before the Ultra has
// published that setting's own topic.
private void noteModule(String entity, String id) {
    String mod = (entity ==~ /zone[0-4]|AllArea|SetZone|SepZone_[1-4]/) ? "radar" :
                 (entity in ["temp", "humidty", "humidity"]) ? "sht" : (entity == "lux") ? "opt" : null
    if (!mod) return
    Map mods = (state.modules ?: [:]) as Map
    if (mods[mod] != id) {
        mods[mod] = id; state.modules = mods
        Map names = [radar: "radar", opt: "light", sht: "temperature/humidity"]
        setAttr("parts", ["radar", "opt", "sht"].findAll { mods.containsKey(it) }.collect { names[it] }.join(", "))
    }
}

// Called by the app with the model picked for this unit
def setModel(String model, Boolean ir = true) {
    state.hasIr = (ir != false)
    setAttr("model", model)
}

private boolean irAllowed() {
    if (state.hasIr == false) {
        if (gate("${device.id}|noIrModel".toString(), IR_ERROR_LOG_MS)) log.warn "${device.displayName}: this model (${device.currentValue('model')}) has no IR blaster"
        return false
    }
    return true
}

// Unknown data (e.g. target coordinates if that upload is switched on, ~1/s) is recorded once a day
// per topic and logged at most once an hour per topic, so it can't flood events or logs.
private void noteUnhandled(String topic, String payload) {
    String k = "${device.id}|${topic}".toString()
    if (!gate("ev|" + k, 24L * 60L * 60L * 1000L)) return     // one event per topic per day
    sendEvent(name: "lastUnhandled", value: "${topic} = ${payload?.take(200)}")
    if (txtEnable && gate("log|" + k, UNHANDLED_LOG_MS)) log.info "${device.displayName}: new/unhandled MQTT data on ${topic} (logged once an hour per topic)"
}

// true when this key hasn't fired within ms (and marks it as fired now)
private boolean gate(String key, long ms) {
    Long last = logGate.get(key)
    if (last != null && now() - last < ms) return false
    logGate.put(key, now())
    return true
}

/* ======================= Sensor settings (MQTT set) ===================== */

def setTargetCoordinatesUpload(String enabled) { publishConfig("radar_tar_up", enabled == "on" ? "1" : "0") }
def setReverseXAxis(String enabled)            { publishConfig("radar_x", enabled == "on" ? "1" : "0") }

def setDetectionRange(String axis, min, max) {
    BigDecimal lo = (axis == "y") ? 0.0 : -3.0
    BigDecimal hi = (axis == "y") ? 6.0 : 3.0
    BigDecimal a = clamp(min as BigDecimal, lo, hi), b = clamp(max as BigDecimal, lo, hi)
    if (a > b) { log.error "${device.displayName}: ${axis} min must be below max"; return }
    publishConfig("radar_${axis}_min", a.setScale(1, BigDecimal.ROUND_HALF_UP).toString())
    publishConfig("radar_${axis}_max", b.setScale(1, BigDecimal.ROUND_HALF_UP).toString())
}

def setTempHumidityInterval(seconds) { publishConfig("sht_i", roundTo(clamp(seconds as BigDecimal, 10, 600), 10).toString()) }
def setLuxInterval(seconds)          { publishConfig("opt_i", roundTo(clamp(seconds as BigDecimal, 10, 600), 10).toString()) }
def setTempThreshold(degreesC)       { publishConfig("t", clamp(degreesC as BigDecimal, 0.1, 5.0).setScale(1, BigDecimal.ROUND_HALF_UP).toString()) }
def setHumidityThreshold(percent)    { publishConfig("h", (clamp(percent as BigDecimal, 1, 100) as int).toString()) }
def setLuxThreshold(lux)             { publishConfig("l", (clamp(lux as BigDecimal, 1, 100) as int).toString()) }

private void publishConfig(String key, String value) {
    String topic = (state.cfgTopics ?: [:])[key]
    if (!topic) {
        String id = ((state.modules ?: [:]) as Map)[CONFIG_MODULE[key]]
        if (id) topic = "home/config/${id}_config_${key}/set".toString()
    }
    if (!topic) { log.warn "${device.displayName}: haven't heard from the Ultra's ${CONFIG_MODULE[key] ?: key} sensor yet - try again in a minute"; return }
    if (parent?.mqttPublish(topic, value) && txtEnable) log.info "${device.displayName}: set ${CONFIG_ATTRS[key] ?: key} = ${value}"
}

/* ================================== IR ================================== */

// Called by the app / remote devices. label is only for logging.
def sendIrCode(String hex, String label = null) {
    if (!irAllowed()) return
    String h = hex?.replaceAll("[^0-9A-Fa-f]", "")
    if (!h || h.length() % 2 || !h.toLowerCase().startsWith("26")) { log.error "${device.displayName}: not a valid IR code"; return }
    queueIr([op: "send", hex: h, label: label ?: "IR code"])
}

// Puts the Ultra in learning mode for LEARN_SECONDS. The result goes to learnStatus and to the app
// (parent.irLearned). Point the remote at the Ultra and press the button once.
def learnIr() {
    if (!irAllowed()) return false
    if (!settings.deviceIp) { setAttr("learnStatus", "no IP address set in Preferences"); return false }
    state.learnUntil = now() + LEARN_SECONDS * 1000L
    setAttr("learnStatus", "waiting")
    queueIr([op: "learnStart", label: "start learning"])
    return true
}

def learnPoll() {
    if (!state.learnUntil) return
    if (now() > (state.learnUntil as long)) { learnFailed("timed out"); return }
    queueIr([op: "learnPoll", label: "check learned code"])
}

// Every way learning can end without a code goes through here, so the app is always told
private void learnFailed(String why) {
    state.remove("learnUntil")
    unschedule("learnPoll")
    setAttr("learnStatus", why)
    try { parent?.irLearned(device.deviceNetworkId, null) } catch (ignored) { }
}

private void queueIr(Map item) {
    if (!settings.deviceIp) { if (gate("${device.id}|noIp".toString(), IR_ERROR_LOG_MS)) log.error "${device.displayName}: set the Ultra's IP address in Preferences to use IR"; return }
    String id = device.id as String
    List q = irQueue[id] ?: []
    item.tries = 0
    item.at = now()
    q << item
    irQueue[id] = q
    if (!state.irAwait || (state.irAwaitAt && now() - (state.irAwaitAt as long) > 10000L)) irPump()
}

def irResume() { irPump() }      // kept for schedules from older versions; irPump drops stale items

private void irPump() {
    List q = irQueue[device.id as String]
    if (q) q.removeAll { it.at == null || now() - (it.at as long) > STALE_IR_MS }
    if (!q) { state.remove("irAwait"); return }
    if (!state.irKey) { irAuth(); return }
    Map item = q[0]
    int cmd = (item.op == "send") ? 0x02 : (item.op == "learnStart") ? 0x03 : 0x04
    byte[] data = (item.op == "send") ? HexUtils.hexStringToByteArray(item.hex) : new byte[0]
    byte[] payload = new byte[4 + data.length]
    putLE(payload, 0, cmd, 4)
    if (data.length) copyBytes(data, 0, payload, 4, data.length)
    state.irAwait = item.op
    state.irAwaitAt = now()
    udpSend(buildPacket(0x6A, payload, HexUtils.hexStringToByteArray(state.irKey), state.irId as int))
    runIn(3, "irTimeout")
}

private void irAuth() {
    byte[] p = new byte[0x50]
    (0x04..<0x14).each { p[it] = (byte) 0x31 }
    p[0x1E] = (byte) 0x01; p[0x2D] = (byte) 0x01
    byte[] name = "Hubitat".getBytes("US-ASCII")
    copyBytes(name, 0, p, 0x30, name.length)
    state.irAwait = "auth"
    state.irAwaitAt = now()
    udpSend(buildPacket(0x65, p, HexUtils.hexStringToByteArray(INIT_KEY), 0))
    runIn(3, "irTimeout")
}

def irTimeout() {
    String stage = state.irAwait
    if (!stage) return
    List q = irQueue[device.id as String]
    Map item = q ? q[0] : null
    state.remove("irAwait")
    if (stage == "learnPoll") {             // no answer while learning = nothing captured yet
        if (q) q.remove(0)
        runIn(1, "learnPoll")
        irPump()
        return
    }
    if (stage == "send") {
        // The code may well have gone out even though the reply was lost - resending could toggle
        // a device twice (e.g. Power), so don't. Log in again before the next send.
        String wm = "${device.displayName}: no reply for ${item?.label ?: 'IR code'} (it may still have been sent)"
        if (gate("${device.id}|irNoReply".toString(), IR_ERROR_LOG_MS)) log.warn wm else logDebug wm
        setAttr("irStatus", "no reply")
        state.remove("irKey"); state.remove("irId")
        if (q) q.remove(0)
        irPump()
        return
    }
    if (stage == "auth" && item && item.tries < 1) {
        item.tries++
        if (gate("${device.id}|authRetry".toString(), IR_ERROR_LOG_MS)) log.warn "${device.displayName}: no IR login reply from ${settings.deviceIp}, retrying"
        irAuth()
        return
    }
    // an unplugged Ultra must not log an error on every button press: error once per 10 min, then debug
    String m = "${device.displayName}: the Ultra did not answer at ${settings.deviceIp} (is it on, and unlocked in the LinknLink app?)"
    if (gate("${device.id}|irNoAnswer".toString(), IR_ERROR_LOG_MS)) log.error m else logDebug m
    setAttr("irStatus", "no response")
    if (item?.op?.startsWith("learn") || state.learnUntil) learnFailed("no response from the Ultra")
    if (q) q.remove(0)
    irPump()
}

def udpResponse(description) {
    try {
        def msg = parseLanMessage(description.toString())
        if (!msg?.payload) return
        byte[] r = HexUtils.hexStringToByteArray(msg.payload as String)
        if (r.length < 0x38) { logDebug "short IR reply (${r.length} bytes)"; return }
        String stage = state.irAwait
        if (!stage) { logDebug "IR reply with nothing waiting - ignored"; return }
        int ptype = (r[0x26] & 0xFF) | ((r[0x27] & 0xFF) << 8)
        // 0x3E9 answers a login, 0x3EE answers a command; anything else is a late/crossed reply
        if ((stage == "auth" && ptype != 0x3E9) || (stage != "auth" && ptype != 0x3EE)) { logDebug "IR reply type 0x${Integer.toHexString(ptype)} doesn't match '${stage}' - ignored"; return }
        unschedule("irTimeout")
        int err = (r[0x22] & 0xFF) | ((r[0x23] & 0xFF) << 8)
        if (err >= 0x8000) err -= 0x10000
        List q = irQueue[device.id as String]
        Map item = q ? q[0] : null
        state.remove("irAwait")

        if (stage == "auth") {
            if (err != 0 || r.length <= 0x38) {
                if (gate("${device.id}|authRefused".toString(), IR_ERROR_LOG_MS)) log.error "${device.displayName}: IR login refused (error ${err}) - unlock the device in the LinknLink app"
                setAttr("irStatus", "login refused")
                irQueue[device.id as String] = []
                if (state.learnUntil) learnFailed("login refused - unlock the Ultra in the LinknLink app")
                return
            }
            byte[] body = aes(slice(r, 0x38, r.length), HexUtils.hexStringToByteArray(INIT_KEY), false)
            state.irId = (body[0] & 0xFF) | ((body[1] & 0xFF) << 8) | ((body[2] & 0xFF) << 16) | ((body[3] & 0xFF) << 24)
            state.irKey = HexUtils.byteArrayToHexString(slice(body, 4, 20))
            logDebug "IR login ok (id ${state.irId})"
            irPump()
            return
        }

        if (q) q.remove(0)
        switch (stage) {
            case "send":
                if (err == 0) {
                    setAttr("irStatus", "ok")
                    sendEvent(name: "lastIr", value: item?.label ?: "IR code")
                    if (txtEnable) log.info "${device.displayName}: sent ${item?.label ?: 'IR code'}"
                } else if (item && item.tries < 1) {
                    // the device rejected it (session expired?) - nothing was sent, so log in and resend
                    item.tries++
                    q.add(0, item)
                    state.remove("irKey"); state.remove("irId")
                    irAuth(); return
                } else {
                    if (gate("${device.id}|sendFail".toString(), IR_ERROR_LOG_MS)) log.error "${device.displayName}: IR send failed (error ${err})"
                    setAttr("irStatus", "error ${err}")
                }
                break
            case "learnStart":
                if (err == 0) { setAttr("learnStatus", "learning - press the remote button now"); runIn(1, "learnPoll") }
                else learnFailed("could not start learning (error ${err})")
                break
            case "learnPoll":
                if (err == 0 && r.length > 0x38) {
                    byte[] body = aes(slice(r, 0x38, r.length), HexUtils.hexStringToByteArray(state.irKey), false)
                    String hex = learnedHex(body)
                    if (hex) {
                        state.remove("learnUntil")
                        setAttr("learnStatus", "learned")
                        if (txtEnable) log.info "${device.displayName}: learned an IR code (${hex.length() / 2} bytes)"
                        try { parent?.irLearned(device.deviceNetworkId, hex) } catch (e) { log.error "${device.displayName}: ${e.message}" }
                        break
                    }
                }
                runIn(1, "learnPoll")
                break
        }
        irPump()
    } catch (e) {
        // Never retry from here - a repeating fault must not loop or flood the logs
        if (gate("${device.id}|replyFail".toString(), IR_ERROR_LOG_MS)) log.error "${device.displayName}: IR reply handling failed: ${e.message}"
        unschedule("irTimeout"); unschedule("learnPoll")
        irQueue[device.id as String] = []
        state.remove("irAwait")
        if (state.learnUntil) learnFailed("error reading the Ultra's reply")
        setAttr("irStatus", "error")
    }
}

// Reply body = 4-byte header + Broadlink code "26 00 len_lo len_hi ...". Cut it to its declared length.
private static String learnedHex(byte[] body) {
    if (body.length < 8 || (body[4] & 0xFF) != 0x26) return null
    int len = (body[6] & 0xFF) | ((body[7] & 0xFF) << 8)
    if (len < 4 || 8 + len > body.length) return null
    return HexUtils.byteArrayToHexString(slice(body, 4, 8 + len)).toLowerCase()
}

private byte[] buildPacket(int ptype, byte[] payload, byte[] key, int id) {
    int count = ((((state.irCount ?: 0x8000) as int) + 1) | 0x8000) & 0xFFFF
    state.irCount = count
    int pad = (16 - (payload.length % 16)) % 16
    byte[] p = new byte[payload.length + pad]
    copyBytes(payload, 0, p, 0, payload.length)
    byte[] enc = aes(p, key, true)
    byte[] pkt = new byte[0x38 + enc.length]
    [0x5a, 0xa5, 0xaa, 0x55, 0x5a, 0xa5, 0xaa, 0x55].eachWithIndex { b, i -> pkt[i] = (byte) b }
    putLE(pkt, 0x24, DEVTYPE, 2)
    putLE(pkt, 0x26, ptype, 2)
    putLE(pkt, 0x28, count, 2)
    byte[] mac = HexUtils.hexStringToByteArray(macLower())
    (0..5).each { i -> pkt[0x2A + i] = mac[5 - i] }
    putLE(pkt, 0x30, id, 4)
    putLE(pkt, 0x34, sum16(payload), 2)
    copyBytes(enc, 0, pkt, 0x38, enc.length)
    putLE(pkt, 0x20, sum16(pkt), 2)
    return pkt
}

private void udpSend(byte[] pkt) {
    def action = new hubitat.device.HubAction(HexUtils.byteArrayToHexString(pkt), hubitat.device.Protocol.LAN,
        [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
         destinationAddress: "${settings.deviceIp}:80",
         encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
         callback: "udpResponse",
         timeout: 3])
    sendHubCommand(action)
}

private byte[] aes(byte[] data, byte[] key, boolean encrypt) {
    Cipher c = Cipher.getInstance("AES/CBC/NoPadding")
    c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
           new IvParameterSpec(HexUtils.hexStringToByteArray(INIT_IV)))
    return c.doFinal(data)
}

private static int sum16(byte[] b) { int s = 0xBEAF; for (byte x : b) s += (x & 0xFF); return s & 0xFFFF }
private static void putLE(byte[] b, int off, int v, int len) { for (int i = 0; i < len; i++) b[off + i] = (byte) ((v >> (8 * i)) & 0xFF) }
private static byte[] slice(byte[] b, int from, int to) { byte[] o = new byte[to - from]; copyBytes(b, from, o, 0, to - from); return o }

/* =============================== Helpers ================================ */

private void setAttr(String name, value, String desc = null, String unit = null) {
    if (device.currentValue(name)?.toString() == value?.toString()) return
    Map ev = [name: name, value: value]
    if (unit) ev.unit = unit
    if (desc) { ev.descriptionText = desc; if (txtEnable) log.info desc }
    sendEvent(ev)
}

private void setNum(String name, String payload) {
    String p = payload?.trim()
    if (p?.isInteger()) setAttr(name, p as Integer)
}

private static Integer jsonInt(String payload, String key) {
    def m = (payload =~ /"${key}"\s*:\s*(-?\d+)/)
    return m.find() ? (m.group(1) as Integer) : null
}

private static BigDecimal clamp(BigDecimal v, BigDecimal lo, BigDecimal hi) { v < lo ? lo : (v > hi ? hi : v) }
private static BigDecimal roundTo(BigDecimal v, int step) { (Math.round(v / step) * step) as BigDecimal }

private void logDebug(String msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }

// System.arraycopy isn't allowed in the Hubitat sandbox
private static void copyBytes(byte[] src, int srcPos, byte[] dst, int dstPos, int len) {
    for (int i = 0; i < len; i++) dst[dstPos + i] = src[srcPos + i]
}
