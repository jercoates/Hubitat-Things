/**
 *  LinknLink Device Service
 *
 *  Connects LinknLink devices to Hubitat:
 *    - eMotion Ultra presence sensors over MQTT (one device per unit)
 *    - IR remotes sent through an Ultra's IR blaster (one device per remote, every button a command)
 *
 *  Pieces (install all four): this app, plus the drivers "LinknLink MQTT Listener",
 *  "LinknLink eMotion Ultra" and "LinknLink IR Remote".
 *
 *  ADD A REMOTE (4 pages, always forward):
 *    1. What is it - device type and brand.
 *    2. One test - the brand's code files are grouped by the IR codes they really use; press Test
 *       Power on the first group and pick it if the device reacted. Nothing is sent until you press Test.
 *    3. Your buttons - every button of that group is already in; test any you like, leave out any
 *       that don't work, add a missing one by learning it or pasting a code.
 *    4. Name it - Next creates the remote and shows a link to it.
 *  Codes come from Flipper-IRDB (Lucaslhm/Flipper-IRDB) or, if the brand isn't there, IRDB (probonopd/irdb).
 *
 *  Structure follows Albert Mulder's InvisOutlet Device Service (almulder/Hubitat-Drivers).
 *
 *  1.1.0 - 2026-11-25 - "Save and connect" button for the broker and "Save sensors" button, so setup
 *                       goes forward without pressing Done and coming back in. Model picker per sensor
 *                       (eMotion Ultra / eMotion Pro); only models with IR are offered as IR blasters.
 *  1.1.1 - 2026-11-25 - The app never deletes sensor devices by itself (unticking only stops managing it).
 *  1.0.0 - 2026-11-24 - Initial version.
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

def appVersion() { "1.1.1" }

@Field static final String NS = "jercoates"
@Field static final String IRDB_RAW = "https://raw.githubusercontent.com/probonopd/irdb/master/codes/"
@Field static final String FLIPPER_RAW = "https://raw.githubusercontent.com/Lucaslhm/Flipper-IRDB/main/"
@Field static final String FLIPPER_API = "https://api.github.com/repos/Lucaslhm/Flipper-IRDB/contents/"
// Downloaded lists and parsed code files - memory only (rebuilt on demand)
@Field static ConcurrentHashMap<String, Object> CACHE = new ConcurrentHashMap<String, Object>()
@Field static final long LOAD_WAIT_MS = 15000L
@Field static final int MAX_GROUPS = 6

// Standard button keys (these are the IR Remote driver's commands), in display order
// Wi-Fi eMotion models this app supports and what they can do (LinknLink's comparison table).
// Temperature/humidity aren't listed: they show up by themselves when the unit sends them (built in on
// the Ultra, an add-on on the Pro). The battery eMotion Air (Bluetooth/Zigbee) is a different device.
@Field static final Map MODELS = ["eMotion Ultra": [ir: true], "eMotion Pro": [ir: true]]
@Field static final String DEFAULT_MODEL = "eMotion Ultra"

@Field static final List STD_ORDER = ["power", "on", "off", "volumeUp", "volumeDown", "mute", "channelUp", "channelDown",
    "input", "up", "down", "left", "right", "ok", "back", "exit", "home", "menu", "info", "guide",
    "play", "pause", "playPause", "stop", "fastForward", "rewind", "next", "previous", "record",
    "digit0", "digit1", "digit2", "digit3", "digit4", "digit5", "digit6", "digit7", "digit8", "digit9"]

// Database button names -> standard keys (names are upper-cased, "_" -> " ", spaces collapsed)
@Field static final List STD_PATTERNS = [
    ["on",          /^(POWER ?ON|ON|DISCRETE ON|PWR ?ON)$/],
    ["off",         /^(POWER ?OFF|OFF|DISCRETE OFF|PWR ?OFF)$/],
    ["power",       /^(POWER|POWER TOGGLE|PWR|POWER ON\/OFF|ON\/OFF|ON ?OFF|POWER ?ONOFF|TOGGLE POWER|STANDBY)$/],
    ["volumeUp",    /^(VOL(UME)? ?(\+|UP)|VOL\+|VOLUP)$/],
    ["volumeDown",  /^(VOL(UME)? ?(-|DOWN|DN)|VOL-|VOLDOWN|VOLDN)$/],
    ["mute",        /^(MUTE|VOLUME MUTE|MUTING)$/],
    ["channelUp",   /^(CH(ANNEL)? ?(\+|UP|NEXT)|PR(OG(RAM)?)? ?(\+|UP)|CH\+|CHUP)$/],
    ["channelDown", /^(CH(ANNEL)? ?(-|DOWN|DN|PREV(IOUS)?)|PR(OG(RAM)?)? ?(-|DOWN|DN)|CH-|CHDOWN|CHDN)$/],
    ["input",       /^(INPUT|SOURCE|INPUT SOURCE|TV\/AV|TV\/VIDEO|AV|INPUT SELECT)$/],
    ["up",          /^(UP|ARROW UP|CURSOR UP|NAV UP|DPAD UP)$/],
    ["down",        /^(DOWN|ARROW DOWN|ARROW DN|CURSOR DOWN|NAV DOWN|DPAD DOWN)$/],
    ["left",        /^(LEFT|ARROW LEFT|ARROW LT|CURSOR LEFT|NAV LEFT|DPAD LEFT)$/],
    ["right",       /^(RIGHT|ARROW RIGHT|ARROW RT|CURSOR RIGHT|NAV RIGHT|DPAD RIGHT)$/],
    ["ok",          /^(OK|ENTER|SELECT|CURSOR OK|CURSOR ENTER|CENTER|DPAD CENTER)$/],
    ["back",        /^(BACK|RETURN)$/],
    ["exit",        /^(EXIT|EXIT\/RETURN)$/],
    ["home",        /^(HOME|SMART ?HOME|SMART)$/],
    ["menu",        /^(MENU|SETUP|SETTINGS|SETTING)$/],
    ["info",        /^(INFO|INFORMATION|DISPLAY)$/],
    ["guide",       /^(GUIDE|EPG)$/],
    ["playPause",   /^(PLAY ?\/ ?PAUSE|PLAY PAUSE|PLAYPAUSE)$/],
    ["play",        /^(PLAY)$/],
    ["pause",       /^(PAUSE)$/],
    ["stop",        /^(STOP)$/],
    ["fastForward", /^(FF|FAST ?FORWARD|FWD|FORWARD|>>)$/],
    ["rewind",      /^(REW|REWIND|<<)$/],
    ["next",        /^(NEXT|SKIP ?\+|SKIP FWD|>>\|)$/],
    ["previous",    /^(PREV|PREVIOUS|SKIP ?-|SKIP BACK|\|<<)$/],
    ["record",      /^(REC|RECORD)$/]
]

definition(
    name: "LinknLink Device Service",
    namespace: "jercoates",
    author: "Jeremy Coates",
    description: "LinknLink eMotion sensors over MQTT, and IR remotes through their IR blaster.",
    category: "Integrations",
    menu: "Integrations",
    iconUrl: "", iconX2Url: "",
    singleInstance: true,
    oauth: false
)

preferences {
    page(name: "mainPage")
    page(name: "devicesPage")
    page(name: "arWhatPage")
    page(name: "arTestPage")
    page(name: "arButtonsPage")
    page(name: "arNamePage")
    page(name: "arDonePage")
}

/* ================================ Pages ================================ */

