package ra.bluetooth;

import ra.util.tasks.BaseTask;
import ra.util.tasks.TaskRunner;

import javax.bluetooth.LocalDevice;
import java.util.logging.Logger;

public final class CheckPowerStatus extends BaseTask {

    private static final Logger LOG = Logger.getLogger(CheckPowerStatus.class.getName());

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
            LOG.info("Bluetooth Radio is On.");
            service.awaken();
        } else {
            powerOn = false;
            LOG.info("Bluetooth Radio is Off.");
            service.sleep();
        }
        return true;
    }
}
