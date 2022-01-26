/**
 *  Ring Virtual Motion Sensor Driver
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
  definition(name: "Ring Virtual Motion Sensor", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-motion-sensor.groovy") {
    capability "Refresh"
    capability "Sensor"
    capability "Motion Sensor"
    capability "Battery"
    capability "TamperAlert"

    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "lastCheckin", "string"
    attribute "sensitivity", "enum", ["low", "medium", "high"]

    command "setSensitivity", [[name: "mode", type: "ENUM", constraints: ["low", "medium", "high"], description: "Set motion sensor sensitivity. WARNING: This setting does not apply immediately. May take up to 12 hours to apply. To apply immediately, open the device cover, wait for LED to stop blinking, then close the cover."]]
  }

  preferences {
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false
  }
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

def setSensitivity(sensitivity) {
  Integer ringSensitivity

  for (it in MOTION_SENSITIVITY) {
    if (it.value == sensitivity) {
      ringSensitivity = it.key
      break
    }
  }

  if (ringSensitivity == null) {
    log.error "Could not map ${sensitivity} to value ring expects"
    return
  }

  logDebug "Attempting to set sensitivity to ${sensitivity}"
  parent.simpleRequest("setdevice", [zid: device.getDataValue("zid"), dst: null, data: [sensitivity: ringSensitivity]])
}

def refresh() {
  logDebug "Attempting to refresh."
  //parent.simpleRequest("refresh-device", [dni: device.deviceNetworkId])
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

  if (deviceInfo.faulted != null) {
    checkChanged("motion", deviceInfo.faulted ? "active" : "inactive")
  }

  if (deviceInfo.sensitivity != null) {
    checkChanged("sensitivity", MOTION_SENSITIVITY[deviceInfo.sensitivity])
  }

  if (deviceInfo.pending != null) {
    final Map deviceInfoPending = deviceInfo.pending

    if (deviceInfoPending != null) {
      if (deviceInfoPending.sensitivity != null) {
        String expectedWakeup = 'unknown'

        if (deviceInfo.nextExpectedWakeup) {
            expectedWakeup = new Date(deviceInfo.nextExpectedWakeup).toString()
        }

        logInfo "Device ${device.label} will set 'Sensitivity' to ${MOTION_SENSITIVITY[deviceInfoPending.sensitivity]} on ${expectedWakeup}"
      }

      if (deviceInfoPending.commands) {
        logDebug "Device ${device.label} will set the commands ${deviceInfoPending.commands} on next wakeup"
      }
    }
  }

  if (deviceInfo.batteryLevel != null) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final String key in ["commStatus", "lastCheckin", "tamper"]) {
    final keyVal = deviceInfo[key]
    if (keyVal != null) {
      checkChanged(key, keyVal)
    }
  }

  // Update state values
  state += deviceInfo.subMap(['impulseType', 'lastCommTime', 'lastUpdate', 'nextExpectedWakeup', 'signalStrength'])

  // Update data values
  for(final String key in ['firmware', 'hardwareVersion']) {
    checkChangedDataValue(key, deviceInfo[key])
  }
}

boolean checkChanged(final String attribute, final newStatus, final String unit=null) {
  final boolean changed = device.currentValue(attribute) != newStatus
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit)
  return changed
}

void checkChangedDataValue(final String name, final value) {
  if (value != null && device.getDataValue(name) != value) {
    device.updateDataValue(name, value)
  }
}

@Field final static Map<Integer, String> MOTION_SENSITIVITY = [
  0: 'high',
  1: 'medium',
  2: 'low',
]