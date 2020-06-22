package ra.bluetooth;

import javax.bluetooth.*;
import java.io.IOException;
import java.util.logging.Logger;

public class BluetoothServiceDiscovery extends NetworkTask implements DiscoveryListener {

    private static final Logger LOG = Logger.getLogger(io.onemfive.network.sensors.bluetooth.BluetoothServiceDiscovery.class.getName());

    private final Object serviceSearchCompletedEvent = new Object();

    private BluetoothSensor sensor;
    private PeerManager peerManager;

    private RemoteDevice currentDevice;
    private NetworkPeer currentPeer;

    private int result;

    public BluetoothServiceDiscovery(PeerManager peerManager, BluetoothSensor sensor, TaskRunner taskRunner) {
        super(io.onemfive.network.sensors.bluetooth.BluetoothServiceDiscovery.class.getName(), taskRunner, sensor);
        this.peerManager = peerManager;
        this.sensor = sensor;
    }

    public int getResult() {
        return result;
    }

    @Override
    public Boolean execute() {
        running = true;
        UUID obexObjPush = ServiceClasses.getUUID(ServiceClasses.OBEX_OBJECT_PUSH);
//        if ((properties != null) && (properties.size() > 0)) {
//            objPush = new UUID(args[0], false);
//        }
//        UUID obexFileXfer = ServiceClasses.getUUID(ServiceClasses.OBEX_FILE_TRANSFER);

        UUID[] searchUuidSet = new UUID[] { obexObjPush };
//        UUID[] searchUuidSet = new UUID[] { obexObjPush, obexFileXfer, oneMFiveEnvPush, oneMFiveBinXfer };

        int[] attrIDs =  new int[] {
                0x0100 // Service name
        };
        LOG.info(sensor.devices.size()+" devices to search services on...");
        for(RemoteDevice device : sensor.devices.values()) {
            try {
                synchronized (serviceSearchCompletedEvent) {
                    currentDevice = device;
                    currentPeer = new NetworkPeer(Network.Bluetooth);
                    currentPeer.getDid().setUsername(device.getFriendlyName(true));
                    currentPeer.getDid().getPublicKey().setAddress(device.getBluetoothAddress());
                    LOG.info("Searching services on " + currentPeer.getDid().getUsername() + " address=" + currentPeer.getDid().getPublicKey().getAddress());
                    LocalDevice.getLocalDevice().getDiscoveryAgent().searchServices(attrIDs, searchUuidSet, device, this);
                    serviceSearchCompletedEvent.wait();
                }
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            } catch (InterruptedException e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
        lastCompletionTime = System.currentTimeMillis();
        running = false;
        return true;
    }

    @Override
    public void deviceDiscovered(RemoteDevice remoteDevice, DeviceClass deviceClass) {
        LOG.warning("deviceDiscovered() implemented in DeviceDiscovery.");
    }

    @Override
    public void inquiryCompleted(int discType) {
        LOG.warning("inquiryCompleted() implemented in DeviceDiscovery.");
    }

    @Override
    public void servicesDiscovered(int transID, ServiceRecord[] serviceRecords) {
        LOG.info(serviceRecords.length+" Services returned for transID: "+transID);
        for (int i = 0; i < serviceRecords.length; i++) {
            String url = serviceRecords[i].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            if (url == null) {
                LOG.info("Not a NoAuthN-NoEncrypt service.");
                continue;
            }

            currentPeer.getDid().getPublicKey().addAttribute("serviceURL", url);

            DataElement serviceName = serviceRecords[i].getAttributeValue(0x0100);
            if (serviceName != null) {
                LOG.info("service " + serviceName.getValue() + " found " + url);
                currentPeer.getDid().getPublicKey().addAttribute("serviceName", serviceName);
            } else {
                LOG.info("service found " + url);
            }

            DataElement id = serviceRecords[i].getAttributeValue(0x5555);
            if(id != null) {
                LOG.info("1M5 id found: "+id);
                currentPeer.setId((String)id.getValue());
                sensor.peersInDiscovery.put(currentPeer.getId(), currentPeer);
            }
        }
    }

    @Override
    public void serviceSearchCompleted(int transID, int respCode) {
        result = respCode;
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
                sensor.devices.remove(currentDevice.getBluetoothAddress());
                break;
            }
            case DiscoveryListener.SERVICE_SEARCH_NO_RECORDS : {
                try {
                    LOG.info("Bluetooth search found no records for device (address; "+currentDevice.getBluetoothAddress()+", name: "+currentDevice.getFriendlyName(false)+").");
                } catch (IOException e) {
                    LOG.info("Bluetooth search found no records for device (address; "+currentDevice.getBluetoothAddress()+").");
                }
            }
            case DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE : {
                try {
                    LOG.info("Bluetooth search device (address; "+currentDevice.getBluetoothAddress()+", name: "+currentDevice.getFriendlyName(false)+") not reachable.");
                } catch (IOException e) {
                    LOG.info("Bluetooth search device (address; "+currentDevice.getBluetoothAddress()+") not reachable.");
                }
                break;
            }
            default: {
                LOG.warning("Unknown Bluetooth search result: "+respCode);
            }
        }
        synchronized (serviceSearchCompletedEvent) {
            serviceSearchCompletedEvent.notifyAll();
        }
    }
}
