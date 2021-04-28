/**
 *  Hubitat Wireless Sensor Tags Connect
 * 
 *  author: Bart Hazer
 *  
 *  Modified from https://github.com/st-swanny/smartthings/tree/master/WirelessTags which had the following license:
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
 */
definition(
    name: "Wireless Sensor Tags Connect",
    namespace: "bhazer",
    author: "Bart Hazer",
    description: "Connects and syncs wireless sensor tags (https://wirelesstag.net/)",
    category: "Convenience",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    oauth: true)


preferences {
    page(name: "preferencesPage")
}

mappings {
    path("/swapToken") {
        action: [
            GET: "swapToken"
        ]
    }
    path("/urlcallback") {
        action: [
            GET: "handleUrlCallback",
            POST: "handleUrlCallback"
        ]
    }    
}

def safeToDouble(val) {
    return (val != null && val.toString().isDouble()) ? val.toDouble() : 0.0
}

def handleUrlCallback () {
    log.trace "url callback"
    debugEvent ("url callback: $params", true)
    
    def id = params.id.toInteger()
    def type = params.type
                
    def dni = getTagUUID(id)
    
    if (dni) {
        def d = getChildDevice(dni)

        if(d)
        {
            def data = null
            
            switch (type) {
                case "update":
                    data = [
                        temperature: safeToDouble(params.temperature).round(1),
                        battery: batteryVoltageToPercentage(safeToDouble(params.batteryVolt)),
                        humidity: safeToDouble(params.cap).round(),
                        illuminance: safeToDouble(params.lux).round(),
                    ]
                    break

                case "oor": data = [presence: "not present"]; break
                case "back_in_range": data = [presence: "present"]; break
                case "motion_detected": data = [acceleration: "active", motion: "active"]; break
                case "motion_timedout": data = [acceleration: "inactive", motion: "inactive"]; break
                case "door_opened": data = [contact: "open"]; break
                case "door_closed": data = [contact: "closed"]; break
                case "water_detected": data = [water : "wet"]; break                
                case "water_dried": data = [water : "dry"]; break
            }
            
            log.trace "callback action = " + data?.toString()
            
            if (data) {
                d.generateEvent(data)
            }
        }
    }
}

def preferencesPage() {
    if (!atomicState.accessToken) {
        log.debug "about to create access token"
        createAccessToken()
        atomicState.accessToken = state.accessToken
    }
    
    // oauth docs = http://www.mytaglist.com/eth/oauth2_apps.html
    if (atomicState.authToken) {
        log.debug "have a valid wirelesstags authToken: ${atomicState.authToken}"
        return wirelessDeviceList()
    } else {
        def redirectUrl = oauthInitUrl()
        log.debug "RedirectUrl = ${redirectUrl}"

        return dynamicPage(title: "Connect", uninstall: true) {
            section() {
                paragraph "Tap below to log in to the Wireless Tags service and authorize Hubitat access."
                href url:redirectUrl, style:"external", required:true, title:"Authorize wirelesstag.net", description:"Click to authorize"
            }
        }
    }
}

def wirelessDeviceList() {
    def availDevices = getWirelessTags()

    def p = dynamicPage(title: "Select Your Devices", install: true, uninstall: true) {
        section() {
            paragraph "Tap below to see the list of devices available in your Wireless Tags account and select the ones you want to connect to your hub."
            paragraph "When you hit Done, the setup can take as much as 10 seconds per device selected."
            input(name: "devices", title:"Tags to Connect", type: "enum", required:true, multiple:true, options:availDevices)
            paragraph "Configure the poll timer if you want to periodically poll the Wireless Tags server. Set to 0 to skip polling."
            input "pollTimer", "number", title:"Minutes between poll updates of the sensors", required:true, defaultValue:5
            paragraph "Select if you want the wireless tags server to push updates to your hub when a tag changes state. Your hub must be connected to the cloud for this to work."
            input(name: "supportCallbacks", title:"Receive pushes from Wireless Tags", type: "bool", defaultValue:false)
            label title: "Assign a name for this app instance (optional)", required: false
        }
    }

    return p
}

