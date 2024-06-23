/* groovylint-disable Indentation */
/**
 *  Ring Virtual Light with Siren Device Driver
 *
 *  Copyright 2019-2020 Ben Rimmasch
 *  Copyright 2021 Caleb Morse
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

metadata {
  definition(name: "Ring Virtual Light with Siren", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Actuator"
    capability "Alarm"
    capability "MotionSensor"
    capability "Polling"
    capability "Refresh"
    capability "Sensor"
    capability "Switch"
    capability "SwitchLevel"

    attribute "connectionStatus", "enum", ["offline", "online"]
    attribute "firmware", "string"
    attribute 'motionActivatedLights', 'enum', ["enabled", "disabled"]
    attribute "rssi", "number"
    attribute "wifi", "string"

    command "alarmOff"
    command "getDings"
    command "motionActivatedLights", [[name: "state", description: "Enable or disable lights on motion", type: "ENUM", constraints: ["enable", "disable"]] ]
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

void installed() { updated() }

void updated() {
  parentCheck()

  unschedule()
  if (lightPolling) {
    pollLight()
  }

  parent.updateEnabledSnappables()
}

void parentCheck() {
  if (device.parentAppId == null || device.parentDeviceId != null) {
    log.error("This device can only be installed using the Unofficial Ring Connect app. Remove this device and create it through the app. parentAppId=${device.parentAppId}, parentDeviceId=${device.parentDeviceId}")
  }
}

void logInfo(Object msg) {
  if (descriptionTextEnable) { log.info msg }
}

void logDebug(Object msg) {
  if (logEnable) { log.debug msg }
}

void logTrace(Object msg) {
  if (traceLogEnable) { log.trace msg }
}

void parse(String description) {
  logDebug "description: ${description}"
}

void poll() { refresh() }

void refresh() {
  logDebug "refresh()"
  parent.apiRequestClientsApiRefresh(device.deviceNetworkId)
  parent.apiRequestClientsApiHealth(device.deviceNetworkId, "doorbots")
  parent.apiRequestDevicesApiSet(device.deviceNetworkId, "devices", action: "settings", method: 'Get')
}

void getDings() {
  logDebug "getDings()"
  parent.apiRequestDings()
}

void pollLight() {
  logTrace "pollLight()"
  refresh()
  if (pollLight) {
    runIn(lightInterval, pollLight)  //time in seconds
  }
}

void on() {
  state.strobing = false
  setFloodlightInternal('on')
}

void off() {
  alarmOff(false)
  switchOff()
}

void setLevel(level, duration=null) {
  if (duration != null) {
    log.warn("setLevel duration value not supported")
  }

  // Translating SwitchLevel 0-100% to Ring brightness (1-10)
  Integer brightnessLevel = Math.max(1, (level / 10).toInteger())
  parent.apiRequestClientsApiSet(device.deviceNetworkId, "doorbots", action: 'light_intensity', method: 'Put',
                                 query: ["doorbot[settings][light_intensity]": brightnessLevel])
}

void switchOff() {
  if (state.strobing) {
    unschedule()
  }
  state.strobing = false
  setFloodlightInternal('off')
}

void alarmOff(boolean modifyLight = true) {
  final String alarm = device.currentValue("alarm")
  logTrace "alarm: $alarm"
  sendEvent(name: "alarm", value: "off")
  if ((alarm == "strobe" || alarm == "both") && modifyLight) {
    switchOff()
  }
  if (alarm == "siren" || alarm == "both") {
    setSirenInternal('off')
  }
}

void siren() {
  setSirenInternal('on')
}

void strobe(value = "strobe") {
  logInfo "${device.displayName} was set to strobe with a rate of $strobeRate milliseconds for $strobeTimeout seconds"
  state.strobing = true
  strobeOn()
  sendEvent(name: "alarm", value: value)
  runIn(strobeTimeout.toInteger(), alarmOff)
}

void both() {
  strobe("both")
  siren()
}

void strobeOn() {
  if (state.strobing) {
    runInMillis(strobeRate.toInteger(), strobeOff)
    setFloodlightInternal('on')
  }
}

void strobeOff() {
  if (state.strobing) {
    runInMillis(strobeRate.toInteger(), strobeOn)
    setFloodlightInternal('off')
  }
}

void motionActivatedLights(state) {
    // This is backwards as it's manipulating "always snooze"
    final boolean stateOfLight = !(state == "enable")
    parent.apiRequestDevicesApiSet(device.deviceNetworkId, "devices", action: "settings", body: [enable: stateOfLight, light_snooze_settings: ["always_on": stateOfLight]])
}

void handleClientsApiSet(final Map msg, final Map arguments) {
  String action = arguments.action

  if (action == "floodlight_light_on") {
    checkChanged("switch", "on")
  }
  else if (action == "floodlight_light_off") {
    checkChanged("switch", "off")
  }
  else if (action == "siren_on") {
    if (device.currentValue("alarm") != "both") {
      checkChanged("alarm", "siren")
    }

    runIn(msg.seconds_remaining + 1, refresh)
  }
  else if (action == "siren_off") {
    checkChanged('alarm', "off")
  }
  else if (action == "light_intensity") {
    Integer brightnessLevel = arguments.query?.get("doorbot[settings][light_intensity]")
    if (brightnessLevel != null) {
      checkChanged("level", brightnessLevel * 10)
    }
  }
  else {
    log.error "handleClientsApiSet unsupported action ${action}, msg=${msg}, arguments=${arguments}"
  }
}

void handleDevicesApiSet(final Map msg, final Map arguments) {
  String action = arguments.action

  if (action == "settings") {
    if (msg.light_snooze_settings?.always_on != null) {
      // This is backwards as it's manipulating "always snooze"
      checkChanged("motionActivatedLights", msg.light_snooze_settings?.always_on ? "disabled" : "enabled")
    }
  }
  else {
    log.error "handleClientsApiSet unsupported action ${action}, msg=${msg}, arguments=${arguments}"
  }
}

void handleClientsApiHealth(final Map msg) {
  if (msg.device_health) {
    if (msg.device_health.wifi_name) {
      checkChanged("wifi", msg.device_health.wifi_name)
    }
  }
}

void handleMotion(final Map msg) {
  if (msg.motion == true) {
    checkChanged("motion", "active")

    runIn(60, motionOff) // We don't get motion off msgs from ifttt, and other motion only happens on a manual refresh
  }
  else if (msg.motion == false) {
    checkChanged("motion", "inactive")
    unschedule(motionOff)
  }
  else {
    log.error("handleMotion unsupported msg: ${msg}")
  }
}

void handleClientsApiRefresh(final Map msg) {
  if (msg.alerts?.connection != null) {
    checkChanged("connectionStatus", msg.alerts.connection) // devices seem to be considered offline after 20 minutes
  }

  if (msg.led_status) {
    checkChanged("switch", msg.led_status)
  }

  if (msg.siren_status?.seconds_remaining != null) {
    final Integer secondsRemaining = msg.siren_status.seconds_remaining
    checkChanged("alarm", secondsRemaining > 0 ? "siren" : "off")
    if (secondsRemaining > 0) {
      runIn(secondsRemaining + 1, refresh)
    }
  }

  if (msg.health) {
    Map health = msg.health

    if (health.firmware_version) {
      checkChanged("firmware", health.firmware_version)
    }

    if (health.rssi) {
      checkChanged("rssi", health.rssi)
    }

    // Per Ring: is_sidewalk_gateway indicates only whether a device *can* be used as a Sidewalk gateway. sidewalk_connection
    // indicates whether Sidewalk is enabled in the Ring account. Both must be true for Sidewalk to be enabled on a device
    if (msg.is_sidewalk_gateway && health.sidewalk_connection) {
      log.warn("Your device is being used as an Amazon sidewalk device.")
    }
  }

  if (msg.settings?.floodlight_settings?.brightness != null) {
    checkChanged("level", msg.settings.floodlight_settings.brightness * 10)
  }
}

void motionOff() {
  checkChanged("motion", "inactive")
}

void runCleanup() {
  state.remove('lastActivity')
  device.removeDataValue("firmware") // Is an attribute now
  device.removeDataValue("device_id")
}

void setFloodlightInternal(String state) {
    parent.apiRequestClientsApiSet(device.deviceNetworkId, "doorbots", action: "floodlight_light_" + state, method: 'Put')
}

void setSirenInternal(String state) {
    parent.apiRequestClientsApiSet(device.deviceNetworkId, "doorbots", action: "siren_" + state, method: 'Put')
}

boolean checkChanged(final String attribute, final newStatus, final String unit=null, final String type=null) {
  final boolean changed = isStateChange(device, attribute, newStatus.toString())
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}