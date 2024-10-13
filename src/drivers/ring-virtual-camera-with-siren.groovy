/**
 *  Ring Virtual Camera with Siren Device Driver
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
  definition(name: "Ring Virtual Camera with Siren", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Alarm"
    capability "Actuator"
    capability "Battery"
    capability "MotionSensor"
    capability "Polling"
    capability "PushableButton"
    capability "Refresh"
    capability "Sensor"

    attribute "firmware", "string"
    attribute "rssi", "number"
    attribute "wifi", "string"

    command "getDings"
  }

  preferences {
    input name: "snapshotPolling", type: "bool", title: "Enable polling for thumbnail snapshots on this device", defaultValue: false
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

def updated() {
  parent.snapshotOption(device.deviceNetworkId, snapshotPolling)
}

def off() {
  parent.apiRequestDeviceSet(device.deviceNetworkId, "doorbots", "siren_off")
}

def siren() {
  parent.apiRequestDeviceSet(device.deviceNetworkId, "doorbots", "siren_on")
}

def strobe(value = "strobe") {
  log.error "Strobe not implemented for device type ${device.getDataValue("kind")}"
}

def both() {
  log.error "Both (strobe and siren) not implemented for device type ${device.getDataValue("kind")}"
}

def push(Integer button) {
  log.error "Push not implemented for device type ${device.getDataValue("kind")}"
}

void handleDeviceSet(final String action, final Map msg, final Map query) {
  if (action == "siren_on") {
    if (device.currentValue("alarm") != "both") {
      checkChanged("alarm", "siren")
    }

    runIn(msg.seconds_remaining + 1, refresh)
  }
  else if (action == "siren_off") {
    checkChanged('alarm', "off")
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
  // Stick up camera has motion = false even for motion events for some reason
  if (msg.motion == true || msg.state == "ringing") {
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
  if (msg.battery_life != null) {
    checkChanged("battery", msg.battery_life, '%')
  }
  else if (msg.battery_life_2 != null) {
    checkChanged("battery", msg.battery_life_2, "%")
  }
  if (msg.siren_status?.seconds_remaining != null) {
    final Integer secondsRemaining = msg.siren_status.seconds_remaining
    checkChanged("alarm", secondsRemaining > 0 ? "siren" : "off")
    if (secondsRemaining > 0) {
      runIn(secondsRemaining + 1, refresh)
    }
  }
  if (msg.health) {
    final Map health = msg.health

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