def getWirelessTags() {
    def result = getTagStatusFromServer()

    def availDevices = [:]
    result?.each { device ->
        def dni = device?.uuid
        availDevices[dni] = device?.name
    }

    log.debug "devices: $availDevices"

    return availDevices
}


def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def getChildNamespace() { "bhazer" }
def getChildName(def tagInfo) {
    def deviceType = "Wireless Sensor Tag" 
    if (tagInfo) {
        switch (tagInfo.tagType) {
            case 32:
            case 33:
                deviceType = "Wireless Tag Water"
                break;
            // add new device types here
        }
    }
    return deviceType
}

def initialize() {
    
    unschedule()
    
    def curDevices = devices.collect { dni ->

        def d = getChildDevice(dni)
        
        def tag = atomicState.tags.find { it.uuid == dni }

        if(!d) {           
            d = addChildDevice(getChildNamespace(), getChildName(tag), dni, null, [label:"${tag?.name} Sensor"])
            d.initialSetup()
            log.debug "created ${d.displayName} $dni"
        }
        else
        {
            log.debug "found ${d.displayName} $dni already exists"
            d.updated()
        }
        
        if (d) { 
            // configure device
            if (supportCallbacks) {
                setupCallbacks(d, tag)
            }
        }

        return dni
    }

    def delete
    // Delete any that are no longer in settings
    if(!curDevices)
    {
        delete = getAllChildDevices()
    }
    else
    {
        delete = getChildDevices().findAll { !curDevices.contains(it.deviceNetworkId) }
    }

    delete.each { deleteChildDevice(it.deviceNetworkId) }

    if (atomicState.tags == null) { atomicState.tags = [:] }

    pollHandler()
    
    // set up internal poll timer
    if ((pollTimer != null) && (pollTimer.toInteger() > 0)) {
        log.trace "setting poll to ${pollTimer}"
        schedule("0 0/${pollTimer.toInteger()} * * * ?", pollHandler)
    }
}

def oauthInitUrl() {
    log.debug "oauthInitUrl"
    atomicState.oauthInitState = UUID.randomUUID().toString()
    def oauthParams = [
        client_id: getClientIdForWirelessTagsOauth(),
        state: atomicState.oauthInitState,
        redirect_uri: buildRedirectUrl()
    ]

    return "https://www.mytaglist.com/oauth2/authorize.aspx?" + toQueryString(oauthParams)
}

def buildRedirectUrl() {
    log.debug "buildRedirectUrl"
    return fullLocalApiServerUrl("/swapToken?access_token=${atomicState?.accessToken}&")
}

def swapToken() {
    log.debug "swapping token: $params"

    def code = params.code
    if (!code) {
        // mytaglist.com doesn't properly add parameters when one exists. So, also look for this mangled version.
        code = params["?code"]
    }
    log.debug "mytaglist.com authorization_code: ${code}"

    if (code) {
        try{
            def getAccessTokenParams = [
                uri: "https://www.mytaglist.com/",
                path: "/oauth2/access_token.aspx",
                query: [
                    grant_type: "authorization_code",
                    client_id: getClientIdForWirelessTagsOauth(),
                    client_secret: "4c3f0cad-48b8-433f-b12d-2da4e8f8893f",
                    code: code,
                    redirect_uri: buildRedirectUrl()
                ],
            ]
            httpPost(getAccessTokenParams) { resp ->
                if (resp.status == 200) {
                    def jsonMap = resp.data
                    if (resp.data) {
                        atomicState.authToken = jsonMap?.access_token
                    } else {
                        log.trace "error = " + resp
                    }
                } else {
                    log.trace "response = " + resp
                }
            }
        } catch ( ex ) {
            atomicState.authToken = null
            log.trace "error = " + ex
        }
    }

    def html
    if (atomicState.authToken) {
        html = """
<!DOCTYPE html>
<html>
<head>
<title>Wireless Tags Connection Successful</title>
<style type="text/css">
    p {
        font-size: 1.5em;
        font-family: 'Swiss 721 W01 Thin';
        text-align: center;
        color: #666666;
    }
</style>
</head>
<body>
    <p>Your Wireless Tags account is now connected to your hub!</p>
    <p>Close this window and finish setup in the app.</p>
</body>
</html>
"""
    } else {
        html = """
<!DOCTYPE html>
<html>
<head>
<title>Wireless Tags Connection Successful</title>
<style type="text/css">
    p {
        font-size: 1.5em;
        font-family: 'Swiss 721 W01 Thin';
        text-align: center;
        color: ##aa3333;
    }
</style>
</head>
<body>
    <p>Problem connecting your Wireless Tags account.</p>
    <p>Close this window and try again in the app.</p>
</body>
</html>
"""
    }

    render contentType: 'text/html', data: html
}

