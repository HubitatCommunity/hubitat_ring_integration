# Hubitat Ring Integration (Unofficial) #

**Notice!!!**

First and foremost I need to warn any who venture to use this integration.  This integration is provided for fun without hope of warranty or safety or stable or permanent use. Ring are not official partners with Hubitat or myself and all of the interactions between the Hubitat hub and Ring's cloud servers in this integration are via the private API calls that Ring does not publish or give permission to use for this purpose. I wrote this integration for fun and I do not trust it with my safety. It's a hobby project. (That said, it is well built and mostly stable. On the hubitat side the hub slows down sometimes and drops the websocket connection but there is a watchdog that starts it back up.)

If some part of this integration does not work now or stops working in the future I make no gaurantees and this is provided "AS IS" without hope of service or warranty.  If you use this integration you agree to hold me unresponsible for what may happen to your Ring account in the event that Ring deems this type of usage of the API unreasonable.  You agree to hold me unresponsible for what may happen to your home, personal property, self, family, etc.  You agree to hold me unresponsible.  End of story.

If that sounds okay then continue onwards...

I don't expect this to be a perfect experience because I'm not providing a lot of direction and I don't have documented very well which drivers go to which devices beyond the names of the drivers and files. And they have A LOT of devices.

Everyone should start by installing the app.  From there, there are two types of devices; devices that communicate via classic HTTP calls and devices that communicate via websockets.  It roughly breaks down like this:

- Security cameras, doorbells and chimes (classic HTTP devices)
- Beams devices (websocket devices)
- Security devices (websocket devices)

The app can interact directly with the non-websocket devices. The driver for the API device is required for all of the websocket devices. The dependency heirarchy will look like this a little:

                         App
             /                        \
        websocket                cameras/chimes/doorbells
          device
        /                \
     security          beams
     devices          devices

Before you install any classic HTTP devices know that since we are not Ring partners we cannot get motion and ring notifications pushed to us.  Because of this you have three options.  One option is to poll for them.  Yes, this is horrible and for that reason I do not poll myself.  However, I added this functionality because it seems to work for the home bridge project.  AND...  I know that I will spend forever explaining why I didn't add it if I don't.  Now I will probably just spend forever explaining why you can't poll more often for dings or dings are missed...  Option two is to setup IFTTT applets for each motion type (ring or motion) for each device.  There is fairly robust documentation for how to do this within the app so I will only cover it briefly here.  You must enable OAuth on the app and authorize Ring to IFTTT.  Then you create applets on Ring events that call into the "Unofficial Ring Connect" app using an OAuth token by way of web service calls from the "Webhooks" IFTTT service.  If it sounds complicated, don't worry.  After you install the app you can navigate through to the IFTTT page and learn more.  Finally, the third option is to use SmartThings and the hublink app and have separate devices for status.  (I have separate devices and I use the SmartThings integration with hublink).

I also added the ability for each light device to poll for its light status.  I don't use this either.  I use these devices for control.  I do not use them for status.  I don't ever need to know their light status therefore I don't care what it is and I don't poll for light status.

You do NOT need to install all of the device drivers in this repository.  You should be able to get away with installing the drivers for just the devices you own and have registered.  Here are the device to driver mappings roughly:

## The app ##

- [unofficial-ring-connect.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/apps/unofficial-ring-connect.groovy) - Required for all.  (does authentication and communication)

## Children of the app ##

