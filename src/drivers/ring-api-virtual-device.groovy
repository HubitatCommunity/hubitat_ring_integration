/**
 *  Ring API Virtual Device Driver
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

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import hubitat.helper.InterfaceUtils
import groovy.transform.Field

metadata {
  definition(name: "Ring API Virtual Device", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    description: "This device holds the websocket connection that controls the alarm hub and/or the lighting bridge",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-api-virtual-device.groovy") {
    capability "Actuator"
    capability "Initialize"
    capability "Refresh"

    attribute "mode", "string"
    attribute "websocket", "string"

    command "createDevices", [[name: "zid", type: "STRING", description: "Optionally create only the device with the provided zid. Leave blank to create all devices"]]

    command "websocketWatchdog", []

    //command "testCommand"
    command "setMode", [[name: "Set Mode*", type: "ENUM", description: "Set the Location's mode", constraints: ["Disarmed", "Home", "Away"]]]
  }

  preferences {
    input name: "suppressMissingDeviceMessages", type: "bool", title: "Suppress log messages for missing/deleted devices", defaultValue: false
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
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
}

def testCommand() {
  //this functionality doesn't work right now.  don't use it.  debug/development in progress

  //def debugDst = state.hubs.first().zid
  //simpleRequest("manager", [dst: debugDst])
  //simpleRequest("finddev", [dst: debugDst, adapterId: "zwave"])
  //simpleRequest("sirenon", [dst: debugDst])

  //parent.simpleRequest("master-key", [dni: device.deviceNetworkId, code: "5555", name: "Guest"])

  //def zeroEpoch = Calendar.getInstance(TimeZone.getTimeZone('GMT'))
  //zeroEpoch.setTimeInMillis(0)
  //println zeroEpoch.format("dd-MMM-yyyy HH:mm:ss zzz")
  //https://currentmillis.com/

}

def setMode(mode) {
  logDebug "setMode(${mode})"
  if (!state.alarmCapable) {
    //TODO: if we ever get a this pushed to us then only allow to change it when it's different
    parent.simpleRequest("mode-set", [mode: mode.toLowerCase(), dni: device.deviceNetworkId])
  }
  else {
    def msg = "Not supported from API device. Ring account has alarm present so use alarm modes!"
    log.error msg
    sendEvent(name: "Invalid Command", value: msg)
  }
}

def initialize() {
  logDebug "initialize()"

  initializeWatchdog()

  if (isWebSocketCapable()) {
    parent.simpleRequest("tickets", [dni: device.deviceNetworkId])
    state.seq = 0
  }
  else {
    log.warn "Nothing to initialize..."
  }
}

def initializeWatchdog() {
  unschedule(watchDogChecking) // For compatibility with old installs
  unschedule(websocketWatchdog)
  if ((getChildDevices()?.size() ?: 0) != 0) {
    runEvery5Minutes(websocketWatchdog)
  }
}

def updated() {
  state.remove("updatedDate")
  initialize()
}

/**
 * Creates device with provided zid. If no zid is provided, all devices all created
 */
def createDevices(zid) {
  logDebug "createDevices(${zid})"
  state.createDevices = true
  if (zid != null) {
    state.createDevicesZid = zid
  }

  refresh()
}

// @note Should only be called by Ring Connect app
void setAlarmCapable(boolean alarmCapable) {
  state.alarmCapable = alarmCapable
}

// @note Should only be called by Ring Connect app
void setCreateableHubs(final HashSet<String> createableHubs) {
  state.createableHubs = createableHubs
}

HashSet<String> getCreateableHubs() {
  final def tmp = state.createableHubs
  if (tmp instanceof HashSet) {
    return tmp
  }
  // Old versions stored createableHubs as a List. Convert it to a HashSet
  state.createableHubs = tmp as HashSet<String>
  return state.createableHubs
}

boolean isWebSocketCapable() {
  return state.createableHubs != null && state.createableHubs.size() > 0
}

boolean isTypePresent(kind) {
  return getChildDevices()?.find {
    it.getDataValue("type") == kind
  } != null
}

void refresh(final String zid=null) {
  refreshInternal(zid, false)
}

void refreshQuiet(final String zid=null) {
  refreshInternal(zid, true)
}

void refreshInternal(final String zid=null, boolean quiet=false) {
  logDebug "refresh(${zid})"

  for (final Map hub in state.hubs) {
    if (zid == null || hub.zid == zid) {
      if (quiet) {
        logDebug "Refreshing hub ${hub.zid} with kind ${hub.kind}"
      } else {
        logInfo "Refreshing hub ${hub.zid} with kind ${hub.kind}"
      }
      simpleRequest("refresh", [dst: hub.zid])
    }
  }
  if (!state.alarmCapable) {
    parent.simpleRequest("mode-get", [mode: "disarmed", dni: device.deviceNetworkId])
  }
}