def mainPage() {
    dynamicPage(name: "mainPage", title: "LinknLink Device Service v${appVersion()}", install: true, uninstall: true) {
        section("<b>MQTT broker</b>") {
            paragraph "The broker your LinknLink devices publish to (LinknLink app: device → ⋯ → MQTT Connection). " +
                      "Hubitat's built-in broker works: Integrations → MQTT Export Integration."
            input "brokerIp", "text", title: "Broker IP", required: true, defaultValue: "192.168.1.233", submitOnChange: true
            input "brokerPort", "number", title: "Port", required: true, defaultValue: 1883, submitOnChange: true
            input "brokerUser", "text", title: "Username", required: false, defaultValue: "hubitat", submitOnChange: true
            input "brokerPassword", "password", title: "Password", required: false, submitOnChange: true
            input "saveBroker", "button", title: "Save and connect"
            if (state.brokerMsg) paragraph "<i>${state.brokerMsg}</i>"
            def l = getListener()
            paragraph "Listener: ${l ? (l.currentValue('connectionStatus') ?: 'starting') : 'not created yet - press Save and connect'}" +
                      (l ? " · LinknLink units seen: ${l.currentValue('unitsSeen') ?: 0}" : "")
        }
        section("<b>eMotion sensors</b>") {
            href "devicesPage", title: "Choose sensors", description: "${getUltras().size()} added"
        }
        section("<b>IR remotes</b>") {
            if (!getBlasters()) paragraph "Add an eMotion sensor with IR first (Ultra or Pro) - its IR blaster sends the remote's codes."
            else href "arWhatPage", title: "➕ Add a remote", description: "4 short steps", params: [fresh: "${now()}".toString()]
            getRemotes().each { cd ->
                href "arButtonsPage", title: "${cd.displayName} (${cd.currentValue('numberOfButtons') ?: 0} buttons)",
                     description: "Test, add or leave out buttons", params: [recheck: "${cd.deviceNetworkId}|${now()}".toString()]
            }
            if (getRemotes()) paragraph "To delete a remote, open it from Devices and use Remove device."
        }
        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false
        }
    }
}

def devicesPage() {
    Map cat = getListener()?.getCatalog() ?: [:]
    Map opts = [:]
    cat.each { mac, info -> opts[mac] = "${info.name ?: mac} (${info.model ?: '?'})" }
    getUltras().each { cd -> String mac = cd.getDataValue("mac"); if (mac && !opts[mac]) opts[mac] = "${cd.displayName} (not heard from yet)" }
    dynamicPage(name: "devicesPage", title: "LinknLink eMotion sensors") {
        section("") {
            if (!opts) paragraph "No LinknLink units heard yet. Check the broker settings, that the units' MQTT Connection says Connected, then come back in a minute."
            input "selectedUnits", "enum", title: "Units to add as Hubitat devices", options: opts, multiple: true, required: false, submitOnChange: true
            (settings.selectedUnits ?: []).each { mac ->
                input "model_${mac}", "enum", title: "Model of ${opts[mac] ?: mac}", options: MODELS.keySet() as List, defaultValue: DEFAULT_MODEL, required: true, submitOnChange: true
                if (MODELS[modelOf(mac)]?.ir) input "ip_${mac}", "text", title: "IP address of ${opts[mac] ?: mac} (needed for IR)", required: false, submitOnChange: true
            }
            input "saveSensors", "button", title: "Save sensors (creates their devices now)"
            paragraph "<small>Unticking a sensor never deletes its device. To delete one, open it from Devices and use Remove device.</small>"
            if (state.sensorsMsg) paragraph "<i>${state.sensorsMsg}</i>"
        }
    }
}

/* ------------------------- Add a remote: 1. What is it ------------------------- */

def arWhatPage(Map params = null) {
    if (params?.fresh && state.arToken != params.fresh) { resetWizard(); state.arToken = params.fresh }
    cascadeChoices()
    Map ultras = [:]
    getBlasters().each { ultras[it.deviceNetworkId] = it.displayName }
    if (settings.arBlaster && !ultras.containsKey(settings.arBlaster)) app.removeSetting("arBlaster")    // that eMotion was deleted
    if (ultras.size() == 1 && !settings.arBlaster) app.updateSetting("arBlaster", [type: "enum", value: (ultras.keySet() as List)[0]])
    boolean own = settings.arOwnOnly == true
    dynamicPage(name: "arWhatPage", title: "Add a remote - 1 of 4: What is it?", nextPage: own ? "arButtonsPage" : "arTestPage") {
        section("") {
            if (ultras.size() > 1) input "arBlaster", "enum", title: "Which eMotion is in the room with it?", options: ultras, required: true
            input "arOwnOnly", "bool", title: "My device isn't in the lists - I'll teach the buttons from its own remote", defaultValue: false, submitOnChange: true
            if (!own) {
                try {
                    if (settings.arUseIrdb) {
                        input "arBrand", "enum", title: "Brand", options: irdbBrands(), required: true, submitOnChange: true
                        if (settings.arBrand) input "arType", "enum", title: "Device type", options: irdbTypes(settings.arBrand), required: true, submitOnChange: true
                    } else {
                        input "arCategory", "enum", title: "Device type", options: flipperList(""), required: true, submitOnChange: true
                        if (settings.arCategory) input "arBrand", "enum", title: "Brand", options: flipperList(settings.arCategory), required: true, submitOnChange: true
                    }
                } catch (e) {
                    paragraph "<span style='color:red'>Couldn't reach the code database (${e.message}). Check the hub's internet, then open this page again.</span>"
                }
                input "arUseIrdb", "bool", title: "Brand not listed? Use the second database instead", defaultValue: false, submitOnChange: true
            }
            paragraph "Then press <b>Next</b>."
        }
    }
}

/* ------------------------- Add a remote: 2. One test ------------------------- */

