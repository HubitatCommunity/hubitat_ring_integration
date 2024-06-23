/**
 *  Ring Virtual Alarm Hub Driver
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
  definition(name: "Ring Virtual Alarm Hub", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Actuator"
    capability "AudioVolume"
    capability "Alarm"
    capability "Refresh"
    capability "PowerSource"
    capability "TamperAlert"

    attribute "acStatus", "enum", ["brownout", "connected", "disconnected"]
    attribute "batteryBackup", "string"
    attribute "batteryStatus", "enum", ["charged", "charging", "failed", "full", "low", "malfunction", "none", "ok", "warn"]
    attribute "brightness", "number"
    attribute "coAlarm", "enum", ["active", "inactive"]
    attribute "cellular", "string"
    attribute "connectionStatus", "enum", ["asset-cell-backup", "backing-up", "connected", "connecting", "extended-cell-backup",
                                           "restoring", "updating"]
    attribute "countdownTimeLeft", "number"
    attribute "countdownTotal", "number"
    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "ethernet", "string"
    attribute "entryDelay", "enum", ["active", "inactive"]
    attribute "exitDelay", "enum", ["active", "inactive"]
    attribute "fireAlarm", "enum", ["active", "inactive"]
    attribute "firmware", "string"
    attribute "mode",  "enum", ["off", "home", "away"]
    attribute "networkConnection", "enum", ["cellular", "ethernet", "unknown", "wifi"]
    attribute "wifi", "string"

    command "setBrightness", [[name: "Set LED Brightness*", type: "NUMBER", range: "0..100", description: "Choose a value between 0 and 100"]]
    command "setMode", [[name: "Set Mode*", type: "ENUM", description: "Set the Ring Alarm's mode", constraints: ["Disarmed", "Home", "Away"]]]
    command "sirenTest"
    command "sirenTestCancel"
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

void createDevices() { parent.createDevices() }

void refresh() {
  parent.refresh(device.getDataValue("src"))
}

void setMode(mode) {
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
    logInfo "${device.label} already set to ${mode}. No change necessary"
    return
  }

  parent.apiWebsocketRequestSetCommand("security-panel.switch-mode", device.getDataValue("src"), device.getDataValue("security-panel-zid"), data)
}

void off() {
  logTrace "previous value alarm: ${device.currentValue("alarm")}"
  parent.apiWebsocketRequestSetCommand("security-panel.silence-siren", device.getDataValue("src"), device.getDataValue("security-panel-zid"))
}

void siren() {
  parent.apiWebsocketRequestSetCommand("security-panel.sound-siren", device.getDataValue("src"), device.getDataValue("security-panel-zid"))
}

void strobe() {
  log.error "The device ${device.getDisplayName()} does not support the strobe functionality"
}

void both() {
  strobe()
  siren()
}

void sirenTest() {
  if (device.currentValue("mode") != "off") {
    log.warn "Please disarm the alarm before testing the siren."
    return
  }
  parent.apiWebsocketRequestSetCommand("siren-test.start", null, device.getDataValue("security-panel-zid"))
}

void sirenTestCancel() {
  parent.apiWebsocketRequestSetCommand("siren-test.stop", device.getDataValue("src"), device.getDataValue("security-panel-zid"))
}

void setVolume(volumelevel) {
  // Value must be in [0, 100]
  volumelevel = Math.min(Math.max(volumelevel == null ? 50 : volumelevel.toInteger(), 0), 100)

  Integer currentVolume = device.currentValue("volume")

  if (currentVolume != volumelevel) {
    logTrace "requesting volume change to ${volumelevel}"
    parent.apiWebsocketRequestSetDevice(null, getHubZid(), [volume: volumelevel.toDouble() / 100])
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

void setBrightness(brightness) {
  // Value must be in [0, 100]
  brightness = Math.min(Math.max(brightness == null ? 100 : brightness.toInteger(), 0), 100)

  parent.apiWebsocketRequestSetDevice(device.getDataValue("src"), getHubZid(), [brightness: brightness.toDouble() / 100])
}

String getHubZid() {
  return device.getDataValue("hub.redsky-zid") ?: device.getDataValue("hub.kili-zid")
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(${deviceInfo})"

  // Save some special zids
  for (final entry in deviceInfo.subMap(['hub.kili-zid', 'hub.redsky-zid', 'security-panel-zid'])) {
    checkChangedDataValue(entry.key, entry.value)
  }

  if (deviceInfo.mode != null) {
    final String mappedMode = MODES.get(deviceInfo.mode)

    if (checkChanged("mode", mappedMode)) {
      parent.updateMode(mappedMode)

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

        if (mappedMode == "off" && cancelAlertsOnDisarm) {
          sendLocationEvent(name: "hsmSetArm", value: "cancelAlerts")
        }
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

  // Use containsKey instead of null check because alarmInfo == null means an alarm was cleared
  if (deviceInfo.containsKey('alarmInfo')) {
    // @note Other possible values of alarmInfo.state: 'panic'

    final String alarmInfo = deviceInfo.alarmInfo?.state
    checkChanged("entryDelay", alarmInfo == "entry-delay" ? "active" : "inactive")

    // These duplicate what the co/smoke alarm devices already display
    checkChanged("coAlarm", alarmInfo == "co-alarm" ? "active" : "inactive")
    checkChanged("fireAlarm", alarmInfo == "fire-alarm" ? "active" : "inactive")
  }

  // Use containsKey instead of null check because lastConnectivityCheckError == null means an connectivity error was resolved
  if (deviceInfo.containsKey('lastConnectivityCheckError')) {
    if (deviceInfo.lastConnectivityCheckError) {
      log.error "Ring connectivity error: ${deviceInfo.lastConnectivityCheckError}"
    } else {
      log.info "Ring connectivity error resolved."
    }
  }

  // Use containsKey instead of null check because transitionDelayEndTimestamp == null means the exit delay ended
  if (deviceInfo.containsKey('transitionDelayEndTimestamp')) {
    checkChanged("exitDelay", deviceInfo.transitionDelayEndTimestamp != null ? "active" : "inactive")
  }

  if (deviceInfo.networks != null) {
    final Map networks = deviceInfo.networks

    if (deviceInfo.containsKey('networkConnection')) {
      checkChanged("networkConnection", networks.getOrDefault(deviceInfo.networkConnection, [type: "unknown"]).type)
    }

    for (final entry in networks.subMap(['eth0', 'ppp0', 'wlan0'])) {
      final Map network = entry.value
      final String networkKey = entry.key

      if (network == null) {
        logDebug "Got a null networks key for ${networkKey}. Ignoring"
        continue
      }

      final String networkType = network.type

      // Sometimes the type isn't included. Just skip updating things
      if (!networkType) {
        logDebug "Could not get network.type for ${networkKey}: ${networks}. Ignoring"
        continue
      }

      checkChangedDataValue(networkKey + "Type", networkType)

      String fullNetworkStr

      if (networkKey == 'eth0') {
        fullNetworkStr = 'connected'
      }
      else {
        String name = ""

        if (networkKey == 'ppp0') {
          name = network.name ?: device.getDataValue(networkKey + "Name")
          checkChangedDataValue(networkKey + "Name", name)
        }
        else if (networkKey == 'wlan0') {
          name = network.ssid ?: device.getDataValue(networkKey + "Ssid")
          checkChangedDataValue(networkKey + "Ssid", name)
        }

        String networkRssi = network.rssi ?: device.getDataValue(networkKey + "Rssi")
        checkChangedDataValue(networkKey + "Rssi", networkRssi)

        fullNetworkStr = name + " RSSI " + networkRssi
      }

      logInfo "${networkKey} ${networkType} ${fullNetworkStr}"
      checkChanged(networkType, fullNetworkStr)
      state[networkKey] = fullNetworkStr
    }
  }

  if (deviceInfo.acStatus != null) {
    final String acStatus = deviceInfo.acStatus
    checkChanged("acStatus", AC_STATUS.getOrDefault(acStatus, "brownout"))
    checkChanged("powerSource", POWER_SOURCE.getOrDefault(acStatus, "unknown"))
  }

  if (deviceInfo.volume != null) {
    final Integer volume = deviceInfo.volume

    Integer prevVolume = device.currentValue("volume")

    if (checkChanged("volume", volume)) {
      state.prevVolume == prevVolume
      checkChanged("mute", volume == 0 ? "muted" : "unmuted")
    }
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final entry in deviceInfo.subMap(["batteryBackup", "batteryStatus", "brightness", "commStatus", "connectionStatus", "firmware", "tamper"])) {
    checkChanged(entry.key, entry.value)
  }

  // Update state values
  Map stateValues = deviceInfo.subMap(['lastNetworkLatencyEvent', 'lastUpdate', 'impulseType'])
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

  if (deviceInfo.transition != null) {
    checkChanged("exitDelay", deviceInfo.transition == "exit" ? "active" : "inactive")
    sendEvent(name: "countdownTimeLeft", value: deviceInfo.timeLeft)
    sendEvent(name: "countdownTotal", value: deviceInfo.total)
  }
}

void runCleanup() {
  state.remove('lastCommTime')
  state.remove('nextExpectedWakeup')
  state.remove('signalStrength')

  device.removeDataValue('firmware') // Is an attribute now
  device.removeDataValue('softwareVersion') // Is an attribute now

  device.removeDataValue('fingerprint') // Hub doesn't appear to have a fingerprint. Previously value was coming from dataType access-code, which doesn't make sense
  device.removeDataValue('null-zid')
  device.removeDataValue('access-code.vault-zid')
  device.removeDataValue('adapter.zigbee-zid')
  device.removeDataValue('adapter.zwave-zid')
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

@Field final static Integer VOLUME_INC = 5

@Field final static Map RING_TO_HSM_MODE_MAP = [
  "home": [set: "armHome", status: "armedHome"],
  "away": [set: "armAway", status: "armedAway"],
  "off": [set: "disarm", status: "disarmed"]
].asImmutable()

@Field final static Map AC_STATUS = [
  ok: "connected",
  error: "disconnected",
].asImmutable()

@Field final static Map POWER_SOURCE = [
  ok: "mains",
  error: "battery",
].asImmutable()

@Field final static Map MODES = [
  none: "off",
  some: "home",
  all: "away"
].asImmutable()