def getEventStates() {
    def tagEventStates = [ 0: "Disarmed", 1: "Armed", 2: "Moved", 3: "Opened", 4: "Closed", 5: "Detected", 6: "Timed Out", 7: "Stabilizing..." ]
    return tagEventStates
}

def pollHandler() {
    log.trace "pollHandler"
    getTagStatusFromServer() 
    updateAllDevices()
}

def updateAllDevices() {
    atomicState.tags.each {device ->
        def dni = device.uuid
        def d = getChildDevice(dni)

        if(d)
        {
            updateDeviceStatus(device, d)
        }
    }
}

def pollSingle(def child) {
    log.trace "pollSingle"
    getTagStatusFromServer() 
    
    def device = atomicState.tags.find { it.uuid == child.device.deviceNetworkId }
    
    if (device) {
        updateDeviceStatus(device, child)
    }
}

def updateDeviceStatus(def device, def d) {
    def tagEventStates = getEventStates()

    // parsing data here
    def data = [
        tagType: convertTagTypeToString(device),
        temperature: safeToDouble(device.temperature).round(1),
        rssi: ((Math.max(Math.min(device.signaldBm, -60),-100)+100)*100/40).toDouble().round(),
        presence: ((device.OutOfRange == true) ? "not present" : "present"),
        battery: batteryVoltageToPercentage(device.batteryVolt),
        switch: ((device.lit == true) ? "on" : "off"),
        humidity: safeToDouble(device.cap).round(),
        contact : (tagEventStates[device.eventState] == "Opened") ? "open" : "closed",
        acceleration  : (tagEventStates[device.eventState] == "Moved") ? "active" : "inactive",
        motion : (tagEventStates[device.eventState] == "Moved") ? "active" : "inactive",
        water : (device.shorted == true) ? "wet" : "dry",
        illuminance : safeToDouble(device.lux).round()
    ]
    d.generateEvent(data)
}

def getPollRateMillis() { return 2 * 1000 }

def getTagStatusFromServer()
{    
    def timeSince = (atomicState.lastPoll != null) ? now() - atomicState.lastPoll : 1000*1000
    
    if ((atomicState.tags == null) || (timeSince > getPollRateMillis())) {
        def result = postMessage("/ethClient.asmx/GetTagList", null)
        atomicState.tags = result?.d
        atomicState.lastPoll = now()
        
    } else {
        log.trace "waiting to refresh from server"
    }
    return atomicState.tags 
}


// Poll Child is invoked from the Child Device itself as part of the Poll Capability
def pollChild( child )
{
    pollSingle(child)

    return null
}

def refreshChild( child )
{
    def id = getTagID(child.device.deviceNetworkId)
    
    if (id != null) {
        // PingAllTags didn't reliable update the tag we wanted so just ping the one
        Map query = [
            "id": id
        ]
        postMessage("/ethClient.asmx/PingTag", query)
        pollSingle( child )
    } else {
        log.trace "Could not find tag"
    }
    
    return null
}