def arTestPage() {
    cascadeChoices()
    List groups = []
    String err = null
    try { groups = codeGroups() } catch (e) { err = e.message }
    Map pick = [:]
    groups.eachWithIndex { g, i -> pick[g.id] = "Group ${(char) (65 + i)} (${g.files.size()} models, ${g.buttons.size()} buttons)" }
    dynamicPage(name: "arTestPage", title: "Add a remote - 2 of 4: One test", nextPage: "arButtonsPage") {
        section("") {
            if (err) paragraph "<span style='color:red'>${err}</span>"
            else if (!groups) paragraph "Still downloading the codes - press <b>Check again</b> in a few seconds."
            if (!err && !groups) input "ar_reload", "button", title: "Check again"
            if (groups) paragraph "Make sure the device is in view of the eMotion, press <b>Test Power</b> on Group A and watch it. " +
                                  "If it turned on or off, choose Group A below and press Next. If not, try Group B."
            if (state.arLastTest) paragraph "<i>${state.arLastTest}</i>"
        }
        groups.eachWithIndex { g, i ->
            section("") {
                String models = g.files.take(4).collect { it.tokenize('/')[-1].replaceAll(/\.ir$|\.csv$/, "") }.join(", ") + (g.files.size() > 4 ? ", …" : "")
                paragraph "<b>Group ${(char) (65 + i)}</b> - used by ${g.files.size()} ${g.files.size() == 1 ? 'model' : 'models'} (${models}) · ${g.buttons.size()} buttons"
                if (g.testHex) input "testgrp_${i}", "button", title: "Test Power - Group ${(char) (65 + i)}"
                else paragraph "<span style='color:gray'>No power button in this group</span>"
            }
        }
        if (groups) section("") {
            input "arGroup", "enum", title: "Which group worked?", options: pick, required: true, submitOnChange: true
            paragraph "None worked? Go back and tick 'I'll teach the buttons from its own remote'."
        }
    }
}

/* ------------------------- Add a remote: 3. Your buttons ------------------------- */

def arButtonsPage(Map params = null) {
    if (params?.recheck && state.arToken != params.recheck) { state.arToken = params.recheck; loadForRecheck(params.recheck.tokenize("|")[0]) }
    List rows = []
    String err = null
    try { rows = buttonRows() } catch (e) { err = e.message }
    Map own = (state.arOwn ?: [:]) as Map
    dynamicPage(name: "arButtonsPage", title: "Add a remote - 3 of 4: Your buttons", nextPage: "arNamePage") {
        section("") {
            if (err) paragraph "<span style='color:red'>${err}</span>"
            paragraph (rows || own ? "These buttons will be on the remote. Testing is optional - press <b>Test</b> to try one, and switch on <b>leave out</b> for any that don't work. Then press <b>Next</b>."
                                   : "Teach the buttons below, one at a time, then press <b>Next</b>.")
            if (state.arLastTest) paragraph "<i>${state.arLastTest}</i>"
        }
        if (rows) section("From the code database (${rows.size()})") {
            rows.eachWithIndex { r, i ->
                paragraph "<b>${r.key ?: r.names[0]}</b>" + (r.key && r.names ? " <span style='color:gray'>(${r.names.take(2).join(' / ')})</span>" : "")
                input "testbtn_${i}", "button", title: "Test"
                input "skip_${i}", "bool", title: "leave out", defaultValue: false
            }
        }
        if (own) section("${state.arRecheck ? 'Current buttons' : 'Taught / pasted'} (${own.size()})") {
            (own.keySet() as List).eachWithIndex { n, i ->
                paragraph "<b>${n}</b>"
                input "testown_${i}", "button", title: "Test"
                input "dropown_${i}", "button", title: "Remove"
            }
        }
        section("") {
            if (rows) input "arShowAdd", "bool", title: "Add a button that's missing", defaultValue: false, submitOnChange: true
            else paragraph "<b>Teach a button</b>"
            if (settings.arShowAdd || !rows) {
                input "learnName", "text", title: "Button name (e.g. power, volumeUp, Netflix)", required: false, submitOnChange: true
                input "learn_go", "button", title: "Teach it: press this, then within 30 s press that button on the device's remote, aimed at the eMotion"
                if (state.arLearnMsg) paragraph "<i>${state.arLearnMsg}</i>"
                if (state.arLearnFor) input "learn_check", "button", title: "Did it get it? (refresh)"
                input "pasteCode", "textarea", title: "…or paste a code for that name (Broadlink hex, Pronto hex, or raw timings in µs)", required: false, submitOnChange: true
                input "paste_add", "button", title: "Add the pasted code"
                if (state.arPasteMsg) paragraph "<i>${state.arPasteMsg}</i>"
            }
        }
    }
}

/* ------------------------- Add a remote: 4. Name it ------------------------- */

def arNamePage() {
    Map chosen = [:]
    try { chosen = chosenButtons() } catch (e) { log.warn "LinknLink: ${e.message}" }
    String defName = state.arRecheck ? getChildDevice(state.arRecheck)?.displayName : [settings.arBrand, settings.arCategory ?: settings.arType].findAll { it }.join(" ").replace("_", " ") ?: "IR Remote"
    state.remove("arCreateMsg")
    dynamicPage(name: "arNamePage", title: "Add a remote - 4 of 4: Name it", nextPage: "arDonePage") {
        section("") {
            paragraph chosen ? "${chosen.size()} buttons: ${chosen.keySet().join(', ')}" :
                               "<span style='color:red'>No buttons yet - go back and add at least one.</span>"
            input "remoteLabel", "text", title: "Remote name", required: true, defaultValue: defName, submitOnChange: true
            paragraph "Press <b>Next</b> to ${state.arRecheck ? 'save the changes' : 'create the remote'}."
        }
    }
}

def arDonePage() {
    if (!state.arCreateMsg && !atomicState.arCreating) {     // once per visit; a re-render or double click can't make a second remote
        atomicState.arCreating = true
        try { createRemote() } finally { atomicState.arCreating = false }
    }
    def cd = state.arRecheck ? getChildDevice(state.arRecheck) : null
    dynamicPage(name: "arDonePage", title: "Remote ready", nextPage: "mainPage") {
        section("") {
            paragraph "<b>${state.arCreateMsg}</b>"
            if (cd) paragraph "<a href='/device/edit/${cd.id}' target='_blank'>Open ${cd.displayName}</a> - each button is a command on its device page " +
                              "(press one to use it). Dashboards, Rule Machine and webCoRE can run them like any device command."
            paragraph "Press <b>Next</b> to go back to the main page."
        }
    }
}

/* ============================ Button handling ============================ */