- [ring-api-virtual-device.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-api-virtual-device.groovy) - This is the "Ring API Virtual Device" or websocket device. Required if you have a Ring Alarm hub or a Ring Beams (Smart Lighting) bridge
- [ring-virtual-chime.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-chime.groovy) - Chime or Chime Pro
- [ring-virtual-light-with-siren.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-light-with-siren.groovy) - Floodlight Cam, Spotlight Cam
- [ring-virtual-light.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-light.groovy) - Spotlight Cam Battery (A few devices where the siren call is different and I haven't reverse engineered it yet.)
- [ring-virtual-camera-with-siren.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-camera-with-siren.groovy) - Indoor Cam, Stick Up Cam
- [ring-virtual-camera.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-camera.groovy) - Doorbells

## Children of the Ring API Virtual Device (websocket device) ##

### Ring Alarm ###

- [ring-virtual-alarm-hub.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-alarm-hub.groovy) - Ring Alarm Hub.  Required to create alarm devices
- [ring-virtual-alarm-range-extender.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-alarm-range-extender.groovy) - Range Extender (1st and 2nd Gen)
- [ring-virtual-alarm-smoke-co-listener.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-alarm-smoke-co-listener.groovy) - Smoke/CO2 Listener
- [ring-virtual-contact-sensor.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-contact-sensor.groovy) - Ring Alarm Contact Sensor (1st and 2nd Gen) and any Z-Wave (Ecolink) tilt sensor that connect to Ring Alarm.  Additionally used for contact sensors connected to the Retrofit Alarm Kit
- [ring-virtual-motion-sensor.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-motion-sensor.groovy) - Ring Alarm Motion Sensor (1st or 2nd Gen)
- [ring-virtual-flood-freeze-sensor.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-flood-freeze-sensor.groovy) Ring Flood & Freeze Sensor
- [ring-virtual-keypad.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-keypad.groovy) - Ring Alarm Keypad (1st or 2nd Gen)
- [ring-virtual-lock.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-lock.groovy) - Any Z-Wave lock that connects to Ring Alarm
- [ring-virtual-siren.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-siren.groovy) - Any Z-wave (Dome) siren that conects to Ring Alarm
- [ring-virtual-switch.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-switch.groovy) - Any Z-Wave switch that connects to Ring Alarm
- [ring-virtual-smoke-alarm.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-smoke-alarm.groovy) Any Z-Wave (First-Alert) smoke detector that connects to Ring Alarm (First Alert)
- [ring-virtual-co-alarm.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-co-alarm.groovy) Any Z-Wave (First Alert) CO detector that connects to Ring Alarm.  Note that these devices typically show as different devices on the Ring side so they show as different devices on the Hubitat side as well
- [ring-virtual-retrofit-alarm-kit.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-keypad.groovy) - Ring Retrofit Alarm Kit

### Ring Security Lighting (Ring Bridge/Beams) ###

- [ring-virtual-beams-bridge.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-beams-bridge.groovy) - Smart Lighting (Beams) bridge.  Required to create security lighting devices
- [ring-virtual-beams-group.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-beams-group.groovy) - Smart Lighting Group
- [ring-virtual-beams-light.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-beams-light.groovy) - Smart Lighting Light with Motion Sensor
- [ring-virtual-beams-motion-sensor.groovy](https://github.com/HubitatCommunity/hubitat_ring_integration/blob/master/src/drivers/ring-virtual-beams-motion-sensor.groovy) - Smart Lighting Motion Sensor

The app will create the camera, chime and doorbell devices automatically.  However, because it was far easier the security and beams devices are NOT created automatically via the app's discover devices functionality.  Once you add the "Ring API Virtual Device" from the app you must go and click "Create Devices" on your respective device to get its websocket children device(s) to create.  For example, if you have alarm devices go to the alarm hub device's edit page and click the "Create Devices" command.

## Brief Installation Instructions (**IF YOU ONLY READ ONE THING LET IT BE THIS SECTION**) ##

The packages can be installed either manually or through the Hubitat Package Manager.

### Using Hubitat Package Manager ###

- Make sure Hubitat Package Manager is installed and working. See [hubitat-packagemanager](https://github.com/HubitatCommunity/hubitatpackagemanager)
- This package can be found in the Integrations section, or you can install by URL using this link: [packageManifest.json](https://raw.githubusercontent.com/HubitatCommunity/hubitat_ring_integration/master/packageManifest.json)
- Make sure to select the optional device drivers that you will need when prompted

### Manual Install ###

- Go to github.com via the links above and install the custom app code.  (Install custom apps using the "Apps Code" link in the left navigation of the Hubitat hub.)
- Also on github.com get the code for all of the devices you need using the list above.  If you have alarm or security lighting devices you also need the "Ring API Virtual Device" driver.  (Install custom drivers using the "Drivers Code" link in the left navigation of the Hubitat hub.)

### Configuration ###

- Create an instance of the app. (Create instances of custom apps using the "Apps" link in the left navigation of the Hubitat hub, Add User App, then hit Done)
- Now go back into App 'Unofficial Ring Connect'
- Authenticate to Ring in the app by giving it your Ring credentials and choose your location.
- Click the "Discover Devices" link in the app.  If you have alarm or security devices be sure to also pick to install the "Ring Virtual API Device".  The app will create any devices you select here for you.
- **You won't get motion or ring notifications or events on cameras and doorbells until you configure them.**  There are instructions in the app to set them up under the "Configure the way that Hubitat will get motion alert and ring events" link.  **It is highly recommended to use IFTTT for notifications.**  You can poll for events but it is often unreliable.
- You are done if you do not have alarm or security lighting devices.
- If you have alarm or security lighting devices you will need to go to the "Ring Virtual API Device" and make sure it is connected by looking at its current state for websocket.
- If it isn't connected, hit the "Initialize" command button
- If it is connected, the API device should have up to two children devices already created.  If you have alarm devices, the alarm hub should be created.  If you have security lighting devices the beams bridge should be created.  Go to each of these devices and click its "Create Devices" command button.  This will automatically create the alarm and security devices.
- At no point should you create a device yourself.  The only thing you need to create is the app instance.  The app and API device create everything else.
