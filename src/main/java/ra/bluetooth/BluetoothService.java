package ra.bluetooth;

import ra.common.Client;
import ra.common.Envelope;
import ra.common.messaging.MessageProducer;
import ra.common.network.*;
import ra.common.route.ExternalRoute;
import ra.common.route.Route;
import ra.common.route.SimpleRoute;
import ra.common.service.ServiceStatus;
import ra.common.service.ServiceStatusObserver;
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
 * Bluetooth Service
 *
 * Connects with local Bluetooth radio via the JSR-82 implementation BlueCove (http://www.bluecove.org).
 * This services uses the Bluecove GPL version for Linux using BlueZ.
 *
 *
 *
 */
public final class BluetoothService extends NetworkService {

    private static final Logger LOG = Logger.getLogger(BluetoothService.class.getName());

    public static final String OPERATION_SEND = "SEND";


    private String bluetoothBaseDir;
    private File bluetoothDir;

    final Map<String, RemoteDevice> devices = new HashMap<>();
    // Peers returned from local peers - may not be accessible directly - provided to build propagating network
    final Map<String, NetworkPeer> peersOfPeers = new HashMap<>();

    private CheckPowerStatus checkPowerStatus;

    private boolean discoveryRunning = false;
//    private BluetoothDeviceDiscovery deviceDiscovery;
    private BluetoothPeerDiscovery discovery;
    private TaskRunner taskRunner;

    private Thread taskRunnerThread;

    private Map<Integer, BluetoothSession> leased = new HashMap<>();

    public BluetoothService() {
        super();
        getNetworkState().network = Network.Bluetooth;
        taskRunner = new TaskRunner(1, 4);
    }

    public BluetoothService(MessageProducer producer, ServiceStatusObserver observer) {
        super(Network.Bluetooth, producer, observer);
        taskRunner = new TaskRunner(1, 4);
    }

    @Override
    public void handleDocument(Envelope envelope) {
        super.handleDocument(envelope);
        Route r = envelope.getRoute();
        if(r instanceof ExternalRoute) {
            // External request
            if(!sendOut(envelope)) {
                envelope.addErrorMessage("Bluetooth Send Out Failed");
                send(envelope);
            }
        } else if(r instanceof SimpleRoute) {
            // Internal request
            switch (r.getOperation()) {

            }
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

    public boolean startDiscovery() {
        LOG.info("Is Bluetooth Radio On: "+LocalDevice.isPowerOn());
        if(LocalDevice.isPowerOn()) {
            taskRunner.addTask(discovery);
            discoveryRunning = true;
            return true;
        }
        return false;
    }

    public boolean stopDiscovery() {
        taskRunner.removeTask(discovery, true);
        discoveryRunning = false;
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
        bluetoothBaseDir = bluetoothDir.getAbsolutePath();
        config.setProperty("bluetooth.dir.base", bluetoothBaseDir);
        // Config Directory
        File configFolder = new File(bluetoothDir, "config");
        if(!configFolder.exists())
            if(!configFolder.mkdir())
                LOG.warning("Unable to create Bluetooth config directory: " +bluetoothBaseDir+"/config");
        if(configFolder.exists()) {
            System.setProperty("bluetooth.dir.config",configFolder.getAbsolutePath());
            config.setProperty("bluetooth.dir.config",configFolder.getAbsolutePath());
        }
        // Log Directory
        File logFolder = new File(bluetoothDir, "log");
        if(!logFolder.exists())
            if(!logFolder.mkdir())
                LOG.warning("Unable to create Bluetooth log directory: "+bluetoothBaseDir+"/log");
        if(logFolder.exists()) {
            System.setProperty("bluetooth.dir.log",logFolder.getAbsolutePath());
            config.setProperty("bluetooth.dir.log",logFolder.getAbsolutePath());
        }

        // run every 5 minutes for now - may want to lower going into production
        discovery = new BluetoothPeerDiscovery(this, taskRunner);
        discovery.setPeriodicity(5 * 60 * 1000L);
        discovery.setLongRunning(true);

        // run every

        // run every 3 seconds
        checkPowerStatus = new CheckPowerStatus(taskRunner, this);
        checkPowerStatus.setPeriodicity(3 * 1000);
        taskRunner.addTask(checkPowerStatus);

        taskRunnerThread = new Thread(taskRunner);
        taskRunnerThread.setName("BluetoothSensor-TaskRunnerThread");
        taskRunnerThread.setDaemon(true);
        taskRunnerThread.start();
        return true;
    }

    @Override
    public boolean pause() {
        LOG.info("Bluetooth Network sleeping...");
        stopDiscovery();
        return true;
    }

    @Override
    public boolean unpause() {
        LOG.info("Bluetooth Network awakening...");
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
//            while (localPeer.getId() == null) {
//                Wait.aMs(100);
            // TODO: Add a break-out to prevent permanent wait
//            }
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

        return startDiscovery();
    }

    @Override
    public boolean restart() {
        return gracefulShutdown() && start(config);
    }

    @Override
    public boolean shutdown() {
        LOG.info("Shutting down Bluetooth Service...");
        super.shutdown();
        updateStatus(ServiceStatus.SHUTTING_DOWN);
        try {
            if(LocalDevice.getLocalDevice().getDiscoverable() != DiscoveryAgent.NOT_DISCOVERABLE)
                LocalDevice.getLocalDevice().setDiscoverable(DiscoveryAgent.NOT_DISCOVERABLE);
        } catch (BluetoothStateException e) {
            LOG.warning(e.getLocalizedMessage());
        }
        taskRunner.removeTask(checkPowerStatus, true);
        taskRunner = null;
        LOG.info("Bluetooth Service shutdown.");
        updateStatus(ServiceStatus.SHUTDOWN);;
        return true;
    }

    @Override
    public boolean gracefulShutdown() {
        LOG.info("Gracefully shutting down Bluetooth Service...");
        super.gracefulShutdown();
        updateStatus(ServiceStatus.GRACEFULLY_SHUTTING_DOWN);
        try {
            if(LocalDevice.getLocalDevice().getDiscoverable() != DiscoveryAgent.NOT_DISCOVERABLE)
                LocalDevice.getLocalDevice().setDiscoverable(DiscoveryAgent.NOT_DISCOVERABLE);
        } catch (BluetoothStateException e) {
            LOG.warning(e.getLocalizedMessage());
        }
        taskRunner.removeTask(checkPowerStatus, false);
        taskRunner = null;
        LOG.info("Bluetooth Service gracefully shutdown.");
        updateStatus(ServiceStatus.GRACEFULLY_SHUTDOWN);
        return true;
    }

    public static void main(String[] args) {
        BluetoothService service = new BluetoothService(new MessageProducer() {
            @Override
            public boolean send(Envelope envelope) {
                LOG.info("Sending: \n\t"+envelope.toJSON());
                return true;
            }

            @Override
            public boolean send(Envelope envelope, Client client) {
                LOG.info("Sending with Client waiting: \n\t"+envelope.toJSON());
                return  true;
            }

            @Override
            public boolean deadLetter(Envelope envelope) {
                    LOG.info("Dead lettering envelope: "+envelope.toJSON());
                    return true;
            }
        }, null);
        service.start(new Properties());
        while(service.getServiceStatus() != ServiceStatus.SHUTDOWN || service.getServiceStatus() != ServiceStatus.GRACEFULLY_SHUTDOWN) {
            Wait.aSec(1);
        }
    }

}