// For compatibility with old installs
def watchDogChecking() {
    logInfo "Old watchdog function called. Setting up new watchdog."
    initializeWatchdog()
}

def websocketWatchdog() {
  if (state.lastWebSocketMsgTime == null) {
    return
  }

  logTrace "websocketWatchdog(${watchDogInterval}) now:${now()} state.lastWebSocketMsgTime:${state.lastWebSocketMsgTime }"

  Long timeSinceContact = (now() - state.lastWebSocketMsgTime).abs() / 1000 / 60 // Time since last msg in minutes

  logDebug "Watchdog checking started. Time since last websocket msg: ${timeSinceContact} minutes"

  if (timeSinceContact >= 5) {
    log.warn "Watchdog checking interval exceeded"
    if (!device.currentValue("websocket").equals("connected")) {
      reconnectWebSocket()
    }
  }
}

void childParse(final String type, final Map params = [:]) {
  logDebug "childParse(${type}, params)"
  logTrace "params ${params}"

  if (type == "ws-connect" || type == "tickets") {
    initWebsocket(params.msg)
    //42["message",{"msg":"RoomGetList","dst":[HUB_ZID],"seq":1}]
  }
  else if (type == "master-key") {
    logTrace "master-key ${params.msg}"
    //simpleRequest("setcode", [code: params.code, dst: "[HUB_ZID]" /*params.dst*/, master_key: params.msg.masterkey])
    //simpleRequest("adduser", [code: params.name, dst: "[HUB_ZID]" /*params.dst*/])
    //simpleRequest("enableuser", [code: params.name, dst: "[HUB_ZID]" /*params.dst*/, acess_code_zid: "[ACCESS_CODE_ZID]"])
  }
  else if (type == "mode-set" || type == "mode-get") {
    logTrace "mode: ${params.msg.mode}"
    logInfo "Mode set to ${params.msg.mode.capitalize()}"
    sendEvent(name: "mode", value: params.msg.mode)
  }
  else {
    log.error "Unhandled type ${type}"
  }
}

void simpleRequest(final String type, final Map params = [:]) {
  logDebug "simpleRequest(${type})"
  logTrace "params: ${params}"

  if (isParentRequest(type)) {
    logTrace "parent request: $type"
    parent.simpleRequest(type, [dni: params.dni, type: params.type])
  }
  else {
    def request = JsonOutput.toJson(getRequests(type, params))
    logTrace "request: ${request}"

    if (request == null || type == "setcode" || type == "adduser" || type == "enableuser") {
      return
    }

    try {
      sendMsg(MESSAGE_PREFIX + request)
    }
    catch (e) {
      log.warn "exception: ${e} cause: ${ex.getCause()}"
      log.warn "request type: ${type} request: ${request}"
    }
  }
}

