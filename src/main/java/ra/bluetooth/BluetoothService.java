package ra.bluetooth;

import ra.common.Envelope;
import ra.common.network.*;
import ra.common.route.ExternalRoute;
import ra.common.route.Route;
import ra.common.service.ServiceStatus;
import ra.util.Config;
import ra.util.SystemSettings;
import ra.util.Wait;
import ra.util.tasks.TaskRunner;

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
public final class BluetoothService extends NetworkService {

    private static final Logger LOG = Logger.getLogger(BluetoothService.class.getName());

    private String bluetoothBaseDir;
    private File bluetoothDir;

    Map<String, RemoteDevice> devices = new HashMap<>();
    Map<String, NetworkPeer> peersInDiscovery = new HashMap<>();

    private CheckPowerStatus checkPowerStatus;

    private boolean deviceDiscoveryRunning = false;
    private BluetoothDeviceDiscovery deviceDiscovery;
    private BluetoothServiceDiscovery serviceDiscovery;
    private BluetoothPeerDiscovery peerDiscovery;
    private TaskRunner taskRunner;

    private boolean peerDiscoveryRunning = false;

    private Thread taskRunnerThread;

    private Map<Integer, BluetoothSession> leased = new HashMap<>();

    public BluetoothService() {
        super(Network.Bluetooth.name());
        taskRunner = new TaskRunner(1, 4);
    }

    @Override
    public void handleDocument(Envelope envelope) {
        super.handleDocument(envelope);
        Route r = envelope.getRoute();
        if(r instanceof ExternalRoute) {
            // External request
            ExternalRoute er = (ExternalRoute)r;

        } else {
            // Internal request

        }
    }

