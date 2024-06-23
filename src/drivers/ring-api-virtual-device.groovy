/* groovylint-disable Indentation */
/**
 *  Ring API Virtual Device Driver
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

/**
 * This device holds the websocket connection that controls the alarm hub and/or the lighting bridge
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
  definition(name: "Ring API Virtual Device", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch") {
    capability "Actuator"
    capability "Initialize"
    capability "Refresh"

    attribute "mode", "string"
    attribute "websocket", "string"

    command "createDevices"

    command "excludeDevice", [[name: "zid", type: "STRING",
                               description: "Add a zid of a device to skip creating. This will also suppress messages about this device being 'missing'"]]

    command "excludeDeviceRemove", [[name: "zid", type: "STRING",
                                     description: "Remove zid from exclusion list. Run 'createDevices()' after this to create the new device"]]

    command "setMode", [[name: "Set Mode*", type: "ENUM", description: "Set the Location's mode", constraints: ["Disarmed", "Home", "Away"]]]

    command "setDebugImpulseTypeExcludeList", [[name: "Debug Impulse Type Exclude list", type: "STRING", description: "DEBUG: Comma-delimited list of impulse types to skip logging. NOTE: 'command.complete' and 'error.set-info' cannot be excluded here. Use the type-specific filtering instead"]]
    command "setDebugImpulseCommandCompleteExcludeList", [[name: "Debug Impulse Command Complete Exclude list", type: "STRING", description: "DEBUG: Comma-delimited list of command.complete commandType types to skip logging"]]
    command "setDebugImpulseErrorSetInfoExcludeList", [[name: "Debug Impulse Error Set Info Exclude list", type: "STRING", description: "DEBUG: Comma-delimited list of error.set-info commandType types to skip logging"]]
  }

  preferences {
    input name: "suppressMissingDeviceMessages", type: "bool", title: "Suppress log messages for missing/deleted devices. WARNING: This option is deprecated. Use excludeDevice instead", defaultValue: false
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false

    input name: "enableDebugPreferences", type: "bool", title: "Enable extra preferences that help with debugging", defaultValue: false
    if (enableDebugPreferences) {
      input name: "debugImpulseType", type: "bool", title: "DEBUG: Log msgs received that have impulses. Use setDebugImpulseTypeExcludeList to exclude impulse values", defaultValue: false
    }
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

void setMode(mode) {
  logDebug "setMode(${mode})"
  if (state.alarmCapable) {
    log.error "setMode supported from API device. Ring account has alarm present so use alarm modes!"
  }
  else {
    parent.apiRequestModeSet(device.deviceNetworkId, mode.toLowerCase())
  }
}

void setDebugImpulseTypeExcludeList(excludeList) {
  state.debugImpulseTypeExcludeList = excludeList?.replaceAll("\\s", "")?.split(",") as Set
}
void setDebugImpulseCommandCompleteExcludeList(excludeList) {
  state.debugImpulseCommandCompleteExcludeList = excludeList?.replaceAll("\\s", "")?.split(",") as Set
}
void setDebugImpulseErrorSetInfoExcludeList(excludeList) {
  state.debugImpulseErrorSetInfoExcludeList = excludeList?.replaceAll("\\s", "")?.split(",") as Set
}

void installed() { initialize() }

void updated() { initialize() }

void initialize() {
  logDebug "initialize()"

  parentCheck()

  unschedule(silentWebsocketReconnect)

  // Setup watchdog
  unschedule(watchDogChecking) // For compatibility with old installs
  unschedule(websocketWatchdog)
  if (getChildDevices()) {
    runEvery5Minutes(websocketWatchdog)
  }

  // If hubs are defined, then setup the websocket
  if (state.hubs) {
    updateTokensAndReconnectWebSocket()
  } else {
    log.warn "No hubs enabled. Nothing to initialize. Use the Unofficial Ring Connect app to enable hubs"
  }
}

void parentCheck() {
  if (device.parentAppId == null || device.parentDeviceId != null) {
    log.error("This device can only be installed using the Unofficial Ring Connect app. Remove this device and create it through the app. parentAppId=${device.parentAppId}, parentDeviceId=${device.parentDeviceId}")
  }
}

void runCleanup() {
  state.remove("updatedDate")

  device.removeDataValue("device_id")

  // Run on children
  getChildDevices()*.runCleanup()
}

// Creates all devices
void createDevices() {
  createDevicesEnable()
  refresh()
}

void createDevicesEnable() {
  logInfo "Enabling createDevices"
  state.createDevices = true
  // Turn createDevices back off after 60 seconds. This delay provides enough time for all hub refreshes to complete
  runIn(60, createDevicesDisable)
}
void createDevicesDisable() {
  logInfo "Disabling createDevices"
  state.remove('createDevices') // Cleanup state
}

// @todo Should this delete the device as well?
void excludeDevice(zid) {
  if (state.excludeDevices == null) {
    state.excludeDevices = [].toSet()
  }
  state.excludeDevices.add(zid.trim())
  logInfo "Zid ${zid} added to exclusion list"
}

void excludeDeviceRemove(zid) {
  state.excludeDevices?.remove(zid.trim())
  logInfo "Zid ${zid} removed from exclusion list"
}

// @note Should only be called by Ring Connect app
void setEnabledHubDoorbotIds(final Set<Integer> enabledHubDoorbotIds) {
  state.enabledHubDoorbotIds = enabledHubDoorbotIds
  state.remove('createableHubs') // Remove old key in case it still happens to be there
}

// @note Should only be called by Ring Connect app
boolean isHubPresent(final Integer doorbotId) {
  final String zid = state.hubs?.find { it.value == doorbotId }?.key
  return zid != null && getChildByZID(zid) != null
}

// @note Should only be called by Ring Connect app or Virtual Alarm Hub
void updateMode(final String mode) {
  logInfo "Mode set to ${mode.capitalize()}"
  sendEvent(name: "mode", value: mode)
}

// @note Should only be called by Ring Connect app
void updateTickets(final Map ticket) {
  logDebug "updateTickets(${ticket})"

  if (!ticket.host) {
    log.error "updateTickets: Failed to get server from json: ${ticket}"
    return
  }

  // Migrate to listing createableHubs with doorbot id and not the device kind
  if (state.createableHubs != null) {
    log.warn("Migrating old createableHubs list to new")

    state.enabledHubDoorbotIds = (ticket.assets.findAll { Map asset -> state.createableHubs.contains(asset.kind) }*.doorbotId).toSet()

    state.remove('createableHubs')
  }

  final List enabledHubs = ticket.assets.findAll { Map asset -> state.enabledHubDoorbotIds.contains(asset.doorbotId) }

  state.hubs = enabledHubs.collectEntries { Map asset -> [(asset.uuid): asset.doorbotId] }
  state.alarmCapable = enabledHubs.find { Map asset -> ALARM_CAPABLE_KINDS.contains(asset.kind) } != null

  final String wsUrl = "wss://${ticket.host}/ws?authcode=${ticket.ticket}&ack=false"
  logTrace "wsUrl: $wsUrl"
  try {
    interfaces.webSocket.connect(wsUrl)
  }
  catch (e) {
    logDebug "initialize error: ${e.message} ${e}"
    log.error "WebSocket connect failed: ${e}"
    sendEvent(name: "websocket", value: "error")
    reconnectWebSocket()
  }
}

// @return True if the hub is enabled
boolean isEnabledHub(final String zid) {
  // Check if old state is still there. If it is, getting a new ticket from parent (which calls updateTickets) will update things
  if (state.createableHubs != null && state.enabledHubDoorbotIds == null) {
    log.warn("Detected old state. Re-initializing websocket to fix")
    updateTokensAndReconnectWebSocket()
    parent.schedulePeriodicMaintenance()
    return false
  }

  return state.hubs?.containsKey(zid)
}

void refresh(final String zid=null) {
  refreshInternal(zid, false)
}

void refreshInternal(final String zid, boolean quiet) {
  logDebug "refresh(${zid})"

  if (!state.hubs) {
    log.warn "Nothing to refresh. No hubs configured. Use 'Discover Devices' in the main Ring app to add hubs"
    return
  }

  final Set zidsToRefresh = zid == null ? state.hubs.keySet() : [zid]
  for (final String curZid in zidsToRefresh) {
    "${quiet ? "logDebug" : "logInfo"}"("Refreshing hub ${curZid}")
    apiWebsocketRequestRefresh(curZid)
  }

  if (!state.alarmCapable) {
    parent.apiRequestModeGet(device.deviceNetworkId)
  }
}

void refreshInternalDelay(Map data) {
  refreshInternal(data.zid, data.getOrDefault('quiet', false))
}

// For compatibility with old installs
void watchDogChecking() {
  logInfo "Old watchdog function called. Setting up new watchdog."
  initialize()
}

void websocketWatchdog() {
  final Long lastWebSocketMsgTime = state.lastWebSocketMsgTime

  if (lastWebSocketMsgTime == null) {
    return
  }

  final Long timeSinceContact = (now() - lastWebSocketMsgTime) / 1000 / 60 // Time since last msg in minutes

  logDebug "Watchdog checking. It has been ${timeSinceContact} minutes since a websocket msg was received"

  if (timeSinceContact >= 5) {
    logDebug "It has been ${timeSinceContact} minutes since a websocket msg was received"
    if (device.currentValue("websocket") != "connected") {
      log.warn "It has been ${timeSinceContact} minutes since a websocket msg was received. Reconnecting"
      reconnectWebSocket()
    }
    else if (timeSinceContact >= 30) {
      log.error "It has been ${timeSinceContact} minutes since a websocket msg was received, but websocket is still connected. This really shouldn't happen. Forcing a reconnect"
      reconnectWebSocket()
    }
    else {
      logDebug "It has been ${timeSinceContact} minutes since a websocket msg was received, but websocket is still connected. Doing nothing for now"
    }
  }
}

void apiWebsocketRequestRefresh(final String dst) {
  logDebug "apiWebsocketRequestRefresh(${dst})"

  sendWebsocketRequest([dst: dst, msg: "DeviceInfoDocGetList"])
}

void apiWebsocketRequestSetCommand(final String type, final String dst, final String zid, final Map data = [:]) {
  logDebug "apiWebsocketRequestSetCommand(${type}, ${dst}, ${zid}, ${data})"

  apiWebsocketRequestDeviceInfoSet(dst, [
    zid: zid,
    command: [v1: [[
      commandType: type,
      data       : data,
    ]]],
  ])
}

void apiWebsocketRequestSetDevice(final String dst, final String zid, final Map data) {
  logDebug "apiWebsocketRequestSetDevice(${dst}, ${zid}, ${data})"

  apiWebsocketRequestDeviceInfoSet(dst, [zid: zid, device: [v1: data]])
}

void apiWebsocketRequestDeviceInfoSet(final String dst, final Map body) {
  sendWebsocketRequest([
    dst: dst,
    msg: "DeviceInfoSet",
    datatype: "DeviceInfoSetType",
    body: [body], // Body is a List of Maps
  ])
}

// Some device values have to be set indirectly through the security-panel device. The security-panel zid is saved in the hub
void apiWebsocketRequestSetDeviceSecurityPanel(final String src, final Map data) {
  logDebug "apiWebsocketRequestSetDeviceSecurityPanel(${src}, ${data})"
  final String securityPanelZid = getChildByZID(src)?.getDataValue('security-panel-zid')
  apiWebsocketRequestSetDevice(src, securityPanelZid, data)
}

void sendWebsocketRequest(Map msg) {
  logTrace("sendWebsocketRequest(${msg})")

  // Increment sequence number and add it to the request
  state.seq = (state.seq ?: 0) + 1
  msg.seq = state.seq

  final String request = JsonOutput.toJson([channel: "message", msg: msg])
  logTrace "request: ${request}"

  if (device.currentValue("websocket") != "connected") {
    log.error "Cannot send request because socket is not connected: ${request}"
    return
  }

  try {
    interfaces.webSocket.sendMessage(request)
  }
  catch (e) {
    log.error "sendWebsocketRequest exception: ${e} cause: ${ex.getCause()}, request: ${request}"
  }
}

void webSocketStatus(final String status) {
  logDebug "webSocketStatus(${status})"

  if (status == 'status: open') {
    boolean isSilentWebsocketReconnect = false
    final Long silentWebSocketReconnectTime = state.silentWebSocketReconnectTime

    // This is a silent reconnect
    if (silentWebSocketReconnectTime != null) {
      logDebug "WebSocket is open (silent reconnect)"

      // Cleanup state
      state.remove('silentWebSocketReconnectTime')
      unschedule(updateWebsocketAttributeClosedDelay) // Cancel pending update websocket attribute. We reconnected

      Long timeSince = (now() - silentWebSocketReconnectTime).abs() / 1000
      // If it had taken > 10 seconds to do a silent reconnect, then just do normal logging
      if (timeSince <= 10) {
        isSilentWebsocketReconnect = true
        logDebug("Silent reconnect succeeded")
      } else {
        log.warn("Silent reconnect took too long (${timeSince}s > 10s)")
      }
    }

    if (!isSilentWebsocketReconnect) {
      logInfo "WebSocket is open"
      sendEvent(name: "websocket", value: "connected")
    }

    // Refresh after a second delay. This gives the 'websocket' attribute time to be update
    runIn(1, refreshInternalDelay, [data: [zid: null, quiet: true]])

    // Schedule silent reconnect for just under 4 hours from now. Should happen just before Ring automatically disconnects us
    runIn(60 * 60 * 4 - 60 - new Random().nextInt(60), silentWebsocketReconnect)

    state.reconnectDelay = 1
  }
  else if (status == "status: closing") {
    boolean isSilentWebsocketReconnect = false
    Long silentWebSocketReconnectTime = state.silentWebSocketReconnectTime

    if (silentWebSocketReconnectTime != null) {
      Long timeSince = (now() - silentWebSocketReconnectTime) / 1000
      // If it has taken > 10 seconds to do a silent reconnect, then just do normal logging
      isSilentWebsocketReconnect = timeSince <= 10
    }

    if (isSilentWebsocketReconnect) {
      logDebug "WebSocket connection closing (silent reconnect)"

      // Set websocket attribute to closed after 15 seconds. Avoids rapid changes to the websocket attribute
      // Most of the time the reconnection happens very quickly. 15 seconds should be more than enough time
      runIn(15, updateWebsocketAttributeClosedDelay)
    } else {
      log.warn "WebSocket connection closing."
      sendEvent(name: "websocket", value: "closed")
    }
  }
  else if (status.startsWith('failure: ')) {
    log.warn("Failure message from web socket: ${status.substring("failure: ".length())}")
    sendEvent(name: "websocket", value: "failure")
    reconnectWebSocket()
  }
  else {
    log.warn "WebSocket error, reconnecting."
    sendEvent(name: "websocket", value: "error")
    reconnectWebSocket()
  }
}

void updateTokensAndReconnectWebSocket() {
  // This results in updateTickets being called
  parent.apiRequestTickets(device.deviceNetworkId)
  state.seq = 0
}

// Ring disconnects websocket connections every 4 hours (+ ~3 seconds). This causes unnecessary log messages
// To reduce these messages we have a scheduled task that runs 4 hours after a websocket connection was opened
void silentWebsocketReconnect() {
  logDebug("silentWebsocketReconnect")
  state.silentWebSocketReconnectTime = now()

  runInMillis(100, silentWebsocketReconnectCloseSocket)
}

// Called on a slight delay from silentWebsocketReconnect. Allows time for the silentWebSocketReconnectTime variable
// to be set to the state
void silentWebsocketReconnectCloseSocket() {
  interfaces.webSocket.close()

  updateTokensAndReconnectWebSocket()
}

// Sends a websocket event on a delay. Used to avoid rapid changes to the websocket attribute when we do a silent reconnect
void updateWebsocketAttributeClosedDelay() {
  logDebug("updateWebsocketAttributeClosedDelay called")
  sendEvent(name: "websocket", value: "closed")
}

void reconnectWebSocket() {
  unschedule(silentWebsocketReconnect)
  unschedule(silentWebsocketReconnectCloseSocket)

  // First delay is 2 seconds, doubles every time
  state.reconnectDelay = (state.reconnectDelay ?: 1) * 2
  // Don't let delay get too crazy, max it out at 30 minutes
  if (state.reconnectDelay > 1800) {
    state.reconnectDelay = 1800
  }

  // If the socket is unavailable, give it some time before trying to reconnect
  runIn(state.reconnectDelay, updateTokensAndReconnectWebSocket)
}

void uninstalled() {
  childDevices.each { ChildDeviceWrapper dev -> deleteChildDevice(dev.deviceNetworkId) }
}

void parse(String description) {
  final Long parseStart = now()

  //logTrace "parse description: ${description}"

  state.lastWebSocketMsgTime = now()

  def json
  try {
    json = parseJson(description)
  } catch (Exception e) {
    if (description.startsWith("42")) {
      log.warn("Websocket appears to be connected to older Ring API. Reconnecting. If this warning persists, report the issue.")
      /* groovylint-disable-next-line ReturnNullFromCatchBlock */
      updateTokensAndReconnectWebSocket()
    } else {
      log.error("Error parsing json: ${e}")
    }
    return
  }

  if (!(json instanceof Map)) {
    if (description == "2" || description == "3") {
      log.warn("Websocket appears to be connected to older Ring API. Reconnecting. If this warning persists, report the issue.")
      updateTokensAndReconnectWebSocket()
    } else {
      log.error("Error parsing json. Expected to get a map. Msg was: '${description}'")
    }
    return
  }

  boolean gotDeviceInfoDocType = false
  boolean unsupportedMsgtypeReceived = false
  boolean unsupportedDatatypeReceived = false

  final String channel = json.channel
  final Map jsonMsg = json.msg
  final String msgtype = jsonMsg?.msg
  final String datatype = jsonMsg?.datatype

  if (channel == "DataUpdate") {
    if (msgtype == "DataUpdate") {
      if (datatype == "DeviceInfoDocType") {
        gotDeviceInfoDocType = true
      }
      else if (datatype == "HubDisconnectionEventType") {
        // Appears to be sent when hub is offline
        logDebug "Ignoring a DataUpdate.DataUpdate.HubDisconnectionEventType"
      }
      else if (datatype == "SystemStatusType") {
        for (final Map systemStatus in jsonMsg.body) {
          final String statusString = systemStatus.statusString

          if (statusString == "device.find.configuring.begin") {
            logDebug "Ring Alarm hub is starting to configure a new device"
          }
          else if (statusString == "device.find.configuring.finished") {
            log.info "Ring Alarm hub has finished configuring a new device. To add this device to hubitat, run 'createDevices' in the 'Ring API Virtual Device'"
          }
          else if (statusString == "device.find.listening") {
            logDebug "Ring Alarm hub is listening for new devices"
          }
          else if (statusString == "device.find.initialize") {
            logDebug "Ring Alarm hub is getting ready to initialize a new device"
          }
          else if (statusString.startsWith("device.find.error")) {
            logDebug "Ring alarm hub encountered a device.find.error error while configuring a new device"
          }
          else if (statusString == "device.remove.initialize") {
            logDebug "Ring Alarm hub is getting ready to remove a device"
          }
          else if (statusString == "device.remove.listening") {
            logDebug "Ring Alarm hub is listening for a device to remove"
          }
          else if (statusString == "device.remove.done") {
            logDebug "Ring Alarm hub finished removing a device"
          }
          else if (statusString.startsWith("device.remove.error")) {
            logDebug "Ring alarm hub encountered a device.remove.error error while removing a new device"
          }
          else {
            log.warn("Got an unsupported DataUpdate.SystemStatusType statusString '${statusString}'")
          }
        }
      }
      else if (datatype == "RemovedDeviceType") {
        for (final Map data in jsonMsg.body) {
          ChildDeviceWrapper child = getChildByZID(data.zid)
          if (child != null) {
            log.warn("The ring device ${child} with zid ${data.zid} was removed. You may want to delete the device in hubitat as well")
          } else {
            logTrace("Didn't find child device with ${data.zid}. Device may have never been created")
          }
        }
      }
      else if (datatype == "DeviceAddDocType") {
        for (final entry in parseDeviceInfoDocType(jsonMsg, jsonMsg.context.assetId, jsonMsg.context.assetKind)) {
          log.info "A new ring device '${entry.value.name}' of type ${entry.value.deviceType} with zid ${entry.value.zid} was added"
        }
      }
      else {
        unsupportedDatatypeReceived = true
      }
    }
    else if (msgtype == "Passthru") {
      if (datatype == "PassthruType") {
        if (isEnabledHub(jsonMsg.context.assetId)) {
          handlePassThruType(jsonMsg)
        }
      } else {
        unsupportedDatatypeReceived = true
      }
    }
    else if (msgtype == "SessionInfo") {
      if (datatype == "SessionInfoType") {
        handleSessionInfoType(jsonMsg)
      } else {
        unsupportedDatatypeReceived = true
      }
    }
    else if (msgtype == "SubscriptionTopicsInfo") {
      if (datatype == "SubscriptionTopicType") {
        logDebug "Ignoring a DataUpdate.SubscriptionTopicsInfo.SubscriptionTopicType"
      } else {
        unsupportedDatatypeReceived = true
      }
    }
    else if (msgtype == "DisconnectClient") {
      if (datatype == "DisconnectClientType") {
        logDebug "Ignoring a DataUpdate.DisconnectClient.DisconnectClientType"
      } else {
        unsupportedDatatypeReceived = true
      }
    }
    else {
      unsupportedMsgtypeReceived = true
    }
  }
  else if (channel == "message") {
    if (msgtype == "DeviceInfoDocGetList") {
      if (datatype == "DeviceInfoDocType") {
        gotDeviceInfoDocType = true
      }
      else if (datatype == null && jsonMsg.context?.uiConnection != null) {
        logDebug "Ignoring a message.DeviceInfoDocGetList with no datatype that has the context.uiConnection key"
      }
      else if (datatype == null && !jsonMsg.containsKey('body') && jsonMsg.status == -66) {
        logDebug "Ignoring a message.DeviceInfoDocGetList with no datatype and no body with a status of -66"
      }
      else {
        unsupportedDatatypeReceived = true
      }
    }
    else if (msgtype == "DeviceInfoSet") {
      // @todo The seq of these messages matches the seq of the command that was sent. This could be used to log when a specific command succeeds
      if (jsonMsg.status == 0) {
        logTrace "DeviceInfoSet with seq ${jsonMsg.seq} succeeded."
      }
      else {
        log.warn "I think a DeviceInfoSet failed? Please report this to the developers so support can be added."
        log.warn description
      }
    }
    else if (msgtype == "RoomGetList") {
      logDebug "Ignoring a message.RoomGetList"
    }
    else {
      unsupportedMsgtypeReceived = true
    }
  }
  else if (channel == "disconnect") {
    logInfo "Websocket timeout hit. Reconnecting..."
    interfaces.webSocket.close()
    sendEvent(name: "websocket", value: "disconnect")
    // We don't disconnect fast enough because we still get a failure from the status method after closing. Because
    // of that failure message and reconnect there we do not need to reconnect here.
  }
  else {
    log.warn "Received unsupported channel ${channel}. Please report this to the developers so support can be added."
    log.warn description
  }

  if (unsupportedMsgtypeReceived) {
    log.warn "Received unsupported ${msgtype} on channel ${channel}. Please report this to the developers so support can be added."
    log.warn description
  }
  if (unsupportedDatatypeReceived) {
    log.warn "Received unsupported ${msgtype}.${datatype} on channel ${channel}. Please report this to the developers so support can be added."
    log.warn description
  }

  if (gotDeviceInfoDocType) {
    final String assetId = jsonMsg.context.assetId

    // Only parse events for hubs that were selected in the app
    if (isEnabledHub(assetId)) {
      final String assetKind = jsonMsg.context.assetKind
      final String hubZid = jsonMsg.src
      List<String> affectedDevices = []

      final Long parseTimeStart = now()
      final Map<String, Map> deviceInfos = parseDeviceInfoDocType(jsonMsg, assetId, assetKind)
      final Long parseTime = now() - parseTimeStart

      final Long sendUpdateStart = now()

      // If the hub for these device infos doesn't exist then create it
      if (!getChildByZID(assetId)) {
        createChild(hubZid, [deviceType: assetKind, zid: assetId])
        createDevicesEnable() // Create child devices after creating the hub
      }

      final boolean createDevices = state.createDevices && msgtype == "DeviceInfoDocGetList"

      for (final entry in deviceInfos) {
        final String zid = entry.key
        final Map deviceInfo = entry.value

        if (state.excludeDevices?.contains(zid)) {
          logDebug("Skipping update for device ${zid} because it is excluded")
          continue
        }

        affectedDevices.add(deviceInfo.name)

        if (createDevices) {
          createChild(hubZid, deviceInfo)
        }

        sendUpdate(assetKind, hubZid, deviceInfo)
      }

      final Long sendUpdateTime = now() - sendUpdateStart

      logDebug "Handled msg for '${affectedDevices}' in ${now() - parseStart}ms (parseTime=${parseTime}ms, sendUpdate=${sendUpdateTime}ms)"
      return
    }
  }

  // @todo Log if it takes a long time to parse a message
  logDebug "Handled websocket msg in ${now() - parseStart}ms"
}