def appButtonHandler(String btn) {
    if (btn == "saveBroker") {
        if (!settings.brokerIp) { state.brokerMsg = "Enter the broker IP first."; return }
        try { initialize() } catch (e) { log.error "LinknLink: save failed: ${e.message}" }
        state.brokerMsg = getListener() ? "Saved - connecting now. Your sensors appear under Choose sensors within about a minute."                                         : "Couldn't create the MQTT Listener - press Done once to finish installing the app, then open it again."
        return
    }
    if (btn == "saveSensors") {
        try { initialize(); state.sensorsMsg = "Saved - ${getUltras().size()} sensor device(s)." } catch (e) { state.sensorsMsg = "Couldn't save: ${e.message}" }
        return
    }
    if (btn.startsWith("testgrp_")) {
        try {
            Map g = codeGroups()[btn.substring(8) as int]
            sendTest(g.testHex, "Power (Group ${(char) (65 + (btn.substring(8) as int))})")
        } catch (e) { state.arLastTest = "Couldn't send the test: ${e.message}" }
    } else if (btn.startsWith("testbtn_")) {
        try {
            Map r = buttonRows()[btn.substring(8) as int]
            sendTest(r.hex, r.key ?: r.names[0])
        } catch (e) { state.arLastTest = "Couldn't send the test: ${e.message}" }
    } else if (btn.startsWith("testown_")) {
        Map own = (state.arOwn ?: [:]) as Map
        String n = (own.keySet() as List)[btn.substring(8) as int]
        sendTest(own[n], n)
    } else if (btn.startsWith("dropown_")) {
        Map own = (state.arOwn ?: [:]) as Map
        own.remove((own.keySet() as List)[btn.substring(8) as int])
        state.arOwn = own
    } else if (btn == "learn_go") {
        String n = normalizeOwnName(settings.learnName)
        if (!n) { state.arLearnMsg = "Type the button's name first."; return }
        def ultra = getChildDevice(settings.arBlaster)
        if (!ultra) { state.arLearnMsg = "No eMotion chosen on page 1."; return }
        if (ultra.learnIr() != true) { state.remove("arLearnFor"); state.arLearnMsg = "Couldn't start learning - check the eMotion's IP address (Choose sensors) and that its model has IR."; return }
        state.arLearnFor = n
        state.arLearnMsg = "Listening for '${n}' for 30 seconds - press that button on the remote now, aimed at the eMotion."
    } else if (btn == "paste_add") {
        String n = normalizeOwnName(settings.learnName)
        String hex = null
        try { hex = pastedToHex(settings.pasteCode) } catch (e) { state.arPasteMsg = "Couldn't read that code: ${e.message}"; return }
        if (!n || !hex) { state.arPasteMsg = "Type the button's name and paste a code."; return }
        Map own = (state.arOwn ?: [:]) as Map
        own[n] = hex
        state.arOwn = own
        state.arPasteMsg = "Added '${n}'."
        app.removeSetting("pasteCode")
    }
    // ar_reload / learn_check just re-draw the page
}

// Called by the Ultra when learning finishes (hex null = nothing received)
def irLearned(String ultraDni, String hex) {
    String n = state.arLearnFor
    if (!n) return
    state.remove("arLearnFor")
    if (!hex) { state.arLearnMsg = "Nothing was received for '${n}'. Try again, closer to the eMotion."; return }
    Map own = (state.arOwn ?: [:]) as Map
    own[n] = hex
    state.arOwn = own
    state.arLearnMsg = "Got '${n}'."
    app.removeSetting("learnName")
}

private void sendTest(String hex, String label) {
    def ultra = getChildDevice(settings.arBlaster)
    if (!ultra) { state.arLastTest = "No eMotion chosen on page 1."; return }
    if (!hex) { state.arLastTest = "That button has no usable code."; return }
    ultra.sendIrCode(hex, "test ${label}")
    state.arLastTest = "Sent ${label}."
}

private String normalizeOwnName(String s) {
    String t = s?.trim()
    if (!t) return null
    return stdKey(t) ?: t
}

private Map chosenButtons() {
    Map out = [:]
    buttonRows().eachWithIndex { r, i ->
        if (r.hex && !settings["skip_${i}"]) {
            String k = r.key ?: r.names[0]
            if (!out.containsKey(k)) out[k] = r.hex
        }
    }
    ((state.arOwn ?: [:]) as Map).each { k, v -> out[k] = v }
    Map ordered = [:]
    STD_ORDER.each { k -> if (out.containsKey(k)) ordered[k] = out[k] }
    out.each { k, v -> if (!ordered.containsKey(k)) ordered[k] = v }
    return ordered
}

private void createRemote() {
    Map buttons
    try { buttons = chosenButtons() } catch (e) { state.arCreateMsg = "Couldn't read the codes: ${e.message}"; return }
    if (!buttons) { state.arCreateMsg = "There are no buttons - go back and add at least one."; return }
    String label = settings.remoteLabel?.trim() ?: "IR Remote"
    def ultra = getChildDevice(settings.arBlaster)
    def cd = state.arRecheck ? getChildDevice(state.arRecheck) : null
    boolean created = false
    if (!cd) {
        String dni = "linknlink-remote-${now()}".toString()
        try { cd = addChildDevice(NS, "LinknLink IR Remote", dni, [label: label, name: "LinknLink IR Remote"]) }
        catch (e) { state.arCreateMsg = "Couldn't create it: ${e.message} (is the 'LinknLink IR Remote' driver installed?)"; return }
        state.arRecheck = dni           // a second pass updates this remote instead of making another
        created = true
    } else cd.setLabel(label)
    cd.setButtons(buttons, settings.arBlaster, ultra?.displayName)
    state.arCreateMsg = "${created ? 'Created' : 'Saved'} '${label}' with ${buttons.size()} buttons."
    log.info "LinknLink: ${created ? 'created' : 'updated'} remote ${label} (${buttons.size()} buttons)"
}

private void loadForRecheck(String dni) {
    def cd = getChildDevice(dni)
    if (!cd) return
    resetWizard()
    state.arRecheck = dni
    String blaster = cd.getBlasterDni()
    if (!blaster || !getChildDevice(blaster)) blaster = getBlasters()[0]?.deviceNetworkId
    if (blaster) app.updateSetting("arBlaster", [type: "enum", value: blaster])
    state.arOwn = cd.getButtonCodes() ?: [:]
}

private void resetWizard() {
    ["arOwnOnly", "arUseIrdb", "arCategory", "arBrand", "arType", "arGroup", "arShowAdd", "learnName", "pasteCode", "remoteLabel"].each { app.removeSetting(it) }
    settings.keySet().findAll { it.startsWith("skip_") }.each { app.removeSetting(it) }
    ["arOwn", "arLastTest", "arLearnMsg", "arLearnFor", "arPasteMsg", "arCreateMsg", "arRecheck", "arRowsFor", "arPrev"].each { state.remove(it) }
    CACHE.remove("rows_${app.id}".toString())
}