private List getRequests(final String type, final Map parts) {
  //logTrace "getRequest(parts)"
  //logTrace "parts: ${parts} ${parts.dni}"
  state.seq = (state.seq ?: 0) + 1 //annoyingly the code editor doesn't like the ++ operator

  Map msg = [
    dst: parts.dst,
    seq: state.seq
  ]

  if (type == "refresh") {
    msg.msg = "DeviceInfoDocGetList"
  }
  else if (type == "manager") {
    msg.msg = "GetAdapterManagersList" // Working but not used
  }
  else if (type == "sysinfo") {
    msg.msg = "GetSystemInformation" // Working but not used
  }
  else if (type == "finddev") {
    //working but not used
    msg.msg = "FindDevice"
    msg.datatype = "FindDeviceType"
    msg.body = [[adapterManagerName: parts.adapterId]]
  }
  /* not finished */
  /*
  else if (type == "setcode") {
    msg.msg = "SetKeychainValue"
    msg.datatype = "KeychainSetValueType"
    msg.body = [[
      zid: device.getDataValue("vault_zid"),
      items: [
        [
          key: "master_key",
          value: parts.master_key
        ],
        [
          key: "access_code",
          value: parts.code
        ]
      ]
    ]]
  }
  else if (type == "adduser") {
    msg.msg = "DeviceInfoSet"
    msg.datatype= "DeviceInfoSetType",
    msg.body = [[
      zid: device.getDataValue("vault_zid"),
      command: [v1: [[
        commandType: "vault.add-user",
        data: {
          label: parts.name
        }
      ]]]
    ]]
  }
  else if (type == "enableuser") {
    msg.msg = "DeviceInfoSet"
    msg.datatype = "DeviceInfoSetType"
    msg.body = [[
      zid: parts.acess_code_zid,
      command: [v1: [[
        commandType: "security-panel.enable-user",
        data: [
          label: parts.name
        ]
      ]]]
    ]]
  }
  else if (type == "confirm") {
    // Not complete
    msg.msg: "SetKeychainValue"
    msg.datatype = "KeychainSetValueType"
    msg.body = [[
      zid: device.getDataValue("vault_zid"),
      items: [
        [
          key: "master_key",
          value: parts.master_key
        ]
      ]
    ]]
  }
  else if (type == "sync-code-to-device") {
    msg.msg = "DeviceInfoSet"
    msg.datatype = "DeviceInfoSetType"
    msg.body = [[
      zid: device.getDataValue("vault_zid"),
      command: [v1: [[
        commandType: "vault.sync-code-to-device",
        data: [zid: parts.acess_code_zid, key: parts.key_pos]
      ]]]
    ]]
  }
  */
  else if (type == "setcommand") {
    msg.msg = "DeviceInfoSet"
    msg.datatype = "DeviceInfoSetType"
    msg.body = [[
      zid: parts.zid,
      command: [v1: [[
        commandType: parts.type,
        data: parts.data
      ]]]
    ]]
  }
  else if (type == "setdevice") {
    msg.msg = "DeviceInfoSet"
    msg.datatype = "DeviceInfoSetType"
    msg.body = [[
      zid: parts.zid,
      device: [v1:
        parts.data
      ]
    ]]
  }
  else {
    return null
  }

  return ["message", msg]

  //future functionality maybe
  //test mode motion detctr 42["message",{"body":[{"zid":"[MOTION_SENSOR_ZID]","command":{"v1":[{"commandType":"detection-test-mode.start","data":{}}]}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":9}]
  //cancel test above       42["message",{"body":[{"zid":"[MOTION_SENSOR_ZID]","command":{"v1":[{"commandType":"detection-test-mode.cancel","data":{}}]}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":10}]
}

void sendMsg(final String s) {
  interfaces.webSocket.sendMessage(s)
}

void webSocketStatus(final String status) {
  logDebug "webSocketStatus(${status})"

  if (status.startsWith('failure: ')) {
    log.warn("Failure message from web socket: ${status.substring("failure: ".length())}")
    sendEvent(name: "websocket", value: "failure")
    reconnectWebSocket()
  }
  else if (status == 'status: open') {
    logInfo "WebSocket is open"
    // success! reset reconnect delay
    sendEvent(name: "websocket", value: "connected")
    pauseExecution(1000)
    state.reconnectDelay = 1
  }
  else if (status == "status: closing") {
    log.warn "WebSocket connection closing."
    sendEvent(name: "websocket", value: "closed")
  }
  else {
    log.warn "WebSocket error, reconnecting."
    sendEvent(name: "websocket", value: "error")
    reconnectWebSocket()
  }
}

void initWebsocket(json) {
  logDebug "initWebsocket(json)"
  logTrace "json: ${json}"

  String wsUrl
  if (json.server) {
    wsUrl = "wss://${json.server}/socket.io/?authcode=${json.authCode}&ack=false&EIO=3&transport=websocket"
  }
  else if (json.host) {
    wsUrl = "wss://${json.host}/socket.io/?authcode=${json.ticket}&ack=false&EIO=3&transport=websocket"

    final HashSet<String> createableHubs = getCreateableHubs()

    state.hubs = json.assets.findAll { createableHubs.contains(it.kind) }.collect { hub ->
      [doorbotId: hub.doorbotId, kind: hub.kind, zid: hub.uuid]
    }
  }
  else {
    log.error "Can't find the server: ${json}"
  }

  //test client: https://www.websocket.org/echo.html
  logTrace "wsUrl: $wsUrl"

  try {
    interfaces.webSocket.connect(wsUrl)
    refreshQuiet()
  }
  catch (e) {
    logDebug "initialize error: ${e.message} ${e}"
    log.error "WebSocket connect failed"
    sendEvent(name: "websocket", value: "error")
    //let's try again in 15 minutes
    if (state.reconnectDelay < 900) {
      state.reconnectDelay = 900
    }
    reconnectWebSocket()
  }
}

def reconnectWebSocket() {
  // first delay is 2 seconds, doubles every time
  state.reconnectDelay = (state.reconnectDelay ?: 1) * 2
  // don't let delay get too crazy, max it out at 30 minutes
  if (state.reconnectDelay > 1800) {
    state.reconnectDelay = 1800
  }

  //If the socket is unavailable, give it some time before trying to reconnect
  runIn(state.reconnectDelay, initialize)
}

def uninstalled() {
  getChildDevices().each {
    deleteChildDevice(it.deviceNetworkId)
  }
}