// Keys to copy from general.v2 for a subset of devices
// @note lastCommTime seems to be useless for hub.redsky. It's always zero
@Field final static List generalV2Keys = [
  'acStatus', 'batteryLevel', 'commStatus', 'componentDevices', 'fingerprint', 'lastCommTime', 'lastUpdate',
  'manufacturerName', 'nextExpectedWakeup', 'serialNumber'
].asImmutable()

// Keys to copy from device.v1 for all devices
// @note For some devices, testMode is boolean, for some it is a string (sensor.glassbreak)
@Field final static List deviceV1Keys = [
  'alarmInfo', 'batteryBackup', 'co', 'faulted', 'flood', 'freeze', 'groupMembers', 'lastConnectivityCheckError',
  'lastNetworkLatencyEvent', 'locked', 'networks', 'networkConnection', 'powerSave', 'sensitivity', 'siren', 'status',
  'smoke', 'testMode', 'transitionDelayEndTimestamp'
].asImmutable()

/**
 * Passes all parsed passThrus to sendPassthru
 * @param json PassthruType msg from websocket
 */
void handlePassThruType(final Map json) {
  logDebug "handlePassThruType(json)"
  logTrace("Parsing ${json.body.size()} PassThru msg parts")

  for (final Map deviceJson in json.body) {
    final String type = deviceJson.type

    Map curDeviceInfo = deviceJson.data.subMap(['percent', 'timeLeft', 'total', 'transition'])

    if (type == 'firmware-update.percent-complete') {
      curDeviceInfo.zid = deviceJson.zid
    }
    else if (type == 'security-panel.countdown' || type == 'speaker-event' || type == 'halo.cloud' || type == 'halo-stats.latency') {
      curDeviceInfo.zid = json.context.assetId
    }
    else {
      log.warn("Received unsupported Passthru type '${type}'. Please report this to the developers so support can be added. ${JsonOutput.toJson(json)}")
      continue
    }

    if (state.excludeDevices?.contains(curDeviceInfo.zid)) {
      logDebug("Skipping passThru for device ${curDeviceInfo.zid} because it is excluded")
      continue
    }

    sendPassthru(curDeviceInfo)
  }
}

