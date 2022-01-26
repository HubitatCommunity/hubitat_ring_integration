/**
 *  Ring Virtual Chime Device Driver
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
  definition(name: "Ring Virtual Chime", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-chime.groovy") {
    capability "Actuator"
    capability "Tone"
    capability "AudioNotification"
    capability "AudioVolume"
    capability "Refresh"
    capability "Polling"

    command "playDing"
    command "playMotion"
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

def configure() {
  logDebug "configure()"
  refresh()
}

def parse(String description) {
  logDebug "description: ${description}"
}

def poll() {
  logDebug "poll()"
  refresh()
}

def refresh() {
  logDebug "Attempting to refresh."
  parent.simpleRequest("refresh", [dni: device.deviceNetworkId])
}

def beep() {
  playMotion()
}

def playMotion() {
  logDebug "Attempting to play motion."
  if (!isMuted()) {
    parent.simpleRequest("device-control", [dni: device.deviceNetworkId, kind: "chimes", action: "play_sound", params: [kind: "motion"]])
  }
  else {
    logInfo "No motion because muted"
  }
}

def playDing() {
  logDebug "Attempting to play ding."
  if (!isMuted()) {
    parent.simpleRequest("device-control", [dni: device.deviceNetworkId, kind: "chimes", action: "play_sound", params: [kind: "ding"]])
  }
  else {
    logInfo "No ding because muted"
  }
}

def mute() {
  logDebug "Attempting to mute."

  if (checkChanged("mute", "muted")) {
    state.prevVolume = device.currentValue("volume")
    setVolume(0)
  }
  else {
    logInfo "Already muted."
  }
}

def unmute() {
  logDebug "Attempting to unmute."

  if (checkChanged("mute", "unmuted")) {
    setVolume(state.prevVolume)
  }
  else {
    logInfo "Already unmuted."
  }
}

def setVolume(volumelevel) {
  logDebug "Attempting to set volume."

  if (device.currentValue("volume") != volumelevel) {
    parent.simpleRequest("device-set", [
      dni: device.deviceNetworkId,
      kind: "chimes",
      params: ["chime[settings][volume]": (volumelevel / 10).toInteger()]
    ])
  }
  else {
    logInfo "Already at volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeUp() {
  logDebug "Attempting to raise volume."
  final Integer currVol = device.currentValue("volume").toInteger() / 10
  if (currVol < 10) {
    setVolume((currVol + 1) * 10)
  }
  else {
    logInfo "Already max volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeDown() {
  logDebug "Attempting to lower volume."
  final Integer currVol = device.currentValue("volume").toInteger() / 10
  if (currVol > 0) {
    setVolume((currVol - 1) * 10)
  }
  else {
    logInfo "Already min volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

//TODO
/*
playText(text, volumelevel)
text required (STRING) - Text to play
volumelevel optional (NUMBER) - Volume level (0 to 100)
playTextAndRestore(text, volumelevel)
text required (STRING) - Text to play
volumelevel optional (NUMBER) - Volume level (0 to 100)
playTextAndResume(text, volumelevel)
text required (STRING) - Text to play
volumelevel optional (NUMBER) - Volume level (0 to 100)
*/

def playTrack(trackuri, volumelevel) {
  log.error "Not implemented! playTrack(trackuri, volumelevel)"
}

def playTrackAndRestore(trackuri, volumelevel) {
  log.error "Not implemented! playTrackAndRestore(trackuri, volumelevel)"
}

def playTrackAndResume(trackuri, volumelevel) {
  log.error "Not implemented! playTrackAndResume(trackuri, volumelevel)"
}

void childParse(final String type, final Map params) {
  logDebug "childParse(${type}, params)"
  logTrace "params ${params}"

  state.lastUpdate = now()

  if (type == "refresh") {
    handleRefresh(params.msg)
  }
  else if (type == "device-control") {
    handleBeep(params)
  }
  else if (type == "device-set") {
    handleVolume(params)
  }
  else {
    log.error "Unhandled type ${type}"
  }
}

private void handleBeep(final Map params) {
  logTrace "handleBeep(${params})"
  if (params.response != 204) {
    log.warn "Not successful?"
    return
  }
  if (params.action == "play_sound") {
    logInfo "Device ${device.label} played ${params.kind}"
  }
}

private void handleVolume(final Map params) {
  logTrace "handleVolume(${params})"
  if (params.response != 204) {
    log.warn "Not successful?"
    return
  }

  final Integer volume = params.volume.toInteger() * 10
  checkChanged('volume', volume)
  checkChanged("mute", volume == 0 ? "muted" : "unmuted")
}

private void handleRefresh(final Map msg) {
  logDebug "handleRefresh(${msg})"

  if (!msg.settings) {
    log.warn "No volume?"
    return
  }

  final Integer volume = msg.settings.volume.toInteger() * 10
  checkChanged("volume", volume)
  checkChanged("mute", volume == 0 ? "muted" : "unmuted")

  if (state.prevVolume == null) {
    state.prevVolume = 50
    logInfo "No previous volume found so arbitrary value given"
  }

  checkChangedDataValue("firmware", msg.firmware_version)
  checkChangedDataValue("kind", msg.kind)
}

private boolean isMuted() {
  return device.currentValue("mute") == "muted"
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