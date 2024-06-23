/**
 *  Ring Virtual Beams Bridge Driver
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

metadata {
  definition(name: "Ring Virtual Beams Bridge", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Refresh"
    capability "Sensor"

    attribute "commStatus", "enum", ["error", "ok", "update-queued", "updating", "waiting-for-join", "wrong-network"]
    attribute "connectionStatus", "enum", ["asset-cell-backup", "backing-up", "connected", "connecting", "extended-cell-backup",
                                           "restoring", "updating"]
    attribute "firmware", "string"
    attribute "networkConnection", "enum", ["unknown", "wifi"]
    attribute "wifi", "string"
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

void refresh() {
  parent.refresh(device.getDataValue("src"))
}

void setValues(final Map deviceInfo) {
  logDebug "setValues(${deviceInfo})"

  if (deviceInfo.containsKey('lastConnectivityCheckError')) {
    if (deviceInfo.lastConnectivityCheckError) {
      log.error "Ring connectivity error: ${deviceInfo.lastConnectivityCheckError}"
    } else {
      log.info "Ring connectivity error resolved."
    }
  }

  if (deviceInfo.networks != null) {
    final Map networks = deviceInfo.networks

    if (deviceInfo.containsKey('networkConnection')) {
      checkChanged("networkConnection", networks.getOrDefault(deviceInfo.networkConnection, [type: "unknown"]).type)
    }

    // Beams bridge appears to only support wifi
    for (final entry in networks.subMap(['wlan0'])) {
      final Map network = entry.value
      final String networkKey = entry.key

      if (network == null) {
        logDebug "Got a null networks key for ${networkKey}. Ignoring"
        continue
      }

      String networkType = network.type

      // Sometimes the type isn't included. Just skip updating things for now
      if (!networkType) {
        logDebug "Could not get network.type for ${networkKey}: ${networks}. Ignoring"
        continue
      }

      String networkName = network.ssid ?: device.getDataValue(networkKey + "Ssid")
      String networkRssi = network.rssi ?: device.getDataValue(networkKey + "Rssi")

      checkChangedDataValue(networkKey + "Type", networkType)
      checkChangedDataValue(networkKey + "Ssid", networkName)
      checkChangedDataValue(networkKey + "Rssi", networkRssi)

      final String fullNetworkStr = networkName + " RSSI " + networkRssi
      logInfo "${networkKey} ${networkType} ${fullNetworkStr}"
      checkChanged(networkType, fullNetworkStr)
      state[networkKey] = fullNetworkStr
    }
  }

  // Update attributes where deviceInfo key is the same as attribute name and no conversion is necessary
  for (final entry in deviceInfo.subMap(["commStatus", "connectionStatus", "firmware"])) {
    checkChanged(entry.key, entry.value)
  }

  // Update state values
  Map stateValues = deviceInfo.subMap(['impulseType', 'lastUpdate'])
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
  device.removeDataValue('nordicFirmwareVersion') // Is an attribute now
  device.removeDataValue('softwareVersion') // Is an attribute now
  device.removeDataValue('version') // Is an attribute now
  state.remove('lastCommTime')
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