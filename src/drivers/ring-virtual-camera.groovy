/* groovylint-disable Indentation */
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

    attribute "connectionStatus", "enum", ["offline", "online"]
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

void installed() {
  sendEvent(name: "numberOfButtons", value: '1')
  updated()
}

void updated() {
  parentCheck()

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

void push(buttonNumber) {
  log.error "Not implemented! push(buttonNumber)"
}

// apiRequestDevicesApiSet(device.deviceNetworkId, "devices", action: "settings") returns something for this device, but there's no use for those values yet
void refresh() {
  logDebug "refresh()"
  parent.apiRequestClientsApiRefresh(device.deviceNetworkId)
  parent.apiRequestClientsApiHealth(device.deviceNetworkId, "doorbots")
}

void getDings() {
  logDebug "getDings()"
  parent.apiRequestDings()
}

void handleDing(final Map msg) {
  logInfo "${device.label} button 1 was pushed"
  sendEvent(name: "pushed", value: 1, isStateChange: true)
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

    // Per Ring: is_sidewalk_gateway indicates only whether a device *can* be used as a Sidewalk gateway. sidewalk_connection
    // indicates whether Sidewalk is enabled in the Ring account. Both must be true for Sidewalk to be enabled on a device
    if (msg.is_sidewalk_gateway && health.sidewalk_connection) {
      log.warn("Your device is being used as an Amazon sidewalk device.")
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
  final boolean changed = isStateChange(device, attribute, newStatus.toString())
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}