// When a choice changes, the choices that depended on it are cleared
// (e.g. a new device type clears the brand). Switching database clears all of them.
private void cascadeChoices() {
    Map prev = (state.arPrev ?: [:]) as Map
    List chain = ["arCategory", "arBrand", "arType", "arGroup"]
    String mode = (settings.arUseIrdb == true) ? "irdb" : "flipper"      // null and false both mean "off"
    boolean clearRest = prev.mode != null && prev.mode != mode
    Map cur = [mode: mode]
    chain.each { k ->
        def v = settings[k]
        if (clearRest) { if (v != null) app.removeSetting(k); v = null }
        else if (prev.containsKey(k) && prev[k] != v) clearRest = true
        cur[k] = v
    }
    if (prev && prev != cur) {      // something earlier changed: old button rows and leave-out switches no longer apply
        state.remove("arRowsFor")
        CACHE.remove("rows_${app.id}".toString())
        settings.keySet().findAll { it.startsWith("skip_") }.each { app.removeSetting(it) }
    }
    state.arPrev = cur
}

/* ======================== Code databases (download) ======================== */

private String httpText(String url) {
    String out = null
    httpGet([uri: url, textParser: true, timeout: 20, headers: ["Accept": "*/*"]]) { resp -> out = resp.data?.text }
    return out
}

private def httpJson(String url) {
    def out = null
    httpGet([uri: url, timeout: 20, headers: ["Accept": "application/vnd.github+json"]]) { resp -> out = resp.data }
    return out
}

private static String enc(String path) {
    path.tokenize("/").collect { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }.join("/")
}

private List irdbIndex() {
    String k = "irdbIndex"
    if (!CACHE[k]) CACHE[k] = httpText(IRDB_RAW + "index").readLines().findAll { it.endsWith(".csv") }
    return CACHE[k] as List
}

private List irdbBrands() { irdbIndex().collect { it.tokenize("/")[0] }.unique().sort { it.toLowerCase() } }

private List irdbTypes(String brand) {
    irdbIndex().findAll { it.startsWith(brand + "/") }.collect { it.tokenize("/")[1] }.unique().sort()
}

// Flipper-IRDB folder listing (GitHub contents API): "" = device types, "TVs" = brands, ...
private List flipperList(String path) {
    String k = "fl_" + path
    if (!CACHE[k]) {
        def items = httpJson(FLIPPER_API + enc(path))
        boolean filesHere = path.contains("/")          // "" = types, "TVs" = brands (folders only), "TVs/LG" = code files
        List keep = items.findAll { !it.name.startsWith(".") && !it.name.startsWith("_") && (filesHere || it.type == "dir") }
        Map types = [:]
        keep.each { types[it.name] = it.type }
        CACHE["flt_" + path] = types
        CACHE[k] = keep.collect { it.name }.sort { it.toLowerCase() }
    }
    return CACHE[k] as List
}

private Map flipperTypes(String path) { flipperList(path); return (CACHE["flt_" + path] ?: [:]) as Map }

// Every code file for the chosen brand (and type)
private List codesetFiles() {
    if (settings.arUseIrdb) {
        if (!settings.arBrand || !settings.arType) return []
        return irdbIndex().findAll { it.startsWith("${settings.arBrand}/${settings.arType}/") }
    }
    if (!settings.arCategory || !settings.arBrand) return []
    String dir = "${settings.arCategory}/${settings.arBrand}"
    List names = flipperList(dir)
    Map types = flipperTypes(dir)
    List files = names.findAll { it.toLowerCase().endsWith(".ir") }.collect { "${dir}/${it}".toString() }
    names.findAll { types[it] == "dir" }.take(10).each { sub ->
        try { flipperList("${dir}/${sub}".toString()).findAll { it.toLowerCase().endsWith(".ir") }.each { files << "${dir}/${sub}/${it}".toString() } } catch (ignored) { }
    }
    return files
}

private String fileUrl(String file) { (file.toLowerCase().endsWith(".ir") ? FLIPPER_RAW : IRDB_RAW) + enc(file) }

// Downloads the files, at most MAX_PARALLEL at a time (Hubitat warns in the logs when an app has
// ~20+ requests open), waiting up to LOAD_WAIT_MS. Returns true when all are in.
@Field static final int MAX_PARALLEL = 4

private boolean loadFiles(List files) {
    List todo = files.findAll { !CACHE.containsKey("cs_" + it) && !CACHE.containsKey("pend_" + it) }
    if (todo) {
        CACHE["loadQueue"] = new java.util.concurrent.ConcurrentLinkedQueue(todo)
        int running = files.count { CACHE.containsKey("pend_" + it) } as int
        for (int i = running; i < MAX_PARALLEL; i++) startNextLoad()      // never more than MAX_PARALLEL open
    }
    long until = now() + LOAD_WAIT_MS
    while (files.any { !CACHE.containsKey("cs_" + it) } && now() < until) pauseExecution(300)
    files.findAll { !CACHE.containsKey("cs_" + it) && CACHE["pend_" + it] && now() - (CACHE["pend_" + it] as long) > 30000L }
         .each { CACHE.remove("pend_" + it) }       // give up on a stuck download so "Check again" retries it
    return files.every { CACHE.containsKey("cs_" + it) }
}

private void startNextLoad() {
    def q = CACHE["loadQueue"]
    String f = q?.poll()
    if (!f) return
    CACHE["pend_" + f] = now()
    asynchttpGet("codesetLoaded", [uri: fileUrl(f), timeout: 15], [file: f])
}

def codesetLoaded(resp, data) {
    String f = data?.file
    if (!f) return
    List parsed = []
    boolean ok = false
    try { if (resp?.status == 200) { parsed = parseCodeset(f, resp.getData()?.toString() ?: ""); ok = true } } catch (ignored) { }
    if (ok) CACHE["cs_" + f] = parsed          // a failed download isn't cached, so "Check again" retries it
    CACHE.remove("pend_" + f)
    startNextLoad()                 // one finished, start the next
}

