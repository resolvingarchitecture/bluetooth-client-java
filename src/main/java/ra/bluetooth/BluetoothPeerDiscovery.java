package ra.bluetooth;

import ra.common.network.NetworkPeer;

import javax.bluetooth.*;
import java.io.IOException;
import java.util.logging.Logger;

public class BluetoothPeerDiscovery implements DiscoveryListener {

    private static final Logger LOG = Logger.getLogger(BluetoothPeerDiscovery.class.getName());

    public BluetoothService service;
    public RemoteDevice remoteDevice;
    public NetworkPeer peer;

    public BluetoothPeerDiscovery(BluetoothService service, RemoteDevice remoteDevice, NetworkPeer peer) {
        this.service = service;
        this.remoteDevice = remoteDevice;
        this.peer = peer;
    }

    @Override
    public void deviceDiscovered(RemoteDevice remoteDevice, DeviceClass deviceClass) {
        LOG.warning("Implemented in BluetoothDeviceDiscovery");
    }

    @Override
    public void inquiryCompleted(int i) {
        LOG.warning("Implemented in BluetoothDeviceDiscovery");
    }

    @Override
    public void servicesDiscovered(int transID, ServiceRecord[] serviceRecords) {
        LOG.info(serviceRecords.length+" Services returned for transID: "+transID);
        for (ServiceRecord serviceRecord : serviceRecords) {
            String url = serviceRecord.getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            if (url == null) {
                LOG.info("Not a NoAuthN-NoEncrypt service.");
                continue;
            }

            peer.getDid().getPublicKey().addAttribute("serviceURL", url);

            DataElement serviceName = serviceRecord.getAttributeValue(0x0100);
            if (serviceName != null) {
                LOG.info("service " + serviceName.getValue() + " found " + url);
                peer.getDid().getPublicKey().addAttribute("serviceName", serviceName);
            } else {
                LOG.info("service found " + url);
            }

            DataElement id = serviceRecord.getAttributeValue(0x5555);
            if (id != null) {
                LOG.info("RA id found: " + id);
                peer.setId((String) id.getValue());
                service.peersInDiscovery.put(peer.getId(), peer);
            }
        }
    }

    @Override
    public void serviceSearchCompleted(int transID, int respCode) {
        LOG.info("transID: "+transID);
        switch(respCode) {
            case DiscoveryListener.SERVICE_SEARCH_COMPLETED : {
                LOG.info("Bluetooth search completed.");break;
            }
            case DiscoveryListener.SERVICE_SEARCH_TERMINATED : {
                LOG.warning("Bluetooth search terminated.");break;
            }
            case DiscoveryListener.SERVICE_SEARCH_ERROR : {
                LOG.warning("Bluetooth search errored. Removing device from list.");
                service.devices.remove(remoteDevice.getBluetoothAddress());
                break;
            }
            case DiscoveryListener.SERVICE_SEARCH_NO_RECORDS : {
                try {
                    LOG.info("Bluetooth search found no records for device (address; "+remoteDevice.getBluetoothAddress()+", name: "+remoteDevice.getFriendlyName(false)+").");
                } catch (IOException e) {
                    LOG.info("Bluetooth search found no records for device (address; "+remoteDevice.getBluetoothAddress()+").");
                }
            }
            case DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE : {
                try {
                    LOG.info("Bluetooth search device (address; "+remoteDevice.getBluetoothAddress()+", name: "+remoteDevice.getFriendlyName(false)+") not reachable.");
                } catch (IOException e) {
                    LOG.info("Bluetooth search device (address; "+remoteDevice.getBluetoothAddress()+") not reachable.");
                }
                break;
            }
            default: {
                LOG.warning("Unknown Bluetooth search result: "+respCode);
            }
        }
    }

}
