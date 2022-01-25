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
  logDebug "updateDevice(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

  if (deviceInfo.state != null) {
    final Map deviceInfoState = deviceInfo.state

    if (deviceInfoState.faulted != null) {
      checkChanged("motion", deviceInfoState.faulted ? "active" : "inactive")
    }

    if (deviceInfoState.sensitivity != null) {
      checkChanged("sensitivity", MOTION_SENSITIVITY[deviceInfoState.sensitivity])
    }
  }

  if (deviceInfo.batteryLevel != null) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }

  if (deviceInfo.tamperStatus != null) {
    checkChanged("tamper", deviceInfo.tamperStatus == "tamper" ? "detected" : "clear")
  }

  if (deviceInfo.impulseType != null) {
    final String impulseType = deviceInfo.impulseType
    state.impulseType = impulseType
    if (impulseType == "comm.heartbeat") {
      sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()))
    }
  }

  for(final String key in ['lastCommTime', 'lastUpdate', 'nextExpectedWakeup', 'signalStrength']) {
    final keyVal = deviceInfo[key]
    if (keyVal != null) {
      state[key] = keyVal
    }
  }

  for(final String key in ['firmware', 'hardwareVersion']) {
    final keyVal = deviceInfo[key]
    if (keyVal != null && device.getDataValue(key) != keyVal) {
      device.updateDataValue(key, keyVal)
    }
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

private String convertToLocalTimeString(final Date dt) {
  final TimeZone timeZone = location?.timeZone
  if (timeZone) {
    return dt.format("yyyy-MM-dd h:mm:ss a", timeZone)
  }
  else {
    return dt.toString()
  }
}

@Field final static Map<Integer, String> MOTION_SENSITIVITY = [
  0: 'high',
  1: 'medium',
  2: 'low',
]