def parse(String description) {
  //logDebug "parse(description)"
  //logTrace "description: ${description}"

  state.lastWebSocketMsgTime = now()

  if (description == "2") {
    //keep alive
    sendMsg("2")
  }
  else if (description == "3") {
    //Do nothing. keep alive response
  }
  else if (description.startsWith(MESSAGE_PREFIX)) {
    final String msg = description.substring(MESSAGE_PREFIX.length())
    def json = new JsonSlurper().parseText(msg)
    //logTrace "json: $json"

   boolean gotDeviceInfoDocType = false

    if (json[0] == "DataUpdate") {
      final String msgtype = json[1].msg
      final String datatype = json[1].datatype

      boolean runExtractDeviceInfos = false

      if (msgtype == "DataUpdate") {
        if (datatype == "DeviceInfoDocType") {
          gotDeviceInfoDocType = true
        }
        else if (datatype == "HubDisconnectionEventType") {
          // Appears to be sent when hub is offline
        }
        else if (datatype == "SystemStatusType") {
          for (final Map systemStatus in json[1].body) {
            final String statusString = systemStatus.statusString

            if (statusString == "device.find.configuring.begin") {
              logDebug "Ring Alarm hub is starting to configure a new device"
            }
            else if (statusString == "device.find.configuring.finished") {
              log.warn("Ring Alarm hub has finished configuring a new device. To add this device to hubitat, run 'createDevices' in the 'Ring API Virtual Device'")
            }
            else if (statusString == "device.find.listening") {
              logDebug "Ring Alarm hub is listening for new devices"
            }
            else if (statusString == "device.find.initialize") {
              logDebug "Ring Alarm hub is getting ready to initialize a new device"
            }
            else if (statusString.startsWith("device.find.error")) {
              logDebug "Ring alarm hub encountered a ${statusString} error while configuring a new device"
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
              logDebug "Ring alarm hub encountered a ${statusString} error while removing a new device"
            }
            else {
              log.warn ("Got an unsupported DataUpdate.SystemStatusType: ${JsonOutput.toJson(systemStatus)}")
            }
          }
        }
        else if (datatype == "DeviceAddDocType" || datatype == "RemovedDeviceType") {
          log.warn ("Got a ${datatype}!! Save this as an example!!!")
          log.warn description
        }
        else {
          log.warn "Received unsupported ${msgtype} datatype ${datatype}. Please report this to the developers so support can be added."
          log.warn description
        }
      }
      else if (msgtype == "Passthru") {
        if (datatype == "PassthruType") {
          // Only create device infos for hubs devices that were selected in the app
          if (getCreateableHubs().contains(json[1].context.assetKind)) {
            for (final Map deviceInfo in extractDeviceInfos(json[1])) {
              logTrace "created deviceInfo: ${JsonOutput.toJson(deviceInfo)}"
              sendPassthru(deviceInfo)
            }
          }
        } else {
          log.warn "Received unsupported ${msgtype} datatype ${datatype}. Please report this to the developers so support can be added."
          log.warn description
        }
      }
      else if (msgtype == "SessionInfo") {
        if (datatype == "SessionInfoType") {
          // Ignored
        } else {
          log.warn "Received unsupported ${msgtype} datatype ${datatype}. Please report this to the developers so support can be added."
          log.warn description
        }
      }
      else if (msgtype == "SubscriptionTopicsInfo") {
        if (datatype == "SubscriptionTopicType") {
          // Ignored
        } else {
          log.warn "Received unsupported ${msgtype} datatype ${datatype}. Please report this to the developers so support can be added."
          log.warn description
        }
      }
      else {
        log.warn "Received unsupported ${msgtype}"
        log.warn description
      }
    }
    else if (json[0] == "message") {
      final String msgtype = json[1].msg

      if (msgtype == "DeviceInfoDocGetList") {
        if (json[1].datatype == "DeviceInfoDocType") {
          gotDeviceInfoDocType = true
        } else if (json[1].context?.uiConnection != null) {
          logDebug "Received weird DeviceInfoDocGetList with no datatype. Ignoring"
          logTrace description
        } else {
          log.warn "Received unsupported DeviceInfoDocGetList. Please report this to the developers so support can be added."
          log.warn description
        }
      }
      else if (msgtype == "DeviceInfoSet") {
        if (json[1].status == 0) {
          logTrace "DeviceInfoSet with seq ${json[1].seq} succeeded."
        } else {
          log.warn "I think a DeviceInfoSet failed? Please report this to the developers so support can be added."
          log.warn description
        }
      }
      else if (msgtype == "SetKeychainValue") {
        if (json[1].status == 0) {
          logTrace "SetKeychainValue with seq ${json[1].seq} succeeded."
        } else {
          log.warn "I think a SetKeychainValue failed? Please report this to the developers so support can be added."
          log.warn description
        }
      }
      else {
        log.warn "Received unsupported json[1].msg: ${msgtype}. Please report this to the developers so support can be added."
        log.warn description
      }
    }
    else if (json[0] == "disconnect") {
      logInfo "Websocket timeout hit.  Reconnecting..."
      interfaces.webSocket.close()
      sendEvent(name: "websocket", value: "disconnect")
      //It appears we don't disconnect fast enough because we still get a failure from the status method when we close.  Because
      //of that failure message and reconnect there we do not need to reconnect here.
    }
    else {
      log.warn "Received unsupported json[0] ${json[0]}. Please report this to the developers so support can be added."
      log.warn description
    }

    if (gotDeviceInfoDocType) {
      final String assetKind = json[1].context.assetKind
      final String assetId = json[1].context.assetId

      // Only create device infos for hubs  that were selected in the app
      if (getCreateableHubs().contains(assetKind)) {
        List<Map> deviceInfos = extractDeviceInfos(json[1])
        // If the hub for these device infos doesn't exist then create it
        if (!getChildByZID(assetId)) {
          createDevice([deviceType: assetKind, zid: assetId, src: json[1].src])
          // If the hub was just created, create the rest of the devices too
          state.createDevices = true
        }

        final boolean createDevices = state.createDevices

        for (final Map deviceInfo in deviceInfos) {
          final boolean isBeamsGroup = deviceInfo.deviceType == "group.light-group.beams"

          if (createDevices) {
            def d = getChildByZID(deviceInfo.zid)

            if (!d) {
              if (isHiddenDeviceType(deviceInfo.deviceType)) {
                  logDebug "Not queuing zid ${deviceInfo.zid} for creation because the device type '${deviceInfo.deviceType}' is hidden"
              } else {
                final String createDevicesZid = state.createDevicesZid
                if (createDevicesZid != null && createDevicesZid != deviceInfo.zid) {
                  log.warn "Not queuing zid ${deviceInfo.zid} because user requested to only create zid ${state.createDevicesZid}"
                  continue
                }

                logInfo "Queuing zid ${deviceInfo.zid} for device creation"
                  queueCreate(deviceInfo)
              }
              continue
            } else {
              logDebug "Not queuing zid ${deviceInfo.zid} for device creation because it already exists"
            }
          }

          if (!isBeamsGroup) {
            sendUpdate(deviceInfo)
          }
        }
        if (createDevices) {
          processCreateQueue()
        }
      }
    }
  }
}

