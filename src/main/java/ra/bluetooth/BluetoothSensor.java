package ra.bluetooth;

import ra.common.Network;
import ra.common.NetworkPeer;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.RemoteDevice;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Integration with JSR-82 implementation BlueCove (http://www.bluecove.org).
 * Bluecove licensed under GPL.
 *
 */
public final class BluetoothSensor extends BaseSensor {

    private static final Logger LOG = Logger.getLogger(io.onemfive.network.sensors.bluetooth.BluetoothSensor.class.getName());

    private String bluetoothBaseDir;
    private File bluetoothDir;

    public static final NetworkState config = new NetworkState();
    Map<String, RemoteDevice> devices = new HashMap<>();
    Map<String, NetworkPeer> peersInDiscovery = new HashMap<>();

    private CheckPowerStatus checkPowerStatus;

    private boolean deviceDiscoveryRunning = false;
    private io.onemfive.network.sensors.bluetooth.BluetoothDeviceDiscovery deviceDiscovery;
    private io.onemfive.network.sensors.bluetooth.BluetoothServiceDiscovery serviceDiscovery;

    private boolean peerDiscoveryRunning = false;
    private NetworkPeerDiscovery peerDiscovery;

    private Thread taskRunnerThread;

    private Map<Integer, BluetoothSession> leased = new HashMap<>();

    public BluetoothSensor() {
        super(Network.Bluetooth);
        taskRunner = new TaskRunner(1, 4);
    }

    public BluetoothSensor(SensorManager sensorManager) {
        super(sensorManager, Network.Bluetooth);
        taskRunner = new TaskRunner(1, 4);
    }

    @Override
    public String[] getOperationEndsWith() {
        return new String[]{".bt"};
    }

    @Override
    public String[] getURLBeginsWith() {
        return new String[]{"bt"};
    }

    @Override
    public String[] getURLEndsWith() {
        return new String[]{".bt"};
    }

    @Override
    public SensorSession establishSession(String address, Boolean autoConnect) {
        if(address==null) {
            address = "default";
        }
        SensorSession session = sessions.get(address);
        if(session==null) {
            if (session.open(address)) {
                if (autoConnect) {
                    session.connect();
                }
                sessions.put(address, session);
            }
        }
        return session;
    }

    public SensorSession establishSession(NetworkPeer peer, Boolean autoConnect) {
        return establishSession(peer.getDid().getPublicKey().getAddress(), autoConnect);
    }

    @Override
    public void updateState(NetworkState networkState) {
        LOG.warning("Not implemented.");
    }

    /**
     * Sends UTF-8 content to a Bluetooth Peer.
     * @param packet Envelope containing Packet as data.
     * @return boolean was successful
     */
    @Override
    public boolean sendOut(NetworkPacket packet) {
        LOG.info("Sending Packet via Bluetooth...");
        NetworkPeer toPeer = packet.getToPeer();
        if (toPeer == null) {
            LOG.warning("No Peer found while sending to Bluetooth.");
            return false;
        }

        if (toPeer.getNetwork() != Network.Bluetooth) {
            LOG.warning("Not a Bluetooth Request.");
            return false;
        }

        if (packet.getEnvelope() == null) {
            LOG.warning("No Envelope found while sending to Bluetooth.");
            return false;
        }
        LOG.info("Envelope to send: " + packet.getEnvelope().toString());
        return establishSession(packet.getToPeer(), true).send(packet);
    }

    @Override
    public boolean sendIn(Envelope envelope) {
        return super.sendIn(envelope);
    }

    public boolean startDeviceDiscovery() {
        LOG.info("Is Bluetooth Radio On: "+LocalDevice.isPowerOn());
        if(LocalDevice.isPowerOn()) {
            // TODO: Increase periodicity once a threshold of known peers is established
            // run every minute
            deviceDiscovery = new io.onemfive.network.sensors.bluetooth.BluetoothDeviceDiscovery(this, taskRunner);
            deviceDiscovery.setPeriodicity(60 * 1000L);
            deviceDiscovery.setLongRunning(true);
            taskRunner.addTask(deviceDiscovery);

            // run every minute 20 seconds after device discovery
            serviceDiscovery = new io.onemfive.network.sensors.bluetooth.BluetoothServiceDiscovery(sensorManager.getPeerManager(), this, taskRunner);
            serviceDiscovery.setPeriodicity(60 * 1000L);
            serviceDiscovery.setLongRunning(true);
            serviceDiscovery.setDelayed(true);
            serviceDiscovery.setDelayTimeMS(20 * 1000L);
            taskRunner.addTask(serviceDiscovery);
            deviceDiscoveryRunning = true;
            return true;
        }
        return false;
    }

    public boolean stopDeviceDiscovery() {
        taskRunner.removeTask(deviceDiscovery, true);
        taskRunner.removeTask(serviceDiscovery, true);
        deviceDiscoveryRunning = false;
        return true;
    }

    private boolean startPeerDiscovery() {
        LOG.info("Starting Bluetooth Peer Discovery...");
        try {
            RemoteDevice[] remoteDevices = LocalDevice.getLocalDevice().getDiscoveryAgent().retrieveDevices(DiscoveryAgent.CACHED);
            if(remoteDevices != null && remoteDevices.length > 0) {
                for(RemoteDevice r : remoteDevices) {
                    devices.put(r.getBluetoothAddress(), r);
                }
            }
            // run every minute 20 seconds after service discovery
            peerDiscovery = new NetworkPeerDiscovery(taskRunner, this);
            peerDiscovery.setLongRunning(true);
            peerDiscovery.setDelayed(true);
            peerDiscovery.setDelayTimeMS(40 * 1000L);
//            taskRunner.addTask(peerDiscovery);
            peerDiscoveryRunning = true;
            LOG.info("Completed Bluetooth Peer Discovery.");
            return true;
        } catch (BluetoothStateException e) {
            LOG.warning(e.getLocalizedMessage());
            return false;
        }
    }

    private boolean stopPeerDiscovery() {
        taskRunner.removeTask(peerDiscovery, true);
        peerDiscoveryRunning = false;
        return true;
    }

    @Override
    public boolean start(Properties properties) {
        LOG.info("Starting Bluetooth Sensor...");
        this.properties = properties;
        updateStatus(SensorStatus.INITIALIZING);
        bluetoothBaseDir = properties.getProperty("1m5.dir.sensors")+"/bluetooth";
        bluetoothDir = new File(bluetoothBaseDir);
        if (!bluetoothDir.exists()) {
            if (!bluetoothDir.mkdir()) {
                LOG.severe("Unable to create Bluetooth base directory: " + bluetoothBaseDir + "; exiting...");
                return false;
            }
        }
        properties.setProperty("bluetooth.dir.base", bluetoothBaseDir);
        properties.setProperty("1m5.dir.sensors.bluetooth", bluetoothBaseDir);
        // Config Directory
        String configDir = bluetoothDir + "/config";
        File configFolder = new File(configDir);
        if(!configFolder.exists())
            if(!configFolder.mkdir())
                LOG.warning("Unable to create Bluetooth config directory: " +configDir);
        if(configFolder.exists()) {
            System.setProperty("bluetooth.dir.config",configDir);
            properties.setProperty("bluetooth.dir.config",configDir);
        }
        // Router Directory
        String routerDir = bluetoothDir + "/router";
        File routerFolder = new File(routerDir);
        if(!routerFolder.exists())
            if(!routerFolder.mkdir())
                LOG.warning("Unable to create Bluetooth router directory: "+routerDir);
        if(routerFolder.exists()) {
            System.setProperty("bluetooth.dir.router",routerDir);
            properties.setProperty("bluetooth.dir.router",routerDir);
        }
        // PID Directory
        String pidDir = bluetoothDir + "/pid";
        File pidFolder = new File(pidDir);
        if(!pidFolder.exists())
            if(!pidFolder.mkdir())
                LOG.warning("Unable to create Bluetooth PID directory: "+pidDir);
        if(pidFolder.exists()) {
            System.setProperty("bluetooth.dir.pid",pidDir);
            properties.setProperty("bluetooth.dir.pid",pidDir);
        }
        // Log Directory
        String logDir = bluetoothDir + "/log";
        File logFolder = new File(logDir);
        if(!logFolder.exists())
            if(!logFolder.mkdir())
                LOG.warning("Unable to create Bluetooth log directory: "+logDir);
        if(logFolder.exists()) {
            System.setProperty("bluetooth.dir.log",logDir);
            properties.setProperty("bluetooth.dir.log",logDir);
        }

        checkPowerStatus = new CheckPowerStatus(taskRunner, this);
        checkPowerStatus.setPeriodicity(3 * 1000);
        taskRunner.addTask(checkPowerStatus);

        taskRunnerThread = new Thread(taskRunner);
        taskRunnerThread.setName("BluetoothSensor-TaskRunnerThread");
        taskRunnerThread.setDaemon(true);
        taskRunnerThread.start();
        return true;
    }

    public boolean awaken() {
        updateStatus(SensorStatus.STARTING);
        NetworkNode localNode = sensorManager.getPeerManager().getLocalNode();
        try {
            String localAddress = LocalDevice.getLocalDevice().getBluetoothAddress();
            localPeer = localNode.getNetworkPeer(Network.Bluetooth);
            if (localPeer == null) {
                localPeer = new NetworkPeer(Network.Bluetooth);
                localNode.addNetworkPeer(localPeer);
            }
            localPeer.getDid().setUsername(LocalDevice.getLocalDevice().getFriendlyName());
            localPeer.getDid().getPublicKey().setAddress(localAddress);
            localPeer.getDid().setPassphrase(localNode.getNetworkPeer().getDid().getPassphrase());
            if (!localAddress.equals(localPeer.getDid().getPublicKey().getAddress())
                    || localPeer.getDid().getPublicKey().getAttribute("uuid") == null) {
                // New address or no UUID
                //                localPeer.getDid().getPublicKey().addAttribute("uuid", UUID.randomUUID().toString());
                // TODO: Remove hard-coding
                localPeer.getDid().getPublicKey().addAttribute("uuid", "11111111111111111111111111111123");
            }
            while (localNode.getNetworkPeer().getId() == null) {
                Wait.aMs(100);
            }
            localPeer.setId(localNode.getNetworkPeer().getId());
            sensorManager.getPeerManager().savePeer(localPeer, true);
            updateModelListeners();
        } catch (BluetoothStateException e) {
            if (e.getLocalizedMessage().contains("Bluetooth Device is not available")) {
                if (getStatus() != SensorStatus.NETWORK_UNAVAILABLE)
                    updateStatus(SensorStatus.NETWORK_UNAVAILABLE);
                LOG.warning("Bluetooth either not installed on machine or not turned on.");
            } else {
                LOG.warning(e.getLocalizedMessage());
            }
            return false;
        }

        networkState.UpdateInterval = 20 * 60; // 20 minutes
        networkState.UpdateIntervalHyper = 60; // every minute

        return startPeerDiscovery();
    }

    public boolean sleep() {
        updateStatus(SensorStatus.NETWORK_UNAVAILABLE);
        stopPeerDiscovery();
        stopDeviceDiscovery();
        return true;
    }

    @Override
    public boolean pause() {
        return false;
    }

    @Override
    public boolean unpause() {
        return false;
    }

    @Override
    public boolean restart() {
        return false;
    }

    @Override
    public boolean shutdown() {
        super.shutdown();
        updateStatus(SensorStatus.SHUTTING_DOWN);
        try {
            if(LocalDevice.getLocalDevice().getDiscoverable() != DiscoveryAgent.NOT_DISCOVERABLE)
                LocalDevice.getLocalDevice().setDiscoverable(DiscoveryAgent.NOT_DISCOVERABLE);
        } catch (BluetoothStateException e) {
            LOG.warning(e.getLocalizedMessage());
        }
        taskRunner.removeTask(checkPowerStatus, true);
        taskRunner.removeTask(peerDiscovery, true);
        taskRunner.removeTask(serviceDiscovery, true);
        taskRunner.removeTask(deviceDiscovery, true);
        taskRunner = null;
        updateStatus(SensorStatus.SHUTDOWN);
        return true;
    }

    @Override
    public boolean gracefulShutdown() {
        super.gracefulShutdown();
        updateStatus(SensorStatus.GRACEFULLY_SHUTTING_DOWN);
        try {
            if(LocalDevice.getLocalDevice().getDiscoverable() != DiscoveryAgent.NOT_DISCOVERABLE)
                LocalDevice.getLocalDevice().setDiscoverable(DiscoveryAgent.NOT_DISCOVERABLE);
        } catch (BluetoothStateException e) {
            LOG.warning(e.getLocalizedMessage());
        }
        taskRunner.removeTask(checkPowerStatus, false);
        taskRunner.removeTask(peerDiscovery, false);
        taskRunner.removeTask(serviceDiscovery, false);
        taskRunner.removeTask(deviceDiscovery, false);
        taskRunner = null;
        updateStatus(SensorStatus.GRACEFULLY_SHUTDOWN);
        return true;
    }

}
