/**
 *  Ring Virtual Camera Device Driver
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
  definition(name: "Ring Virtual Camera", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-camera.groovy") {
    capability "Actuator"
    capability "Sensor"
    capability "Refresh"
    capability "Polling"
    capability "MotionSensor"
    capability "Battery"
    capability "PushableButton"

    command "getDings"
  }

  preferences {
    input name: "snapshotPolling", type: "bool", title: "Enable polling for thumbnail snapshots on this device", defaultValue: false
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

def configure() {

}

def updated() {
  checkChanged("numberOfButtons", 1)
  parent.snapshotOption(device.deviceNetworkId, snapshotPolling)
}

def parse(String description) {
  logDebug "description: ${description}"
}

def poll() {
  refresh()
}

def refresh() {
  logDebug "refresh()"
  parent.simpleRequest("refresh", [dni: device.deviceNetworkId])
}

def getDings() {
  logDebug "getDings()"
  parent.simpleRequest("dings")
}

void childParse(final String type, final Map params) {
  logDebug "childParse(${type}, params)"
  logTrace "params ${params}"

  if (type == "refresh") {
    handleRefresh(params.msg)
  }
  else if (type == "dings") {
    handleDings(params.type, params.msg)
  }
  else {
    log.error "Unhandled type ${type}"
  }
}

private void handleRefresh(final Map msg) {
  logDebug "handleRefresh(${msg})"

  if (msg.battery_life != null && !["jbox_v1", "lpd_v1", "lpd_v2"].contains(device.getDataValue("kind"))) {
    checkChanged("battery", msg.battery_life)
  }

  checkChangedDataValue("firmware", msg.firmware_version)
}

private void handleDings(final String type, final Map msg) {
  logTrace "msg: ${msg}"
  if (msg == null) {
    log.warn "Got a null msg!"
  }
  else if (msg.kind == "motion" && msg.motion == true) {
    checkChanged("motion", "active")

    if (type == "IFTTT") {
      runIn(60, motionOff)
    } else {
      unschedule(motionOff)
    }
  }
  else if (msg.kind == "ding") {
    logInfo "${device.label} button 1 was pushed"
    sendEvent(name: "pushed", value: 1, isStateChange: true)
  }
}

void motionOff() {
  checkChanged("motion", "inactive")
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