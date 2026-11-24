/**
 *  LinknLink MQTT Listener
 *
 *  Hidden helper device created by the "LinknLink Device Service" app - you don't add or use it
 *  directly. It holds the one MQTT broker connection (only drivers can, on Hubitat), keeps a
 *  catalog of the LinknLink units it hears about (from their Home Assistant discovery messages),
 *  and hands every LinknLink message to the app, which routes it to the right device.
 *
 *  1.0.0 - 2026-11-24 - Initial version, modelled on Albert Mulder's InvisOutlet / YoLink listeners
 *          (reconnect backoff, watchdog, debug logging that turns itself off).
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

def driverVersion() { "1.0.0" }

@Field static ConcurrentHashMap<String, Long> lastMsgTs = new ConcurrentHashMap<String, Long>()
// The Ultra re-sends every value every 20-40 s. Only changed values go to the app (each call wakes
// the app), plus one message per unit per minute so its healthStatus stays "online".
@Field static ConcurrentHashMap<String, String> lastPayload = new ConcurrentHashMap<String, String>()
@Field static ConcurrentHashMap<String, Long> lastForward = new ConcurrentHashMap<String, Long>()
@Field static final long ALIVE_MS = 60000L
@Field static ConcurrentHashMap<String, Boolean> knownUnits = new ConcurrentHashMap<String, Boolean>()
@Field static final int RECONNECT_MIN_SECS = 30
@Field static final int RECONNECT_MAX_SECS = 600
@Field static final long STALE_MS = 15L * 60L * 1000L          // no traffic for 15 min => reconnect
@Field static final long STABLE_MS = 5L * 60L * 1000L          // connected this long => reset the backoff
@Field static ConcurrentHashMap<String, Boolean> staleWarned = new ConcurrentHashMap<String, Boolean>()

metadata {
    definition(name: "LinknLink MQTT Listener", namespace: "jercoates", author: "Jeremy Coates", singleThreaded: true) {
        capability "Initialize"
        capability "Refresh"

        command "connect"
        command "disconnect"
        command "clearCatalog"

        attribute "connectionStatus", "enum", ["connected", "disconnected"]
        attribute "lastMessage", "string"
        attribute "unitsSeen", "number"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false
        input name: "rawEnable", type: "bool", title: "Log every LinknLink MQTT message (turns off after 30 minutes)", defaultValue: false
    }
}

/* ============================== Lifecycle ============================== */

def installed() { log.info "${device.displayName}: installed" }

def updated() {
    if (logEnable || rawEnable) runIn(1800, "logsOff")
}

def initialize() {
    state.reconnectDelay = RECONNECT_MIN_SECS
    state.remove("nextReconnectAt")
    runEvery1Minute("watchdog")
    if (state.broker) connect()
}

def uninstalled() {
    unschedule()
    try { interfaces.mqtt.disconnect() } catch (ignored) { }
}

def logsOff() {
    log.warn "${device.displayName}: debug and raw logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    device.updateSetting("rawEnable", [value: "false", type: "bool"])
}

def refresh() { if (!interfaces.mqtt.isConnected()) connect() }

// Called by the app. The password is NOT kept here - it's read from the app at connect time.
def configure(Map cfg) {
    state.broker = cfg.broker
    state.username = cfg.username
    logDebug "configured: broker=${state.broker} user=${state.username}"
    initialize()
}

// The app calls this after adding devices: the next copy of every value is passed on again
def resendAll() {
    lastPayload.clear()
    lastForward.clear()
}

def clearCatalog() {
    state.catalog = [:]
    String p = "${device.id}|".toString()
    knownUnits.keySet().findAll { it.startsWith(p) }.each { knownUnits.remove(it) }   // so units are re-found from their data
    sendEvent(name: "unitsSeen", value: 0)
}

// Units seen in discovery messages: [mac: [name:, model:, manufacturer:, lastSeen:]]
Map getCatalog() { (state.catalog ?: [:]) as Map }

/* ================================ MQTT ================================= */

