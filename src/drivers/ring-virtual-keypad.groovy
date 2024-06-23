/**
 *  Ring Virtual Keypad Driver
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

import groovy.transform.Field

metadata {
  definition(name: "Ring Virtual Keypad", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "AudioVolume"
    capability "Battery"
    capability "Motion Sensor"
    capability "Refresh"
    capability "Sensor"
    capability "TamperAlert"

    attribute "batteryStatus", "enum", ["charged", "charging", "failed", "full", "low", "malfunction", "none", "ok", "warn"]
    attribute "brightness", "number"
    attribute "chirps", "enum", ["disabled", "enabled"]
    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "firmware", "string"
    attribute "powerSave", "enum", ["off", "on", "unknown"]

    command "setBrightness", [[name: "Set LED Brightness*", type: "NUMBER", range: "0..100", description: "Choose a value between 0 and 100"]]
    command "setChirps", [[name: "mode", type: "ENUM", constraints: ["disabled", "enabled"]]]
    command "setPowerSave", [[name: "mode", type: "ENUM", constraints: ["off", "on"]]]
  }

  preferences {
    input name: "motionTimeout", type: "number", range: '5..600', title: "Time in seconds before motion resets to inactive", defaultValue: 15
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

void setVolume(volumelevel) {
  // Value must be in [0, 100]
  volumelevel = Math.min(Math.max(volumelevel == null ? 50 : volumelevel.toInteger(), 0), 100)

  Integer currentVolume = device.currentValue("volume")

  if (currentVolume != volumelevel) {
    logTrace "requesting volume change to ${volumelevel}"
    parent.apiWebsocketRequestSetDevice(null, device.getDataValue("zid"), [volume: volumelevel.toDouble() / 100])
  }
  else {
    logInfo "Already at volume."
    sendEvent(name: "volume", value: currentVolume)
  }
}

void volumeUp() {
  Integer currentVolume = device.currentValue("volume")
  Integer nextVol = currentVolume + VOLUME_INC
  if (nextVol <= 100) {
    setVolume(nextVol)
  }
  else {
    logInfo "Already max volume."
    sendEvent(name: "volume", value: currentVolume)
  }
}

void volumeDown() {
  Integer currentVolume = device.currentValue("volume")
  Integer nextVol = currentVolume - VOLUME_INC
  if (nextVol >= 0) {
    setVolume(nextVol)
  }
  else {
    logInfo "Already min volume."
    sendEvent(name: "volume", value: currentVolume)
  }
}

void mute() {
  setVolume(0)
}

void unmute() {
  setVolume(state.prevVolume)
}

void updateVolumeInternal(Integer volume) {
  Integer prevVolume = device.currentValue("volume")

  if (checkChanged("volume", volume)) {
    state.prevVolume == prevVolume
    checkChanged("mute", volume == 0 ? "muted" : "unmuted")
  }
}

void setBrightness(brightness) {
  // Value must be in [0, 100]
  brightness = Math.min(Math.max(brightness == null ? 100 : brightness.toInteger(), 0), 100)

  parent.apiWebsocketRequestSetDevice(null, device.getDataValue("zid"), [brightness: brightness.toDouble() / 100])
}

void setChirps(chirps) {
  parent.apiWebsocketRequestSetDevice(null, device.getDataValue("zid"), [chirps: chirps])
}

void setPowerSave(powerSave) {
  String ringValue

  for (it in POWER_SAVE) {
    if (it.value == powerSave) {
      ringValue = it.key
      break
    }
  }

  if (ringValue == null) {
    log.error "Could not map ${powerSave} to value ring expects"
    return
  }

  parent.apiWebsocketRequestSetDevice(null, device.getDataValue("zid"), [powerSave: ringValue])
}

void refresh() {
  parent.refresh(device.getDataValue("src"))
}

void stopMotion() {
  checkChanged("motion", "inactive")
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(${deviceInfo})"

  if (deviceInfo.powerSave != null) {
    checkChanged("powerSave", POWER_SAVE.getOrDefault(deviceInfo.powerSave, 'unknown'))
  }

  if (deviceInfo.batteryLevel != null) {
    checkChanged("battery", deviceInfo.batteryLevel, "%")
  }

  if (deviceInfo.impulseType == "keypad.motion") {
    checkChanged("motion", "active")
    //The inactive message almost never comes reliably. for now we'll schedule it off
    runIn(motionTimeout.toInteger(), stopMotion)
  }

  if (deviceInfo.volume != null) {
    updateVolumeInternal(deviceInfo.volume)
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final entry in deviceInfo.subMap(["batteryStatus", "brightness", "chirps", "commStatus", "firmware", "tamper"])) {
    checkChanged(entry.key, entry.value)
  }

  // Update state values
  Map stateValues = deviceInfo.subMap(['impulseType', 'lastCommTime', 'lastUpdate', 'signalStrength'])
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

  if (deviceInfo.percent != null) {
    log.warn "Firmware update ${deviceInfo.percent}% complete"
  }
}

void runCleanup() {
  device.removeDataValue('firmware') // Is an attribute now
  state.remove('nextExpectedWakeup') // Device doesn't seem to have this value
}

boolean checkChanged(final String attribute, final newStatus, final String unit=null, final String type=null) {
  final boolean changed = isStateChange(device, attribute, newStatus.toString())
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}

@Field final static Integer VOLUME_INC = 5

@Field final static Map POWER_SAVE = [
  'off': 'off',
  'extended': 'on',
].asImmutable()