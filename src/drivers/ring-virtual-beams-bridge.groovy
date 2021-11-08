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

void setValues(final Map deviceInfo) {
  logDebug "updateDevice(deviceInfo)"
  logTrace "deviceInfo: ${JsonOutput.prettyPrint(JsonOutput.toJson(deviceInfo))}"

  if (deviceInfo.state != null) {
    final Map deviceInfoState = deviceInfo.state

    if (deviceInfoState.networks != null) {
      final Map nw = deviceInfoState.networks

      final Map ppp0 = nw.ppp0
      if (ppp0 != null) {
        if (ppp0.type) {
          device.updateDataValue("ppp0Type", ppp0.type.capitalize())
        }
        if (ppp0.name) {
          device.updateDataValue("ppp0Name", ppp0.name)
        }
        if (ppp0.rssi) {
          device.updateDataValue("ppp0Rssi", ppp0.rssi.toString())
        }

        final String ppp0Type = device.getDataValue("ppp0Type")
        final String ppp0Name = device.getDataValue("ppp0Name")
        final String ppp0Rssi = device.getDataValue("ppp0Rssi")

        logInfo "ppp0 ${ppp0Type} ${ppp0Name} RSSI ${RSSI}"
        checkChanged('cellular', "${ppp0Name} RSSI ${ppp0Rssi}")
        state.ppp0 = "${ppp0Name} RSSI ${ppp0Rssi}"
      }

      final Map wlan0 = nw.wlan0
      if (wlan0 != null) {
        if (wlan0.type) {
          device.updateDataValue("wlan0Type", wlan0.type.capitalize())
        }
        if (wlan0.ssid) {
          device.updateDataValue("wlan0Ssid", wlan0.ssid)
        }
        if (wlan0.rssi) {
          device.updateDataValue("wlan0Rssi", wlan0.rssi.toString())
        }

        final String wlan0Type = device.getDataValue("wlan0Type")
        final String wlan0Ssid = device.getDataValue("wlan0Ssid")
        final String wlan0Rssi = device.getDataValue("wlan0Rssi")

        logInfo "wlan0 ${wlan0Type} ${wlan0Ssid} RSSI ${RSSI}"
        checkChanged('wifi', "${wlan0Ssid} RSSI ${wlan0Rssi}")
        state.wlan0 = "${wlan0Ssid} RSSI ${wlan0Rssi}"
      }
    }

    if (deviceInfoState.version != null) {
      final Map version = deviceInfoState.version

      if (deviceInfo.deviceType == "adapter.ringnet") {
        for(final String key in ['buildNumber', 'nordicFirmwareVersion', 'softwareVersion']) {
          final keyVal = version[key]
          if (keyVal && device.getDataValue(key) != keyVal) {
            device.updateDataValue(key, keyVal)
          }
        }
      } else if (device.getDataValue("version") != version) {
        device.updateDataValue("version", version.toString())
      }
    }

    if (deviceInfoState.status == 'success') {
      if (deviceInfo.deviceType == "halo-stats.latency") {
        sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()))
      }
    }
  }

  for(final String key in ['impulseType', 'lastCommTime', 'lastUpdate']) {
    final keyVal = deviceInfo[key]
    if (keyVal != null) {
      state[key] = keyVal
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
