/**
 * IMPORT URL: https://raw.githubusercontent.com/HubitatCommunity/hubitat_ring_integration/master/src/drivers/ring-virtual-beams-light.groovy
 *
 *  Ring Virtual Beams Light Driver
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

import groovy.transform.Field

metadata {
  definition(name: "Ring Virtual Beams Light", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Battery"
    capability "Motion Sensor"
    capability "Refresh"
    capability "Sensor"
    capability "Switch"

    attribute "brightness", "number"
    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "firmware", "string"
    attribute "rfChannel", "number"
    attribute "rssi", "number"
    attribute "sensitivity", "enum", ["low", "medium", "high", "custom2", "custom4"]

    command "on", [[name: "Duration", type: "NUMBER", range: "0..28800", description: "Choose a value between 0 and 28800 seconds"]]
    command "setBrightness", [[name: "Set LED Brightness*", type: "NUMBER", range: "0..100", description: "Choose a value between 0 and 100"]]
    command "setSensitivity", [[name: "mode", type: "ENUM", constraints: ["low", "medium", "high", "custom2", "custom4"], description: "Set motion sensor sensitivity"]]
  }

  preferences {
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

def refresh() {
  parent.refresh(device.getDataValue("src"))
}

def on(duration = 60) {
  parent.apiWebsocketRequestSetCommand("light-mode.set", device.getDataValue("src"), device.getDataValue("zid"), [lightMode: "on", duration: duration])
}

def off() {
  parent.apiWebsocketRequestSetCommand("light-mode.set", device.getDataValue("src"), device.getDataValue("zid"), [lightMode: "default"])
}

def setBrightness(brightness) {
  if (NO_BRIGHTNESS_DEVICES.contains(device.getDataValue("fingerprint"))) {
    log.error "This device doesn't support brightness!"
    return
  }
  parent.apiWebsocketRequestSetDevice(device.getDataValue("src"), device.getDataValue("zid"),
                                      [level: ((brightness == null ? 100 : brightness).toDouble() / 100)])
}

void setSensitivity(String sensitivity) {
  Integer ringSensitivity = MOTION_SENSITIVITY.find { it.value == sensitivity }?.key

  if (ringSensitivity == null) {
    log.error "Could not map ${sensitivity} to value ring expects"
    return
  }

  parent.apiWebsocketRequestSetDevice(device.getDataValue("src"), device.getDataValue("zid"), [sensitivity: ringSensitivity])
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(${deviceInfo})"

  if (deviceInfo.level != null && !NO_BRIGHTNESS_DEVICES.contains(device.getDataValue("fingerprint"))) {
    checkChanged("brightness", deviceInfo.level)
  }

  if (deviceInfo.batteryLevel != null && !NO_BATTERY_DEVICES.contains(device.getDataValue("fingerprint"))) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }

  if (deviceInfo.sensitivity != null) {
    checkChanged("sensitivity", MOTION_SENSITIVITY[deviceInfo.sensitivity])
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final entry in deviceInfo.subMap(["commStatus", "firmware", "motion", "rfChannel", "rssi", "switch"])) {
    checkChanged(entry.key, entry.value)
  }

  // Update state values
  Map stateValues = deviceInfo.subMap(['impulseType', 'lastUpdate'])
  if (stateValues) {
      state << stateValues
  }
}

void setPassthruValues(final Map deviceInfo) {
  logDebug "setPassthruValues(${deviceInfo})"

  if (deviceInfo.percent != null) {
    log.warn "${device.label} is updating firmware: ${deviceInfo.percent}% complete"
  }
}

void runCleanup() {
  device.removeDataValue('firmware') // Is an attribute now
  state.remove('lastCommTime')
  state.remove('signalStrength')
  state.remove('nextExpectedWakeup')
}

boolean checkChanged(final String attribute, final newStatus, final String unit=null, final String type=null) {
  final boolean changed = device.currentValue(attribute) != newStatus
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}

@Field final Set NO_BATTERY_DEVICES = ["ring-beams-c5000"].toSet().asImmutable()
@Field final Set NO_BRIGHTNESS_DEVICES = ["ring-beams-c5000"].toSet().asImmutable()

@Field final static Map MOTION_SENSITIVITY = [
  0: 'high', // Custom 5
  63: 'custom4',
  127: 'medium', // Custom 3
  191: 'custom2',
  255: 'low', // Custom 1
].asImmutable()