def postMessage(path, def query) {
    log.trace "sending ${path}"
    
    def message
    if (query != null) {
        if (query instanceof String) {
            message = [
                uri: "https://www.mytaglist.com/",
                path: path,        
                headers: ["Content-Type": "application/json", "Authorization": "Bearer ${atomicState.authToken}"],
                body: query
            ]         
        } else {
            message = [
                uri: "https://www.mytaglist.com/",
                path: path,        
                headers: ["Content-Type": "application/json", "Authorization": "Bearer ${atomicState.authToken}"],
                body: toJson(query)
            ]
        }       
    } else {
        message = [
            uri: "https://www.mytaglist.com/",
            path: path,        
            headers: ["Content-Type": "application/json", "Authorization": "Bearer ${atomicState.authToken}"]
        ]    
    }
    
    //dumpMsg(message)
    
    def jsonMap
    try{
        httpPost(message) { resp ->
            if(resp.status == 200)
            {                
                if (resp.data) {
                    log.trace "success"
                    jsonMap = resp.data
                } else {
                    log.trace "error = " + resp
                }
            } else {
                log.debug "http status: ${resp.status}"
                if (resp.status == 500 && resp.data.status.code == 14) {
                    log.debug "Need to refresh auth token?"
                    atomicState.authToken = null
                } else {
                    log.error "Authentication error, invalid authentication method, lack of credentials, etc."
                }
            }            
        }
    } catch ( ex ) {
        //atomicState.authToken = null
        log.trace "error = " + ex
    }
    
    return jsonMap
}

def setSingleCallback(def tag, Map callback, def type) {

    def parameters = "?access_token=${atomicState?.accessToken}&type=$type&"
    
    switch (type) {
        case "update":
            parameters = parameters + "name={0}&id={1}&temperature={2}&cap={3}&lux={4}&batteryVolt={6}"
            break;
        case "water_dried": 
        case "water_detected":
            // 2 params
            parameters = parameters + "name={0}&id={1}"
            break;
        case "oor": 
        case "back_in_range":
        case "motion_timedout":
            // 3 params
            parameters = parameters + "name={0}&time={1}&id={2}"
            break;
        case "door_opened": 
        case "door_closed":     
            parameters = parameters + "name={0}&orientchg={1}&x={2}&y={3}&z={4}&id={5}"
            break;
        case "motion_detected":
            // to do, check if PIR type
               if (getTagTypeInfo(tag).isPIR == true) {
                // pir & als
                parameters = parameters + "name={0}&time={1}&id={2}"
            } else {
                // standard
                parameters = parameters + "name={0}&orientchg={1}&x={2}&y={3}&z={4}&id={5}"
            }
            break;    
    }
    String callbackString = """{"url":"${getFullApiServerUrl()}/urlcallback${parameters}","verb":"GET","content":"","disabled":false,"nat":false}"""
    log.trace "callbackString: ${callbackString}"
    return callbackString
}

def getQuoted(def orig) { return (orig != null) ? "\"${orig}\"": orig }

def useExitingCallback(Map callback) {
    String callbackString = """{"url":"${callback.url}","verb":${getQuoted(callback.verb)},"content":${getQuoted(callback.content)},"disabled":${callback.disabled},"nat":${callback.nat}}"""
    return callbackString
}