/**
 * Passes all parsed sessionInfos to sendSessionInfo
 * @param json SessionInfoType msg from websocket
 */
void handleSessionInfoType(final Map json) {
  logDebug "handleSessionInfoType(json)"
  logTrace("Parsing ${json.body.size()} SessionInfo msg parts")

  for (final Map deviceJson in json.body) {
    final String zid = deviceJson.assetUuid

    if (isEnabledHub(zid)) {
      // Not checking state.excludeDevices here because SessionInfo msgs are only sent for hubs
      sendSessionInfo([connectionStatus: deviceJson.connectionStatus, zid: zid])
    }
  }
}

/**
 * @param json DeviceInfoDocType msg from websocket
 * @param assetId Zid of the associated base station
 * @return Map where the key is the zid of the device and the value is the deviceInfo
 */
Map<String, Map> parseDeviceInfoDocType(final Map json, final String assetId, final String assetKind) {
  logDebug "parseDeviceInfoDocType(json)"
  //logTrace "json: ${JsonOutput.toJson(json)}"

  final boolean debugImpulses = enableDebugPreferences && debugImpulseType
  boolean impulseExcludesInitialized = false

  // These will be lazily initialized on first use
  Set<String> impulseTypeExcludeList = null
  Set<String> impulseCommandCompleteExcludeList = null
  Set<String> impulseErrorSetInfoExcludeList =  null

  Map<String, Map> deviceInfos = [:].withDefault { [:] }

  final List<Map> jsonBody = json.body

  for (final Map deviceJson in jsonBody) {
    Map curDeviceInfo = [:]

    final Map tmpGeneral = deviceJson.general?.v2

    if (!tmpGeneral) {
      log.error("parseDeviceInfoDocType Got a deviceJson without a general.v2 key: ${JsonOutput.toJson(deviceJson)}")

      if (deviceJson.general?.v1) {
        log.error("got a deviceJson.general.v1! Please let the developers know this value is still being used: ${JsonOutput.toJson(deviceJson)}")
      }
      continue
    }

    final String deviceType = tmpGeneral.deviceType

    if (deviceType == null) {
      log.error "parseDeviceInfoDocType Got a deviceJson with a null deviceType: ${JsonOutput.toJson(deviceJson)}"
      continue
    }

    // Only include these device types if they have an impulse
    if (IMPULSE_ONLY_DEVICE_TYPES.contains(deviceType)) {
      if (!deviceJson.impulse?.v1) {
        logDebug "parseDeviceInfoDocType Skipping impulse only deviceType ${deviceType} because it doesn't have an impulse"
        continue
      }
    }

    final boolean isPartialDeviceType = ALARM_HUB_PARTIAL_DEVICE_TYPES.contains(deviceType)

    // Get basic keys
    if (HUB_COMPOSITE_DEVICES.contains(deviceType)) {
      curDeviceInfo.deviceType = assetKind
      curDeviceInfo.zid = assetId // Use hub zid instead of child
      curDeviceInfo[deviceType + '-zid'] = tmpGeneral.zid // Pass along the child zid
    } else {
      curDeviceInfo << tmpGeneral.subMap(['deviceType', 'zid'])

      // context.v1.deviceName key seems to only show up for full refreshes, otherwise value is at general.v2.name
      curDeviceInfo.name = tmpGeneral.name ?: deviceJson.context?.v1?.deviceName
    }

    /**
     * Begin parse deviceJson impulse.v1
     * @note impulse.v1 is parsed early because we skip heartbeat messages
     */

    final List tmpImpulses = deviceJson.impulse?.v1

    if (tmpImpulses) {
      boolean gotHeartbeat = false

      Map impulses = [:]
      for (final Map impulse in tmpImpulses) {
        final String impulseType = impulse.impulseType
        if (debugImpulses) {
          if (!impulseExcludesInitialized) {
            impulseExcludesInitialized = true
            impulseTypeExcludeList = state.debugImpulseTypeExcludeList
            impulseCommandCompleteExcludeList = state.debugImpulseCommandCompleteExcludeList
            impulseErrorSetInfoExcludeList = state.debugImpulseErrorSetInfoExcludeList
          }

          if (impulseType == 'command.complete') {
            if (!impulseCommandCompleteExcludeList?.contains(impulse.data?.commandType)) {
              log.debug("Special Debug: Got impulse command.complete (${impulse.data?.commandType}): ${JsonOutput.toJson(json)}")
            }
          }
          else if (impulseType == 'error.set-info') {
            if (!impulseErrorSetInfoExcludeList?.contains(impulse.data?.info?.command?.v1?.commandType)) {
              log.debug("Special Debug: Got impulse error.set-info (${impulse.data.info.command.v1.commandType}): ${JsonOutput.toJson(json)}")
            }
          }
          else if (!impulseTypeExcludeList?.contains(impulseType)) {
            log.debug("Special Debug: Got impulse ${impulseType}: ${JsonOutput.toJson(json)}")
          }
        }

        // Sometimes heartbeats come with other impulses, so keep building
        if (impulseType == "comm.heartbeat") {
          gotHeartbeat = true
        }
        else {
          impulses[impulseType] = impulse.data
        }
      }

      // Heartbeats don't have any new information. We can safely skip them. If for some weird reason an attribute
      // was got an update in a heartbeat, that value will eventually get set when we have to do a websocket reconnect
      if (gotHeartbeat) {
        if (!impulses) {
          logDebug("parseDeviceInfoDocType Got a heartbeat for '${curDeviceInfo.name}'. Skipping")
          continue
        }

        logDebug("parseDeviceInfoDocType Got a heartbeat for '${curDeviceInfo.name}'. Not skipping because msg has other impulses")
      }

      curDeviceInfo.impulses = impulses
      curDeviceInfo.impulseType = impulses.keySet().first()
    }

    // Hubs get information from multiple deviceTypes. Exclude some of the values to avoid incorrect values getting set
    if (!isPartialDeviceType) {
      /**
       * Begin parse deviceJson general.v2
       */

      curDeviceInfo << tmpGeneral.subMap(generalV2Keys)

      if (tmpGeneral.batteryStatus && BATTERY_STATUS_DEVICE_TYPES.contains(deviceType)) {
        // @note batteryStatus is also in context.v1. It seems to only do this when the value is not updated. Just use value from general.v2
        curDeviceInfo.batteryStatus = tmpGeneral.batteryStatus
      }

      final String tamperStatus = tmpGeneral.tamperStatus
      if (tamperStatus != null) {
        curDeviceInfo.tamper = tamperStatus == "tamper" ? "detected" : "clear"
      }

      /**
       * Begin parse deviceJson adapter.v1
       */

      // adapter.v1 only contains changed values when msgtype == "DataUpdate". When msgtype == "DeviceInfoDocGetList" it contains all values
      // context.v1.adapter.v1. When present, seems to contain all values
      final Map tmpAdapter = deviceJson.adapter?.v1

      if (tmpAdapter) {
        curDeviceInfo << tmpAdapter.subMap(['rfChannel', 'rssi', 'signalStrength'])

        if (tmpAdapter.firmwareVersion) {
          curDeviceInfo.firmware = tmpAdapter.firmwareVersion
        }

        final Map fingerprint = tmpAdapter?.fingerprint
        if (fingerprint) {
          if (fingerprint.firmware?.version) {
            curDeviceInfo.firmware = fingerprint.firmware.version.toString() + '.' + fingerprint.firmware.subversion.toString()
          }
          curDeviceInfo.hardwareVersion = fingerprint.hardwareVersion
        }
      }
    }

    /**
     * Begin parse deviceJson pending
     */

    final Map tmpPending = deviceJson.pending
    if (tmpPending) {
      curDeviceInfo.pending = [:]

      if (tmpPending.device?.v1) {
        curDeviceInfo.pending << tmpPending.device.v1.subMap(['sensitivity'])
      }

      if (tmpPending.command?.v1) {
        curDeviceInfo.pending.commands = tmpPending.command.v1
      }
    }

    /**
     * Begin parse deviceJson device.v1
     */

    // device.v1 only contains changed values when msgtype == "DataUpdate". When msgtype == "DeviceInfoDocGetList" it contains all values.
    // When present, context.v1.device.v1 seems to contain all values
    // @note Some keys, like alarminfo and transitionDelayEndTimestamp, are set to null on disable, but in subsequent
    //       message are omitted entirely. This could mean that there are some scenarios where
    //       updates to those values are missed entirely. We may need to handle this
    final Map deviceV1 = deviceJson.device?.v1

    if (deviceV1) {
      final boolean isSecurityPanel = isPartialDeviceType && deviceType == 'security-panel'

      curDeviceInfo << deviceV1.subMap(deviceV1Keys)
      if (!isSecurityPanel) {
        curDeviceInfo << deviceV1.subMap(['chirps'])
      }

      // Hubs get information from multiple deviceTypes. Exclude some of the values to avoid incorrect values getting set
      if (!isPartialDeviceType) {
        for (final entry in deviceV1.subMap(['brightness', 'level', 'volume'])) {
          curDeviceInfo[entry.key] = (entry.value * 100).toInteger()
        }
      }
      else if (isSecurityPanel) {
        // security-panel stores some configuration for other devices. Break those out here
        final String mode = deviceV1.mode
        if (mode != null) {
          curDeviceInfo.mode = mode

          // device.v1.devices is only included when mode is 'some' or 'all', so fall back to context.v1.device.v1.devices
          final Map deviceV1Devices = deviceV1.devices ?: deviceJson.context?.v1?.device?.v1?.devices

          if (deviceV1Devices == null) {
            log.error("Failed to get security-panel device.v1.devices: ${JsonOutput.toJson(deviceJson)}")
          }
          else {
            // When mode changes, get values from devices to update bypassed, deviceActive, and sensorReporting
            for (final entry in deviceV1Devices) {
              Map extraDeviceInfo = [zid: entry.key]

              if (entry.value.bypassed != null) {
                extraDeviceInfo.bypassed = entry.value.bypassed.toString()
              }

              if (entry.value.modes != null) {
                // If the right mode key value is not null, then this device is active
                extraDeviceInfo.deviceActive = entry.value.modes[mode] != null
              }

              if (entry.value.sensorReporting != null) {
                extraDeviceInfo.sensorReporting = entry.value.sensorReporting
              }

              // Merge these values into any existing deviceInfos
              deviceInfos[entry.key] << extraDeviceInfo
            }
          }
        }

        for (final entry in deviceV1.chirps) {
          // Merge these values into any existing deviceInfos
          deviceInfos[entry.key] << [
            chirp: entry.value.type,
            zid: entry.key
          ]
        }
      }

      final Object version = deviceV1.version
      if (version != null) {
        if (version instanceof Map) {
          if (deviceType == 'adapter.ringnet') {
            // firmwareVersion is also stored in adapter.v1 for switch.multilevel.beams
            if (version?.firmwareVersion != null) {
              curDeviceInfo.firmware = version.firmwareVersion
            }
          } else {
            if (version?.buildNumber != null && version?.softwareVersion != null) {
              curDeviceInfo.firmware = version.softwareVersion + ' (' + version.buildNumber + ')'
            }
          }
        } else {
          curDeviceInfo.firmware = version
        }
      }

      final String motionStatus = deviceV1.motionStatus
      if (motionStatus != null) {
        curDeviceInfo.motion = motionStatus == "clear" ? "inactive" : "active"
      }

      final Boolean on = deviceV1.on
      if (on != null) {
        curDeviceInfo.switch = on ? "on" : "off"
      }
    }

    // Merge curDeviceInfo into any existing deviceInfos
    deviceInfos[curDeviceInfo.zid] << curDeviceInfo
  }

  logTrace "Parsed ${jsonBody.size()} DeviceInfoDocType msg parts"

  return deviceInfos
}

