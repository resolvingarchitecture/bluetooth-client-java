package ra.bluetooth;

import ra.util.tasks.BaseTask;
import ra.util.tasks.TaskRunner;

public class BluetoothPeerDiscovery extends BaseTask {

    private BluetoothService service;

    public BluetoothPeerDiscovery(TaskRunner taskRunner, BluetoothService service) {
        super(BluetoothPeerDiscovery.class.getSimpleName(), taskRunner);
        this.service = service;
    }

    @Override
    public Boolean execute() {
        return null;
    }
}
