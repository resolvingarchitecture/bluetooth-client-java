package ra.bluetooth;

import ra.common.Envelope;
import ra.common.network.BaseClientSession;
import ra.common.network.NetworkClientSession;
import ra.common.network.NetworkPeer;
import ra.common.network.NetworkStatus;
import ra.common.route.ExternalRoute;
import ra.common.route.SimpleRoute;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.microedition.io.Connector;
import javax.obex.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.logging.Logger;

class BluetoothSession extends BaseClientSession {

    private static final Logger LOG = Logger.getLogger(BluetoothSession.class.getName());

    private final BluetoothService service;
    private ClientSession clientSession;
    private SessionNotifier sessionNotifier;
    private RequestHandler handler;
    private Thread serverThread;
    private String remotePeerAddress;

    BluetoothSession(BluetoothService service) {
        this.service = service;
    }

    @Override
    public Boolean send(Envelope envelope) {
        if(service.getNetworkState().networkStatus != NetworkStatus.CONNECTED) {
            connect();
        }
        HeaderSet hsOperation = clientSession.createHeaderSet();
        hsOperation.setHeader(HeaderSet.NAME, service.getNetworkState().localPeer.getId());
        hsOperation.setHeader(HeaderSet.TYPE, "text");

        //Create PUT Operation
        Operation putOperation = null;
        OutputStream os = null;
        try {
            putOperation = clientSession.put(hsOperation);
            os = putOperation.openOutputStream();
            os.write(envelope.toJSON().getBytes());
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
        service.getNetworkState().networkStatus = NetworkStatus.WARMUP;
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
            String uuid = (String)service.getNetworkState().localPeer.getDid().getPublicKey().getAttribute("uuid");
            try {
                String url = "btgoep://localhost:"+uuid+";name=1M5";
                LOG.info("Setting up listener on: "+url);
                sessionNotifier = (SessionNotifier) Connector.open(url);
                handler = new RequestHandler(service, this);
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
                while (getStatus() != NetworkClientSession.Status.STOPPING) {
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
        service.getNetworkState().networkStatus = NetworkStatus.CONNECTING;
        if(clientSession==null) {
            if(!open(remotePeerAddress))
                return false;
        }
        try {
            HeaderSet hsOperation = clientSession.createHeaderSet();
            hsOperation.setHeader(HeaderSet.NAME, service.getNetworkState().localPeer.getId());
            hsOperation.setHeader(HeaderSet.TYPE, "text");
            HeaderSet hsConnectReply = clientSession.connect(hsOperation);
            if (hsConnectReply.getResponseCode() != ResponseCodes.OBEX_HTTP_OK) {
                LOG.info("Not connected.");
                return false;
            } else {
                service.getNetworkState().networkStatus = NetworkStatus.CONNECTED;
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
                service.getNetworkState().networkStatus = NetworkStatus.CONNECTING;
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
        return true;
    }

    @Override
    public boolean isConnected() {
        return service.getNetworkState().networkStatus == NetworkStatus.CONNECTED;
    }

    @Override
    public boolean close() {
        service.getNetworkState().networkStatus = NetworkStatus.DISCONNECTED;
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
        return true;
    }

    private static class RequestHandler extends ServerRequestHandler {

        private BluetoothService service;
        private BluetoothSession session;

        private RequestHandler (BluetoothService service, BluetoothSession session) {
            this.session = session;
        }

        @Override
        public int onConnect(HeaderSet request, HeaderSet reply) {
            LOG.info("Inbound Connection request...");
            try {
                String id = (String)request.getHeader(HeaderSet.NAME);
                LOG.info("id="+id);
                // TODO: Send peer to Peer Manager
                Envelope e = Envelope.documentFactory();
                e.setRoute(new SimpleRoute());
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
            return ResponseCodes.OBEX_HTTP_OK;
        }

//        @Override
//        public int onSetPath(HeaderSet request, HeaderSet reply, boolean backup, boolean create) {
//
//        }

        /**
         * Received a put operation pushing data to this peer.
         * @param op
         * @return
         */
        public int onPut(Operation op) {
            LOG.info("Received Put Operation: "+op.toString());
            Envelope envelope = Envelope.documentFactory();
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
                    LOG.info("headers: tag="+tag+"; length="+length);
                }

                InputStream is = op.openInputStream();

                StringBuffer buf = new StringBuffer();
                int data;
                while ((data = is.read()) != -1) {
                    buf.append((char) data);
                }

                envelope.fromJSON(buf.toString());
                LOG.info("Put received:" + envelope.toJSON());
                service.send(envelope);

                op.close();
                return ResponseCodes.OBEX_HTTP_OK;
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
                return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
            }
        }

        /**
         * Received a Get operation asking for data from this peer.
         * @param op
         * @return
         */
        @Override
        public int onGet(Operation op) {
            LOG.info("Received Get Operation: "+op.toString());
            Envelope envelope = Envelope.documentFactory();
            try {
                HeaderSet hs = op.getReceivedHeaders();
                String name = (String) hs.getHeader(HeaderSet.NAME);
                if (name != null) {
                    LOG.info("get name: " + name);
                }

                InputStream is = op.openInputStream();

                StringBuffer buf = new StringBuffer();
                int data;
                while ((data = is.read()) != -1) {
                    buf.append((char) data);
                }

                envelope.fromJSON(buf.toString());
                LOG.info("Get Request:" + envelope.toJSON());
                if(envelope.markerPresent("NetOpReq")) {
                    ExternalRoute er = (ExternalRoute)envelope.getRoute();
                    LOG.info("Received NetOpReq id: "+envelope.getId().substring(0,7)+"... from: "+er.getOrigination().getDid().getPublicKey().getFingerprint().substring(0,7));
                    List<NetworkPeer> recommendedPeers = (List<NetworkPeer>) envelope.getContent();
                    if (recommendedPeers != null) {
                        LOG.info(recommendedPeers.size() + " Known Peers Received.");
                        service.addToKnownPeers(recommendedPeers);
                    }
                    envelope.mark("NetOpRes");
                    envelope.addContent(service.getKnownPeers());
                    envelope.addExternalRoute(BluetoothService.class.getName(), BluetoothService.OPERATION_PEER_STATUS_REPLY, service.getNetworkState().localPeer, er.getOrigination());
                    envelope.ratchet();

                    // TODO: Return back with PUT request

                }

                op.close();
                return ResponseCodes.OBEX_HTTP_OK;
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
                return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
            }
        }

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
