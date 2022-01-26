/**
 *  Ring Virtual Camera with Siren Device Driver
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
  definition(name: "Ring Virtual Camera with Siren", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-camera-with-siren.groovy") {
    capability "Actuator"
    capability "Sensor"
    capability "Refresh"
    capability "Polling"
    capability "Alarm"
    capability "MotionSensor"
    capability "Battery"

    command "getDings"
    //command "test"
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

def test() {
  //parent.simpleRequest("history", [dni: device.deviceNetworkId])
  parent.simpleRequest("snapshot-image-tmp", [dni: device.deviceNetworkId])
}

def updated() {
  parent.snapshotOption(device.deviceNetworkId, snapshotPolling)
}

def off(boolean modifyAlarm = true) {
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "siren_off"])
}

def siren() {
  logDebug "Attempting to turn on siren."
  parent.simpleRequest("device-set", [dni: device.deviceNetworkId, kind: "doorbots", action: "siren_on"])
}

def strobe(value = "strobe") {
  log.error "Strobe not implemented for device type ${device.getDataValue("kind")}"
}

def both() {
  log.error "Both (strobe and siren) not implemented for device type ${device.getDataValue("kind")}"
}

void childParse(final String type, final Map params) {
  logDebug "childParse(${type}, params)"
  logTrace "params ${params}"

  if (type == "refresh") {
    handleRefresh(params.msg)
  }
  else if (type == "device-set") {
    handleSet(params)
  }
  else if (type == "dings") {
    handleDings(params.type, params.msg)
  }
  else if (type == "snapshot-image") {
    state.snapshot = params.jpg
  }
  else {
    log.error "Unhandled type ${type}"
  }
}

private void handleRefresh(final Map msg) {
  logDebug "handleRefresh(${msg.description})"

  if (msg.battery_life != null) {
    checkChanged("battery", msg.battery_life)
  }
  if (msg.siren_status?.seconds_remaining != null) {
    final Integer seconds_remaining = msg.siren_status.seconds_remaining
    checkChanged("alarm", seconds_remaining > 0 ? "siren" : "off")
    if (seconds_remaining > 0) {
      runIn(seconds_remaining + 1, refresh)
    }
  }
  checkChangedDataValue("firmware", msg.firmware_version)
}

private void handleSet(final Map params) {
  logTrace "handleSet(${params})"
  if (params.response != 200) {
    log.warn "Not successful?"
    return
  }
  if (params.action == "siren_on") {
    if (device.currentValue("alarm") != "both") {
      checkChanged("alarm", "siren")
    }
    runIn(params.msg.seconds_remaining + 1, refresh)
  }
  else if (params.action == "siren_off") {
    checkChanged("alarm", "off")
  }
  else {
    log.error "Unsupported set ${params.action}"
  }
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