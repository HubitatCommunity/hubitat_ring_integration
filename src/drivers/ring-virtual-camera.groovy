/**
 *  Ring Virtual Camera Device Driver
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
  definition(name: "Ring Virtual Camera", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Actuator"
    capability "Battery"
    capability "MotionSensor"
    capability "Polling"
    capability "PushableButton"
    capability "Refresh"
    capability "Sensor"
    capability "Health Check"

    attribute "firmware", "string"
    attribute "rssi", "number"
    attribute "wifi", "string"
    attribute "healthStatus", "enum", [ "unknown", "offline", "online" ]

    command "getDings"
  }

  preferences {
    input name: "deviceStatusPollingEnable", type: "bool", title: "Enable polling for device status", defaultValue: true
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

def updated() {
  checkChanged("numberOfButtons", 1)
  parent.snapshotOption(device.deviceNetworkId, snapshotPolling)
  scheduleDevicePolling()
}

def parse(String description) {
  logDebug "description: ${description}"
}

def poll() {
  refresh()
}

void push(buttonNumber) {
  log.error "Not implemented! push(buttonNumber)"
}

def refresh() {
  logDebug "refresh()"
  parent.apiRequestDeviceRefresh(device.deviceNetworkId)
  parent.apiRequestDeviceHealth(device.deviceNetworkId, "doorbots")
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

def getDings() {
  logDebug "getDings()"
  parent.apiRequestDings()
}

void handleDing(final Map msg) {
  logInfo "${device.label} button 1 was pushed"
  sendEvent(name: "pushed", value: 1, isStateChange: true)
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
    
  if (!["jbox_v1", "lpd_v1", "lpd_v2"].contains(device.getDataValue("kind"))) {
    if (msg.battery_life != null) {
      checkChanged("battery", msg.battery_life, '%')
    }
    else if (msg.battery_life_2 != null) {
      checkChanged("battery", msg.battery_life_2, "%")
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
