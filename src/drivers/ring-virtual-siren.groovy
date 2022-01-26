/**
 *  Ring Virtual Siren Driver
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

metadata {
  definition(name: "Ring Virtual Siren", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-siren.groovy") {
    capability "Refresh"
    capability "Sensor"
    capability "Battery"
    //capability "Alarm" For now this is commented out because I can't see a way through the WS or API to turn the siren on
    //using the alarm hub's 'security-panel.sound-siren' set command does not work.  technically, the siren tests could be
    //chained back to back with a scheduled call back but leaving this as is for now

    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "lastCheckin", "string"

    command "sirenTest"
    command "sirenTestCancel"
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

def sirenTest() {
  //TODO: make this impossible when alarm is armed.  if you attempt this through Ring's UIs it is prevented
  //pearl is too deep a dive to add code so this device can ask the hub device what the mode is right now.
  parent.simpleRequest("setcommand", [type: "siren-test.start", zid: device.getDataValue("zid"), dst: device.getDataValue("src"), data: {
  }])
}

def sirenTestCancel() {
  parent.simpleRequest("setcommand", [type: "siren-test.stop", zid: device.getDataValue("zid"), dst: device.getDataValue("src"), data: {
  }])
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

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