void createChild(final String hubZid, final Map deviceInfo) {
  if (getChildByZID(deviceInfo.zid)) {
    logDebug "Not creating device for zid ${deviceInfo.zid} because it already exists"
  }
  else {
    final String deviceType = deviceInfo.deviceType

    final String mappedDeviceTypeName = DEVICE_TYPE_NAMES[deviceType]
    if (mappedDeviceTypeName == null) {
      log.error "Cannot create a ${deviceType} device because it is unsupported"
      return
    }

    final String formattedDNI = getFormattedDNI(deviceInfo.zid)

    try {
      ChildDeviceWrapper d = addChildDevice("ring-hubitat-codahq", mappedDeviceTypeName, formattedDNI,
                                            [label: deviceInfo.name ?: mappedDeviceTypeName])
      setInitialDeviceDataValues(d, deviceInfo.deviceType, hubZid, deviceInfo)

      log.info "Created a ${mappedDeviceTypeName} (${deviceType}) with dni: ${formattedDNI}"
    }
    catch (e) {
      log.info "Error creating ${mappedDeviceTypeName} (${deviceType}) with dni: ${formattedDNI}: ${e}"
      if (e.toString().replace(mappedDeviceTypeName, "") ==
        "com.hubitat.app.exception.UnknownDeviceTypeException: Device type '' in namespace 'ring-hubitat-codahq' not found") {
        log.error '<b style="color: red;">The "' + mappedDeviceTypeName + '" driver was not found and needs to be installed. NOTE: If you installed this using HPM, you can fix this by going to "Update" in HPM and selecting the optional drivers you need.</b>'
      }
    }
  }
}