@Field final static List<String> deviceJsonGeneralKeys = ['acStatus', 'batteryLevel', 'batteryStatus', 'componentDevices', 'deviceType',
                                                          'manufacturerName', 'nextExpectedWakeup', 'zid']

@Field final static List<String> deviceJsonDeviceKeys = ['batteryBackup', 'chirps', 'co', 'faulted', 'flood', 'freeze', 'groupMembers',
                                                         'locked', 'mode', 'networks', 'powerSave', 'sensitivity', 'status', 'smoke',
                                                         'testMode', 'version']

List<Map> extractDeviceInfos(final Map json) {
  logDebug "extractDeviceInfos(json)"
  //logTrace "json: ${JsonOutput.toJson(json)}"

  final String msg = json.msg

  List<Map> deviceInfos = []

  Map defaultDeviceInfo = [
    // @todo Figure out if this is needed
    src: json.src,
  ]

  if (json.context) {
    defaultDeviceInfo << json.context.subMap(['assetId'])
  }

  // @todo Experiment with commStatus. "error" == "offline"

  final String lastCheckin = convertToLocalTimeString(new Date())

  //iterate each device
  for (final Map deviceJson in json.body) {
    Map curDeviceInfo = defaultDeviceInfo.clone()

    //logTrace "now deviceJson: ${JsonOutput.toJson(deviceJson)}"
    if (deviceJson.isEmpty()) {
      log.error "Received empty deviceJson"
      deviceInfos.add(curDeviceInfo)
      continue
    }

    // Likely a passthru
    if (msg == 'Passthru') {
      curDeviceInfo.state = deviceJson.data.subMap(['percent', 'timeLeft', 'total', 'transition'])
      curDeviceInfo.zid = curDeviceInfo.assetId
      curDeviceInfo.deviceType = deviceJson.type
    } else {
      // curDeviceInfo.state gets filled out from multiple locations, so create a dummy empty entry here
      Map curDeviceInfoState = [:]

      String deviceType

      if (deviceJson.general) {
        final Map tmpGeneral = deviceJson.general.v1 ?: deviceJson.general.v2

        if (tmpGeneral) {
          curDeviceInfo << tmpGeneral.subMap(deviceJsonGeneralKeys)

          deviceType = curDeviceInfo.deviceType

          List<String> list1 = ['adapter.zigbee', 'adapter.zwave', 'access-code', 'access-code.vault', 'security-panel']
          if (!list1.contains(deviceType)) {
            curDeviceInfo << tmpGeneral.subMap(['commStatus', 'fingerprint', 'lastCommTime', 'lastUpdate', 'name', 'serialNumber'])

            final tamperStatus = tmpGeneral.tamperStatus
            if (tamperStatus != null) {
              curDeviceInfo.tamper = tamperStatus == "tamper" ? "detected" : "clear"
            }
          }
        }
      }

      final Map tmpContext = deviceJson.context?.v1

      if (tmpContext) {
        curDeviceInfo << tmpContext.subMap(['batteryStatus', 'deviceName'])

        if (tmpContext.device?.v1) {
          final Map deviceV1 = tmpContext.device.v1

          // alarmInfo and transitionDelayEndTimestamp are inconsistently duplicated in deviceJson.device.v1
          curDeviceInfo << deviceV1.subMap(['alarmInfo', 'siren', 'transitionDelayEndTimestamp'])
        }
      }

      if (tmpContext || deviceJson.adapter) {
        final Map tmpAdapter = tmpContext?.adapter?.v1 ?: deviceJson.adapter?.v1

        if (tmpAdapter) {
          curDeviceInfo << tmpAdapter.subMap(['firmwareVersion', 'signalStrength'])

          // @todo Would like to confirm that 'firmwareVersion' is still a thing
          if (curDeviceInfo.firmwareVersion) {
            log.warn ("Has firmwareVersion! ${deviceJson}")
          }

          // 'access-code' device types publish a firmware and hardwareVersion. There's no reason to use that value
          if (deviceType != 'access-code') {
            final Map fingerprint = tmpAdapter?.fingerprint
            if (fingerprint?.firmware?.version) {
              curDeviceInfo.firmware = fingerprint.firmware.version.toString() + '.' + fingerprint.firmware.subversion.toString()
              curDeviceInfo.hardwareVersion = fingerprint.hardwareVersion?.toString()
            }
          }
        }
      }

      if (deviceJson.impulse?.v1) {
        final List tmpImpulses = deviceJson.impulse.v1

        final String impulseType = tmpImpulses[0].impulseType

        curDeviceInfo.impulseType = impulseType

        if (impulseType == "comm.heartbeat") {
          curDeviceInfo.lastCheckin = lastCheckin
        }

        Map impulses = [:]
        for (final Map impulse in tmpImpulses) {
          impulses[impulse.impulseType] = impulse.data
        }
        curDeviceInfo.impulses = impulses
      }

      if (deviceJson.pending) {
        final Map curDeviceInfoPending = [:]

        final Map tmpPending = deviceJson.pending

        if (tmpPending.device?.v1) {
          final Map tmpPendingDevice = tmpPending.device.v1

          curDeviceInfoPending << tmpPendingDevice.subMap(['sensitivity'])

          // Log if some other unsupported keys are found
          final Set otherKeys = ['sensitivity'] - tmpPendingDevice.keySet()
          if (otherKeys) {
            log.warn("Found unexpected pending keys: ${otherKeys}: ${JsonOutput.toJson(tmpPendingDevice)}")
          }
        }

        if (tmpPending.command?.v1) {
          curDeviceInfoPending.commands = []

          for (final Map command in tmpPending.command.v1) {
            curDeviceInfoPending.commands.add(command)
          }
        }

        if (!curDeviceInfoPending.isEmpty()) {
          curDeviceInfo.pending = curDeviceInfoPending
        }
      }

      if (deviceJson.device?.v1) {
        final Map deviceV1 = deviceJson.device.v1

        // I have at least one example where these keys is set to null in deviceJson.device.v1., but wasn't set in deviceJson.context.v1.device.v1.
        // Make sure to copy it here
        for (final String key in ['alarmInfo', 'transitionDelayEndTimestamp']) {
          if (!curDeviceInfo.containsKey(key) && deviceV1.containsKey(key)) {
            curDeviceInfo[key] = deviceV1[key]
          }
        }

        curDeviceInfo << deviceV1.subMap(deviceJsonDeviceKeys)

        for (final String key in ['brightness', 'level', 'volume']) {
          final keyVal = deviceV1.get(key)
          if (keyVal != null) {
            curDeviceInfo[key] = (keyVal * 100).toInteger()
          }
        }

        final motionStatus = deviceV1.motionStatus
        if (motionStatus != null) {
          curDeviceInfo.motion = motionStatus == "clear" ? "inactive" : "active"
        }

        final on = deviceV1.on
        if (on != null) {
          curDeviceInfo.switch = on ? "on" : "off"
        }
      }
    }

    deviceInfos.add(curDeviceInfo)

    if (curDeviceInfo.deviceType == null) {
      log.warn "null device type message?: ${JsonOutput.toJson(deviceJson)}"
    }
  }

  logTrace "found ${deviceInfos.size()} devices"

  return deviceInfos
}

