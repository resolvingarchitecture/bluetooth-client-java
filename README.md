# Bluetooth
Client to local Bluetooth radio supporting Bluetooth Low-Energy to
integrate with Bluetooth Mesh networks.

Currently supporting Bluetooth version 4. Version 5.1 Bluetooth Mesh on roadmap.


## Features
* Peer Discovery - uses local radio to search for other Bluetooth devices within range. For each device, it then
requests service information from each discovered device. If there is a service id recognized as a RA based id,
it requests peer information from that device.

## Install BlueCove
* Ensure an OpenJDK 11 or higher is installed.

### Ubuntu
sudo apt-get install libbluetooth-dev

## Development

### Links
* http://www.bluecove.org/bluecove/apidocs/index.html
* http://bluecove.org/bluecove-examples/index.html
* http://bluecove.org/bluecove-gpl/index.html
* https://code.google.com/archive/p/bluecove/wikis/Documentation.wiki
* http://www.bluecove.org/bluecove/apidocs/javax/bluetoothSensor/DiscoveryListener.html
* https://www.bluetoothSensor.com/specifications/assigned-numbers/service-discovery/
