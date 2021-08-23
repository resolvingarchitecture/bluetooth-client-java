package ra.bluetooth;

import ra.common.Envelope;
import ra.common.identity.PublicKey;
import ra.common.network.Network;
import ra.common.network.NetworkPeer;
import ra.common.network.NetworkStatus;
import ra.common.tasks.BaseTask;
import ra.common.tasks.TaskRunner;

import javax.bluetooth.*;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Discovers nearby devices, filters them out by RA ID, then makes a NetOp Request for their network knowledge.
 */
public class BluetoothPeerDiscovery extends BaseTask implements DiscoveryListener {

    private static final Logger LOG = Logger.getLogger(BluetoothPeerDiscovery.class.getName());

    private final Object inquiryCompletedEvent = new Object();

    public BluetoothService service;
    public RemoteDevice remoteDevice;
    public NetworkPeer remotePeer;

    public BluetoothPeerDiscovery(BluetoothService service, TaskRunner taskRunner) {
        super(BluetoothPeerDiscovery.class.getSimpleName(), taskRunner);
        this.service = service;
    }

    public BluetoothPeerDiscovery(BluetoothService service, TaskRunner taskRunner, RemoteDevice remoteDevice, NetworkPeer remotePeer) {
        this(service, taskRunner);
        this.remoteDevice = remoteDevice;
        this.remotePeer = remotePeer;
    }

    @Override
    public Boolean execute() {
        running = true;
        // Update service cache with bluetooth radio cache
        try {
            RemoteDevice[] devices = LocalDevice.getLocalDevice().getDiscoveryAgent().retrieveDevices(DiscoveryAgent.CACHED);
            if(devices!=null) {
                synchronized (service.devices) {
                    for (RemoteDevice device : devices) {
                        service.devices.put(device.getBluetoothAddress(), device);
                    }
                }
            }
        } catch (BluetoothStateException e) {
            LOG.warning(e.getLocalizedMessage());
            return false;
        }
        try {
            synchronized (inquiryCompletedEvent) {
                boolean inquiring = LocalDevice.getLocalDevice().getDiscoveryAgent().startInquiry(DiscoveryAgent.GIAC, this);
                if (inquiring) {
                    LOG.info("wait for device inquiry to complete...");
                    inquiryCompletedEvent.wait();
                }
            }
        } catch (BluetoothStateException e) {
            if("Bluetooth Device is not available".equals(e.getLocalizedMessage())) {
                LOG.warning("PLease turn on the bluetooth radio.");
            } else {
                LOG.warning(e.getLocalizedMessage());
                return false;
            }
        } catch (InterruptedException e) {
            LOG.warning(e.getLocalizedMessage());
            return false;
        }
        return true;
    }

    /**
     * Inbound separate thread from Bluecove Bluez from starting inquiry in above execute()
     * @param remoteDevice
     * @param deviceClass
     */
    @Override
    public void deviceDiscovered(RemoteDevice remoteDevice, DeviceClass deviceClass) {
        String msg = "Device " + remoteDevice.getBluetoothAddress() + " discovered.";
        NetworkPeer peer = service.peersOfPeers.get(remoteDevice.getBluetoothAddress());
        try {
            if(peer==null) {
                peer = new NetworkPeer(Network.Bluetooth, remoteDevice.getFriendlyName(true), "1234");
                PublicKey pk = peer.getDid().getPublicKey();
                pk.setAddress(remoteDevice.getBluetoothAddress());
                pk.addAttribute("isAuthenticated", remoteDevice.isAuthenticated());
                pk.addAttribute("isEncrypted", remoteDevice.isEncrypted());
                pk.addAttribute("isTrustedDevice", remoteDevice.isTrustedDevice());
                pk.addAttribute("majorDeviceClass", deviceClass.getMajorDeviceClass());
                pk.addAttribute("minorDeviceClass", deviceClass.getMinorDeviceClass());
                pk.addAttribute("serviceClasses", deviceClass.getServiceClasses());
                service.devices.put(remoteDevice.getBluetoothAddress(), remoteDevice);
            } else {
                // TODO: Update peer

            }
            service.getNetworkState().networkStatus = NetworkStatus.CONNECTED;

            // Now request its services
            UUID obexObjPush = ServiceClasses.getUUID(ServiceClasses.OBEX_OBJECT_PUSH);
//        if ((properties != null) && (properties.size() > 0)) {
//            objPush = new UUID(args[0], false);
//        }
//        UUID obexFileXfer = ServiceClasses.getUUID(ServiceClasses.OBEX_FILE_TRANSFER);

            UUID[] searchUuidSet = new UUID[]{obexObjPush};
//        UUID[] searchUuidSet = new UUID[] { obexObjPush, obexFileXfer };

            int[] attrIDs = new int[]{
                    0x0100 // Service name
            };

            peer = new NetworkPeer(Network.Bluetooth);
            peer.getDid().setUsername(remoteDevice.getFriendlyName(true));
            peer.getDid().getPublicKey().setAddress(remoteDevice.getBluetoothAddress());
            LOG.info("Searching services on " + peer.getDid().getUsername() + " address=" + peer.getDid().getPublicKey().getAddress());
            LocalDevice.getLocalDevice()
                    .getDiscoveryAgent()
                    .searchServices(attrIDs, searchUuidSet, remoteDevice, new BluetoothPeerDiscovery(service, taskRunner, remoteDevice, peer));

            lastCompletionTime = System.currentTimeMillis();

        } catch (IOException e) {
            LOG.warning(e.getLocalizedMessage());
        }
        LOG.info(msg);
    }

    @Override
    public void inquiryCompleted(int discType) {
        switch (discType) {
            case DiscoveryListener.INQUIRY_COMPLETED : {
                LOG.info("Bluetooth inquiry completed.");
                break;
            }
            case DiscoveryListener.INQUIRY_TERMINATED : {
                LOG.warning("Bluetooth inquiry terminated.");break;
            }
            case DiscoveryListener.INQUIRY_ERROR : {
                LOG.warning("Bluetooth inquiry errored.");break;
            }
            default: {
                LOG.warning("Unknown Bluetooth inquiry result code: "+discType);
            }
        }
        synchronized(inquiryCompletedEvent){
            inquiryCompletedEvent.notifyAll();
        }
        lastCompletionTime = System.currentTimeMillis();
        running = false;
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
