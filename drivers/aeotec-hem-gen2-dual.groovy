/**
 *  Aeotec Home Energy Meter Gen2 (DSB28) - Dual Clamp
 *  Version 1.1.0 (2026-11-22)
 *
 *  Reports whole-meter power/energy plus each leg (clamp 1 / clamp 2) separately.
 *  Optional child devices per leg (Generic Component Energy Meter) for dashboards / RM.
 *
 *  Param 101-103 bitmask (Gen2):
 *     4 = W whole       8 = kWh whole
 *   256 = W clamp 1   512 = W clamp 2
 *  2048 = kWh clamp 1 4096 = kWh clamp 2
 *  Defaults: Group 1 = W whole + W clamps (772) every 30 s
 *            Group 2 = kWh whole + kWh clamps (6152) every 300 s
 *            Group 3 = off
 */

import groovy.transform.Field

@Field static final Map CC_VERSIONS = [0x20: 1, 0x32: 3, 0x60: 3, 0x70: 1, 0x72: 2, 0x85: 2, 0x86: 1]

metadata {
    definition(name: "Aeotec HEM Gen2 Dual Clamp", namespace: "jercoates", author: "Jeremy") {
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Refresh"
        capability "Configuration"
        capability "Sensor"

        attribute "power1", "number"
        attribute "power2", "number"
        attribute "energy1", "number"
        attribute "energy2", "number"

        // Rollups (kWh) - whole house, leg 1, leg 2
        attribute "energyToday", "number"
        attribute "energyYesterday", "number"
        attribute "energyThisMonth", "number"
        attribute "energyLastMonth", "number"
        attribute "energy1Today", "number"
        attribute "energy1Yesterday", "number"
        attribute "energy1ThisMonth", "number"
        attribute "energy1LastMonth", "number"
        attribute "energy2Today", "number"
        attribute "energy2Yesterday", "number"
        attribute "energy2ThisMonth", "number"
        attribute "energy2LastMonth", "number"

        command "resetEnergy"
        command "createChildren"
        command "removeChildren"

        fingerprint mfr: "0086", prod: "0002", deviceId: "001C", inClusters: "0x70,0x32,0x60,0x85,0x59,0x72,0x86"
    }

    preferences {
        input name: "g1Mask", type: "number", title: "Group 1 report mask (param 101)", defaultValue: 772
        input name: "g1Interval", type: "number", title: "Group 1 interval, sec (param 111)", defaultValue: 30, range: "1..4294967295"
        input name: "g2Mask", type: "number", title: "Group 2 report mask (param 102)", defaultValue: 6152
        input name: "g2Interval", type: "number", title: "Group 2 interval, sec (param 112)", defaultValue: 300, range: "1..4294967295"
        input name: "g3Mask", type: "number", title: "Group 3 report mask (param 103)", defaultValue: 0
        input name: "g3Interval", type: "number", title: "Group 3 interval, sec (param 113)", defaultValue: 3600, range: "1..4294967295"

        input name: "selective", type: "bool", title: "Enable change-triggered reports (param 3)", defaultValue: false
        input name: "thrWhole", type: "number", title: "Whole W change threshold (param 4)", defaultValue: 50
        input name: "thrC1", type: "number", title: "Clamp 1 W change threshold (param 5)", defaultValue: 50
        input name: "thrC2", type: "number", title: "Clamp 2 W change threshold (param 6)", defaultValue: 50

        input name: "maxWatts", type: "number", title: "Discard power readings above (W) - filters Gen2 garbage spikes", defaultValue: 30000
        input name: "useChildren", type: "bool", title: "Create child devices for each leg", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Description text logging", defaultValue: false
        input name: "logEnable", type: "bool", title: "Debug logging", defaultValue: false
    }
}

// ---------------- lifecycle ----------------

void installed() {
    scheduleRollups()
    configure()
}

void updated() {
    log.info "updated"
    unschedule()            // also clears any leftover jobs from a previous driver
    scheduleRollups()
    if (logEnable) runIn(1800, "logsOff")
    if (useChildren) createChildren()
    configure()
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "debug logging disabled"
}

