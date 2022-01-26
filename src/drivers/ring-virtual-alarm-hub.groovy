/**
 *  Ring Virtual Alarm Hub Driver
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

import groovy.transform.Field

metadata {
  definition(name: "Ring Virtual Alarm Hub", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-alarm-hub.groovy") {
    capability "Actuator"
    capability "Audio Volume"
    capability "Alarm"
    capability "Refresh"
    capability "PowerSource"
    capability "TamperAlert"

    attribute "acStatus", "enum", ["brownout", "connected", "disconnected"]
    attribute "batteryBackup", "string"
    attribute "brightness", "number"
    attribute "countdownTimeLeft", "number"
    attribute "countdownTotal", "number"
    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "cellular", "string"
    attribute "entryDelay", "enum", ["active", "inactive"]
    attribute "exitDelay", "enum", ["active", "inactive"]
    attribute "coAlarm", "enum", ["active", "inactive"]
    attribute "fireAlarm", "enum", ["active", "inactive"]
    attribute "mode",  "enum", ["off", "home", "away"]
    attribute "wifi", "string"

    command "setBrightness", [[name: "Set LED Brightness*", type: "NUMBER", range: "0..100", description: "Choose a value between 0 and 100"]]
    command "setMode", [[name: "Set Mode*", type: "ENUM", description: "Set the Ring Alarm's mode", constraints: ["Disarmed", "Home", "Away"]]]
    command "sirenTest"
    command "createDevices"
  }

  preferences {
    input name: "syncRingToHsm", type: "bool", title: "<b>Sync Ring Alarm mode to HSM mode?</b>", description: "When the Ring mode changes would you like the HSM mode to follow it?", defaultValue: false
    input name: "cancelAlertsOnDisarm", type: "bool", title: "Cancel HSM Alerts on Ring Alarm Disarm?", defaultValue: true
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

@Field final static Integer VOLUME_INC = 5 //somebody can make this a preference if they feel strongly about it

def createDevices() {
  logDebug "Attempting to create devices."
  parent.createDevices(device.getDataValue("zid"))
}

def refresh() {
  logDebug "Attempting to refresh."
  parent.refresh(device.getDataValue("zid"))
}

def setMode(mode) {
  logDebug "setMode(${mode})"
  Map data
  if (mode == "Disarmed" && device.currentValue("mode") != "off") {
    data = ["mode": "none"]
  }
  else if (mode == "Home" && device.currentValue("mode") != "home") {
    data = ["mode": "some"]
  }
  else if (mode == "Away" && device.currentValue("mode") != "away") {
    data = ["mode": "all"]
  }
  else {
    logInfo "${device.label} already set to ${mode}.  No change necessary"
    return
  }

  parent.simpleRequest("setcommand", [type: "security-panel.switch-mode", zid: device.getDataValue("security-panel-zid"), dst: device.getDataValue("src"), data: data])
}

/*alarm capabilities start*/

def off() {
  logDebug "Attempting to stop siren and/or strobe"
  final String  alarm = device.currentValue("alarm")
  logTrace "previous value alarm: $alarm"
  parent.simpleRequest("setcommand", [type: "security-panel.silence-siren", zid: device.getDataValue("security-panel-zid"), dst: device.getDataValue("src"), data: {
  }])
}

def siren() {
  logDebug "Attempting to turn on siren."
  parent.simpleRequest("setcommand", [type: "security-panel.sound-siren", zid: device.getDataValue("security-panel-zid"), dst: device.getDataValue("src"), data: {
  }])
}

def strobe() {
  log.error "The device ${device.getDisplayName()} does not support the strobe functionality"
}

def both() {
  logDebug "Attempting to turn on siren and strobe."
  strobe()
  siren()
}

def sirenTest() {
  if (device.currentValue("mode") != "off") {
    log.warn "Please disarm the alarm before testing the siren."
    return
  }
  //siren-test.stop to cancel
  parent.simpleRequest("setcommand", [type: "siren-test.start", zid: device.getDataValue("security-panel-zid"), dst: null, data: {
  }])
}
/*alarm capabilities end*/