def setupCallbacks(def child, def tag) {
    log.trace "setupCallbacks"
    def id = getTagID(child.device.deviceNetworkId)
    
    if (id != null) {
        Map query = [
            "id": id
        ]
        def respMap = postMessage("/ethClient.asmx/LoadEventURLConfig", query)
        
        if (respMap.d != null) {
            
            String message = """{"id":${id},
                "config":{
                "__type":"MyTagList.EventURLConfig",
                "update":${setSingleCallback(tag, respMap.d?.update, "update")},
                "oor":${setSingleCallback(tag, respMap.d?.oor, "oor")},
                "back_in_range":${setSingleCallback(tag, respMap.d?.back_in_range, "back_in_range")},
                "low_battery":${useExitingCallback(respMap.d?.low_battery)},
                "motion_detected":${setSingleCallback(tag, respMap.d?.motion_detected, "motion_detected")},
                "door_opened":${setSingleCallback(tag, respMap.d?.door_opened, "door_opened")},
                "door_closed":${setSingleCallback(tag, respMap.d?.door_closed, "door_closed")},
                "door_open_toolong":${useExitingCallback(respMap.d?.door_open_toolong)},
                "temp_toohigh":${useExitingCallback(respMap.d?.temp_toohigh)},
                "temp_toolow":${useExitingCallback(respMap.d?.temp_toolow)},
                "temp_normal":${useExitingCallback(respMap.d?.temp_normal)},
                "cap_normal":${useExitingCallback(respMap.d?.cap_normal)},
                "too_dry":${useExitingCallback(respMap.d?.too_dry)},
                "too_humid":${useExitingCallback(respMap.d?.too_humid)},
                "water_detected":${setSingleCallback(tag, respMap.d?.water_detected, "water_detected")},
                "water_dried":${setSingleCallback(tag, respMap.d?.water_dried, "water_dried")},
                "motion_timedout":${setSingleCallback(tag, respMap.d?.motion_timedout, "motion_timedout")}
                },
                "applyAll":false}"""
                
            postMessage("/ethClient.asmx/SaveEventURLConfig", message)
            
        }
    }
}

def beep(def child, int len) {
    def id = getTagID(child.device.deviceNetworkId)
    
    if (id != null) {
        Map query = [
            "id": id,
            "beepDuration": len
        ]
        postMessage("/ethClient.asmx/Beep", query)
    } else {
        log.trace "Could not find tag"
    }
    
    return null
}

def light(def child, def on, def flash) {
    def id = getTagID(child.device.deviceNetworkId)
    
    def command = (on == true) ? "/ethClient.asmx/LightOn" : "/ethClient.asmx/LightOff"
    
    if (id != null) {
        Map query = [
            "id": id,
            "flash": flash
        ]
        postMessage(command, query)
    } else {
        log.trace "Could not find tag"
    }
    
    return null
}

def startMotionTimer(def child) {   
    log.trace "start motion timer"

    if (state.motionTimers == null) {
        state.motionTimers = [:]
    }
    
    def delayTime = child.getMotionDecay()
    
    // don't do less than a minute in this way, once WST has the callback working it will be better
    delayTime = (delayTime < 60) ? 60 : delayTime
    
    state.motionTimers[child.device.deviceNetworkId] = now() + delayTime
    
    runIn(delayTime, motionTimerHander)    
}

def motionTimerHander() {
    def more = 0
    def removeList = []
    
    state.motionTimers.each { child, time ->
        if (time <= now()) {
            def tag = getChildDevice(child)
            resMotionDetection(tag)
            removeList.add(child)
        } else {
            if ((more == 0) || (more > time)) {
                more = time
            }
        }
    }
    
    if (more != 0) { 
        log.trace "running again"
        more = more + 5 - now()
        runIn((more < 60) ? 60 : more, motionTimerHander)
    }    
    
    // clean up handled events
    removeList.each {
        state.motionTimers.remove(it)
    }
}

def resMotionDetection(def child) {
    log.trace "turning off motion"
    
    // now turn off in device
    def data = [acceleration: "inactive", motion: "inactive"]
    child.generateEvent(data)
    
    return null
}

def armMotion(def child) {
    def id = getTagID(child.device.deviceNetworkId)
    
    if (id != null) {
        Map query = [
            "id": id,
            "door_mode_set_closed": true
        ]
        postMessage("/ethClient.asmx/Arm", query)
    } else {
        log.trace "Could not find tag"
    }
    
    return null 
}

def disarmMotion(def child) {
    def id = getTagID(child.device.deviceNetworkId)
    
    if (id != null) {
        Map query = [
            "id": id
        ]
        postMessage("/ethClient.asmx/DisArm", query)
    } else {
        log.trace "Could not find tag"
    }
    
    return null 
}