// [[name:, proto:, family:, hex: (null if unsupported)]]
private List parseCodeset(String file, String txt) {
    List out = []
    if (file.toLowerCase().endsWith(".ir")) {
        txt.split(/(?m)^#.*$/).each { block ->
            Map f = [:]
            block.readLines().each { line -> int c = line.indexOf(":"); if (c > 0) f[line.substring(0, c).trim()] = line.substring(c + 1).trim() }
            if (!f.name) return
            String hex = null
            boolean raw = f.type == "raw"
            try {
                hex = raw ? durationsToHex(f.data.tokenize(" ").collect { it as int }.withIndex().collect { v, i -> i % 2 ? -v : v })
                          : flipperParsedToHex(f.protocol, f.address, f.command)
            } catch (ignored) { hex = null }
            out << [name: f.name, proto: raw ? "raw" : (f.protocol ?: "?"), family: raw ? null : "${f.protocol}|${f.address}".toString(), hex: hex]
        }
    } else {
        txt.readLines().drop(1).each { line ->
            List p = line.split(",", -1) as List
            if (p.size() < 5) return
            String fn = p[-1], sub = p[-2], dev = p[-3], proto = p[-4]
            String name = p[0..-5].join(",").trim()
            String hex = null
            try { hex = irdbToHex(proto, dev as int, sub as int, fn as int) } catch (ignored) { hex = null }
            out << [name: name ?: "function ${fn}", proto: proto, family: "${proto}|${dev}|${sub}".toString(), hex: hex]
        }
    }
    return out
}

// Groups the brand's files by the IR codes they use (protocol + device address), biggest group first.
// Each group's buttons are merged from all its files; for each button the most common code wins.
private List codeGroups() {
    List files = codesetFiles()
    if (!files) return []
    if (!loadFiles(files)) return []
    Map groups = [:]
    files.each { f ->
        List b = (CACHE["cs_" + f] ?: []) as List
        List usable = b.findAll { it.hex }
        if (!usable) return
        Map fc = usable.findAll { it.family }.countBy { it.family }
        String fam = fc ? fc.max { it.value }.key : "raw:${f}".toString()
        Map g = groups[fam] ?: [id: fam, files: [], votes: [:], names: [:]]
        g.files << f
        usable.each { btn ->
            String k = stdKey(btn.name) ?: btn.name.trim()
            Map v = (g.votes[k] ?: [:]) as Map
            v[btn.hex] = ((v[btn.hex] ?: 0) as int) + 1
            g.votes[k] = v
            List nm = (g.names[k] ?: []) as List
            if (!nm.contains(btn.name)) nm << btn.name
            g.names[k] = nm
        }
        groups[fam] = g
    }
    List out = groups.values().collect { g ->
        Map buttons = [:]
        g.votes.each { k, v -> buttons[k] = (v as Map).max { it.value }.key }
        String testHex = buttons["power"] ?: buttons["off"] ?: buttons["on"]
        [id: g.id, files: g.files, buttons: buttons, names: g.names, testHex: testHex]
    }
    out.sort { a, b -> b.files.size() <=> a.files.size() ?: b.buttons.size() <=> a.buttons.size() }
    return out.take(MAX_GROUPS)
}

// Rows for page 3: one per button of the chosen group, standard buttons first
private List buttonRows() {
    String gid = settings.arGroup
    String k = "rows_${app.id}".toString()
    if (settings.arOwnOnly || !gid) return []
    if (state.arRowsFor == gid && CACHE[k]) return CACHE[k] as List
    boolean changed = state.arRowsFor && state.arRowsFor != gid
    Map g = codeGroups().find { it.id == gid }
    if (!g) return []
    List rows = g.buttons.collect { key, hex -> [key: STD_ORDER.contains(key) ? key : null, names: (g.names[key] ?: [key]) as List, hex: hex] }
    rows.sort { a, b -> (STD_ORDER.indexOf(a.key) < 0 ? 999 : STD_ORDER.indexOf(a.key)) <=> (STD_ORDER.indexOf(b.key) < 0 ? 999 : STD_ORDER.indexOf(b.key)) }
    // the same code under several spellings (Q Menu / Q.menu / Q_menu) is one button: keep the first,
    // standard names first; drop names that are only punctuation
    Map seen = [:]
    rows = rows.findAll { r ->
        String label = r.key ?: r.names[0]
        if (!(label ==~ /.*[A-Za-z0-9].*/)) return false
        if (seen.containsKey(r.hex)) { (seen[r.hex].names as List).addAll(r.names.findAll { !(seen[r.hex].names as List).contains(it) }); return false }
        seen[r.hex] = r
        return true
    }
    if (changed) settings.keySet().findAll { it.startsWith("skip_") }.each { app.removeSetting(it) }
    CACHE[k] = rows
    state.arRowsFor = gid
    return rows
}

private static String stdKey(String name) {
    String n = name?.toUpperCase()?.replace("_", " ")?.replaceAll(/\s+/, " ")?.trim()
    if (!n) return null
    String exact = STD_ORDER.find { it.equalsIgnoreCase(name.trim()) }      // a driver command name typed as-is
    if (exact) return exact
    if (n == "INPUTSOURCE") return "input"
    if (n == "SHOWINFO") return "info"
    def d = (n =~ /^(DIGIT |NUM |NUMBER |KEY |BTN )?([0-9])$/)
    if (d.matches()) return "digit${d[0][2]}"
    for (List p : STD_PATTERNS) { if (n ==~ p[1]) return p[0] }
    return null
}

/* ============================ IR encoders ============================ */
// Every encoder returns signed durations in µs: + = IR on (mark), - = off (space).

private static void bits(List out, long v, int n, boolean msb, int unit, List zero, List one) {
    for (int i = 0; i < n; i++) {
        int b = msb ? (int) ((v >> (n - 1 - i)) & 1) : (int) ((v >> i) & 1)
        (b ? one : zero).each { u -> out << (int) (u * unit) }
    }
}

// Adds the gap that ends a frame; merges with a trailing space so marks/spaces keep alternating
private static void padTo(List out, int totalUs) {
    int sum = out.sum { Math.abs(it as int) } as int
    int gap = -Math.max(10000, totalUs - sum)
    if (out && (out[-1] as int) < 0) out[-1] = (out[-1] as int) + gap else out << gap
}

// Merges neighbouring marks/spaces and drops leading spaces, so the list strictly alternates
private static List norm(List o) {
    List m = []
    o.each { v ->
        int x = v as int
        if (x == 0) return
        if (m && (((m[-1] as int) > 0) == (x > 0))) m[-1] = (m[-1] as int) + x else m << x
    }
    while (m && (m[0] as int) < 0) m.remove(0)
    return m
}

private static List necFrame(int d, int s, int f, int f2, int leaderUnits) {
    int u = 564
    List o = [leaderUnits * u, -8 * u]
    [d, s, f, f2].each { bits(o, it & 0xFF, 8, false, u, [1, -1], [1, -3]) }
    o << u
    padTo(o, 108000)
    return o
}

private static List sonyFrame(int f, int d, int dBits, int s, int sBits) {
    int u = 600
    List o = []
    3.times {
        List fr = [4 * u, -u]
        bits(fr, f, 7, false, u, [1, -1], [2, -1])
        bits(fr, d, dBits, false, u, [1, -1], [2, -1])
        if (sBits) bits(fr, s, sBits, false, u, [1, -1], [2, -1])
        fr.remove(fr.size() - 1)      // the last bit's space becomes the frame gap
        padTo(fr, 45000)
        o.addAll(fr)
    }
    return o
}

private static List rc5Frame(int d, int f) {
    int u = 889
    List o = []
    List one = [-1, 1], zero = [1, -1]
    bits(o, 1, 1, true, u, zero, one)
    bits(o, f < 64 ? 1 : 0, 1, true, u, zero, one)
    bits(o, 0, 1, true, u, zero, one)                 // toggle
    bits(o, d, 5, true, u, zero, one)
    bits(o, f & 0x3F, 6, true, u, zero, one)
    List frame = norm(o)
    padTo(frame, 114000)
    return frame + frame
}

private static List rc6Frame(int d, int f) {
    int u = 444
    List o = [6 * u, -2 * u]
    bits(o, 1, 1, true, u, [-1, 1], [1, -1])          // start bit
    bits(o, 0, 3, true, u, [-1, 1], [1, -1])          // mode 0
    bits(o, 0, 1, true, u, [-2, 2], [2, -2])          // toggle (double length)
    bits(o, d, 8, true, u, [-1, 1], [1, -1])
    bits(o, f, 8, true, u, [-1, 1], [1, -1])
    List frame = norm(o)
    padTo(frame, 107000)
    return frame
}

private static List pulseDistance(int u, List header, List<List> fields, List trailer) {
    List o = []
    header.each { o << (int) (it * u) }
    fields.each { fl -> bits(o, fl[0] as long, fl[1] as int, false, u, fl[2] as List, fl[3] as List) }
    trailer.each { o << (int) (it * u) }
    return o
}

// IRDB protocols. dev/sub/fn as in the CSV; sub -1 = default.
private static String irdbToHex(String proto, int dev, int sub, int fn) {
    List d
    switch (proto) {
        case "NEC": case "NEC1": case "NEC2":
            d = necFrame(dev, sub >= 0 ? sub : (~dev & 0xFF), fn, ~fn & 0xFF, 16); break
        case "NECx1": case "NECx2":
            d = necFrame(dev, sub >= 0 ? sub : dev, fn, ~fn & 0xFF, 8); break
        case "Pioneer":
            List p = necFrame(dev, sub >= 0 ? sub : (~dev & 0xFF), fn, ~fn & 0xFF, 16)
            d = p + p; break
        case "Sony12": d = sonyFrame(fn, dev, 5, 0, 0); break
        case "Sony15": d = sonyFrame(fn, dev, 8, 0, 0); break
        case "Sony20": d = sonyFrame(fn, dev, 5, Math.max(sub, 0), 8); break
        case "RC5": d = rc5Frame(dev, fn); break
        case "RC6": d = rc6Frame(dev, fn); break
        case "JVC":
            List hdr = [16 * 525, -8 * 525]
            List body = pulseDistance(525, [], [[dev, 8, [1, -1], [1, -3]], [fn, 8, [1, -1], [1, -3]]], [1, -45])
            d = hdr + body + body; break
        case "Panasonic":
            int s = Math.max(sub, 0)
            d = pulseDistance(432, [8, -4], [[2, 8, [1, -1], [1, -3]], [32, 8, [1, -1], [1, -3]], [dev, 8, [1, -1], [1, -3]],
                              [s, 8, [1, -1], [1, -3]], [fn, 8, [1, -1], [1, -3]], [(dev ^ s ^ fn) & 0xFF, 8, [1, -1], [1, -3]]], [1, -173])
            break
        case "Denon": case "Sharp":
            boolean denon = proto == "Denon"
            List z = [1, -3], o1 = [1, -7]
            d = pulseDistance(264, [], [[dev, 5, z, o1], [fn, 8, z, o1], [denon ? 0 : 1, 2, z, o1]], [1, -165]) +
                pulseDistance(264, [], [[dev, 5, z, o1], [~fn & 0xFF, 8, z, o1], [denon ? 3 : 2, 2, z, o1]], [1, -165])
            break
        case "Aiwa":
            int s = Math.max(sub, 0)
            d = pulseDistance(550, [16, -8], [[dev, 8, [1, -1], [1, -3]], [s, 5, [1, -1], [1, -3]], [~dev & 0xFF, 8, [1, -1], [1, -3]],
                              [~s & 0x1F, 5, [1, -1], [1, -3]], [fn, 8, [1, -1], [1, -3]], [~fn & 0xFF, 8, [1, -1], [1, -3]]], [1, -42])
            break
        default: throw new IllegalArgumentException("unsupported " + proto)
    }
    return durationsToHex(d)
}

// Flipper "parsed" protocols. address/command are little-endian byte strings like "04 00 00 00".
private static String flipperParsedToHex(String proto, String address, String command) {
    List<Integer> a = address.tokenize(" ").collect { Integer.parseInt(it, 16) }
    List<Integer> c = command.tokenize(" ").collect { Integer.parseInt(it, 16) }
    int A = a[0] | (a[1] << 8), C = c[0] | (c[1] << 8)
    List d
    switch (proto) {
        case "NEC":       d = necFrame(a[0], ~a[0] & 0xFF, c[0], ~c[0] & 0xFF, 16); break
        case "NECext":    d = necFrame(a[0], a[1], c[0], c[1], 16); break
        case "Samsung32": d = necFrame(a[0], a[0], c[0], ~c[0] & 0xFF, 8); break
        case "Pioneer":   List p = necFrame(a[0], ~a[0] & 0xFF, c[0], ~c[0] & 0xFF, 16); d = p + p; break
        case "SIRC":      d = sonyFrame(C & 0x7F, A & 0x1F, 5, 0, 0); break
        case "SIRC15":    d = sonyFrame(C & 0x7F, A & 0xFF, 8, 0, 0); break
        case "SIRC20":    d = sonyFrame(C & 0x7F, A & 0x1F, 5, (A >> 5) & 0xFF, 8); break
        case "RC5": case "RC5X": d = rc5Frame(A & 0x1F, C & 0x7F); break
        case "RC6":       d = rc6Frame(A & 0xFF, C & 0xFF); break
        default: throw new IllegalArgumentException("unsupported " + proto)
    }
    return durationsToHex(d)
}

// Pasted code: Broadlink hex, Pronto hex, or raw µs timings
private String pastedToHex(String code) {
    String t = code?.trim()
    if (!t) return null
    String compact = t.replaceAll(/\s+/, "")
    List parts = t.split(/[\s,]+/) as List
    boolean byteHex = parts.size() > 4 && parts.every { it ==~ /(?i)[0-9a-f]{2}/ }     // "26 00 4a 00 ..."
    // Broadlink hex: one hex string (or space-separated bytes) starting 26, whose length field matches
    if ((!(t =~ /[\s,]/) || byteHex) && compact ==~ /(?i)26[0-9a-f]+/ && compact.length() % 2 == 0 && compact.length() >= 8) {
        int declared = Integer.parseInt(compact.substring(4, 6), 16) | (Integer.parseInt(compact.substring(6, 8), 16) << 8)
        if (compact.length() / 2 >= 4 + declared) return compact.substring(0, (4 + declared) * 2).toLowerCase()
        throw new IllegalArgumentException("Broadlink code is shorter than its length field")
    }
    if (parts[0] == "0000" && parts.every { it ==~ /(?i)[0-9a-f]{4}/ }) {          // Pronto
        List<Integer> w = parts.collect { Integer.parseInt(it, 16) }
        double period = w[1] * 0.241246
        int n1 = w[2], n2 = w[3]
        if (w.size() < 4 + 2 * (n1 + n2)) throw new IllegalArgumentException("Pronto code is shorter than it says")
        List<Integer> once = w.subList(4, 4 + 2 * n1), rep = w.subList(4 + 2 * n1, 4 + 2 * (n1 + n2))
        // once-part followed by the repeat part; codes with only a repeat part (Sony) send it 3 times
        List<Integer> seq = n1 ? (once + rep) : (rep + rep + rep)
        return durationsToHex(seq.withIndex().collect { v, i -> (int) Math.round(v * period) * (i % 2 ? -1 : 1) })
    }
    List<Integer> nums = parts.collect { it.replace("+", "") as int }
    if (nums.every { it >= 0 }) nums = nums.withIndex().collect { v, i -> i % 2 ? -v : v }
    return durationsToHex(nums)
}

// Signed µs durations -> Broadlink IR hex (units of 269/8192 ms)
private static String durationsToHex(List durationsIn) {
    double tick = 269.0 / 8192.0 * 1000.0
    List durations = norm(durationsIn)
    if (!durations) throw new IllegalArgumentException("empty code")
    List<Integer> body = []
    durations.each { v ->
        int units = (int) Math.round(Math.abs(v as int) / tick)
        units = Math.max(1, Math.min(units, 0xFFFF))
        if (units > 255) { body << 0; body << (units >> 8); body << (units & 0xFF) } else body << units
    }
    body << 0x0d; body << 0x05
    int n = body.size()
    List<Integer> out = [0x26, 0x00, n & 0xFF, (n >> 8) & 0xFF] + body
    return out.collect { String.format("%02x", it) }.join()
}

/* ================================ Devices ================================ */

def installed() { initialize() }

def updated() {
    unsubscribe()
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

def initialize() {
    def l = ensureListener()
    l?.configure([broker: "tcp://${settings.brokerIp}:${settings.brokerPort ?: 1883}", username: settings.brokerUser])
    syncUltras()
    l?.resendAll()          // new devices get every current value on the Ultra's next publish
}

def uninstalled() {
    getChildDevices().each { try { deleteChildDevice(it.deviceNetworkId) } catch (ignored) { } }
}

def logsOff() { app.updateSetting("logEnable", [type: "bool", value: false]) }

private String listenerDni() { "linknlink-mqtt-listener" }
def getListener() { getChildDevice(listenerDni()) }

private def ensureListener() {
    def l = getListener()
    if (l) return l
    try { l = addChildDevice(NS, "LinknLink MQTT Listener", listenerDni(), [label: "LinknLink MQTT Listener", isComponent: true]) }
    catch (e) { log.error "LinknLink: couldn't create the MQTT Listener (is its driver installed?): ${e.message}" }
    return l
}

List getUltras()  { getChildDevices().findAll { it.typeName == "LinknLink eMotion Ultra" } }
// eMotion devices whose model has an IR blaster
List getBlasters() { getUltras().findAll { MODELS[it.getDataValue("model") ?: DEFAULT_MODEL]?.ir } }
private String modelOf(String mac) { MODELS.containsKey(settings["model_${mac}"]) ? settings["model_${mac}"] : DEFAULT_MODEL }
List getRemotes() { getChildDevices().findAll { it.typeName == "LinknLink IR Remote" } }

private void syncUltras() {
    List want = (settings.selectedUnits ?: []) as List
    Map cat = getListener()?.getCatalog() ?: [:]
    want.each { mac ->
        String dni = "linknlink-${mac}"
        def cd = getChildDevice(dni)
        if (!cd) {
            try {
                cd = addChildDevice(NS, "LinknLink eMotion Ultra", dni, [label: cat[mac]?.name ?: "eMotion ${mac}", name: "LinknLink eMotion Ultra"])
                log.info "LinknLink: created ${cd.displayName}"
            } catch (e) { log.error "LinknLink: couldn't create the device for ${mac}: ${e.message}"; return }
        }
        cd.updateDataValue("mac", mac)
        String model = modelOf(mac)
        cd.updateDataValue("model", model)
        try { cd.setModel(model, MODELS[model]?.ir == true) } catch (ignored) { }
        String ip = settings["ip_${mac}"]?.trim()
        if (ip) cd.updateSetting("deviceIp", [type: "text", value: ip])
    }
    // Never delete devices here: a lost or empty selection (page glitch, update) must not wipe sensors that
    // rules, dashboards and remotes use. To delete one, use Remove device on its device page.
}

/* ===================== Called by the Listener / devices ===================== */

String getMqttPassword() { settings.brokerPassword }

def mqttMessage(String topic, String payload) {
    def m = (topic =~ /^home\/(?:config\/)?([0-9a-f]{12})/)
    if (!m.find()) return
    def cd = getChildDevice("linknlink-${m.group(1)}")
    cd?.mqttUpdate(topic, payload)
}

boolean mqttPublish(String topic, String value) { getListener()?.publish(topic, value) ?: false }

def refreshListener() { getListener()?.refresh() }

def sendIr(String blasterDni, String hex, String label) {
    def u = getChildDevice(blasterDni)
    if (!u) {                       // at most one error per 10 min, so a rule pressing it can't flood the log
        Long last = CACHE["blasterGone"] as Long
        if (!last || now() - last > 600000L) { CACHE["blasterGone"] = now(); log.error "LinknLink: the IR blaster for ${label} is gone - re-check the remote" }
        return
    }
    u.sendIrCode(hex, label)
}