void createDevice(final Map deviceInfo) {
  logDebug "createDevice(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

  final String deviceType = deviceInfo.deviceType

  if (deviceType == null) {
    logDebug "Cannot create deviceType ${deviceType} because it is null"
    return
  }

  final String mappedDeviceTypeName = DEVICE_TYPE_NAMES[deviceType]

  if (mappedDeviceTypeName == null) {
    log.warn "Cannot create a ${deviceType} device. Unsupported device type!"
    return
  }

  if (isHiddenDeviceType(deviceType)) {
    logDebug "Cannot create ${deviceType} because it is a hidden type"
    return
  }

  // Deeper check to enable auto-create on initialize
  if (!isHub(deviceType)) {
    final String parentKind = state.hubs.find { it.zid == deviceInfo.src }.kind

    if (!getCreateableHubs().contains(parentKind)) {
      logDebug "not creating ${deviceInfo.name} because parent ${parentKind} is not creatable!"
      return
    }
  }

  final String formattedDNI = getFormattedDNI(deviceInfo.zid)

  def d = getChildDevice(formattedDNI)
  if (!d) {
    // Devices that have drivers that store in devices
    log.warn "Creating a ${mappedDeviceTypeName} (${deviceType}) with dni: ${formattedDNI}"
    try {
      d = addChildDevice("ring-hubitat-codahq", mappedDeviceTypeName, formattedDNI,
                         [label: deviceInfo.name ?: mappedDeviceTypeName])

      setInitialDeviceDataValues(d, deviceInfo)

      log.warn "Successfully added ${deviceType} with dni: ${formattedDNI}"
    }
    catch (e) {
      if (e.toString().replace(mappedDeviceTypeName, "") ==
        "com.hubitat.app.exception.UnknownDeviceTypeException: Device type '' in namespace 'ring-hubitat-codahq' not found") {
        log.error '<b style="color: red;">The "' + mappedDeviceTypeName + '" driver was not found and needs to be installed.</b>\r\n'
      }
      else {
        log.error "Error adding device: ${e}"
      }
    }
  }
  else {
    logDebug "Device ${d} already exists. No need to create."
  }
}