def setMotionMode(def child, def mode, def timeDelay) {
    log.trace "setting door to closed"
        
    def id = getTagID(child.device.deviceNetworkId)
    
    if (id != null) {
        if (mode == "disarmed") {
            disarmMotion(child)
        } else {        
            Map query = [
                "id": id
            ]
            def result = postMessage("/ethClient.asmx/LoadMotionSensorConfig", query)

            if (result?.d) {

                switch (mode) {
                    case "accel":
                        result.d.door_mode = false
                        break
                    case "door":
                        result.d.door_mode = true
                        break
                }

                result.d.auto_reset_delay = timeDelay

                String jsonString = toJson(result.d)
                jsonString = toJson(result.d).substring(1, toJson(result.d).size()-1)

                String queryString = """{"id":${id},
                "config":{"__type":"MyTagList.MotionSensorConfig",${jsonString}},
                "applyAll":false}"""

                postMessage("/ethClient.asmx/SaveMotionSensorConfig", queryString)
                            
                armMotion(child)
            }
        }
    } else {
        log.trace "Could not find tag"
    }
    
    return null    
}

def getTagID(def uuid) {
    
    return atomicState.tags.find{ it.uuid == uuid}?.slaveId
}

def getTagUUID(def id) {
    
    return atomicState.tags.find{ it.slaveId == id}?.uuid
}

def getTagTypeInfo(def tag) {
    Map tagInfo = [:]

    tagInfo.isMsTag = (tag.tagType == 12 || tag.tagType == 13);
    tagInfo.isMoistureTag = (tag.tagType == 32 || tag.tagType == 33);
    tagInfo.hasBeeper = (tag.tagType == 13 || tag.tagType == 12);
    tagInfo.isReed = (tag.tagType == 52 || tag.tagType == 53);
    tagInfo.isPIR = (tag.tagType == 72 || tag.tagType == 26);
    tagInfo.isKumostat = (tag.tagType == 62);
    tagInfo.isHTU = (tag.tagType == 52 || tag.tagType == 62 || tag.tagType == 72 || tag.tagType == 13);
    
    return tagInfo
} 

def getTagVersion(def tag) {
    if (tag.version1 == 2) {
        if (tag.rev == 14) return " (v2.1)";
        else return " (v2.0)";
    }
    if (tag.tagType != 12) return "";
    if (tag.rev == 0) return " (v1.1)";
    else if (tag.rev == 1) return ' (v1.2)';
    else if (tag.rev == 11) return " (v1.3)";
    else if (tag.rev == 12) return " (v1.4)";
    else if (tag.rev == 13) return " (v1.5)";
    else return "";
}

def convertTagTypeToString(def tag) {
    def tagString = "Unknown"
    
    switch (tag.tagType) {
        case 12:
            tagString = "MotionSensor"
            break;
        case 13:
            tagString = "MotionHTU"
            break;
        case 26:
            tagString = "ProALS"
            break;
        case 72:
            tagString = "PIR"
            break;
        case 52:
            tagString = "ReedHTU"
            break;   
        case 53:
            tagString = "Reed"
            break;   
        case 62:
            tagString = "Kumostat"
            break; 
        case 32:
        case 33:
            tagString = "Moisture"
            break;     
    }

    return tagString + getTagVersion(tag)
}

def batteryVoltageToPercentage(batteryVolt) {
    if (batteryVolt >= 3) {
        return 100
    } else if (batteryVolt <= 2.6) {
        return 1
    }
    return (((batteryVolt - 2.6)/0.4) * 100).toDouble().round()
}

def toJson(Map m)
{
    return new groovy.json.JsonBuilder(m).toString()
}

def toQueryString(Map m)
{
    return m.collect { k, v -> "${k}=${URLEncoder.encode(v.toString())}" }.sort().join("&")
}

def getClientIdForWirelessTagsOauth() { "bb2c712a-5c37-4e31-8a00-8a6c1deeb3d7" }

def debugEvent(message, displayEvent) {

    def results = [
        name: "appdebug",
        descriptionText: message,
        displayed: displayEvent
    ]
    log.debug "Generating AppDebug Event: ${results}"
    sendEvent (results)

}