void setInitialDeviceDataValues(ChildDeviceWrapper d, final String type, final String hubZid, final Map deviceInfo) {
  d.updateDataValue("zid",  deviceInfo.zid)
  d.updateDataValue("fingerprint", deviceInfo.fingerprint ?: "N/A")
  d.updateDataValue("hardwareVersion", deviceInfo.hardwareVersion?.toString() ?: "N/A")
  d.updateDataValue("manufacturer", deviceInfo.manufacturerName ?: "Ring")
  d.updateDataValue("serial", deviceInfo.serialNumber ?: "N/A")
  d.updateDataValue("type", type)
  d.updateDataValue("src", hubZid)
}

void sendUpdate(final String assetKind, final String hubZid, final Map deviceInfo) {
  logDebug "sendUpdate for zid ${deviceInfo.zid}"

  final String deviceType = deviceInfo.deviceType

  ChildDeviceWrapper d = getChildByZID(deviceInfo.zid)
  if (d) {
    d.setValues(deviceInfo)

    // Old versions set device data fields incorrectly. Hubitat v2.2.4 appears to clean up
    // the bad data fields. Reproduce the necessary fields
    if (d.getDataValue('zid') == null) {
      log.warn "Device ${d} is missing 'zid' data field. Fixing..."
      setInitialDeviceDataValues(d, deviceInfo.deviceType, hubZid, deviceInfo)
    }
    else if (ALARM_CAPABLE_KINDS.contains(deviceType)) {
      // The fix above was applied to the hub.* datatypes with incorrect values. Check for a mismatch and update as necessary
      final String type = d.getDataValue('type')
      if (type != deviceType) {
        log.warn "Device ${d} has incorrect 'type' data field '${type}'. Fixing..."
        setInitialDeviceDataValues(d, assetKind, hubZid, deviceInfo)
      }
    }
  } else if (!suppressMissingDeviceMessages) {
    if (DEVICE_TYPE_NAMES.containsKey(deviceType)) {
      logMissingDeviceMsg("sendUpdate", deviceInfo.zid, deviceInfo.name)
    } else {
      log.warn "Device ${deviceInfo.name} of type ${deviceType} with zid ${deviceInfo.zid} is not currently supported"
      log.warn(JsonOutput.toJson(deviceInfo))
    }
  }
}