void setInitialDeviceDataValues(d, final Map deviceInfo) {
  d.updateDataValue("zid",  deviceInfo.zid)
  d.updateDataValue("fingerprint", deviceInfo.fingerprint ?: "N/A")
  d.updateDataValue("manufacturer", deviceInfo.manufacturerName ?: "Ring")
  d.updateDataValue("serial", deviceInfo.serialNumber ?: "N/A")
  d.updateDataValue("type", deviceInfo.deviceType)
  d.updateDataValue("src", deviceInfo.src)
}

void queueCreate(final Map deviceInfo) {
  if (state.queuedCreates == null) {
    state.queuedCreates = []
  }
  state.queuedCreates.add(deviceInfo)
}

void processCreateQueue() {
  for (final Map deviceInfo in state.queuedCreates) {
    createDevice(deviceInfo)
    sendUpdate(deviceInfo)
  }
  state.createDevices = false
  state.remove("queuedCreates")
  state.remove("createDevicesZid")
}

void sendUpdate(final Map deviceInfo) {
  logDebug "sendUpdate(deviceInfo)"
  //logTrace "deviceInfo: ${deviceInfo}"

  if (deviceInfo == null) {
    log.error "sendUpdate deviceInfo is null"
    return
  }

  final String deviceType = deviceInfo.deviceType

  if (deviceType == null) {
    log.error "sendUpdate deviceInfo.deviceType is null"
    return
  }

  def d = getChildByZID(isHiddenDeviceType(deviceType) ? deviceInfo.assetId : deviceInfo.zid)
  if (!d) {
    if (!suppressMissingDeviceMessages) {
      log.warn "Couldn't find device ${deviceInfo.name ?: deviceInfo.deviceName} of type ${deviceType} with zid ${deviceInfo.zid} (Run createDevice() to create missing devices)"
    }
  }
  else {
    logDebug "Updating device ${d}"
    d.setValues(deviceInfo)

    // Old versions set device data fields incorrectly. Hubitat v2.2.4 appears to clean up
    // the bad data fields. Reproduce the necessary fields
    if (d.getDataValue('zid') == null) {
      if (!['adapter.zigbee', 'adapter.zwave', 'access-code', 'access-code.vault', 'security-panel'].contains(deviceType)) {
        log.warn "Device ${d} is missing 'zid' data field. Attempting to fix"
        setInitialDeviceDataValues(d, deviceInfo)
      }
    } else if (deviceType ==  'hub.redsky' || deviceType == 'hub.kili') {
      // The fix above was applied to the hub.* datatypes with incorrect values. Check for a mismatch and update as necessary
      if (d.getDataValue('type') != deviceType) {
        log.warn "Device ${d} has incorrect 'type' data field '${d.getDataValue('type')}. Attempting to fix"
        setInitialDeviceDataValues(d, deviceInfo)
      }
    }
  }
}

