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
  if (!isMuted()) {
    state.prevVolume = device.currentValue("volume")
    setVolume(0)
  }
  else {
    logInfo "Already muted."
    sendEvent(name: "mute", value: "muted")
  }
}

def unmute() {
  logDebug "Attempting to unmute."
  if (isMuted()) {
    setVolume(state.prevVolume)
  }
  else {
    logInfo "Already unmuted."
    sendEvent(name: "mute", value: "unmuted")
  }
}

def setVolume(volumelevel) {
  logDebug "Attempting to set volume."
  if (device.currentValue("volume") != volumelevel) {
    parent.simpleRequest("device-set", [
      dni: device.deviceNetworkId,
      kind: "chimes",
      params: ["chime[settings][volume]": (volumelevel / 10 as Integer)]
    ])
  }
  else {
    logInfo "Already at volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeUp() {
  logDebug "Attempting to raise volume."
  def currVol = device.currentValue("volume") / 10 as Integer
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
  def currVol = device.currentValue("volume") / 10 as Integer
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

def childParse(type, params = []) {
  logDebug "childParse(type, params)"
  logTrace "type ${type}"
  logTrace "params ${params}"

  state.lastUpdate = now()

  if (type == "refresh") {
    logTrace "refresh"
    handleRefresh(params.msg)
  }
  else if (type == "device-control") {
    logTrace "device-control"
    handleBeep(type, params)
  }
  else if (type == "device-set") {
    logTrace "volume"
    handleVolume(type, params)
  }
  else {
    log.error "Unhandled type ${type}"
  }
}

private handleBeep(id, params) {
  logTrace "handleBeep(${id}, ${params.response})"
  if (params.response != 204) {
    log.warn "Not successful?"
    return
  }
  if (params.action == "play_sound") {
    logInfo "Device ${device.label} played ${params.kind}"
  }
}

private handleVolume(id, params) {
  logTrace "handleVolume(${id}, ${params})"
  if (params.response != 204) {
    log.warn "Not successful?"
    return
  }

  sendEvent(name: "volume", value: (params.volume as Integer) * 10)
  logInfo "Device ${device.label} volume set to ${(params.volume as Integer) * 10}"

  if (params.volume == 0 && device.currentValue("mute") != "muted") {
    sendEvent(name: "mute", value: "muted")
    logInfo "Device ${device.label} set to muted"
  }
  if (params.volume != 0 && device.currentValue("mute") == "muted") {
    sendEvent(name: "mute", value: "unmuted")
    logInfo "Device ${device.label} set to unmuted"
  }
}

private handleRefresh(json) {
  logDebug "handleRefresh(json)"
  logTrace "json ${json}"
  if (!json.settings) {
    log.warn "No volume?"
    return
  }
  if (device.currentValue("volume") != (json.settings.volume as Integer) * 10) {
    sendEvent(name: "volume", value: (json.settings.volume as Integer) * 10)
    logInfo "Device ${device.label} volume set to ${(json.settings.volume as Integer) * 10}"
  }
  if (device.currentValue("mute") == null) {
    sendEvent(name: "mute", value: "unmuted")
    logInfo "Device ${device.label} set to unmuted"
  }
  if (state.prevVolume == null) {
    state.prevVolume = 50
    logInfo "No previous volume found so arbitrary value given"
  }

  if (json.firmware_version && device.getDataValue("firmware") != json.firmware_version) {
    device.updateDataValue("firmware", json.firmware_version)
  }
  if (json.kind && device.getDataValue("kind") != json.kind) {
    device.updateDataValue("kind", json.kind)
  }

}

private isMuted() {
  return device.currentValue("mute") == "muted"
}
