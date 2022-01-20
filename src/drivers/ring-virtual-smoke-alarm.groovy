/**
 *  Ring Virtual Smoke Alarm
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
  definition(name: "Ring Virtual Smoke Alarm", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-smoke-alarm.groovy") {
    capability "Refresh"
    capability "Sensor"
    capability "Battery"
    capability "TamperAlert"
    capability "SmokeDetector" //smoke - ENUM ["clear", "tested", "detected"]

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

def setValues(deviceInfo) {
  logDebug "updateDevice(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

  /* this block possibly unecessary*/
  if (deviceInfo?.state?.smoke != null) {
    def alarmStatus = deviceInfo.state.smoke.alarmStatus
    checkChanged("smoke", alarmStatus == "active" ? "detected" : (alarmStatus == "inactive" ? "clear" : "tested"))
    if (deviceInfo.state.smoke.enabledTimeMs)
      state.smokeEnabled = deviceInfo.state.smoke.enabledTimeMs
  }
  /* end block */
  if (deviceInfo?.state?.alarmStatus != null) {
    def alarmStatus = deviceInfo.state.alarmStatus
    checkChanged("smoke", alarmStatus == "active" ? "detected" : (alarmStatus == "inactive" ? "clear" : "tested"))
    if (deviceInfo.state.enabledTimeMs)
      state.smokeEnabled = deviceInfo.state.enabledTimeMs
  }
  if (deviceInfo.batteryLevel != null) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }
  if (deviceInfo.tamperStatus) {
    checkChanged("tamper", deviceInfo.tamperStatus == "tamper" ? "detected" : "clear")
  }

  for(key in ['impulseType', 'lastCommTime', 'lastUpdate', 'nextExpectedWakeup', 'signalStrength']) {
    if (deviceInfo[key]) {
      state[key] = deviceInfo[key]
    }
  }

  if (deviceInfo?.impulseType == "comm.heartbeat") {
    sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
  }

  for(key in ['firmware', 'hardwareVersion']) {
    if (deviceInfo[key] && device.getDataValue(key) != deviceInfo[key]) {
      device.updateDataValue(key, deviceInfo[key])
    }
  }
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
