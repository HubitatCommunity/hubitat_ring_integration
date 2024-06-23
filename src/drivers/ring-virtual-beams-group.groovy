/**
 *  Ring Virtual Beams Group Driver
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

import com.hubitat.app.ChildDeviceWrapper

metadata {
  definition(name: "Ring Virtual Beams Group", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Battery"
    capability "Motion Sensor"
    capability "Refresh"
    capability "Sensor"
    capability "Switch"

    command "on", [[name: "Duration", type: "NUMBER", range: "0..28800", description: "Choose a value between 0 and 28800 seconds"]]
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

void on(duration = 60) {
  parent.apiWebsocketRequestSetCommand("light-mode.set", device.getDataValue("src"), device.getDataValue("zid"), [lightMode: "on", duration: duration])
}

void off() {
  parent.apiWebsocketRequestSetCommand("light-mode.set", device.getDataValue("src"), device.getDataValue("zid"), [lightMode: "default"])
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(${deviceInfo})"

  if (deviceInfo.groupMembers) {
    Map members = [:]
    for (final String groupMemeber in deviceInfo.groupMembers) {
      ChildDeviceWrapper d = parent.getChildByZID(groupMemeber)
      if (d) {
        members[d.deviceNetworkId] = d.label
      } else {
        log.warn "Device ${groupMemeber} isn't created in Hubitat and will not be included in this group's members."
      }
    }
    state.members = members
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final entry in deviceInfo.subMap(["motion", "switch"])) {
    checkChanged(entry.key, entry.value)
  }

  // Update state values
  Map stateValues = deviceInfo.subMap(['impulseType', 'lastUpdate'])
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
}

void runCleanup() {
  state.remove('lastCommTime')
}

boolean checkChanged(final String attribute, final newStatus, final String unit=null, final String type=null) {
  final boolean changed = isStateChange(device, attribute, newStatus.toString())
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}