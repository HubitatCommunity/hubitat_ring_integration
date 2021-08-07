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
 *
 *
 *  Change Log:
 *  2019-03-24: Initial
 *  2019-11-15: Import URL
 *  2019-12-20: Support for First Alert Smoke/CO Alarm (probably battery only)
 *  2020-02-11: Support for tilt sensors (using the contact sensor driver)
 *              Support for the Ring Flood & Freeze Sensor
 *              Updated to the documented location of the websocket client
 *              Added an informational log when the websocket timeout it received
 *  2020-02-29: Support for Retrofit Alarm Kit
 *              Supressed more websocket nonsense logging errors
 *              Changed namespace
 *  2020-05-11: Support for non-alarm modes (Ring Modes)
 *              Changes to auto-create hub/bridge devices
 *              Optimization around uncreated devices
 *  2020-12-01: Fix bug with how device data fields are set. Caused device commands to fail in hubitat v2.2.4
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

    //command "testCommand"
    command "setMode", [[name: "Set Mode*", type: "ENUM", description: "Set the Location's mode", constraints: ["Disarmed", "Home", "Away"]]]
  }

  preferences {
    input name: "watchDogInterval", type: "number", range: 10..1440, title: "Watchdog Interval", description: "Duration in minutes between checks", defaultValue: 60, required: true
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
  //old method of getting websocket auth
  //parent.simpleRequest("ws-connect", [dni: device.deviceNetworkId])
  if (isWebSocketCapable()) {
    parent.simpleRequest("tickets", [dni: device.deviceNetworkId])
    state.seq = 0
  }
  else {
    log.warn "Nothing to initialize..."
  }
}

def updated() {
  //refresh()
}

/**
 * This will create all devices possible. If the user doesn't want some of them they will have to delete them manually for now.
 */
def createDevices(zid) {
  logDebug "createDevices(${zid})"
  state.createDevices = true
  refresh(zid)
}

def setState(attr, value, type) {
  if (type == "array-add") {
    if (state."$attr" == null) {
      state."$attr" = []
    }
    state."$attr" << value
  }
  else if (type == "bool-set") {
    state."$attr" = value
  }
  else {
    log.error "unknown type $type!"
  }
}

def resetState(attr) {
  state.remove(attr)
}

def isWebSocketCapable() {
  return state.createableHubs && state.createableHubs.size() > 0
}

def isTypePresent(kind) {
  return getChildDevices()?.find {
    it.getDataValue("type") == kind
  } != null
}

def refresh(zid) {
  logDebug "refresh(${zid})"
  unschedule()
  state.updatedDate = now()
  state.hubs?.each { hub ->
    if (hub.zid == zid || zid == null) {
      logInfo "Refreshing hub ${hub.zid} with kind ${hub.kind}"
      simpleRequest("refresh", [dst: hub.zid])
    }
  }
  watchDogChecking()
  if (!state.alarmCapable) {
    parent.simpleRequest("mode-get", [mode: "disarmed", dni: device.deviceNetworkId])
  }
}

def watchDogChecking() {
  logTrace "watchDogChecking(${watchDogInterval}) now:${now()} state.updatedDate:${state.updatedDate}"
  if ((getChildDevices()?.size() ?: 0) == 0) {
    logInfo "Watchdog checking canceled. No composite devices!"
    return
  }
  double timesSinceContact = (now() - state.updatedDate).abs() / 1000  //time since last update in seconds
  logDebug "Watchdog checking started.  Time since last check: ${(timesSinceContact / 60).round(1)} minutes"
  if ((timesSinceContact / 60) > (watchDogInterval ?: 30)) {
    logDebug "Watchdog checking interval exceeded"
    if (!device.currentValue("websocket").equals("connected")) {
      reconnectWebSocket()
    }
  }
  runIn(watchDogInterval * 60, watchDogChecking)  //time in seconds
}