def setVolume(vol) {
  logDebug "Attempting to set volume."
  vol > 100 ? 100 : vol
  vol < 0 ? 0 : vol

  if (vol == 0) {
    if (checkChanged("mute", "muted")) {
      state.prevVolume = device.currentValue("volume")
    }
  }
  else {
    checkChanged("mute", "unmuted")
  }

  if (device.currentValue("volume") != vol) {
    logTrace "requesting volume change from ${device.currentValue("volume")} to ${vol}"
    Map data = ["volume": (vol == null ? 50 : vol).toDouble() / 100]
    parent.simpleRequest("setdevice", [zid: getHubZid(), dst: null, data: data])
  }
  else {
    logInfo "Already at volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeUp() {
  logDebug "Attempting to raise volume."
  def nextVol = device.currentValue("volume") + VOLUME_INC
  if (nextVol <= 100) {
    setVolume(nextVol)
  }
  else {
    logInfo "Already max volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeDown() {
  logDebug "Attempting to lower volume."
  def nextVol = device.currentValue("volume") - VOLUME_INC
  if (nextVol >= 0) {
    setVolume(nextVol)
  }
  else {
    logInfo "Already min volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def mute() {
  logDebug "Attempting to mute."
  setVolume(0)
}

def unmute() {
  logDebug "Attempting to unmute."
  setVolume(state.prevVolume)
}

def setBrightness(brightness) {
  logDebug "Attempting to set brightness ${brightness}."
  brightness = brightness > 100 ? 100 : brightness
  brightness = brightness < 0 ? 0 : brightness
  Map data = ["brightness": (brightness == null ? 100 : brightness).toDouble() / 100]
  parent.simpleRequest("setdevice", [zid: getHubZid(), dst: device.getDataValue("src"), data: data])
}

String getHubZid() {
  String hubZid = device.getDataValue("hub.redsky-zid")
  if (hubZid == null) {
    hubZid = device.getDataValue("hub.kili-zid")
  }
  return hubZid
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

  final String deviceType = deviceInfo.deviceType

  if (state.containsKey('lastCommTime')) {
    log.warn ("Cleaning up old state/data values from ${device}")
    state.remove('lastCommTime')
    state.remove('nextExpectedWakeup')
    state.remove('signalStrength')
    device.removeDataValue('fingerprint') // Hub doesn't appear to have a fingerprint. Previously value was coming from dataType access-code, which doesn't make sense
    device.removeDataValue('null-zid')
  }

  if (CHILD_ZID_DEVICE_TYPES.contains(deviceInfo.deviceType)) {
    checkChangedDataValue(deviceInfo.deviceType + '-zid', deviceInfo.zid)
  }

  if (deviceInfo.mode != null) {
    final String mappedMode = MODES.get(deviceInfo.mode)

    if (checkChanged("mode", mappedMode)) {
      parent.childParse('mode-set', [msg: [mode: mappedMode]])
    }

    if (mappedMode == "off") {
      sendEvent(name: "countdownTimeLeft", value: 0)
      sendEvent(name: "countdownTotal", value: 0)
      checkChanged("entryDelay", "inactive")
      checkChanged("exitDelay", "inactive")
    }

    if (syncRingToHsm) {
      final Map<String, String> hsmMode = RING_TO_HSM_MODE_MAP[mappedMode]

      if (location.hsmStatus != hsmMode.status) {
        logInfo "Setting HSM to ${hsmMode.set}"
        logTrace "mode: ${mappedMode} hsmStatus: ${location.hsmStatus}"
        sendLocationEvent(name: "hsmSetArm", value: hsmMode.set)
      }

      if (cancelAlertsOnDisarm && mappedMode == "off") {
        sendLocationEvent(name: "hsmSetArm", value: "cancelAlerts")
      }
    }
  }

  if (deviceInfo.siren != null) {
    final String alarm = deviceInfo.siren.state == "on" ? "siren" : "off"

    if (checkChanged("alarm", alarm)) {
      if (alarm != "off") {
        sendEvent(name: "countdownTimeLeft", value: 0)
        sendEvent(name: "countdownTotal", value: 0)
        checkChanged("entryDelay", "inactive")
      }
    }
  }

  // Use containsKey instead of null chuck because alarmInfo == null means an alarm was cleared
  if (deviceInfo.containsKey('alarmInfo')) {
    final alarmInfo = deviceInfo.alarmInfo
    checkChanged("entryDelay", alarmInfo == "entry-delay" ? "active" : "inactive")

    // These duplicate what the co/smoke alarm devices already display
    checkChanged("coAlarm", alarmInfo == "co-alarm" ? "active" : "inactive")
    checkChanged("fireAlarm", alarmInfo == "fire-alarm" ? "active" : "inactive")
  }

  if (deviceInfo.transition != null) {
    checkChanged("exitDelay", deviceInfo.transition == "exit" ? "active" : "inactive")
    sendEvent(name: "countdownTimeLeft", value: deviceInfo.timeLeft)
    sendEvent(name: "countdownTotal", value: deviceInfo.total)
  }

  if (deviceInfo.containsKey('transitionDelayEndTimestamp')) {
    checkChanged("exitDelay", deviceInfo.transitionDelayEndTimestamp != null ? "active" : "inactive")
  }

  if (deviceInfo.percent != null) {
    log.warn "${device.label} is updating firmware: ${deviceInfo.percent}% complete"
  }

  if (deviceInfo.version != null) {
    final softwareVersion = deviceInfo.version?.softwareVersion
    if (softwareVersion != null) {
      checkChangedDataValue("softwareVersion", softwareVersion)
    }
  }

  if (deviceInfo.networks != null) {
    final Map nw = deviceInfo.networks

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


  if (deviceInfo.acStatus != null) {
    final acStatus = deviceInfo.acStatus
    checkChanged("acStatus", AC_STATUS.getOrDefault(acStatus, "brownout"))
    checkChanged("powerSource", POWER_SOURCE.getOrDefault(acStatus, "unknown"))
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final String key in ["batteryBackup", "brightness", "commStatus", "tamper", "volume"]) {
    final keyVal = deviceInfo[key]
    if (keyVal != null) {
      checkChanged(key, keyVal)
    }
  }

  // Update state values
  state += deviceInfo.subMap(['impulseType', 'lastUpdate'])

  // Update data values
  for(final String key in ['firmware', 'hardwareVersion']) {
    checkChangedDataValue(key, deviceInfo[key])
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

void checkChangedDataValue(final String name, final value) {
  if (value != null && device.getDataValue(name) != value) {
    device.updateDataValue(name, value)
  }
}

// Child device types to save zid for
@Field final static HashSet<String> CHILD_ZID_DEVICE_TYPES = ['access-code.vault', 'adapter.zwave', 'security-panel']

@Field final static Map RING_TO_HSM_MODE_MAP = [
  "home": [set: "armHome", status: "armedHome"],
  "away": [set: "armAway", status: "armedAway"],
  "off": [set: "disarm", status: "disarmed"]
]

@Field final static Map<String, String> AC_STATUS = [
  ok: "connected",
  error: "disconnected",
]

@Field final static Map<String, String> POWER_SOURCE = [
  ok: "mains",
  error: "battery",
]

@Field final static Map<String, String> MODES = [
  "none": "off",
  "some": "home",
  "all": "away"
]