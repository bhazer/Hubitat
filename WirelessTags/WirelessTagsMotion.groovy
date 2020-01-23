/**
 *  Wireless Sensor Tag Motion Device Driver
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
metadata {
    definition (name: "Wireless Sensor Tag", namespace: "bhazer", author: "Bart Hazer") {
        capability "Presence Sensor"
        capability "Acceleration Sensor"
        capability "Motion Sensor"
        capability "Tone"
        capability "Relative Humidity Measurement"
        capability "Temperature Measurement"
        capability "Signal Strength"
        capability "Battery"
        capability "Refresh"
        capability "Polling"
        capability "Switch"
        capability "Contact Sensor"
        capability "Illuminance Measurement"

        command "generateEvent"
        command "armMotion"
        command "disarmMotion"
        command "setDoorClosedPosition"
        command "initialSetup"

        attribute "tagType","string"
        attribute "motionMode", "string"
    }

    simulator {
        // TODO: define status and reply messages here
    }

    tiles {
        standardTile("acceleration", "device.acceleration") {
            state("active", label:'${name}', icon:"st.motion.acceleration.active", backgroundColor:"#53a7c0")
            state("inactive", label:'${name}', icon:"st.motion.acceleration.inactive", backgroundColor:"#ffffff")
        }
        standardTile("motion", "device.motion") {
            state("active", label:'${name}', icon:"st.motion.motion.active", backgroundColor:"#53a7c0")
            state("inactive", label:'${name}', icon:"st.motion.motion.inactive", backgroundColor:"#ffffff")
        }
        valueTile("temperature", "device.temperature") {
            state("temperature", label:'${currentValue}°',
                backgroundColors:[
                    [value: 31, color: "#153591"],
                    [value: 44, color: "#1e9cbb"],
                    [value: 59, color: "#90d2a7"],
                    [value: 74, color: "#44b621"],
                    [value: 84, color: "#f1d801"],
                    [value: 95, color: "#d04e00"],
                    [value: 96, color: "#bc2323"]
                ]
            )
        }
        valueTile("humidity", "device.humidity", inactiveLabel: false) {
            state "humidity", label:'${currentValue}% humidity', unit:""
        }
        valueTile("rssi", "device.rssi", inactiveLabel: false, decoration: "flat") {
            state "rssi", label:'${currentValue}% signal', unit:""
        }
        standardTile("presence", "device.presence", canChangeBackground: true) {
            state "present", labelIcon:"st.presence.tile.present", backgroundColor:"#53a7c0"
            state "not present", labelIcon:"st.presence.tile.not-present", backgroundColor:"#ffffff"
        }
        standardTile("beep", "device.beep", decoration: "flat") {
            state "beep", label:'', action:"tone.beep", icon:"st.secondary.beep", backgroundColor:"#ffffff"
        }
        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false) {
            state "battery", label:'${currentValue}% battery', unit:"V"
        }
        standardTile("refresh", "device.temperature", inactiveLabel: false, decoration: "flat") {
            state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        standardTile("button", "device.switch") {
            state "off", label: 'Off', action: "switch.on", icon: "st.switches.light.off", backgroundColor: "#ffffff", nextState: "on"
            state "on", label: 'On', action: "switch.off", icon: "st.switches.light.on", backgroundColor: "#79b821", nextState: "off"
        }
        valueTile("type", "device.tagType", decoration: "flat") {
            state "default", label:'${currentValue}'
        }
        standardTile("contact", "device.contact") {
            state("open", label:'${name}', icon:"st.contact.contact.open", backgroundColor:"#ffa81e")
            state("closed", label:'${name}', icon:"st.contact.contact.closed", backgroundColor:"#79b821")
        }
        valueTile("setdoorclosed", "device.temperature", inactiveLabel: false, decoration: "flat") {
            state "default", label:'Arm & Set Door Closed Position', action:"setDoorClosedPosition", nextState: "default"
        }
        valueTile("illuminance", "device.illuminance", inactiveLabel: false) {
            state "illuminance", label:'${currentValue}% illuminance', unit:""
        }
        main(["temperature", "acceleration", "motion", "presence", "humidity", "contact", "illuminance"])
        details(["temperature", "presence", "humidity", "acceleration", "motion", "contact", "button", "refresh", "type", "doorClosed", "setdoorclosed", "beep", "rssi", "battery", "illuminance"])
    }

    preferences {
            input "motionDecay", "number", title: "Motion Rearm Time", description: "Seconds (min 60 for now)", defaultValue: 60, required: true, displayDuringSetup: true
    }
}

// parse events into attributes
def parse(String description) {
    log.debug "Parsing '${description}'"
}

// handle commands
def beep() {
    log.debug "Executing 'beep'"
    parent.beep(this, 3)
}

def on() {
    log.debug "Executing 'on'"
    parent.light(this, true, false)
    sendEvent(name: "switch", value: "on")
}

def off() {
    log.debug "Executing 'off'"
    parent.light(this, false, false)
    sendEvent(name: "switch", value: "off")
}

void poll() {
    log.debug "poll"
    parent.pollChild(this)
}

def refresh() {
    log.debug "refresh"
    parent.refreshChild(this)
}

def armMotion() {
    log.trace "armMotion"
    parent.armMotion(this)
}

def disarmMotion() {
    log.trace "disarmMotion"
    parent.disarmMotion(this)
}

def setDoorClosedPosition() {
    log.debug "set door closed pos"
    parent.disarmMotion(this)
    parent.armMotion(this)
}

def initialSetup() {
}

def getMotionDecay() {
    def timer = (settings.motionDecay != null) ? settings.motionDecay.toInteger() : 60
    return timer
}

def updated() {
    log.trace "updated"
}

void generateEvent(Map results) {
    log.debug "parsing data $results"

    if(results) {
        results.each { name, value ->
            if (name == "water") {
                // Doesn't have this capability. Do nothing.
            } else if (name == "temperature") {
                sendEvent(name: name, value: getTemperature(value), unit: getTemperatureScale())
            } else {
                sendEvent(name: name, value: value)
            }
        }
    }
}

def getTemperature(value) {
    def celsius = value
    if(getTemperatureScale() == "C"){
        return celsius
    } else {
        return celsiusToFahrenheit(celsius).toDouble().round(1)
    }
}