void configure() {
    List<String> cmds = []
    cmds << secure(zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: [zwaveHubNodeId]))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 3, size: 1, scaledConfigurationValue: selective ? 1 : 0))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 4, size: 2, scaledConfigurationValue: (thrWhole ?: 50) as Integer))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 5, size: 2, scaledConfigurationValue: (thrC1 ?: 50) as Integer))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 6, size: 2, scaledConfigurationValue: (thrC2 ?: 50) as Integer))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 101, size: 4, scaledConfigurationValue: num(g1Mask, 772)))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 102, size: 4, scaledConfigurationValue: num(g2Mask, 6152)))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 103, size: 4, scaledConfigurationValue: num(g3Mask, 0)))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 111, size: 4, scaledConfigurationValue: num(g1Interval, 30)))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 112, size: 4, scaledConfigurationValue: num(g2Interval, 300)))
    cmds << secure(zwave.configurationV1.configurationSet(parameterNumber: 113, size: 4, scaledConfigurationValue: num(g3Interval, 3600)))
    [101, 102, 103, 111, 112, 113].each { cmds << secure(zwave.configurationV1.configurationGet(parameterNumber: it)) }
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, 500), hubitat.device.Protocol.ZWAVE))
    runIn(10, "refresh")
}

private Integer num(val, Integer dflt) { (val == null ? dflt : val) as Integer }

// ---------------- commands ----------------

void refresh() {
    List<String> cmds = []
    cmds << secure(zwave.meterV3.meterGet(scale: 0))   // kWh whole
    cmds << secure(zwave.meterV3.meterGet(scale: 2))   // W whole
    [1, 2].each { ep ->
        cmds << secure(ep, zwave.meterV3.meterGet(scale: 0))
        cmds << secure(ep, zwave.meterV3.meterGet(scale: 2))
    }
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, 300), hubitat.device.Protocol.ZWAVE))
}

void resetEnergy() {
    log.warn "resetting kWh counters and rollups"
    state.remove("eLast"); state.remove("eSkip")
    state.eDay = [:]; state.eMonth = [:]
    [0, 1, 2].each { ep ->
        sendEvent(name: "energy${sfx(ep)}Today", value: 0, unit: "kWh")
        sendEvent(name: "energy${sfx(ep)}ThisMonth", value: 0, unit: "kWh")
    }
    List<String> cmds = [secure(zwave.meterV2.meterReset()), secure(zwave.meterV3.meterGet(scale: 0))]
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, 1000), hubitat.device.Protocol.ZWAVE))
    runIn(5, "refresh")
}

void createChildren() {
    [1, 2].each { ep ->
        String dni = "${device.deviceNetworkId}-ep${ep}"
        if (!getChildDevice(dni)) {
            addChildDevice("hubitat", "Generic Component Energy Meter", dni,
                    [name: "${device.displayName} Leg ${ep}", label: "${device.displayName} Leg ${ep}", isComponent: false])
            log.info "created child Leg ${ep}"
        }
    }
}

void removeChildren() {
    childDevices.each { deleteChildDevice(it.deviceNetworkId) }
}

// component callbacks (required by Generic Component drivers)
void componentRefresh(cd) { refresh() }
void componentResetEnergy(cd) { resetEnergy() }

// ---------------- parsing ----------------

void parse(String description) {
    def cmd = zwave.parse(description, CC_VERSIONS)
    if (cmd) {
        if (logEnable) log.debug "parsed: ${cmd}"
        zwaveEvent(cmd)
    } else if (logEnable) {
        log.debug "unparsed: ${description}"
    }
}

void zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
    def enc = cmd.encapsulatedCommand(CC_VERSIONS)
    if (enc) zwaveEvent(enc, cmd.sourceEndPoint as Integer)
}

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    def enc = cmd.encapsulatedCommand(CC_VERSIONS)
    if (enc) zwaveEvent(enc)
}

void zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd, Integer ep = 0) {
    if (cmd.meterType != 1) return   // electric only
    BigDecimal val = cmd.scaledMeterValue as BigDecimal
    switch (cmd.scale as Integer) {
        case 0: // kWh
            emit(ep, "energy", val.setScale(3, BigDecimal.ROUND_HALF_UP), "kWh")
            trackEnergy(ep, val)
            break
        case 2: // W
            if (val < 0 || val > ((maxWatts ?: 30000) as BigDecimal)) {
                if (logEnable) log.debug "discarding bogus power ${val} W (ep ${ep})"
                return
            }
            emit(ep, "power", val.setScale(1, BigDecimal.ROUND_HALF_UP), "W")
            break
        default:
            if (logEnable) log.debug "ignored meter scale ${cmd.scale} ep ${ep}: ${val}"
    }
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    if (logEnable) log.debug "param ${cmd.parameterNumber} = ${cmd.scaledConfigurationValue}"
}

