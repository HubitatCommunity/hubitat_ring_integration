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
  def data = ["lightMode": "on", "duration": duration]
  parent.simpleRequest("setcommand", [type: "light-mode.set", zid: device.getDataValue("zid"), dst: device.getDataValue("src"), data: data])
}

def off() {
  logDebug "Attempting to turn the light off."
  def data = ["lightMode": "default"]
  parent.simpleRequest("setcommand", [type: "light-mode.set", zid: device.getDataValue("zid"), dst: device.getDataValue("src"), data: data])
}

def setBrightness(brightness) {
  logDebug "Attempting to set brightness ${brightness}."
  if (NO_BRIGHTNESS_DEVICES.contains(device.getDataValue("fingerprint"))) {
    log.error "This device doesn't support brightness!"
    return
  }
  def data = ["level": ((brightness == null ? 100 : brightness).toDouble() / 100)]
  parent.simpleRequest("setdevice", [zid: device.getDataValue("zid"), dst: device.getDataValue("src"), data: data])
}

def setValues(deviceInfo) {
  logDebug "updateDevice(deviceInfo)"
  logTrace "deviceInfo: ${JsonOutput.prettyPrint(JsonOutput.toJson(deviceInfo))}"

  if (deviceInfo?.state?.motionStatus != null) {
    checkChanged("motion", deviceInfo.state.motionStatus == "clear" ? "inactive" : "active")
  }
  if (deviceInfo?.state?.on != null) {
    checkChanged("switch", deviceInfo.state.on ? "on" : "off")
  }
  if (deviceInfo?.state?.level != null && !NO_BRIGHTNESS_DEVICES.contains(device.getDataValue("fingerprint"))) {
    checkChanged("brightness", (deviceInfo.state.level.toDouble() * 100).toInteger())
  }
  if (deviceInfo.batteryLevel != null && !discardBatteryLevel && !NO_BATTERY_DEVICES.contains(device.getDataValue("fingerprint"))) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }
  if (deviceInfo.tamperStatus) {
    checkChanged("tamper", deviceInfo.tamperStatus == "tamper" ? "detected" : "clear")
  }
  if (deviceInfo.lastUpdate != state.lastUpdate) {
    sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
  }

  for(key in ['impulseType', 'lastCommTime', 'lastUpdate', 'nextExpectedWakeup', 'signalStrength']) {
    if (deviceInfo[key]) {
      state[key] = deviceInfo[key]
    }
  }

  if (deviceInfo.firmware && device.getDataValue("firmware") != deviceInfo.firmware) {
    device.updateDataValue("firmware", deviceInfo.firmware)
  }
  if (deviceInfo.hardwareVersion && deviceInfo.hardwareVersion != "null" && device.getDataValue("hardwareVersion") != deviceInfo.hardwareVersion) {
    device.updateDataValue("hardwareVersion", deviceInfo.hardwareVersion)
  }

}

def getNO_BATTERY_DEVICES() {
  return [
    "ring-beams-c5000"
  ]
}

def getNO_BRIGHTNESS_DEVICES() {
  return [
    "ring-beams-c5000"
  ]
}

def checkChanged(attribute, newStatus, unit=null) {
  if (device.currentValue(attribute) != newStatus) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
    sendEvent(name: attribute, value: newStatus, unit: unit)
  }
}

private convertToLocalTimeString(dt) {
  def timeZoneId = location?.timeZone?.ID
  if (timeZoneId) {
    return dt.format("yyyy-MM-dd h:mm:ss a", TimeZone.getTimeZone(timeZoneId))
  }
  else {
    return "$dt"
  }
}