void sendPassthru(final Map deviceInfo) {
  logDebug "sendPassthru for zid ${deviceInfo.zid}"

  ChildDeviceWrapper d = getChildByZID(deviceInfo.zid)
  if (d) {
    d.setPassthruValues(deviceInfo)
  } else {
    logMissingDeviceMsg("passthru", deviceInfo.zid)
  }
}

void sendSessionInfo(final Map deviceInfo) {
  logDebug "sendSessionInfo for zid ${deviceInfo.zid}"

  ChildDeviceWrapper d = getChildByZID(deviceInfo.zid)
  if (d) {
    d.setValues(deviceInfo)
  } else {
    logMissingDeviceMsg("sessionInfo", deviceInfo.zid)
  }
}

void logMissingDeviceMsg(final String source, final String zid, final String name = null) {
  if (!suppressMissingDeviceMessages) {
    log.warn "Couldn't find device ${zid} with name '${name}' for ${source} (Run createDevices() to create missing devices, or use excludeDevice(${zid}) to suppress this warning)"
  }
}

String getFormattedDNI(final String id) { return 'RING||' + id }

ChildDeviceWrapper getChildByZID(final String zid) {
  return getChildDevice(getFormattedDNI(zid))
}

@Field final static Set ALARM_CAPABLE_KINDS = [
  "base_station_k1",
  "base_station_v1",
].toSet().asImmutable()

