package ra.bluetooth;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.microedition.io.Connector;
import javax.obex.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Logger;

class BluetoothSession extends BaseSession {

    private static final Logger LOG = Logger.getLogger(io.onemfive.network.sensors.bluetooth.BluetoothSession.class.getName());

    private ClientSession clientSession;
    private SessionNotifier sessionNotifier;
    private RequestHandler handler;
    private Thread serverThread;
    private boolean connected = false;
    private String remotePeerAddress;

    BluetoothSession(io.onemfive.network.sensors.bluetooth.BluetoothSensor sensor) {
        super(sensor);
    }

    @Override
    public Boolean send(NetworkPacket packet) {
        return send((JSONSerializable)packet);
    }

    @Override
    public boolean send(NetworkRequestOp requestOp) {
        requestOp.start = System.currentTimeMillis();
        waitingOps.put(requestOp.id, requestOp);
        return send((NetworkOp)requestOp);
    }

    @Override
    public boolean notify(NetworkNotifyOp notifyOp) {
        return send(notifyOp);
    }

    private boolean send(JSONSerializable jsonSerializable) {
        if(!connected) {
            connect();
        }
        HeaderSet hsOperation = clientSession.createHeaderSet();
        hsOperation.setHeader(HeaderSet.NAME, sensor.getSensorManager().getPeerManager().getLocalNode().getNetworkPeer().getId());
        hsOperation.setHeader(HeaderSet.TYPE, "text");

        //Create PUT Operation
        Operation putOperation = null;
        OutputStream os = null;
        try {
            putOperation = clientSession.put(hsOperation);
            os = putOperation.openOutputStream();
            os.write(jsonSerializable.toJSON().getBytes());
        } catch (IOException e) {
            LOG.warning(e.getLocalizedMessage());
            return false;
        } finally {
            try {
                if(os!=null)
                    os.close();
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
            try {
                if(putOperation!=null)
                    putOperation.close();
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
        return true;
    }

    @Override
    public boolean open(String address) {
        LOG.info("Establishing session based on provided address: "+address);
        sensor.updateStatus(SensorStatus.NETWORK_WARMUP);
        // Client
        remotePeerAddress = address;
        try {
            clientSession = (ClientSession) Connector.open(address);
        } catch (IOException e) {
            LOG.warning("Failed to open connection: "+e.getLocalizedMessage());
            return false;
        }
        // Server
        if(serverThread==null || !serverThread.isAlive()) {
            try {
                String url = "btgoep://localhost:"+sensor.getSensorManager().getPeerManager().getLocalNode().getNetworkPeer(Network.Bluetooth).getDid().getPublicKey().getAttribute("uuid")+";name=1M5";
                LOG.info("Setting up listener on: "+url);
                sessionNotifier = (SessionNotifier) Connector.open(url);
                handler = new RequestHandler(this);
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
                return false;
            }
            // Place device in discovery mode
            try {
                LocalDevice.getLocalDevice().setDiscoverable(DiscoveryAgent.GIAC);
            } catch (BluetoothStateException e) {
                LOG.warning(e.getLocalizedMessage());
                return false;
            }
            serverThread = new Thread(() -> {
                while (getStatus() != SensorSession.Status.STOPPING) {
                    try {
                        sessionNotifier.acceptAndOpen(handler);
                    } catch (IOException e) {
                        LOG.warning(e.getLocalizedMessage());
                    }
                }
                // Take device out of discovery mode
                try {
                    LocalDevice.getLocalDevice().setDiscoverable(DiscoveryAgent.NOT_DISCOVERABLE);
                } catch (BluetoothStateException e) {
                    LOG.warning(e.getLocalizedMessage());
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
        }

        LOG.info("Session established.");
        return true;
    }

    @Override
    public boolean connect() {
        LOG.info("Connecting to remote bluetooth device of peer: "+remotePeerAddress);
        sensor.updateStatus(SensorStatus.NETWORK_CONNECTING);
        connected = false;
        if(clientSession==null) {
            if(!open(remotePeerAddress))
                return false;
        }
        try {
            HeaderSet hsOperation = clientSession.createHeaderSet();
            hsOperation.setHeader(HeaderSet.NAME, sensor.getSensorManager().getPeerManager().getLocalNode().getNetworkPeer().getId());
            hsOperation.setHeader(HeaderSet.TYPE, "text");
            HeaderSet hsConnectReply = clientSession.connect(hsOperation);
            if (hsConnectReply.getResponseCode() != ResponseCodes.OBEX_HTTP_OK) {
                LOG.info("Not connected.");
                return false;
            } else {
                connected = true;
                sensor.updateStatus(SensorStatus.NETWORK_CONNECTED);
            }
        } catch (IOException e) {
            LOG.warning(e.getLocalizedMessage());
            return false;
        }
        return true;
    }

    @Override
    public boolean disconnect() {
        if(clientSession!=null) {
            try {
                clientSession.disconnect(null);
                sensor.updateStatus(SensorStatus.NETWORK_CONNECTING);
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
        return true;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean close() {
        sensor.updateStatus(SensorStatus.NETWORK_STOPPING);
        if(clientSession!=null) {
            try {
                clientSession.close();
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
                return false;
            }
        }
        if(sessionNotifier!=null) {
            try {
                sessionNotifier.close();
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
        serverThread.interrupt();
        sensor.releaseSession(this);
        sensor.updateStatus(SensorStatus.NETWORK_STOPPED);
        return true;
    }

    private static class RequestHandler extends ServerRequestHandler {

        private io.onemfive.network.sensors.bluetooth.BluetoothSession session;

        private RequestHandler (io.onemfive.network.sensors.bluetooth.BluetoothSession session) {
            this.session = session;
        }

        @Override
        public int onConnect(HeaderSet request, HeaderSet reply) {
            LOG.info("Inbound Connection request...");
            try {
                String id = (String)request.getHeader(HeaderSet.NAME);
                LOG.info("id="+id);
                NetworkPeer networkPeer = session.sensor.getSensorManager().getPeerManager().loadPeer(id);
                if(networkPeer!=null) {
                    LOG.info("Known peer...");

                } else {
                    LOG.info("Unknown peer...");

                }
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
            return ResponseCodes.OBEX_HTTP_OK;
        }

//        @Override
//        public int onSetPath(HeaderSet request, HeaderSet reply, boolean backup, boolean create) {
//
//        }

        public int onPut(Operation op) {
            LOG.info("Received Put Operation: "+op.toString());
            try {
                HeaderSet hs = op.getReceivedHeaders();
                String name = (String) hs.getHeader(HeaderSet.NAME);
                if (name != null) {
                    LOG.info("put name:" + name);
                }

                byte[] appHeader = (byte[]) hs.getHeader(HeaderSet.APPLICATION_PARAMETER);
                if(appHeader != null && appHeader.length > 1) {
                    int appHeaderLength = appHeader.length;
                    byte tag = appHeader[0];
                    byte length = appHeader[1];

                }

                InputStream is = op.openInputStream();

                StringBuffer buf = new StringBuffer();
                int data;
                while ((data = is.read()) != -1) {
                    buf.append((char) data);
                }

                LOG.info("got:" + buf.toString());

                op.close();
                return ResponseCodes.OBEX_HTTP_OK;
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
                return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
            }
        }

//        @Override
//        public int onGet(Operation op) {
//            LOG.info("Received Get Operation: "+op.toString());
//            try {
//                HeaderSet hs = op.getReceivedHeaders();
//                String name = (String) hs.getHeader(HeaderSet.NAME);
//                if (name != null) {
//                    LOG.info("get name: " + name);
//                }
//
//                InputStream is = op.openInputStream();
//
//                StringBuffer buf = new StringBuffer();
//                int data;
//                while ((data = is.read()) != -1) {
//                    buf.append((char) data);
//                }
//
//                LOG.info("got:" + buf.toString());
//
//                op.close();
//                return ResponseCodes.OBEX_HTTP_OK;
//            } catch (IOException e) {
//                LOG.warning(e.getLocalizedMessage());
//                return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
//            }
//        }

//        @Override
//        public int onDelete(HeaderSet request, HeaderSet reply) {
//
//        }

        @Override
        public void onDisconnect(HeaderSet request, HeaderSet reply) {
            LOG.info("Disconnect request received. Disconnecting session...");
            if(session.disconnect()) {
                LOG.info("Session disconnected successfully.");
            } else {
                LOG.info("Issues with Session disconnection.");
            }
        }
    }

}