void zwaveEvent(hubitat.zwave.Command cmd, Integer ep = 0) {
    if (logEnable) log.debug "unhandled (ep ${ep}): ${cmd}"
}

private void emit(Integer ep, String base, BigDecimal val, String unit) {
    String attr = ep ? "${base}${ep}" : base
    String txt = ep ? "${device.displayName} leg ${ep} ${base} is ${val} ${unit}" : "${device.displayName} ${base} is ${val} ${unit}"
    if (txtEnable) log.info txt
    sendEvent(name: attr, value: val, unit: unit, descriptionText: txt)
    if (ep) {
        def child = getChildDevice("${device.deviceNetworkId}-ep${ep}")
        child?.parse([[name: base, value: val, unit: unit, descriptionText: txt]])
    }
}

// ---------------- rollups ----------------

private void scheduleRollups() {
    schedule("0 59 23 * * ?", "refresh")   // grab final readings before midnight
    schedule("5 0 0 * * ?", "rollDay")     // 00:00:05 daily
}

private String sfx(Integer ep) { ep ? "${ep}" : "" }

private BigDecimal bd(v) { v == null ? null : new BigDecimal(v.toString()) }

private BigDecimal r3(v) { bd(v).setScale(3, BigDecimal.ROUND_HALF_UP) }

// Accumulates per-report deltas so meter counter resets and Gen2 garbage values don't wreck totals
private void trackEnergy(Integer ep, BigDecimal val) {
    String k = "ep${ep}"
    Map last = state.eLast ?: [:]
    Map skip = state.eSkip ?: [:]
    Map day = state.eDay ?: [:]
    Map mon = state.eMonth ?: [:]

    BigDecimal prev = bd(last[k])
    BigDecimal delta = 0
    boolean accept = true

    if (prev != null) {
        if (val >= prev) {
            delta = val - prev
            if (delta > 50) accept = false              // implausible jump
        } else if (val < 1) {
            delta = val                                 // genuine counter reset
        } else {
            accept = false                              // implausible drop
        }
    }

    if (!accept) {
        int n = ((skip[k] ?: 0) as Integer) + 1
        if (n < 3) {
            skip[k] = n
            state.eSkip = skip
            if (logEnable) log.debug "ep ${ep}: rejected kWh ${val} (prev ${prev}), strike ${n}"
            return
        }
        log.warn "ep ${ep}: kWh counter moved to ${val} (was ${prev}); rebaselining"
        delta = 0                                       // 3 strikes: accept as new baseline
    }

    skip[k] = 0
    last[k] = val
    day[k] = (bd(day[k]) ?: 0) + delta
    mon[k] = (bd(mon[k]) ?: 0) + delta
    state.eLast = last; state.eSkip = skip; state.eDay = day; state.eMonth = mon

    sendEvent(name: "energy${sfx(ep)}Today", value: r3(day[k]), unit: "kWh")
    sendEvent(name: "energy${sfx(ep)}ThisMonth", value: r3(mon[k]), unit: "kWh")
}

void rollDay() {
    Map day = state.eDay ?: [:]
    Map mon = state.eMonth ?: [:]
    boolean newMonth = new Date().format("d", location.timeZone) == "1"

    [0, 1, 2].each { ep ->
        String k = "ep${ep}"
        if (day[k] == null) return
        sendEvent(name: "energy${sfx(ep)}Yesterday", value: r3(day[k]), unit: "kWh", isStateChange: true)
        day[k] = 0
        sendEvent(name: "energy${sfx(ep)}Today", value: 0, unit: "kWh")
        if (newMonth) {
            sendEvent(name: "energy${sfx(ep)}LastMonth", value: r3(mon[k] ?: 0), unit: "kWh", isStateChange: true)
            mon[k] = 0
            sendEvent(name: "energy${sfx(ep)}ThisMonth", value: 0, unit: "kWh")
        }
    }
    state.eDay = day; state.eMonth = mon
    if (txtEnable) log.info "${device.displayName} daily rollup done${newMonth ? ' (new month)' : ''}"
}

// ---------------- helpers ----------------

private String secure(hubitat.zwave.Command cmd) {
    zwaveSecureEncap(cmd.format())
}

private String secure(Integer ep, hubitat.zwave.Command cmd) {
    zwaveSecureEncap(zwave.multiChannelV3.multiChannelCmdEncap(sourceEndPoint: 0, destinationEndPoint: ep).encapsulate(cmd).format())
}
