/**
 *  Ring Virtual CO Alarm
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
  definition(name: "Ring Virtual CO Alarm", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Battery"
    capability "CarbonMonoxideDetector"
    capability "Refresh"
    capability "Sensor"
    capability "TamperAlert"

    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "firmware", "string"
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

void refresh() {
  parent.refresh(device.getDataValue("src"))
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(${deviceInfo})"

  if (deviceInfo.co != null) {
    final Map co = deviceInfo.co

    checkChanged("carbonMonoxide", ALARM_STATUS.getOrDefault(co.alarmStatus, "tested"))
    state.coEnabled = co.enabledTimeMs
  }

  if (deviceInfo.batteryLevel != null) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final entry in deviceInfo.subMap(["commStatus", "firmware", "tamper"])) {
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

boolean checkChanged(final String attribute, final newStatus, final String unit=null, final String type=null) {
  final boolean changed = isStateChange(device, attribute, newStatus.toString())
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}

@Field final static Map ALARM_STATUS = [
  active: 'detected',
  inactive: 'clear',
].asImmutable()