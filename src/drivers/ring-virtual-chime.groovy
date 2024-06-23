/* groovylint-disable Indentation */
/**
 *  Ring Virtual Chime Device Driver
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

// @todo Consider using /ringtones

import groovy.transform.Field

metadata {
  definition(name: "Ring Virtual Chime", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Actuator"
    capability "AudioNotification"
    capability "AudioVolume"
    capability "Refresh"
    capability "Polling"
    capability "Tone"

    attribute "connectionStatus", "enum", ["offline", "online"]
    attribute "rssi", "number"
    attribute "wifi", "string"

    command "playDing"
    command "playMotion"

    command "clearSnooze"
    command "snooze", [[name: 'Time', type: 'NUMBER', description: 'Snooze sounds for x minutes. [0..1440]']]
  }

  preferences {
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false
  }
}

void installed() { updated() }

void updated() { parentCheck() }

void parentCheck() {
  if (device.parentAppId == null || device.parentDeviceId != null) {
    log.error("This device can only be installed using the Unofficial Ring Connect app. Remove this device and create it through the app. parentAppId=${device.parentAppId}, parentDeviceId=${device.parentDeviceId}")
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

void parse(String description) {
  logDebug "description: ${description}"
}

void poll() { refresh() }

// apiRequestDevicesApiSet(device.deviceNetworkId, "devices", action: "settings") returns something for this device, but there's no use for those values yet
void refresh() {
  logDebug "refresh()"
  parent.apiRequestClientsApiRefresh(device.deviceNetworkId)
  parent.apiRequestClientsApiHealth(device.deviceNetworkId, "chimes")

  // @todo This isn't quite working yet
  // parent.apiRequestDeviceGet(device.deviceNetworkId, "chimes", "linked_doorbots")
}

void beep() { playMotion() }

void playMotion() {
  if (isMuted()) {
    logInfo "playMotion: Not playing because device is muted"
  } else {
    parent.apiRequestClientsApiSet(device.deviceNetworkId, "chimes", action: 'play_sound', query: [kind: "motion"], method: 'Post')
  }
}

void playDing() {
  if (isMuted()) {
    logInfo "playDing: Not playing because device is muted"
  } else {
    parent.apiRequestClientsApiSet(device.deviceNetworkId, "chimes", action: 'play_sound', query: [kind: "ding"], method: 'Post')
  }
}

void snooze(minutes) {
  // Value must be in [1, 1440 (24 * 60)]
  minutes = Math.min(Math.max(minutes == null ? 60 : minutes.toInteger(), 1), 24 * 60)

  logTrace "Requesting snooze for $minutes min"

  parent.apiRequestClientsApiSet(device.deviceNetworkId, "chimes", action: 'do_not_disturb', method: 'Post', body: [time: minutes])
}

void clearSnooze() {
  logTrace "Clearing snooze"
  parent.apiRequestClientsApiSet(device.deviceNetworkId, "chimes", action: 'do_not_disturb', method: 'Post', body: [:])
}

void setVolume(volumelevel) {
  // Value must be in [0, 100]
  volumelevel = Math.min(Math.max(volumelevel == null ? 50 : volumelevel.toInteger(), 0), 100)

  Integer currentVolume = device.currentValue("volume")

  if (currentVolume != volumelevel) {
    logTrace "requesting volume change to ${volumelevel}"

    // Chime only accepts volume from 0 to 10
    final Integer sentValue = volumelevel / 10

    parent.apiRequestClientsApiSet(device.deviceNetworkId, "chimes", method: 'Put', body: [chime: [settings: [volume: sentValue]]])
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

void updateVolumeInternal(volume) {
  Integer prevVolume = device.currentValue("volume")

  volume = volume.toInteger() * 10

  if (checkChanged("volume", volume)) {
    state.prevVolume == prevVolume
    if (volume == 0) {
      checkChanged("mute", "muted")
    } else {
      checkChanged("mute", "unmuted")
    }
  }
}

void playText(text, volumelevel) { log.error "playText not implemented!" }
void playTextAndRestore(text, volumelevel) { log.error "playTextAndRestore not implemented!" }
void playTextAndResume(text, volumelevel) { log.error "playTextAndResume not implemented!" }
void playTrack(trackuri, volumelevel) { log.error "playTrack not implemented!" }
void playTrackAndRestore(trackuri, volumelevel) { log.error "playTrackAndRestore not implemented!" }
void playTrackAndResume(trackuri, volumelevel) { log.error "playTrackAndResume not implemented!" }

private boolean isMuted() {
  return device.currentValue("mute") == "muted"
}

void handleClientsApiSet(final Map msg, final Map arguments) {
  String action = arguments.action

  if (action == null) {
    if (arguments.body?.settings?.volume) {
      updateVolumeInternal(arguments.body?.settings?.volume)
    }
    else {
      log.error "handleClientsApiSet unsupported null action with body: ${arguments.body}"
    }
  }
  else if (action == 'play_sound') {
      logInfo "Device ${device.label} played '${arguments.query?.kind}'"
  }
  else if (action == 'do_not_disturb') {
      logInfo "Device ${device.label} snooze enabled with ${msg.time_remaining} minutes remaining"
  }
  else {
    log.error "handleClientsApiSet unsupported action ${action}, msg=${msg}, arguments=${arguments}"
  }
}

void handleClientsApiHealth(final Map msg) {
  if (msg.device_health) {
    if (msg.device_health.wifi_name) {
      checkChanged("wifi", msg.device_health.wifi_name)
    }
  }
}

void handleClientsApiRefresh(final Map msg) {
  if (msg.alerts?.connection != null) {
    checkChanged("connectionStatus", msg.alerts.connection) // devices seem to be considered offline after 20 minutes
  }

  if (msg.settings?.volume != null) {
    updateVolumeInternal(msg.settings.volume)
  }

  if (msg.kind != null) {
    checkChangedDataValue("kind", msg.kind)
  }
}

void runCleanup() {
  state.remove('lastUpdate')
  device.removeDataValue("firmware") // Doesn't appear to be available for this device
  device.removeDataValue("device_id")
}

boolean checkChanged(final String attribute, final newStatus, final String unit=null, final String type=null) {
  final boolean changed = isStateChange(device, attribute, newStatus.toString())
  if (changed) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
  }
  sendEvent(name: attribute, value: newStatus, unit: unit, type: type)
  return changed
}

void checkChangedDataValue(final String name, final value) {
  if (device.getDataValue(name) != value) {
    device.updateDataValue(name, value)
  }
}

@Field final static Integer VOLUME_INC = 10 // Chime volume is only in multiples of 10