package ra.bluetooth;

import ra.common.Network;
import ra.common.NetworkPeer;
import ra.common.PublicKey;
import ra.util.tasks.TaskRunner;

import javax.bluetooth.*;
import java.io.IOException;
import java.util.logging.Logger;

public class BluetoothDeviceDiscovery extends NetworkTask implements DiscoveryListener {

    private static Logger LOG = Logger.getLogger(BluetoothDeviceDiscovery.class.getName());

    private final Object inquiryCompletedEvent = new Object();
    private int result;
    private NetworkPeer currentPeer;
    private RemoteDevice currentDevice;

    public BluetoothDeviceDiscovery(BluetoothSensor sensor, TaskRunner taskRunner) {
        super(BluetoothDeviceDiscovery.class.getName(), taskRunner, sensor);
        this.sensor = sensor;
    }

    public int getResult() {
        return result;
    }

    @Override
    public Boolean execute() {
        running = true;
        try {
            RemoteDevice[] devices = LocalDevice.getLocalDevice().getDiscoveryAgent().retrieveDevices(DiscoveryAgent.CACHED);
            if(devices!=null) {
                for(RemoteDevice device : devices) {
                    ((BluetoothSensor)sensor).devices.put(device.getBluetoothAddress(), device);
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
        String msg = "Device " + remoteDevice.getBluetoothAddress() + " found.";
        currentDevice = remoteDevice;
        try {
            currentPeer = new NetworkPeer(Network.Bluetooth, remoteDevice.getFriendlyName(true), "1234");
            PublicKey pk = currentPeer.getDid().getPublicKey();
            pk.setAddress(remoteDevice.getBluetoothAddress());
            pk.addAttribute("isAuthenticated", remoteDevice.isAuthenticated());
            pk.addAttribute("isEncrypted", remoteDevice.isEncrypted());
            pk.addAttribute("isTrustedDevice", remoteDevice.isTrustedDevice());
            pk.addAttribute("majorDeviceClass", deviceClass.getMajorDeviceClass());
            pk.addAttribute("minorDeviceClass", deviceClass.getMinorDeviceClass());
            pk.addAttribute("serviceClasses", deviceClass.getServiceClasses());
        } catch (IOException e) {
            LOG.warning(e.getLocalizedMessage());
        }
        LOG.info(msg);
    }

    @Override
    public void inquiryCompleted(int discType) {
        result = discType;
        switch (discType) {
            case DiscoveryListener.INQUIRY_COMPLETED : {
                LOG.info("Bluetooth inquiry completed. Caching peer.");
                if(currentDevice!=null) {
                    ((BluetoothSensor) sensor).devices.put(currentDevice.getBluetoothAddress(), currentDevice);
                    ((BluetoothSensor) sensor).updateStatus(SensorStatus.NETWORK_CONNECTED);
                }
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
        LOG.warning("servicesDiscovered() implemented in ServiceDiscovery.");
    }

    @Override
    public void serviceSearchCompleted(int transID, int respCode) {
        LOG.warning("serviceSearchCompleted() implemented in ServiceDiscovery.");
    }
}