def childParse(type, params = []) {
  logDebug "childParse(type, params)"
  logTrace "type ${type}"
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

def simpleRequest(type, params = [:]) {
  logDebug "simpleRequest(${type})"
  logTrace "params: ${params}"

  if (isParentRequest(type)) {
    logTrace "parent request: $type"
    parent.simpleRequest(type, [dni: params.dni, type: params.type])
  }
  else {
    def request = JsonOutput.toJson(getRequests(params).getAt(type))
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

private getRequests(parts) {
  //logTrace "getRequest(parts)"
  //logTrace "parts: ${parts} ${parts.dni}"
  state.seq = (state.seq ?: 0) + 1 //annoyingly the code editor doesn't like the ++ operator
  return [
    "refresh": ["message", [msg: "DeviceInfoDocGetList", dst: parts.dst, seq: state.seq]],
    "manager": ["message", [msg: "GetAdapterManagersList", dst: parts.dst, seq: state.seq]],//working but not used
    "sysinfo": ["message", [msg: "GetSystemInformation", dst: parts.dst, seq: state.seq]],  //working but not used
    "finddev": ["message", [   //working but not used
      msg: "FindDevice",
      datatype: "FindDeviceType",
      body: [[adapterManagerName: parts.adapterId]],
      dst: parts.dst,
      seq: state.seq
    ]],
    /* not finished */
    /*
    "setcode": ["message", [
      msg: "SetKeychainValue",
      datatype: "KeychainSetValueType",
      body: [[
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
      ]],
      dst: parts.dst,
      seq: state.seq
    ]],
    "adduser": ["message", [
      msg: "DeviceInfoSet",
      datatype: "DeviceInfoSetType",
      body: [[
        zid: device.getDataValue("vault_zid"),
        command: [v1: [[
          commandType: "vault.add-user",
          data: {
            label:
            parts.name
          }
        ]]]
      ]],
      dst: parts.dst,
      seq: state.seq
    ]],
    "enableuser": ["message", [
      msg: "DeviceInfoSet",
      datatype: "DeviceInfoSetType",
      body: [[
        zid: parts.acess_code_zid,
        command: [v1: [[
          commandType: "security-panel.enable-user",
          data: {
            label:
            parts.name
          }
        ]]]
      ]],
      dst: parts.dst,
      seq: state.seq
    ]],
    "confirm": ["message", [   //not complete
      msg: "SetKeychainValue",
      datatype: "KeychainSetValueType",
      body: [[
        zid: device.getDataValue("vault_zid"),
        items: [
          [
            key: "master_key",
            value: parts.master_key
          ]
        ]
      ]],
      dst: parts.dst,
      seq: state.seq
    ]],
    "sync-code-to-device": ["message", [
      msg: "DeviceInfoSet",
      datatype: "DeviceInfoSetType",
      body: [[
        zid: device.getDataValue("vault_zid"),
        command: [v1: [[
          commandType: "vault.sync-code-to-device",
          data: ["zid": parts.acess_code_zid, "key": parts.key_pos]
        ]]]
      ]],
      dst: parts.dst,
      seq: state.seq
    ]],
    */
    "setcommand": ["message", [
      body: [[
        zid: parts.zid,
        command: [v1: [[
          commandType: parts.type,
          data: parts.data
        ]]]
      ]],
      datatype: "DeviceInfoSetType",
      dst: parts.dst,
      msg: "DeviceInfoSet",
      seq: state.seq
    ]],
    "setdevice": ["message", [
      body: [[
        zid: parts.zid,
        device: [v1:
          parts.data
        ]
      ]],
      datatype: "DeviceInfoSetType",
      dst: parts.dst,
      msg: "DeviceInfoSet",
      seq: state.seq
    ]],

    //future functionality maybe
    //set power save keypad   42["message",{"body":[{"zid":"[KEYPAD_ZID]","device":{"v1":{"powerSave":"extended"}}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":7}]
    //set power save off keyp 42["message",{"body":[{"zid":"[KEYPAD_ZID]","device":{"v1":{"powerSave":"off"}}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":8}]
    //test mode motion detctr 42["message",{"body":[{"zid":"[MOTION_SENSOR_ZID]","command":{"v1":[{"commandType":"detection-test-mode.start","data":{}}]}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":9}]
    //cancel test above       42["message",{"body":[{"zid":"[MOTION_SENSOR_ZID]","command":{"v1":[{"commandType":"detection-test-mode.cancel","data":{}}]}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":10}]
    //motion sensitivy motdet 42["message",{"body":[{"zid":"[MOTION_SENSOR_ZID]","device":{"v1":{"sensitivity":1}}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":11}]
    //more                    42["message",{"body":[{"zid":"[MOTION_SENSOR_ZID]","device":{"v1":{"sensitivity":0}}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":12}]
    //0 high, 1 mid, 2 low    42["message",{"body":[{"zid":"[MOTION_SENSOR_ZID]","device":{"v1":{"sensitivity":2}}}],"datatype":"DeviceInfoSetType","dst":null,"msg":"DeviceInfoSet","seq":13}]

  ]
}

def sendMsg(String s) {
  interfaces.webSocket.sendMessage(s)
}

def webSocketStatus(String status) {
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

def initWebsocket(json) {
  logDebug "initWebsocket(json)"
  logTrace "json: ${json}"

  def wsUrl
  if (json.server) {
    wsUrl = "wss://${json.server}/socket.io/?authcode=${json.authCode}&ack=false&EIO=3&transport=websocket"
  }
  else if (json.host) {
    wsUrl = "wss://${json.host}/socket.io/?authcode=${json.ticket}&ack=false&EIO=3&transport=websocket"
    state.hubs = json.assets.findAll { state.createableHubs.contains(it.kind) }.collect { hub ->
      [doorbotId: hub.doorbotId, kind: hub.kind, zid: hub.uuid]
    }
    /*
    if (!state.hubs) {
      state.hubs = []
    }
    newHubs.each { nHub ->
      def eHub = state.hubs.find {it.doorbotId == nHub.doorbotId}
      if (!dHub) {
        state.hubs << nHub
      }
    }*/
  }
  else {
    log.error "Can't find the server: ${json}"
  }

  //test client: https://www.websocket.org/echo.html
  logTrace "wsUrl: $wsUrl"

  try {
    interfaces.webSocket.connect(wsUrl)
    logInfo "Connected!"
    sendEvent(name: "websocket", value: "connected")
    refresh()
  }
  catch (e) {
    logDebug "initialize error: ${e.message} ${e}"
    log.error "WebSocket connect failed"
    sendEvent(name: "websocket", value: "error")
    //let's try again in 15 minutes
    if (state.reconnectDelay < 900) state.reconnectDelay = 900
    reconnectWebSocket()
  }
}

def reconnectWebSocket() {
  // first delay is 2 seconds, doubles every time
  state.reconnectDelay = (state.reconnectDelay ?: 1) * 2
  // don't let delay get too crazy, max it out at 30 minutes
  if (state.reconnectDelay > 1800) state.reconnectDelay = 1800

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
  if (description.equals("2")) {
    //keep alive
    sendMsg("2")
  }
  else if (description.equals("3")) {
    //Do nothing. keep alive response
  }
  else if (description.startsWith(MESSAGE_PREFIX)) {
    def msg = description.substring(MESSAGE_PREFIX.length())
    def slurper = new groovy.json.JsonSlurper()
    def json = slurper.parseText(msg)
    //logTrace "json: $json"

    def deviceInfos

    if (json[0].equals("DataUpdate")) {
      //only keep device infos for devices that were selected in the app
      if (state.createableHubs.contains(json[1].context.assetKind)) {
        deviceInfos = extractDeviceInfos(json[1])
      }
      //else {
      //  logTrace "Discarded update from hub ${json[1].context.assetKind}"
      //}
    }
    else if (json[0].equals("message") && json[1].msg == "DeviceInfoDocGetList" && json[1].datatype == "DeviceInfoDocType") {
      //only keep device infos for devices that were selected in the app
      if (state.createableHubs.contains(json[1].context.assetKind)) {
        deviceInfos = extractDeviceInfos(json[1])
        //if the hub for these device infos doesn't exist then create it
        if (!getChildByZID(json[1].context.assetId)) {
          def d = createDevice([deviceType: json[1].context.assetKind, zid: json[1].context.assetId, src: json[1].src])
          //might as well create the devices
          state.createDevices = true
        }
      }
      //else {
      //  logTrace "Discarded device list from hub ${json[1].context.assetKind}"
      //}
    }
    else if (json[0].equals("message") && json[1].msg == "DeviceInfoSet") {
      if (json[1].status == 0) {
        logTrace "DeviceInfoSet with seq ${json[1].seq} succeeded."
      }
      else {
        log.warn "I think a DeviceInfoSet failed?"
        log.warn description
      }
    }
    else if (json[0].equals("message") && json[1].msg == "SetKeychainValue") {
      if (json[1].status == 0) {
        logTrace "SetKeychainValue with seq ${json[1].seq} succeeded."
      }
      else {
        log.warn "I think a SetKeychainValue failed?"
        log.warn description
      }
    }
    else if (json[0].equals("disconnect")) {
      logInfo "Websocket timeout hit.  Reconnecting..."
      interfaces.webSocket.close()
      sendEvent(name: "websocket", value: "disconnect")
      //It appears we don't disconnect fast enough because we still get a failure from the status method when we close.  Because
      //of that failure message and reconnect there we do not need to reconnect here.  Commenting out for now.
      //reconnectWebSocket()
    }
    else {
      log.warn "huh? what's this?"
      log.warn description
    }

    deviceInfos.each {
      logTrace "created deviceInfo: ${JsonOutput.prettyPrint(JsonOutput.toJson(it))}"

      if (it?.msg == "Passthru") {
        sendPassthru(it)
      }
      else {
        if (state.createDevices) {
          if (it.deviceType != "group.light-group.beams") {
            createDevice(it)
          }
          else {
            queueCreate(it)
          }
        }
        if (it.deviceType != "group.light-group.beams") {
          sendUpdate(it)
        }
      }
    }
    if (state.createDevices) {
      processCreateQueue()
      state.createDevices = false
    }
  }
}

def extractDeviceInfos(json) {
  logDebug "extractDeviceInfos(json)"
  //logTrace "json: ${JsonOutput.prettyPrint(JsonOutput.toJson(json))}"

  //if (json.msg == "Passthru" && update.datatype == "PassthruType")
  if (IGNORED_MSG_TYPES.contains(json.msg)) {
    return
  }
  if (json.msg != "DataUpdate" && json.msg != "DeviceInfoDocGetList") {
    logTrace "msg type: ${json.msg}"
    logTrace "json: ${JsonOutput.prettyPrint(JsonOutput.toJson(json))}"
  }

  def deviceInfos = []

  def orig = json

  def jsonSlurper = new JsonSlurper()
  def jsonString = '''
{
"deviceType": ""
}
'''

  //"lastUpdate": "",
  //"contact": "closed",
  //"motion": "inactive"

  def returnResult = jsonSlurper.parseText(jsonString)

  returnResult.src = json.src
  returnResult.msg = json.msg

  if (json.context) {
    def tmpContext = json.context
    returnResult.eventOccurredTsMs = tmpContext.eventOccurredTsMs
    returnResult.level = tmpContext.eventLevel
    returnResult.affectedEntityType = tmpContext.affectedEntityType
    returnResult.affectedEntityId = tmpContext.affectedEntityId
    returnResult.affectedEntityName = tmpContext.affectedEntityName
    returnResult.accountId = tmpContext.accountId
    returnResult.assetId = tmpContext.assetId
    returnResult.assetKind = tmpContext.assetKind
  }

  //iterate each device
  json.body.each {
    def copy = new JsonSlurper().parseText(JsonOutput.toJson(returnResult))

    def deviceJson = it
    //logTrace "now deviceJson: ${JsonOutput.prettyPrint(JsonOutput.toJson(deviceJson))}"
    if (!deviceJson) {
      deviceInfos << copy
    }

    if (deviceJson.general) {
      def tmpGeneral
      if (deviceJson.general.v1) {
        tmpGeneral = deviceJson.general.v1
      }
      else {
        tmpGeneral = deviceJson.general.v2
      }
      copy.lastUpdate = tmpGeneral.lastUpdate
      copy.lastCommTime = tmpGeneral.lastCommTime
      copy.nextExpectedWakeup = tmpGeneral.nextExpectedWakeup
      copy.deviceType = tmpGeneral.deviceType
      copy.adapterType = tmpGeneral.adapterType
      copy.zid = tmpGeneral.zid
      copy.roomId = tmpGeneral.roomId
      copy.serialNumber = tmpGeneral.serialNumber
      copy.fingerprint = tmpGeneral.fingerprint
      copy.manufacturerName = tmpGeneral.manufacturerName
      copy.tamperStatus = tmpGeneral.tamperStatus
      copy.name = tmpGeneral.name
      copy.acStatus = tmpGeneral.acStatus
      copy.batteryLevel = tmpGeneral.batteryLevel
      copy.batteryStatus = tmpGeneral.batteryStatus
      if (tmpGeneral.componentDevices) {
        copy.componentDevices = tmpGeneral.componentDevices
      }
    }
    if (deviceJson.context || deviceJson.adapter) {
      def tmpAdapter
      if (deviceJson.context?.v1?.adapter?.v1) {
        tmpAdapter = deviceJson.context.v1.adapter.v1
      }
      else if (deviceJson.adapter?.v1) {
        tmpAdapter = deviceJson.adapter?.v1
      }

      copy.signalStrength = tmpAdapter?.signalStrength
      copy.firmware = tmpAdapter?.firmwareVersion
      if (tmpAdapter?.fingerprint?.firmware?.version)
        copy.firmware = "${tmpAdapter?.fingerprint?.firmware?.version}.${tmpAdapter?.fingerprint?.firmware?.subversion}"
      copy.hardwareVersion = tmpAdapter?.fingerprint?.hardwareVersion.toString()

      def tmpContext = deviceJson.context?.v1
      copy.deviceName = tmpContext?.deviceName
      copy.roomName = tmpContext?.roomName
      if (copy.batteryStatus == null && tmpContext.batteryStatus != null)
        copy.batteryStatus = tmpContext.batteryStatus
      if ("alarm.smoke" == copy.deviceType && tmpContext?.device?.v1?.alarmStatus) {
        copy.state = jsonSlurper.parseText('{"smoke": "" }')
        copy.state.smoke = tmpContext.device.v1
      }
    }
    if (deviceJson.impulse) {
      def tmpImpulse
      if (deviceJson.impulse?.v1[0]) {
        tmpImpulse = deviceJson.impulse?.v1[0]
      }
      copy.impulseType = tmpImpulse.impulseType

      copy.impulses = deviceJson.impulse.v1.collectEntries {
        [(it.impulseType): it.data]
      }

    }

    //if (deviceJson.adapter) {
    //  copy.signalStrength = [deviceJson.adapter.v1]
    //}

    if (deviceJson.device) {
      tmpDevice
      //logTrace "what has this device? ${tmpDevice}"
      if (deviceJson.device.v1) {
        copy.state = deviceJson.device.v1
        //copy.faulted = tmpDevice.faulted
        //copy.mode = tmpDevice.mode
      }

    }

    //likely a passthru
    if (deviceJson.data) {
      assert returnResult.msg == 'Passthru'
      copy.state = deviceJson.data
      copy.zid = copy.assetId
      copy.deviceType = deviceJson.type
    }

    deviceInfos << copy

    //if (copy.deviceType == "range-extender.zwave") {
    //  log.warn "range-extender.zwave message: ${JsonOutput.prettyPrint(JsonOutput.toJson(deviceJson))}"
    //}
    if (copy.deviceType == null) {
      log.warn "null device type message?: ${JsonOutput.prettyPrint(JsonOutput.toJson(deviceJson))}"
    }

  }

  logTrace "found ${deviceInfos.size()} devices"

  return deviceInfos
}

def createDevice(deviceInfo) {
  logDebug "createDevice(deviceInfo)"
  logTrace "deviceInfo: ${deviceInfo}"

  //quick check
  if (deviceInfo == null
    || deviceInfo.deviceType == null
    || DEVICE_TYPES[deviceInfo.deviceType] == null
    || DEVICE_TYPES[deviceInfo.deviceType].hidden) {
    logDebug "Not a creatable device. ${deviceInfo.deviceType}"
    return
  }

  //deeper check to enable auto-create on initialize
  if (!isHub(deviceInfo.deviceType)) {
    def parentKind = state.hubs.find { it.zid == deviceInfo.src }.kind
    if (!state.createableHubs.contains(parentKind)) {
      logDebug "not creating ${deviceInfo.name} because parent ${parentKind} is not creatable!"
      return
    }
  }

  def d = getChildDevices()?.find {
    it.deviceNetworkId == getFormattedDNI(deviceInfo.zid)
  }
  if (!d) {
    //devices that have drivers that store in devices
    log.warn "Creating a ${DEVICE_TYPES[deviceInfo.deviceType].name} (${deviceInfo.deviceType}) with dni: ${getFormattedDNI(deviceInfo.zid)}"
    try {
      d = addChildDevice("ring-hubitat-codahq", DEVICE_TYPES[deviceInfo.deviceType].name, getFormattedDNI(deviceInfo.zid), data)
      d.label = deviceInfo.name ?: DEVICE_TYPES[deviceInfo.deviceType].name

      d.updateDataValue("zid",  deviceInfo.zid)
      d.updateDataValue("fingerprint", deviceInfo.fingerprint ?: "N/A")
      d.updateDataValue("manufacturer", deviceInfo.manufacturerName ?: "Ring")
      d.updateDataValue("serial", deviceInfo.serialNumber ?: "N/A")
      d.updateDataValue("type", deviceInfo.deviceType)
      d.updateDataValue("src", deviceInfo.src)

      //if (sensor.general.v2.deviceType == "security-panel") {
      //  d.updateDataValue("hub-zid", hubNode.general.v2.zid)
      //}

      log.warn "Successfully added ${deviceInfo.deviceType} with dni: ${getFormattedDNI(deviceInfo.zid)}"
    }
    catch (e) {
      if (e.toString().replace(DEVICE_TYPES[deviceInfo.deviceType].name, "") ==
        "com.hubitat.app.exception.UnknownDeviceTypeException: Device type '' in namespace 'ring-hubitat-codahq' not found") {
        log.error '<b style="color: red;">The "' + DEVICE_TYPES[deviceInfo.deviceType].name + '" driver was not found and needs to be installed.</b>\r\n'
      }
      else {
        log.error "Error adding device: ${e}"
      }
    }
  }
  else {
    logDebug "Device ${d} already exists. No need to create."
  }
  return d
}

def queueCreate(deviceInfo) {
  if (!state.queuedCreates) {
    state.queuedCreates = []
  }
  state.queuedCreates << deviceInfo
}

def processCreateQueue() {
  state.queuedCreates.each {
    createDevice(it)
    sendUpdate(it)
  }
  state.remove("queuedCreates")
}

def sendUpdate(deviceInfo) {
  logDebug "sendUpdate(deviceInfo)"
  //logTrace "deviceInfo: ${deviceInfo}"

  if (deviceInfo == null || deviceInfo.deviceType == null) {
    log.warn "No device or type"
    return
  }
  if (DEVICE_TYPES[deviceInfo.deviceType] == null) {
    log.warn "Unsupported device type! ${deviceInfo.deviceType}"
    return
  }

  def dni = DEVICE_TYPES[deviceInfo.deviceType].hidden ? deviceInfo.assetId : deviceInfo.zid
  def d = getChildDevices()?.find {
    it.deviceNetworkId == getFormattedDNI(dni)
  }
  if (!d) {
    if (!suppressMissingDeviceMessages) {
      log.warn "Couldn't find device ${deviceInfo.name ?: deviceInfo.deviceName} of type ${deviceInfo.deviceType} with zid ${deviceInfo.zid}"
    }
  }
  else {
    logDebug "Updating device ${d}"
    d.setValues(deviceInfo)

    // Old versions set device data fields incorrectly. Hubitat v2.2.4 appears to clean up
    // the bad data fields. Reproduce the necessary fields
    if (d.getDataValue('zid') == null) {
      log.warn "Device ${d} is missing 'zid' data field. Attempting to fix"
      d.updateDataValue("zid",  deviceInfo.zid)
      d.updateDataValue("fingerprint", deviceInfo.fingerprint ?: "N/A")
      d.updateDataValue("manufacturer", deviceInfo.manufacturerName ?: "Ring")
      d.updateDataValue("serial", deviceInfo.serialNumber ?: "N/A")
      d.updateDataValue("type", deviceInfo.deviceType)
      d.updateDataValue("src", deviceInfo.src)
    }
  }
}

def sendPassthru(deviceInfo) {
  logDebug "sendPassthru(deviceInfo)"
  //logTrace "deviceInfo: ${deviceInfo}"

  if (deviceInfo == null) {
    log.warn "No data"
  }
  def d = getChildDevices()?.find {
    it.deviceNetworkId == getFormattedDNI(deviceInfo.zid)
  }
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

def String getFormattedDNI(id) {
  return "RING||${id}"
}

def String getRingDeviceId(dni) {
  //logDebug "getRingDeviceId(dni)"
  //logTrace "dni: ${dni}"
  return dni?.split("||")?.getAt(1)
}

def getChildByZID(zid) {
  logDebug "getChildByZID(${zid})"
  def d = getChildDevices()?.find { it.deviceNetworkId == getFormattedDNI(zid) }
  logTrace "Found child ${d}"
  return d
}

def boolean isParentRequest(type) {
  return ["refresh-security-device"].contains(type)
}

def isHub(kind) {
  return HUB_TYPES.contains(kind)
}

@Field static def DEVICE_TYPES = [
  //physical alarm devices
  "sensor.contact": [name: "Ring Virtual Contact Sensor", hidden: false],
  "sensor.tilt": [name: "Ring Virtual Contact Sensor", hidden: false],
  "sensor.zone": [name: "Ring Virtual Contact Sensor", hidden: false],
  "sensor.motion": [name: "Ring Virtual Motion Sensor", hidden: false],
  "sensor.flood-freeze": [name: "Ring Virtual Alarm Flood & Freeze Sensor", hidden: false],
  "listener.smoke-co": [name: "Ring Virtual Alarm Smoke & CO Listener", hidden: false],
  "alarm.co": [name: "Ring Virtual CO Alarm", hidden: false],
  "alarm.smoke": [name: "Ring Virtual Smoke Alarm", hidden: false],
  "range-extender.zwave": [name: "Ring Virtual Alarm Range Extender", hidden: false],
  "lock": [name: "Ring Virtual Lock", hidden: false],
  "security-keypad": [name: "Ring Virtual Keypad", hidden: false],
  "security-panic": [name: "Ring Virtual Panic Button", hidden: false],
  "base_station_v1": [name: "Ring Virtual Alarm Hub", hidden: false],
  "siren": [name: "Ring Virtual Siren", hidden: false],
  "siren.outdoor-strobe": [name: "Ring Virtual Siren", hidden: false],
  "switch": [name: "Ring Virtual Switch", hidden: false],
  "bridge.flatline": [name: "Ring Virtual Retrofit Alarm Kit", hidden: false],
  //virtual alarm devices
  "adapter.zwave": [name: "Ring Z-Wave Adapter", hidden: true],
  "adapter.zigbee": [name: "Ring Zigbee Adapter", hidden: true],
  "security-panel": [name: "Ring Alarm Security Panel", hidden: true],
  "hub.redsky": [name: "Ring Alarm Base Station", hidden: true],
  "access-code.vault": [name: "Code Vault", hidden: true],
  "access-code": [name: "Access Code", hidden: true],
  //physical beams devices
  "switch.multilevel.beams": [name: "Ring Virtual Beams Light", hidden: false],
  "motion-sensor.beams": [name: "Ring Virtual Beams Motion Sensor", hidden: false],
  "group.light-group.beams": [name: "Ring Virtual Beams Group", hidden: false],
  "beams_bridge_v1": [name: "Ring Virtual Beams Bridge", hidden: false],
  //virtual beams devices
  "adapter.ringnet": [name: "Ring Beams Ringnet Adapter", hidden: true]
]

@Field static def IGNORED_MSG_TYPES = [
  "SessionInfo",
  "SubscriptionTopicsInfo"
]

@Field static def HUB_TYPES = [
  "base_station_v1",
  "beams_bridge_v1"
]

@Field static def MESSAGE_PREFIX = "42"
