package ra.bluetooth;

import ra.common.Envelope;
import ra.common.network.NetworkPeer;

import javax.bluetooth.*;
import java.io.IOException;
import java.util.logging.Logger;

public class BluetoothPeerDiscovery implements DiscoveryListener {

    private static final Logger LOG = Logger.getLogger(BluetoothPeerDiscovery.class.getName());

    public BluetoothService service;
    public RemoteDevice remoteDevice;
    public NetworkPeer remotePeer;

    public BluetoothPeerDiscovery(BluetoothService service, RemoteDevice remoteDevice, NetworkPeer remotePeer) {
        this.service = service;
        this.remoteDevice = remoteDevice;
        this.remotePeer = remotePeer;
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

            remotePeer.getDid().getPublicKey().addAttribute("serviceURL", url);

            DataElement serviceName = serviceRecord.getAttributeValue(0x0100);
            if (serviceName != null) {
                LOG.info("service " + serviceName.getValue() + " found " + url);
                remotePeer.getDid().getPublicKey().addAttribute("serviceName", serviceName);
            } else {
                LOG.info("service found " + url);
            }

            DataElement id = serviceRecord.getAttributeValue(0x5555);
            if (id != null) {
                String idStr = (String)id.getValue();
                LOG.info("RA id found: " + idStr);
                NetworkPeer remoteSavedPeer = service.lookupRemotePeer(idStr);
                if(remoteSavedPeer!=null) {
                    if(!url.equals(remoteSavedPeer.getDid().getPublicKey().getAttribute("serviceURL"))) {
                        // URL changed
                        remoteSavedPeer.getDid().getPublicKey().addAttribute("serviceURL", url);
                    }
                }
                remotePeer.setId(idStr);
                Envelope e = Envelope.documentFactory();
                e.addExternalRoute(BluetoothService.class.getName(), BluetoothService.OPERATION_PEER_STATUS, service.getNetworkState().localPeer, remotePeer);
                service.sendOut(e);
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