@Field final static Map DEVICE_TYPE_NAMES = [
  // Alarm devices
  "sensor.contact": "Ring Virtual Contact Sensor",
  "sensor.tilt": "Ring Virtual Contact Sensor",
  "sensor.zone": "Ring Virtual Contact Sensor",
  "sensor.motion": "Ring Virtual Motion Sensor",
  "sensor.glassbreak": "Ring Virtual Glass Break Sensor",
  "sensor.flood-freeze": "Ring Virtual Alarm Flood & Freeze Sensor",
  "listener.smoke-co": "Ring Virtual Alarm Smoke & CO Listener",
  "alarm.co": "Ring Virtual CO Alarm",
  "alarm.smoke": "Ring Virtual Smoke Alarm",
  "range-extender.zwave": "Ring Virtual Alarm Range Extender",
  "lock": "Ring Virtual Lock",
  "security-keypad": "Ring Virtual Keypad",
  "security-panic": "Ring Virtual Panic Button",
  "base_station_k1": "Ring Virtual Alarm Hub",
  "base_station_v1": "Ring Virtual Alarm Hub",
  "siren": "Ring Virtual Siren",
  "siren.outdoor-strobe": "Ring Virtual Siren",
  "switch": "Ring Virtual Switch",
  "bridge.flatline": "Ring Virtual Retrofit Alarm Kit",
  // Beams devices
  "switch.multilevel.beams": "Ring Virtual Beams Light",
  "motion-sensor.beams": "Ring Virtual Beams Motion Sensor",
  "group.light-group.beams": "Ring Virtual Beams Group",
  "beams_bridge_v1": "Ring Virtual Beams Bridge",
  "switch.transformer.beams": "Ring Virtual Beams Light",
].asImmutable()

// The alarm hub and beams bridge are made up of multiple composite devices. Messages for these device types will be
// rolled up into the alarm hub
@Field final static Set HUB_COMPOSITE_DEVICES = [
  "access-code.vault",
  "access-code",
  "adapter.ringnet",
  "adapter.zigbee",
  "adapter.zwave",
  "security-panel",
  "hub.kili",
  "hub.redsky",
].toSet().asImmutable()

@Field final static Set IMPULSE_ONLY_DEVICE_TYPES = [
  "access-code",
  "access-code.vault",
  "adapter.zigbee",
  "adapter.zwave",
].toSet().asImmutable()

@Field final static Set BATTERY_STATUS_DEVICE_TYPES = [
  "hub.kili",
  "hub.redsky",
  "range-extender.zwave",
  "security-keypad"
].toSet().asImmutable()

@Field final static Set ALARM_HUB_PARTIAL_DEVICE_TYPES = [
  "access-code",
  "access-code.vault",
  "adapter.zigbee",
  "adapter.zwave",
  "security-panel"
].toSet().asImmutable()