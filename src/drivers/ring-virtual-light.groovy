/**
 *  Ring Virtual Light Device Driver
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
  definition(name: "Ring Virtual Light", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Actuator"
    capability "Battery"
    capability "MotionSensor"
    capability "Polling"
    capability "Sensor"
    capability "Switch"
    capability "Refresh"
    capability "Health Check"

    attribute "firmware", "string"
    attribute "battery2", "number"
    attribute "rssi", "number"
    attribute "wifi", "string"
    attribute "healthStatus", "enum", [ "unknown", "offline", "online" ]

    command "flash"
    command "getDings"
    command "snoozeMotionAlerts", [
        [name:"minutes", type:"NUMBER", description:"Number of minutes to snooze motion alerts for", constraints:["NUMBER"]] ]
    command "motionActivatedLights", [
		[name:"state", description:"Enable or disable lights on motion", type: "ENUM", constraints: ["enabled","disabled"]] ]
  }

  preferences {
    input name: "deviceStatusPollingEnable", type: "bool", title: "Enable polling for device status", defaultValue: true
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

void logInfo(msg) {
  if (descriptionTextEnable) { log.info msg }
}

void logDebug(msg) {
  if (logEnable) { log.debug msg }
}

void logTrace(msg) {
  if (traceLogEnable) { log.trace msg }
}

def parse(String description) {
  logDebug "description: ${description}"
}

def poll() {
  refresh()
}

def refresh() {
  logDebug "refresh()"
  parent.apiRequestDeviceRefresh(device.deviceNetworkId)
  parent.apiRequestDeviceHealth(device.deviceNetworkId, "doorbots")
}

def getDings() {
  logDebug "getDings()"
  parent.apiRequestDings()
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

def snoozeMotionAlerts(minutes = 60) {
    parent.apiRequestDeviceControl(device.deviceNetworkId, "doorbots", "motion_snooze?time=${minutes}", null)
}

def motionActivatedLights(state) {
    // This is backwards as it's manipulating "always snooze"
    stateOfLight = state == "enabled" ? false : true
    parent.apiRequestDeviceApiSet(device.deviceNetworkId, "devices", "settings", ["enable":stateOfLight,"light_snooze_settings":["always_on":stateOfLight]])
}

def updated() {
  setupPolling()
  parent.snapshotOption(device.deviceNetworkId, snapshotPolling)
  scheduleDevicePolling()
}

def installed() {
  scheduleDevicePolling()
}

def scheduleDevicePolling() {
  unschedule(pollDeviceStatus)
  // Schedule at a random second starting at the next minute
  def nextMinute = ((new Date().format( "m" ) as int) + 1) % 60
  Random rnd = new Random()
  if (deviceStatusPollingEnable) {
      schedule( "${rnd.nextInt(59)} ${nextMinute}/30 * ? * *", "refresh" )
  }
}

def on() {
  state.strobing = false
  parent.apiRequestDeviceSet(device.deviceNetworkId, "doorbots", "floodlight_light_on")
}

def off() {
  if (state.strobing) {
    unschedule()
  }
  state.strobing = false
  parent.apiRequestDeviceSet(device.deviceNetworkId, "doorbots", "floodlight_light_off")
}

def flash() {
  logInfo "$device was set to flash with a rate of $strobeRate milliseconds for $strobeTimeout seconds"
  state.strobing = true
  strobeOn()
  runIn(strobeTimeout.toInteger(), off)
}

def strobeOn() {
  if (state.strobing) {
    runInMillis(strobeRate.toInteger(), strobeOff)
    parent.apiRequestDeviceSet(device.deviceNetworkId, "doorbots", "floodlight_light_on")
  }
}

def strobeOff() {
  if (state.strobing) {
    runInMillis(strobeRate.toInteger(), strobeOn)
    parent.apiRequestDeviceSet(device.deviceNetworkId, "doorbots", "floodlight_light_off")
  }
}

void handleDeviceSet(final String action, final Map msg, final Map query) {
  if (action == "floodlight_light_on") {
    checkChanged("switch", "on")
  }
  else if (action == "floodlight_light_off") {
    checkChanged("switch", "off")
  }
  else {
    log.error "handleDeviceSet unsupported action ${action}, msg=${msg}, query=${query}"
  }
}

void handleHealth(final Map msg) {
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

void handleRefresh(final Map msg) {  
  if (msg?.alerts?.connection != null) {
    checkChanged("healthStatus", msg.alerts.connection) // devices seem to be considered offline after 20 minutes
  }
  else {
    checkChanged("healthStatus", "unknown")
  }
    
  if (!discardBatteryLevel) {
    if (msg.battery_life != null) {
      checkChanged("battery", msg.battery_life, "%")
      if (msg.battery_life_2 != null) {
        checkChanged("battery2", msg.battery_life_2, "%")
      }
    }
    else if (msg.battery_life_2 != null) {
      checkChanged("battery", msg.battery_life_2, "%")
    }
  }

  if (msg.led_status) {
    if (!(msg.led_status instanceof String) && msg.led_status.seconds_remaining != null) {
      checkChanged("switch", msg.led_status.seconds_remaining > 0 ? "on" : "off")
    }
    else {
      checkChanged("switch", msg.led_status)
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

boolean checkChanged(final String attribute, final newStatus, final String unit=null, final String type=null) {
  final boolean changed = device.currentValue(attribute) != newStatus
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}
