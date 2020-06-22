package ra.bluetooth;

import javax.bluetooth.LocalDevice;

public class CheckPowerStatus extends NetworkTask {

    private boolean powerOn = false;

    public CheckPowerStatus(TaskRunner taskRunner, io.onemfive.network.sensors.bluetooth.BluetoothSensor sensor) {
        super(io.onemfive.network.sensors.bluetooth.CheckPowerStatus.class.getName(), taskRunner, sensor);
    }

    @Override
    public Boolean execute() {
        if(!powerOn && LocalDevice.isPowerOn()) {
            powerOn = true;
            ((io.onemfive.network.sensors.bluetooth.BluetoothSensor)sensor).awaken();
        } else {
            powerOn = false;
            ((io.onemfive.network.sensors.bluetooth.BluetoothSensor)sensor).sleep();
        }
        return true;
    }
}
