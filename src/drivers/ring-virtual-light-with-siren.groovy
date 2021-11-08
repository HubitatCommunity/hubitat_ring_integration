/**
 *  Ring Virtual Light with Siren Device Driver
 *
 *  Copyright 2019 Ben Rimmasch
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */

import groovy.transform.Field

metadata {
  definition(name: "Ring Virtual Light with Siren", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-light-with-siren.groovy") {
    capability "Actuator"
    capability "Switch"
    capability "Sensor"
    capability "Refresh"
    capability "Polling"
    capability "Alarm"
    capability "MotionSensor"

    attribute "lastActivity", "string"

    command "alarmOff"
    command "getDings"
  }

  preferences {
    input name: "lightPolling", type: "bool", title: "Enable polling for light status on this device", defaultValue: false
    input name: "lightInterval", type: "number", range: 10..600, title: "Number of seconds in between light polls", defaultValue: 15
    input name: "snapshotPolling", type: "bool", title: "Enable polling for thumbnail snapshots on this device", defaultValue: false
    input name: "strobeTimeout", type: "enum", title: "Strobe Timeout", options: [[30: "30s"], [60: "1m"], [120: "2m"], [180: "3m"]], defaultValue: 30
    input name: "strobeRate", type: "enum", title: "Strobe rate", options: [[1000: "1s"], [2000: "2s"], [5000: "5s"]], defaultValue: 1000
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false
  }
}

@Field final static Integer LAST_ACTIVITY_THRESHOLD = 60 //minutes

private logInfo(msg) {
  if (descriptionTextEnable) log.info msg
}

def logDebug(msg) {
  if (logEnable) log.debug msg
}

def logTrace(msg) {
  if (traceLogEnable) log.trace msg
}

def configure() {

}

def parse(String description) {
  logDebug "description: ${description}"
}

def poll() {
  refresh()
}

def refresh() {
  logDebug "refresh()"
  parent.simpleRequest("refresh", [dni: device.deviceNetworkId])
}

def getDings() {
  logDebug "getDings()"
  parent.simpleRequest("dings")
}

def setupPolling() {
  unschedule()
  if (lightPolling) {
    pollLight()
  }
}

def pollLight() {
  logTrace "pollLight()"
  refresh()
  if (pollLight) {
    runIn(lightInterval, pollLight)  //time in seconds
  }
}

def updated() {
  setupPolling()
  parent.snapshotOption(device.deviceNetworkId, snapshotPolling)
}

def on() {
  state.strobing = false
  logDebug "Attempting to switch on."
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "floodlight_light_on"])
}

def off(boolean modifyAlarm = true) {
  if (modifyAlarm) {
    alarmOff(false)
  }
  switchOff()
}

def switchOff() {
  if (state.strobing) {
    unschedule()
  }
  state.strobing = false
  logDebug "Attempting to set switch to off."
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "floodlight_light_off"])
}

def alarmOff(boolean modifyLight = true) {
  logDebug "Attempting to set alarm to off."
  final String alarm = device.currentValue("alarm")
  logTrace "alarm: $alarm"
  sendEvent(name: "alarm", value: "off")
  if ((alarm == "strobe" || alarm == "both") && modifyLight) {
    switchOff()
  }
  if (alarm == "siren" || alarm == "both") {
    parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "siren_off"])
  }
}

def siren() {
  logDebug "Attempting to turn on siren."
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "siren_on"])
}

def strobe(value = "strobe") {
  logInfo "${device.getDisplayName()} was set to strobe with a rate of ${strobeRate} milliseconds for ${strobeTimeout.toInteger()} seconds"
  state.strobing = true
  strobeOn()
  sendEvent(name: "alarm", value: value)
  runIn(strobeTimeout.toInteger(), alarmOff)
}

def both() {
  logDebug "Attempting to turn on siren and strobe."
  strobe("both")
  siren()
}

def strobeOn() {
  if (state.strobing) {
    runInMillis(strobeRate.toInteger(), strobeOff)
    parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "floodlight_light_on"])
  }
}

def strobeOff() {
  if (state.strobing) {
    runInMillis(strobeRate.toInteger(), strobeOn)
    parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "floodlight_light_off"])
  }
}

void childParse(final String type, final Map params) {
  logDebug "childParse(${type}, params)"
  logTrace "params ${params}"

  if (canReportLastActivity()) {
    sendEvent(name: "lastActivity", value: convertToLocalTimeString(new Date()))
  }

  if (type == "refresh") {
    logTrace "refresh"
    handleRefresh(params.msg)
  }
  else if (type == "device-set") {
    logTrace "set"
    handleSet(params)
  }
  else if (type == "dings") {
    logTrace "dings"
    handleDings(params.type, params.msg)
  }
  else {
    log.error "Unhandled type ${type}"
  }
}

boolean canReportLastActivity() {
  if (state.lastActivity == null || now() > (state.lastActivity + (LAST_ACTIVITY_THRESHOLD * 60 * 1000))) {
    state.lastActivity = now()
    return true
  }
  return false
}

private void handleRefresh(final Map msg) {
  logDebug "handleRefresh(${msg})"
  if (!msg.led_status) {
    log.warn "No status?"
    return
  }

  checkChanged("switch", msg.led_status)

  if (msg.siren_status?.seconds_remaining != null) {
    final Integer seconds_remaining = msg.siren_status.seconds_remaining
    checkChanged("alarm", seconds_remaining > 0 ? "siren" : "off")
    if (seconds_remaining > 0) {
      runIn(seconds_remaining + 1, refresh)
    }
  }
  if (msg.firmware_version && device.getDataValue("firmware") != msg.firmware_version) {
    device.updateDataValue("firmware", msg.firmware_version)
  }
}

private void handleSet(final Map params) {
  logTrace "handleSet(${params})"
  if (params.response != 200) {
    log.warn "Not successful?"
    return
  }
  if (params.action == "floodlight_light_on") {
    checkChanged("switch", "on")
  }
  else if (params.action == "floodlight_light_off") {
    checkChanged("switch", "off")
  }
  else if (params.action == "siren_on") {
    if (device.currentValue("alarm") != "both") {
      checkChanged("alarm", "siren")
    }
    runIn(params.msg.seconds_remaining + 1, refresh)
  }
  else if (params.action == "siren_off") {
    checkChanged('alarm', "off")
  }
  else {
    log.error "Unsupported set ${params.action}"
  }

}

private void handleDings(final String type, final Map msg) {
  logTrace "msg: ${msg}"
  if (msg == null) {
    log.warn "Got a null msg!"
    checkChanged("motion", "inactive")
  }
  else if (msg.kind == "motion" && msg.motion == true) {
    checkChanged("motion", "active")

    if (type == "IFTTT") {
      runIn(60, motionOff)
    } else {
      unschedule(motionOff)
    }
  }
}

void motionOff() {
  checkChanged("motion", "inactive")
}

boolean checkChanged(final String attribute, final newStatus, final String unit=null) {
  final boolean changed = device.currentValue(attribute) != newStatus
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit)
  return changed
}

private String convertToLocalTimeString(final Date dt) {
  TimeZone timeZone = location?.timeZone
  if (timeZone) {
    return dt.format("yyyy-MM-dd h:mm:ss a", timeZone)
  }
  else {
    return dt.toString()
  }
}