    private NetworkClientSession establishSession(String address, Boolean autoConnect) {
        if(address==null) {
            address = "default";
        }
        NetworkClientSession session = sessions.get(address);
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

    private NetworkClientSession establishSession(NetworkPeer peer, Boolean autoConnect) {
        return establishSession(peer.getDid().getPublicKey().getAddress(), autoConnect);
    }

    /**
     * Sends Envelope to a Bluetooth Peer.
     * @param envelope Envelope containing data.
     * @return Boolean was successful
     */
    @Override
    public Boolean sendOut(Envelope envelope) {
        LOG.info("Sending Packet via Bluetooth...");
        Route r = envelope.getRoute();
        if(!(r instanceof ExternalRoute)) {
            LOG.warning("Not an external route.");
            // TODO: Reply with error code
            return false;
        }
        ExternalRoute er = (ExternalRoute)r;
        NetworkPeer toPeer = er.getDestination();
        if (toPeer == null) {
            LOG.warning("No Peer found while sending to Bluetooth.");
            return false;
        }

        if (!Network.Bluetooth.name().equals(toPeer.getNetwork())) {
            LOG.warning("Not a Bluetooth Request.");
            return false;
        }

        LOG.info("Envelope to send: " + envelope.toJSON());
        return establishSession(toPeer, true).send(envelope);
    }

    public boolean startDeviceDiscovery() {
        LOG.info("Is Bluetooth Radio On: "+LocalDevice.isPowerOn());
        if(LocalDevice.isPowerOn()) {
            // TODO: Increase periodicity once a threshold of known peers is established
            // run every minute
            deviceDiscovery = new BluetoothDeviceDiscovery(this, taskRunner);
            deviceDiscovery.setPeriodicity(60 * 1000L);
            deviceDiscovery.setLongRunning(true);
            taskRunner.addTask(deviceDiscovery);

            // run every minute 20 seconds after device discovery
            serviceDiscovery = new BluetoothServiceDiscovery(this, taskRunner);
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
            peerDiscovery = new BluetoothPeerDiscovery(taskRunner, this);
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
    public boolean start(Properties p) {
        LOG.info("Starting Bluetooth Service...");
        updateStatus(ServiceStatus.INITIALIZING);
        try {
            config = Config.loadFromClasspath("ra-bluetooth-client.config", p, false);
        } catch (Exception e) {
            LOG.severe(e.getLocalizedMessage());
            return false;
        }
        if(System.getProperty("bluetooth.dir.base")==null) {
            // Set up BT Directories within RA Services Directory
            File homeDir = SystemSettings.getUserHomeDir();
            File raDir = new File(homeDir, ".ra");
            if(!raDir.exists() && !raDir.mkdir()) {
                LOG.severe("Unable to create home/.ra directory.");
                return false;
            }
            File servicesDir = new File(raDir, "services");
            if(!servicesDir.exists() && !servicesDir.mkdir()) {
                LOG.severe("Unable to create services directory in home/.ra");
                return false;
            }
            bluetoothDir = new File(servicesDir, BluetoothService.class.getName());
            if(!bluetoothDir.exists() && !bluetoothDir.mkdir()) {
                LOG.severe("Unable to create "+BluetoothService.class.getName()+" directory in home/.ra/services");
                return false;
            }
            System.setProperty("bluetooth.dir.base", bluetoothDir.getAbsolutePath());
        } else {
            bluetoothDir = new File(System.getProperty("bluetooth.dir.base"));
        }
        config.setProperty("bluetooth.dir.base", bluetoothBaseDir);
        // Config Directory
        String configDir = bluetoothDir + "/config";
        File configFolder = new File(configDir);
        if(!configFolder.exists())
            if(!configFolder.mkdir())
                LOG.warning("Unable to create Bluetooth config directory: " +configDir);
        if(configFolder.exists()) {
            System.setProperty("bluetooth.dir.config",configDir);
            config.setProperty("bluetooth.dir.config",configDir);
        }
        // Router Directory
        String routerDir = bluetoothDir + "/router";
        File routerFolder = new File(routerDir);
        if(!routerFolder.exists())
            if(!routerFolder.mkdir())
                LOG.warning("Unable to create Bluetooth router directory: "+routerDir);
        if(routerFolder.exists()) {
            System.setProperty("bluetooth.dir.router",routerDir);
            config.setProperty("bluetooth.dir.router",routerDir);
        }
        // PID Directory
        String pidDir = bluetoothDir + "/pid";
        File pidFolder = new File(pidDir);
        if(!pidFolder.exists())
            if(!pidFolder.mkdir())
                LOG.warning("Unable to create Bluetooth PID directory: "+pidDir);
        if(pidFolder.exists()) {
            System.setProperty("bluetooth.dir.pid",pidDir);
            config.setProperty("bluetooth.dir.pid",pidDir);
        }
        // Log Directory
        String logDir = bluetoothDir + "/log";
        File logFolder = new File(logDir);
        if(!logFolder.exists())
            if(!logFolder.mkdir())
                LOG.warning("Unable to create Bluetooth log directory: "+logDir);
        if(logFolder.exists()) {
            System.setProperty("bluetooth.dir.log",logDir);
            config.setProperty("bluetooth.dir.log",logDir);
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
        getNetworkState().networkStatus = NetworkStatus.CONNECTING;
        try {
            String localAddress = LocalDevice.getLocalDevice().getBluetoothAddress();
            NetworkPeer localPeer = getNetworkState().localPeer;
            localPeer.getDid().setUsername(LocalDevice.getLocalDevice().getFriendlyName());
            localPeer.getDid().getPublicKey().setAddress(localAddress);
            if (!localAddress.equals(localPeer.getDid().getPublicKey().getAddress())
                    || localPeer.getDid().getPublicKey().getAttribute("uuid") == null) {
                // New address or no UUID
                //                localPeer.getDid().getPublicKey().addAttribute("uuid", UUID.randomUUID().toString());
                // TODO: Remove hard-coding
                localPeer.getDid().getPublicKey().addAttribute("uuid", "11111111111111111111111111111123");
            }
            while (localPeer.getId() == null) {
                Wait.aMs(100);
                // TODO: Add a break-out to prevent permanent wait
            }
            // TODO: Send Peer Manager update to Local Peer
//            sensorManager.getPeerManager().savePeer(localPeer, true);
            // TODO: Update Network Manager of new status
//            updateModelListeners();
        } catch (BluetoothStateException e) {
            if (e.getLocalizedMessage().contains("Bluetooth Device is not available")) {
               getNetworkState().networkStatus = NetworkStatus.DISCONNECTED;
                LOG.warning("Bluetooth either not installed on machine or not turned on.");
            } else {
                LOG.warning(e.getLocalizedMessage());
            }
            return false;
        }

        getNetworkState().updateIntervalSeconds = 20 * 60; // 20 minutes
        getNetworkState().updateIntervalHyperSeconds = 60; // every minute

        return startPeerDiscovery();
    }

    public boolean sleep() {
        getNetworkState().networkStatus = NetworkStatus.DISCONNECTED;
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
        updateStatus(ServiceStatus.SHUTTING_DOWN);
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
        updateStatus(ServiceStatus.SHUTDOWN);
        return true;
    }

    @Override
    public boolean gracefulShutdown() {
        super.gracefulShutdown();
        updateStatus(ServiceStatus.GRACEFULLY_SHUTTING_DOWN);
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
        updateStatus(ServiceStatus.GRACEFULLY_SHUTDOWN);
        return true;
    }

}
