package ra.bluetooth;

import ra.util.tasks.BaseTask;
import ra.util.tasks.TaskRunner;

import javax.bluetooth.LocalDevice;

public final class CheckPowerStatus extends BaseTask {

    private final BluetoothService service;
    private boolean powerOn = false;

    public CheckPowerStatus(TaskRunner taskRunner, BluetoothService service) {
        super(CheckPowerStatus.class.getSimpleName(), taskRunner);
        this.service = service;
    }

    @Override
    public Boolean execute() {
        if(!powerOn && LocalDevice.isPowerOn()) {
            powerOn = true;
            service.awaken();
        } else {
            powerOn = false;
            service.sleep();
        }
        return true;
    }
}
