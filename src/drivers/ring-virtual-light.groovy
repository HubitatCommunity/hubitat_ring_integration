/**
 *  Ring Virtual Light Device Driver
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
  definition(name: "Ring Virtual Light", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-light.groovy") {
    capability "Actuator"
    capability "Switch"
    capability "Sensor"
    capability "Refresh"
    capability "Polling"
    capability "MotionSensor"
    capability "Battery"

    attribute "battery2", "number"
    attribute "lastActivity", "string"

    command "flash"
    command "getDings"
  }

  // simulator metadata
  simulator {
  }

  // UI tile definitions
  tiles {
    standardTile("button", "device.switch", width: 2, height: 2, canChangeIcon: true) {
      state "off", label: 'Off', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff", nextState: "on"
      state "on", label: 'On', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#00A0DC", nextState: "off"
    }
    main "button"
    details "button"
  }
  preferences {
    input name: "lightPolling", type: "bool", title: "Enable polling for light status on this device", defaultValue: false
    input name: "lightInterval", type: "number", range: 10..600, title: "Number of seconds in between light polls", defaultValue: 15
    input name: "snapshotPolling", type: "bool", title: "Enable polling for thumbnail snapshots on this device", defaultValue: false
    input name: "strobeTimeout", type: "enum", title: "Flash Timeout", options: [[30: "30s"], [60: "1m"], [120: "2m"], [180: "3m"]], defaultValue: 30
    input name: "strobeRate", type: "enum", title: "Flash rate", options: [[1000: "1s"], [2000: "2s"], [5000: "5s"]], defaultValue: 1000
    input name: "discardBatteryLevel", type: "bool", title: "<b>Discard the battery level because this device is plugged in or doesn't support " +
      "battery level</b>", description: "This setting can prevent a battery level attribute from showing up but it cannot remove one once battery " +
      "has been set.  Nothing can.", defaultValue: true
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false
  }
}

@Field static def LAST_ACTIVITY_THRESHOLD = 60 //minutes

def canPollLight() {
  return pollLight
}

def canPollDings() {
  return pollDings
}

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
  logDebug "Attempting to switch on."
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "floodlight_light_on"])
}

def off() {
  if (state.strobing) {
    unschedule()
  }
  state.strobing = false
  logDebug "Attempting to switch off."
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "floodlight_light_off"])
}


def flash() {
  logInfo "${device.getDisplayName()} was set to flash with a rate of ${strobeRate} milliseconds for ${strobeTimeout.toInteger()} seconds"
  state.strobing = true
  strobeOn()
  runIn(strobeTimeout.toInteger(), off)
}

def strobeOn() {
  if (!state.strobing) return
  runInMillis(strobeRate.toInteger(), strobeOff)
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "floodlight_light_on"])
}

def strobeOff() {
  if (!state.strobing) return
  runInMillis(strobeRate.toInteger(), strobeOn)
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "floodlight_light_off"])
}

def childParse(type, params) {
  logDebug "childParse(type, msg)"
  logTrace "type ${type}"
  logTrace "params ${params}"

  if (canReportLastActivity()) {
    sendEvent(name: "lastActivity", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
  }
  if (type == "refresh") {
    logTrace "refresh"
    handleRefresh(params.msg)
  }
  else if (type == "device-set") {
    logTrace "set"
    handleSet(type, params)
  }
  else if (type == "dings") {
    logTrace "dings"
    handleDings(params.type, params.msg)
  }
  else {
    log.error "Unhandled type ${type}"
  }
}

def canReportLastActivity() {
  def now = new Date().getTime()
  if (state.lastActivity == null || now > (state.lastActivity + (LAST_ACTIVITY_THRESHOLD * 60 * 1000))) {
    state.lastActivity = now
    return true
  }
  return false
}

private handleRefresh(json) {
  logTrace "handleRefresh(${json.description})"

  if (json.battery_life != null && !discardBatteryLevel) {
    checkChanged("battery", json.battery_life, "%")
    if (json.battery_life_2 != null) {
      checkChanged("battery2", json.battery_life_2, "%")
    }
  }

  if (!json.led_status) {
    log.warn "No status?"
    return
  }

  if (json.led_status && !(json.led_status instanceof java.lang.String) && json.led_status.seconds_remaining != null) {
    checkChanged("switch", json.led_status.seconds_remaining > 0 ? "on" : "off")
  }
  else if (json.led_status) {
    checkChanged("switch", json.led_status)
  }
  if (json.firmware_version && device.getDataValue("firmware") != json.firmware_version) {
    device.updateDataValue("firmware", json.firmware_version)
  }
}

private handleSet(id, params) {
  logTrace "handleSet(${id}, ${params})"
  if (params.response != 200) {
    log.warn "Not successful?"
    return
  }
  if (params.action == "floodlight_light_on") {
    logInfo "Device ${device.label} switch is on"
    sendEvent(name: "switch", value: "on")
  }
  else if (params.action == "floodlight_light_off") {
    logInfo "Device ${device.label} switch is off"
    sendEvent(name: "switch", value: "off")
  }
  else {
    log.error "Unsupported set ${params.action}"
  }
}

private handleDings(type, json) {
  logTrace "json: ${json}"
  if (json == null) {
    checkChanged("motion", "inactive")
  }
  else if (json.kind == "motion" && json.motion == true) {
    checkChanged("motion", "active")
    unschedule(motionOff)
  }
  if (type == "IFTTT") {
    def motionTimeout = 60
    runIn(motionTimeout, motionOff)
  }
}

def motionOff(data) {
  logDebug "motionOff($data)"
  childParse("dings", [msg: null])
}

def checkChanged(attribute, newStatus, unit=null) {
  if (device.currentValue(attribute) != newStatus) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
    sendEvent(name: attribute, value: newStatus, unit: unit)
  }
}

private convertToLocalTimeString(dt) {
  def timeZoneId = location?.timeZone?.ID
  if (timeZoneId) {
    return dt.format("yyyy-MM-dd h:mm:ss a", TimeZone.getTimeZone(timeZoneId))
  }
  else {
    return "$dt"
  }
}