def connect() {
    unschedule("reconnectIfNeeded")
    try { interfaces.mqtt.disconnect() } catch (ignored) { }
    state.subscribed = false
    if (!state.broker) { log.warn "${device.displayName}: no broker set yet - open the LinknLink Device Service app"; return }
    String pw = null
    try { pw = parent?.getMqttPassword() } catch (ignored) { }
    String clientId = "hubitat_linknlink_${device.id}"
    try {
        logDebug "connecting to ${state.broker} as ${clientId}"
        interfaces.mqtt.connect(state.broker, clientId, state.username ?: null, pw ?: null, cleanSession: true, keepAlive: 60)
        runInMillis(1500, "subscribeTopics")
    } catch (e) {
        log.error "${device.displayName}: MQTT connect failed: ${e.message}"
        setStatus("disconnected")
        scheduleReconnect()
    }
}

def disconnect() {
    unschedule("reconnectIfNeeded")
    try { interfaces.mqtt.disconnect() } catch (ignored) { }
    state.subscribed = false
    setStatus("disconnected")
}

def subscribeTopics() {
    if (!interfaces.mqtt.isConnected()) { logDebug "subscribe skipped: not connected (watchdog will retry)"; return }
    try {
        interfaces.mqtt.subscribe("home/#")                          // LinknLink state + config topics
        interfaces.mqtt.subscribe("homeassistant/+/+/+/config")      // discovery (to learn which units exist)
        interfaces.mqtt.subscribe("homeassistant/+/+/config")        // discovery topics without a node id
        lastPayload.clear()                                          // after a reconnect, pass everything on once
        state.subscribed = true
        state.connectedAt = now()       // backoff is only reset after STABLE_MS connected (see watchdog), so a
        state.remove("nextReconnectAt") // broker that accepts and then drops us still backs off to 10 min
        lastMsgTs[device.id as String] = now()
        setStatus("connected")
        log.info "${device.displayName}: connected to ${state.broker}"
    } catch (e) {
        log.warn "${device.displayName}: subscribe failed: ${e.message} (will retry with backoff)"
        try { interfaces.mqtt.disconnect() } catch (ignored) { }
        state.subscribed = false
        setStatus("disconnected")
        scheduleReconnect()
    }
}

def reconnectIfNeeded() {
    if (interfaces.mqtt.isConnected() && state.subscribed) return
    connect()
}

private void scheduleReconnect() {
    Long pending = state.nextReconnectAt as Long
    if (pending && now() < pending) return
    int delay = Math.max(RECONNECT_MIN_SECS, Math.min(RECONNECT_MAX_SECS, (state.reconnectDelay ?: RECONNECT_MIN_SECS) as int))
    state.nextReconnectAt = now() + delay * 1000L
    state.reconnectDelay = Math.min(RECONNECT_MAX_SECS, delay * 2)
    runIn(delay, "reconnectIfNeeded")
    logDebug "reconnect in ${delay}s"
}

def mqttClientStatus(String message) {
    logDebug "mqttClientStatus: ${message}"
    if (message?.toLowerCase()?.startsWith("error")) {
        boolean first = ((state.reconnectDelay ?: RECONNECT_MIN_SECS) as int) <= RECONNECT_MIN_SECS
        if (first) log.error "${device.displayName}: MQTT ${message}" else log.warn "${device.displayName}: MQTT ${message}"
        try { interfaces.mqtt.disconnect() } catch (ignored) { }
        state.subscribed = false
        setStatus("disconnected")
        scheduleReconnect()
    }
}

def watchdog() {
    if (!state.broker) return
    if (!interfaces.mqtt.isConnected()) {
        Long next = state.nextReconnectAt as Long
        if (!(next && now() < next)) scheduleReconnect()
        if (device.currentValue("connectionStatus") != "disconnected") setStatus("disconnected")
        return
    }
    if (!state.subscribed) { subscribeTopics(); return }
    Long ca = state.connectedAt as Long
    if (ca && now() - ca > STABLE_MS && ((state.reconnectDelay ?: RECONNECT_MIN_SECS) as int) > RECONNECT_MIN_SECS) state.reconnectDelay = RECONNECT_MIN_SECS
    Long last = lastMsgTs[device.id as String]
    if (last && now() - last > STALE_MS) {
        String msg = "${device.displayName}: no MQTT traffic for ${Math.round((now() - last) / 60000)} min, reconnecting"
        if (staleWarned.put(device.id as String, true)) logDebug msg else log.warn msg     // warn once until traffic returns
        lastMsgTs[device.id as String] = now()
        connect()
    }
}

