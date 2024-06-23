/**
 *  Ring Virtual Motion Sensor Driver
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
  definition(name: "Ring Virtual Motion Sensor", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Battery"
    capability "Motion Sensor"
    capability "Refresh"
    capability "Sensor"
    capability "TamperAlert"

    attribute "bypassed", "enum", ["true", "false"]
    attribute "chirp", "string"
    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "firmware", "string"
    attribute "sensitivity", "enum", ["low", "medium", "high"]

    command "setChirp", [[name: "Set Chirp", type: "ENUM", description: "Choose the sound your Base Station and Keypads will make when this contact sensor is triggered",
                          constraints: ['ding-dong', 'harp', 'navi', 'wind-chime', 'none']]]
    command "setSensitivity", [[name: "mode", type: "ENUM", constraints: ["low", "medium", "high"], description: "Set motion sensor sensitivity. WARNING: This setting does not apply immediately. May take up to 12 hours to apply. To apply immediately, open the device cover, wait for LED to stop blinking, then close the cover."]]
  }

  preferences {
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false
  }
}

void installed() { updated() }

void updated() { parentCheck() }

void parentCheck() {
  if (device.parentDeviceId == null || device.parentAppId != null) {
    log.error("This device can only be installed using the Ring API Virtual Device. Remove this device and use createDevices in Ring API Virtual Device. parentAppId=${device.parentAppId}, parentDeviceId=${device.parentDeviceId}")
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

void setChirp(chirp) {
  final Map data = [chirps: [(device.getDataValue("zid")): [type: chirp]]]
  parent.apiWebsocketRequestSetDeviceSecurityPanel(device.getDataValue("src"), data)
}

void setSensitivity(String sensitivity) {
  Integer ringSensitivity = MOTION_SENSITIVITY.find { it.value == sensitivity }?.key

  if (ringSensitivity == null) {
    log.error "Could not map ${sensitivity} to value ring expects"
    return
  }

  parent.apiWebsocketRequestSetDevice(device.getDataValue("src"), device.getDataValue("zid"), [sensitivity: ringSensitivity])
}

void refresh() {
  parent.refresh(device.getDataValue("src"))
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(${deviceInfo})"

  if (deviceInfo.faulted != null) {
    checkChanged("motion", deviceInfo.faulted ? "active" : "inactive")
  }

  if (deviceInfo.sensitivity != null) {
    checkChanged("sensitivity", MOTION_SENSITIVITY[deviceInfo.sensitivity])
  }

  if (deviceInfo.pending != null) {
    final Map deviceInfoPending = deviceInfo.pending

    if (deviceInfoPending.sensitivity != null) {
      logInfo "Device ${device.label} will set 'Sensitivity' to ${MOTION_SENSITIVITY[deviceInfoPending.sensitivity]} on ${getNextExpectedWakeupString(deviceInfo)}"
    }

    if (deviceInfoPending.commands) {
      logDebug "Device ${device.label} will set the commands ${deviceInfoPending.commands} on ${getNextExpectedWakeupString(deviceInfo)}"
    }
  }

  if (deviceInfo.batteryLevel != null) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final entry in deviceInfo.subMap(["bypassed", "chirp", "commStatus", "firmware", "tamper"])) {
    checkChanged(entry.key, entry.value)
  }

  // Update state values
  Map stateValues = deviceInfo.subMap(['impulseType', 'lastCommTime', 'lastUpdate', 'nextExpectedWakeup', 'signalStrength'])
  if (stateValues) {
    state << stateValues

    if (stateValues.impulseType?.startsWith('firmware-update.')) {
      String impulseTypeSuffix = stateValues.impulseType.substring(16)

      if (impulseTypeSuffix in ['canceled', 'downloading', 'reverted', 'started', 'succeeded', 'user-aborted', 'verified']) {
        log.warn('Firmware update ' + impulseTypeSuffix)
      }
      else if (impulseTypeSuffix in ['failed', 'unsuccessful']) {
        log.error('Firmware update ' + impulseTypeSuffix)
      }
    }
  }
}

void setPassthruValues(final Map deviceInfo) {
  logDebug "setPassthruValues(${deviceInfo})"

  if (deviceInfo.percent != null) {
    log.warn "Firmware update ${deviceInfo.percent}% complete"
  }
}

void runCleanup() {
  device.removeDataValue('firmware') // Is an attribute now
}

// @return Next expected wakeup time as a string.
// Gets value from deviceInfo first, falls back to state because nextExpectedWakeup isn't sent with every deviceInfo
String getNextExpectedWakeupString(final Map deviceInfo) {
  final Long nextExpectedWakeup = deviceInfo.nextExpectedWakeup ?: state.nextExpectedWakeup

  return nextExpectedWakeup ? new Date(nextExpectedWakeup).toString() : 'unknown'
}

boolean checkChanged(final String attribute, final newStatus, final String unit=null, final String type=null) {
  final boolean changed = isStateChange(device, attribute, newStatus.toString())
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}

@Field final static Map MOTION_SENSITIVITY = [
  0: 'high',
  1: 'medium',
  2: 'low',
].asImmutable()