/**
 *  Ring Virtual Beams Bridge Driver
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
  definition(name: "Ring Virtual Beams Bridge", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-beams-bridge.groovy") {
    capability "Refresh"
    capability "Sensor"

    attribute "cellular", "string"
    attribute "lastCheckin", "string"
    attribute "wifi", "string"

    command "createDevices"
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

def createDevices() {
  logDebug "Attempting to create devices."
  parent.createDevices(device.getDataValue("zid"))
}

def refresh() {
  logDebug "Attempting to refresh."
  parent.refresh(device.getDataValue("zid"))
}

def setValues(deviceInfo) {
  logDebug "updateDevice(deviceInfo)"
  logTrace "deviceInfo: ${JsonOutput.prettyPrint(JsonOutput.toJson(deviceInfo))}"

  for(key in ['impulseType', 'lastCommTime', 'lastUpdate']) {
    if (deviceInfo[key]) {
      state[key] = deviceInfo[key]
    }
  }

  if (deviceInfo.deviceType == "halo-stats.latency" && deviceInfo.state?.status == "success") {
    sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
  }
  if (deviceInfo.state?.networks) {
    def nw = deviceInfo.state.networks

    if (nw.ppp0 != null) {
      if (nw.ppp0?.type) {
        device.updateDataValue("ppp0Type", nw.ppp0.type.capitalize())
      }
      if (nw.ppp0?.name) {
        device.updateDataValue("ppp0Name", nw.ppp0.name)
      }
      if (nw.ppp0?.rssi) {
        device.updateDataValue("ppp0Rssi", nw.ppp0.rssi.toString())
      }

      def type = device.getDataValue("ppp0Type")
      def name = device.getDataValue("ppp0Name")
      def rssi = device.getDataValue("ppp0Rssi")

      logInfo "ppp0 ${type} ${name} RSSI ${RSSI}"
      checkChanged('cellular', "${name} RSSI ${rssi}")
      state.ppp0 = "${name} RSSI ${rssi}"
    }

    if (nw.wlan0 != null) {
      if (nw.wlan0?.type) {
        device.updateDataValue("wlan0Type", nw.wlan0.type.capitalize())
      }
      if (nw.wlan0?.ssid) {
        device.updateDataValue("wlan0Ssid", nw.wlan0.ssid)
      }
      if (nw.wlan0?.rssi) {
        device.updateDataValue("wlan0Rssi", nw.wlan0.rssi.toString())
      }

      def type = device.getDataValue("wlan0Type")
      def ssid = device.getDataValue("wlan0Ssid")
      def rssi = device.getDataValue("wlan0Rssi")

      logInfo "wlan0 ${type} ${ssid} RSSI ${RSSI}"
      checkChanged('wifi', "${ssid} RSSI ${rssi}")
      state.wlan0 = "${ssid} RSSI ${rssi}"
    }
  }
  if (deviceInfo.deviceType == "adapter.ringnet" && deviceInfo.state?.version) {
    def version = deviceInfo.state.version

    for(key in ['buildNumber', 'nordicFirmwareVersion', 'softwareVersion']) {
      if (version[key] && device.getDataValue(key) != version[key]) {
        device.updateDataValue(key, version[key])
      }
    }
  }
  else if (deviceInfo.state?.version) {
    if (device.getDataValue("version") != deviceInfo.state?.version) {
      device.updateDataValue("version", deviceInfo.state?.version.toString())
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
