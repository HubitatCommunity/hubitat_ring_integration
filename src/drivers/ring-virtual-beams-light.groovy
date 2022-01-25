/**
 *  Ring Virtual Beams Light Driver
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

import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
  definition(name: "Ring Virtual Beams Light", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-beams-light.groovy") {
    capability "Refresh"
    capability "Sensor"
    capability "Motion Sensor"
    capability "Battery"
    capability "TamperAlert"
    capability "Switch"

    attribute "brightness", "number"
    attribute "lastCheckin", "string"

    command "on", [[name: "Duration", type: "NUMBER", range: "0..28800", description: "Choose a value between 0 and 28800 seconds"]]
    command "setBrightness", [[name: "Set LED Brightness*", type: "NUMBER", range: "0..100", description: "Choose a value between 0 and 100"]]
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

def on(duration = 60) {
  logDebug "Attempting to turn the light on."
  parent.simpleRequest("setcommand", [type: "light-mode.set", zid: device.getDataValue("zid"), dst: device.getDataValue("src"),
                                      data: [lightMode: "on", duration: 60]])
}

def off() {
  logDebug "Attempting to turn the light off."
  parent.simpleRequest("setcommand", [type: "light-mode.set", zid: device.getDataValue("zid"), dst: device.getDataValue("src"),
                                      data: [lightMode: "default"]])
}

def setBrightness(brightness) {
  logDebug "Attempting to set brightness ${brightness}."
  if (NO_BRIGHTNESS_DEVICES.contains(device.getDataValue("fingerprint"))) {
    log.error "This device doesn't support brightness!"
    return
  }
  Map data = ["level": ((brightness == null ? 100 : brightness).toDouble() / 100)]
  parent.simpleRequest("setdevice", [zid: device.getDataValue("zid"), dst: device.getDataValue("src"), data: data])
}

void setValues(final Map deviceInfo) {
  logDebug "updateDevice(deviceInfo)"
  logTrace "deviceInfo: ${JsonOutput.prettyPrint(JsonOutput.toJson(deviceInfo))}"

  if (deviceInfo.state != null) {
    final Map deviceInfoState = deviceInfo.state

    if (deviceInfoState.motionStatus != null) {
      checkChanged("motion", deviceInfoState.motionStatus == "clear" ? "inactive" : "active")
    }

    if (deviceInfoState.on != null) {
      checkChanged("switch", deviceInfoState.on ? "on" : "off")
    }

    if (deviceInfoState.level != null) {
      if (!NO_BRIGHTNESS_DEVICES.contains(device.getDataValue("fingerprint"))) {
        checkChanged("brightness", (deviceInfoState.level.toDouble() * 100).toInteger())
      }
    }
  }

  if (deviceInfo.batteryLevel != null) {
    if (!discardBatteryLevel && !NO_BATTERY_DEVICES.contains(device.getDataValue("fingerprint"))) {
      checkChanged("battery", deviceInfo.batteryLevel, "%")
    }
  }

  if (deviceInfo.tamperStatus != null) {
    checkChanged("tamper", deviceInfo.tamperStatus == "tamper" ? "detected" : "clear")
  }

  if (deviceInfo.lastUpdate != null && deviceInfo.lastUpdate != state.lastUpdate) {
    sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()))
  }

  for(final String key in ['impulseType', 'lastCommTime', 'lastUpdate', 'nextExpectedWakeup', 'signalStrength']) {
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

@Field final HashSet<String> NO_BATTERY_DEVICES = ["ring-beams-c5000"]
@Field final HashSet<String> NO_BRIGHTNESS_DEVICES= ["ring-beams-c5000"]

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