def publish(String topic, String payload) {
    if (!interfaces.mqtt.isConnected()) { log.warn "${device.displayName}: can't publish, not connected"; return false }
    interfaces.mqtt.publish(topic, payload)
    logDebug "published ${topic} = ${payload}"
    return true
}

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    String topic = msg?.topic
    String payload = msg?.payload
    if (!topic) return
    lastMsgTs[device.id as String] = now()
    staleWarned.remove(device.id as String)

    if (topic.startsWith("homeassistant/")) { handleDiscovery(topic, payload); return }
    if (!topic.startsWith("home/")) return
    def mm = (topic =~ /^home\/(?:config\/)?([0-9a-f]{12})/)
    String mac = mm.find() ? mm.group(1) : null
    if (!mac) return                // something else publishing under home/ - not ours, don't track it
    if (rawEnable) log.debug "${device.displayName} RAW ${topic} ${payload}"
    String key = "${device.id}|${topic}".toString()
    String unit = "${device.id}|${mac}".toString()
    // Units only announce themselves (discovery) when they connect, so also learn them from their data
    if (mac && !knownUnits.containsKey(unit)) {
        knownUnits.put(unit, true)
        Map catalog = (state.catalog ?: [:]) as Map
        if (!catalog.containsKey(mac)) {
            catalog[mac] = [name: "LinknLink ${mac}".toString(), model: "seen on MQTT", lastSeen: now()]
            state.catalog = catalog
            sendEvent(name: "unitsSeen", value: catalog.size())
            log.info "${device.displayName}: found LinknLink unit ${mac} from its MQTT data"
        }
    }
    boolean changed = lastPayload.get(key) != payload
    Long lf = lastForward.get(unit)
    boolean aliveDue = topic.endsWith("/status") && (lf == null || now() - lf > ALIVE_MS)
    if (!changed && !aliveDue) return
    lastPayload.put(key, payload ?: "")
    if (topic.endsWith("/status")) lastForward.put(unit, now())
    try { parent?.mqttMessage(topic, payload) } catch (e) { log.error "${device.displayName}: routing failed for ${topic}: ${e.message}" }
}

// LinknLink discovery configs carry device: {identifiers:[mac], name, model, manufacturer:"LinknLink"}
private void handleDiscovery(String topic, String payload) {
    if (!payload || !payload.contains("LinknLink")) return
    Map cfg
    try { cfg = new JsonSlurper().parseText(payload) as Map } catch (ignored) { return }
    Map dev = cfg?.device as Map
    if (!dev || dev.manufacturer != "LinknLink") return
    String mac = (dev.identifiers instanceof List && dev.identifiers) ? dev.identifiers[0]?.toString()?.toLowerCase()?.replaceAll(/[^0-9a-f]/, "") : null
    if (!mac || mac.length() < 12) return
    mac = mac.take(12)              // same 12-hex form the data topics and the app use
    Map catalog = (state.catalog ?: [:]) as Map
    Map entry = (catalog[mac] ?: [:]) as Map
    boolean isNew = !catalog.containsKey(mac)
    entry.name = dev.name; entry.model = dev.model; entry.manufacturer = dev.manufacturer
    long last = (entry.lastSeen ?: 0L) as long
    if (isNew || now() - last > 60000L) {           // don't rewrite state on every republish
        entry.lastSeen = now()
        catalog[mac] = entry
        state.catalog = catalog
        if (isNew) {
            log.info "${device.displayName}: found ${dev.name} (${dev.model})"
            sendEvent(name: "unitsSeen", value: catalog.size())
        }
    }
}

/* =============================== Helpers ================================ */

private void setStatus(String s) {
    if (device.currentValue("connectionStatus") != s) sendEvent(name: "connectionStatus", value: s)
}

private void logDebug(String msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }
