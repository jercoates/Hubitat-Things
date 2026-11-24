/**
 *  LinknLink IR Remote
 *
 *  One IR remote (TV, soundbar, fan, AC, ...) created by the "LinknLink Device Service" app after
 *  its buttons were tested. Every confirmed button is its own command, so panels (Hubitat
 *  dashboards), Rule Machine and webCoRE can use them directly. The IR is sent through the
 *  LinknLink eMotion Ultra chosen when the remote was made.
 *
 *  - Standard buttons have their own commands (on, off, power, volumeUp, ok, digit, ...).
 *    A standard command this remote has no code for logs a warning and does nothing.
 *  - Any button (including non-standard ones like "Netflix") can be sent with pushButton(name).
 *  - PushableButton: push(n) presses button number n (see the buttonMap attribute), so button
 *    tiles/cards work too.
 *  - Switch on/off use discrete Power On/Off codes when the remote has them, otherwise Power toggle
 *    (then the switch state is a best guess).
 *
 *  1.0.0 - 2026-11-24 - Initial version.
 */

import groovy.json.JsonOutput

def driverVersion() { "1.0.0" }

metadata {
    definition(name: "LinknLink IR Remote", namespace: "jercoates", author: "Jeremy Coates") {
        capability "Actuator"
        capability "Switch"
        capability "PushableButton"

        attribute "buttons", "string"          // comma list of the buttons this remote has
        attribute "buttonMap", "string"        // JSON {"1":"power", "2":"volumeUp", ...} for push(n)
        attribute "lastButton", "string"
        attribute "blaster", "string"

        command "power"
        command "volumeUp"
        command "volumeDown"
        command "mute"
        command "channelUp"
        command "channelDown"
        command "up"
        command "down"
        command "left"
        command "right"
        command "ok"
        command "back"
        command "exit"
        command "home"
        command "menu"
        command "showInfo"
        command "guide"
        command "inputSource"
        command "play"
        command "pause"
        command "playPause"
        command "stop"
        command "fastForward"
        command "rewind"
        command "next"
        command "previous"
        command "record"
        command "digit", [[name: "number*", type: "NUMBER", description: "0-9"]]
        command "pushButton", [[name: "name*", type: "STRING", description: "Button name (see the buttons attribute)"]]
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Log button presses", defaultValue: true
    }
}

def installed() { log.info "${device.displayName}: installed" }
def updated() { }

/* ===================== Called by the app when (re)built ===================== */

// buttons: ordered [name: hexCode]; blasterDni: the Ultra that sends them
def setButtons(Map buttons, String blasterDni, String blasterName = null) {
    state.buttons = buttons
    state.blaster = blasterDni
    Map numbered = [:]
    (buttons.keySet() as List).eachWithIndex { n, i -> numbered[(i + 1).toString()] = n }
    state.numbered = numbered
    sendEvent(name: "buttons", value: buttons.keySet().join(", "))
    sendEvent(name: "buttonMap", value: JsonOutput.toJson(numbered))
    sendEvent(name: "numberOfButtons", value: buttons.size())
    sendEvent(name: "blaster", value: blasterName ?: blasterDni)
    log.info "${device.displayName}: ${buttons.size()} buttons set"
}

Map getButtonCodes() { (state.buttons ?: [:]) as Map }
String getBlasterDni() { state.blaster }

/* ================================ Commands ================================ */

// With discrete codes on/off always send. With only a Power toggle, sending when the switch already
// shows the wanted state would flip the device the wrong way, so that case does nothing.
def on() {
    if (has("on")) press("on")
    else if (has("power")) { if (device.currentValue("switch") == "on") return; press("power") }
    else { warnMissing("on"); return }
    sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} was turned on")
}

def off() {
    if (has("off")) press("off")
    else if (has("power")) { if (device.currentValue("switch") == "off") return; press("power") }
    else { warnMissing("off"); return }
    sendEvent(name: "switch", value: "off", descriptionText: "${device.displayName} was turned off")
}

def power() {
    if (!has("power")) {             // no toggle code: use the discrete ones based on the last known state
        if (device.currentValue("switch") == "on") off() else on()
        return
    }
    press("power")
    sendEvent(name: "switch", value: device.currentValue("switch") == "on" ? "off" : "on")
}

def volumeUp()    { press("volumeUp") }
def volumeDown()  { press("volumeDown") }
def mute()        { press("mute") }
def channelUp()   { press("channelUp") }
def channelDown() { press("channelDown") }
def up()          { press("up") }
def down()        { press("down") }
def left()        { press("left") }
def right()       { press("right") }
def ok()          { press("ok") }
def back()        { press("back") }
def exit()        { press("exit") }
def home()        { press("home") }
def menu()        { press("menu") }
def showInfo()    { press("info") }
def guide()       { press("guide") }
def inputSource() { press("input") }
def play()        { press("play") }
def pause()       { press("pause") }
def playPause()   { has("playPause") ? press("playPause") : press("play") }
def stop()        { press("stop") }
def fastForward() { press("fastForward") }
def rewind()      { press("rewind") }
def next()        { press("next") }
def previous()    { press("previous") }
def record()      { press("record") }
def digit(number) { press("digit${(number as int)}") }

def pushButton(String name) {
    String key = state.buttons?.keySet()?.find { it.equalsIgnoreCase(name?.trim()) }
    if (!key) { warnMissing(name); return }
    if (key == "on") on() else if (key == "off") off() else if (key == "power") power() else press(key)   // keep the switch state right
}

def push(buttonNumber) {
    String name = state.numbered?.get((buttonNumber as int).toString())
    if (!name) { log.warn "${device.displayName}: there is no button ${buttonNumber}"; return }
    sendEvent(name: "pushed", value: buttonNumber as int, isStateChange: true)
    if (name == "on") on() else if (name == "off") off() else if (name == "power") power() else press(name)
}

// Hubitat's standard "input" name is taken by the settings DSL, so the button key "input" is sent
// by inputSource(); pushButton("input") also works.

/* ================================ Helpers ================================ */

private boolean has(String name) { state.buttons?.containsKey(name) }

private void press(String name) {
    String code = state.buttons?.get(name)
    if (!code) { warnMissing(name); return }
    parent?.sendIr(state.blaster, code, "${device.displayName} ${name}")
    sendEvent(name: "lastButton", value: name, isStateChange: true)
    if (txtEnable) log.info "${device.displayName}: ${name}"
}

private void warnMissing(String name) {
    log.warn "${device.displayName}: this remote has no '${name}' button (it has: ${state.buttons?.keySet()?.join(', ')})"
}
