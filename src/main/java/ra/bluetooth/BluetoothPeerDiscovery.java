package ra.bluetooth;

import ra.common.Envelope;
import ra.common.network.NetworkPeer;
import ra.common.network.NetworkService;
import ra.util.tasks.BaseTask;
import ra.util.tasks.TaskRunner;

import java.util.ArrayList;
import java.util.List;

public class BluetoothPeerDiscovery extends BaseTask {

    private BluetoothService service;

    public BluetoothPeerDiscovery(BluetoothService service, TaskRunner taskRunner) {
        super(BluetoothPeerDiscovery.class.getSimpleName(), taskRunner);
        this.service = service;
    }

    @Override
    public Boolean execute() {
        running = true;
        List<NetworkPeer> peers = new ArrayList<>();
        peers.addAll(service.peersInDiscovery.values());
        for(NetworkPeer destination : peers) {
            Envelope e = Envelope.commandFactory();
            // Add in reverse order as underlying routing slip is a stack
            e.addExternalRoute(BluetoothService.class.getName(), NetworkService.OPERATION_PEER_STATUS_REPLY, destination, service.getNetworkState().localPeer);
            e.addExternalRoute(BluetoothService.class.getName(), NetworkService.OPERATION_PEER_STATUS, service.getNetworkState().localPeer, destination);
            service.sendOut(e);
        }
        running = false;
        return true;
    }
}