void sendPassthru(final Map deviceInfo) {
  logDebug "sendPassthru(deviceInfo)"
  //logTrace "deviceInfo: ${deviceInfo}"

  def d = getChildByZID(deviceInfo.zid)
  if (!d) {
    if (!suppressMissingDeviceMessages) {
      log.warn "Couldn't find device ${deviceInfo.zid} for passthru"
    }
  }
  else {
    logDebug "Passthru for device ${d}"
    d.setValues(deviceInfo)
  }
}

private String convertToLocalTimeString(final Date dt) {
  final TimeZone timeZone = location?.timeZone
  if (timeZone) {
    return dt.format("yyyy-MM-dd h:mm:ss a", timeZone)
  }
  else {
    return dt.toString()
  }
}

String getFormattedDNI(final String id) {
  return 'RING||' + id.toString()
}

def getChildByZID(final String zid) {
  logDebug "getChildByZID(${zid})"
  def d = getChildDevice(getFormattedDNI(zid))
  logTrace "Found child ${d}"
  return d
}

boolean isParentRequest(type) {
  return ["refresh-security-device"].contains(type)
}

boolean isHub(final String kind) {
  return HUB_TYPES.contains(kind)
}

boolean isHiddenDeviceType(final String deviceType) {
  return HIDDEN_DEVICES.contains(deviceType)
}

@Field final static Map<String, String> DEVICE_TYPE_NAMES = [
  //physical alarm devices
  "sensor.contact": "Ring Virtual Contact Sensor",
  "sensor.tilt": "Ring Virtual Contact Sensor",
  "sensor.zone": "Ring Virtual Contact Sensor",
  "sensor.motion": "Ring Virtual Motion Sensor",
  "sensor.flood-freeze": "Ring Virtual Alarm Flood & Freeze Sensor",
  "listener.smoke-co": "Ring Virtual Alarm Smoke & CO Listener",
  "alarm.co": "Ring Virtual CO Alarm",
  "alarm.smoke": "Ring Virtual Smoke Alarm",
  "range-extender.zwave": "Ring Virtual Alarm Range Extender",
  "lock": "Ring Virtual Lock",
  "security-keypad": "Ring Virtual Keypad",
  "security-panic": "Ring Virtual Panic Button",
  "base_station_k1": "Ring Virtual Alarm Hub Pro",
  "base_station_v1": "Ring Virtual Alarm Hub",
  "siren": "Ring Virtual Siren",
  "siren.outdoor-strobe": "Ring Virtual Siren",
  "switch": "Ring Virtual Switch",
  "bridge.flatline": "Ring Virtual Retrofit Alarm Kit",
  //virtual alarm devices
  "adapter.zwave": "Ring Z-Wave Adapter",
  "adapter.zigbee": "Ring Zigbee Adapter",
  "security-panel": "Ring Alarm Security Panel",
  "hub.redsky": "Ring Alarm Base Station",
  "hub.kili": "Ring Alarm Pro Base Station",
  "access-code.vault": "Code Vault",
  "access-code": "Access Code",
  //physical beams devices
  "switch.multilevel.beams": "Ring Virtual Beams Light",
  "motion-sensor.beams": "Ring Virtual Beams Motion Sensor",
  "group.light-group.beams": "Ring Virtual Beams Group",
  "beams_bridge_v1": "Ring Virtual Beams Bridge",
  //virtual beams devices
  "adapter.ringnet": "Ring Beams Ringnet Adapter"
]

@Field final static HashSet<String> HIDDEN_DEVICES = [
  "access-code.vault",
  "access-code",
  "adapter.ringnet",
  "adapter.zigbee",
  "adapter.zwave",
  "security-panel",
  "hub.kili",
  "hub.redsky",
]

@Field final static HashSet<String> HUB_TYPES = [
  "base_station_k1",
  "base_station_v1",
  "beams_bridge_v1"
]

@Field final static String MESSAGE_PREFIX = "42"