/**
 *  Ring Virtual Beams Group Driver
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
  definition(name: "Ring Virtual Beams Group", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-beams-group.groovy") {
    capability "Refresh"
    capability "Sensor"
    capability "Motion Sensor"
    capability "Battery"
    capability "TamperAlert"
    capability "Switch"
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

def on() {
  logDebug "Attempting to turn the light on."
  def data = ["lightMode": "on", "duration": 60]
  parent.simpleRequest("setcommand", [type: "light-mode.set", zid: device.getDataValue("zid"), dst: device.getDataValue("src"), data: data])
}

def off() {
  logDebug "Attempting to turn the light off."
  def data = ["lightMode": "default"]
  parent.simpleRequest("setcommand", [type: "light-mode.set", zid: device.getDataValue("zid"), dst: device.getDataValue("src"), data: data])
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

  for(key in ['impulseType', 'lastCommTime', 'lastUpdate']) {
    if (deviceInfo[key]) {
      state[key] = deviceInfo[key]
    }
  }

  if (deviceInfo.state?.groupMembers) {
    state.members = deviceInfo.state.groupMembers?.collectEntries {
      def d = parent.getChildByZID(it)
      if (d) {
        [(d.deviceNetworkId): d.label]
      }
      else {
        log.warn "Device ${it} isn't created in Hubitat and will not be included in this group's members."
      }
    }
  }
}

def checkChanged(attribute, newStatus, unit=null) {
  if (device.currentValue(attribute) != newStatus) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
    sendEvent(name: attribute, value: newStatus, unit: unit)
  }
}
