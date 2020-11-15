package ra.bluetooth;

import ra.common.identity.PublicKey;
import ra.common.network.Network;
import ra.common.network.NetworkPeer;
import ra.common.network.NetworkStatus;
import ra.util.tasks.BaseTask;
import ra.util.tasks.TaskRunner;

import javax.bluetooth.*;
import java.io.IOException;
import java.util.logging.Logger;

public class BluetoothDeviceDiscovery extends BaseTask implements DiscoveryListener {

    private final static Logger LOG = Logger.getLogger(BluetoothDeviceDiscovery.class.getName());

    private final BluetoothService service;

    private final Object inquiryCompletedEvent = new Object();


    public BluetoothDeviceDiscovery(BluetoothService service, TaskRunner taskRunner) {
        super(BluetoothDeviceDiscovery.class.getSimpleName(), taskRunner);
        this.service = service;
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

    @Override
    public void deviceDiscovered(RemoteDevice remoteDevice, DeviceClass deviceClass) {
        String msg = "Device " + remoteDevice.getBluetoothAddress() + " discovered.";
        NetworkPeer peer = service.peersInDiscovery.get(remoteDevice.getBluetoothAddress());
        try {
            if(peer==null) {
                peer = new NetworkPeer(Network.Bluetooth.name(), remoteDevice.getFriendlyName(true), "1234");
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

            peer = new NetworkPeer(Network.Bluetooth.name());
            peer.getDid().setUsername(remoteDevice.getFriendlyName(true));
            peer.getDid().getPublicKey().setAddress(remoteDevice.getBluetoothAddress());
            LOG.info("Searching services on " + peer.getDid().getUsername() + " address=" + peer.getDid().getPublicKey().getAddress());
            LocalDevice.getLocalDevice()
                    .getDiscoveryAgent()
                    .searchServices(attrIDs, searchUuidSet, remoteDevice, new BluetoothPeerDiscovery(service, remoteDevice, peer));

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

    }

    @Override
    public void serviceSearchCompleted(int transID, int respCode) {

    }
}
