# Bluetooth
Client to local Bluetooth radio supporting Bluetooth Low-Energy to
integrate with Bluetooth Mesh networks.

## Features
* Peer Discovery - uses local radio to search for other Bluetooth devices within range. For each device, it then
requests service information from each discovered device. If there is a service id recognized as a RA based id,
it requests peer information from that device.

## Install BlueCove
* Ensure an OpenJDK 11 or higher is installed.

### Ubuntu
sudo apt-get install libbluetooth-dev

### Raspberry Pi
1. Verify Bluez installed: ```bluetoothd -v```
1. If not installed, follow these directions: https://3pl46c46ctx02p7rzdsvsg21-wpengine.netdna-ssl.com/wp-content/uploads/2020/04/Developer-Study-Guide-How-to-Deploy-BlueZ-on-a-Raspberry-Pi-Board-as-a-Bluetooth-Mesh-Provisioner.pdf
1. If installed, but not version 5.54, install this version.

## Development

### Links
* http://www.bluecove.org/bluecove/apidocs/index.html
* http://bluecove.org/bluecove-examples/index.html
* http://bluecove.org/bluecove-gpl/index.html
* https://code.google.com/archive/p/bluecove/wikis/Documentation.wiki
* http://www.bluecove.org/bluecove/apidocs/javax/bluetoothSensor/DiscoveryListener.html
* https://www.bluetoothSensor.com/specifications/assigned-numbers/service-discovery/
