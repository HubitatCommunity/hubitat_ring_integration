/**
 *  Ring Virtual Alarm Smoke & CO Listener Driver
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
  definition(name: "Ring Virtual Alarm Smoke & CO Listener", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-alarm-smoke-co-listener.groovy") {
    capability "Refresh"
    capability "Sensor"
    capability "Battery"
    capability "TamperAlert"
    capability "CarbonMonoxideDetector" //carbonMonoxide - ENUM ["detected", "tested", "clear"]
    capability "SmokeDetector" //smoke - ENUM ["clear", "tested", "detected"]

    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "listeningCarbonMonoxide", "enum", ["listening", "inactive"]
    attribute "listeningSmoke", "enum", ["listening", "inactive"]
    attribute "testMode", "string"
    attribute "lastCheckin", "string"
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

def refresh() {
  logDebug "Attempting to refresh."
  //parent.simpleRequest("refresh-device", [dni: device.deviceNetworkId])
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

  if (deviceInfo.co != null) {
    final Map co = deviceInfo.co

    checkChanged("carbonMonoxide", ALARM_STATUS.getOrDefault(co.alarmStatus, "tested"))
    state.coEnabled = co.enabledTimeMs

    checkChanged("listeningCarbonMonoxide", co.enabled ? "listening" : "inactive")
  }

  if (deviceInfo.smoke != null) {
    final Map smoke = deviceInfo.smoke

    checkChanged("smoke", ALARM_STATUS.getOrDefault(smoke.alarmStatus, "tested"))
    state.smokeEnabled = smoke.enabledTimeMs

    checkChanged("listeningSmoke", smoke.enabled ? "listening" : "inactive")
  }

  if (deviceInfo.pending != null) {
    final Map deviceInfoPending = deviceInfo.pending

    if (deviceInfoPending != null) {
      if (deviceInfoPending.commands) {
        logDebug "Device ${device.label} will set the commands ${deviceInfoPending.commands} on next wakeup"
      }
    }
  }

  if (deviceInfo.batteryLevel != null) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final String key in ["commStatus", "lastCheckin", "tamper", "testMode"]) {
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

@Field final static Map<String, String> ALARM_STATUS = [
  active: 'detected',
  inactive: 'clear',
]