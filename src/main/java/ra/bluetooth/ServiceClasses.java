package ra.bluetooth;

import javax.bluetooth.UUID;

public class ServiceClasses {

    public static final int OBEX_OBJECT_PUSH = 0x1105;
    public static final int OBEX_FILE_TRANSFER = 0x1106;

    public static UUID getUUID(int serviceClassId){
        return new UUID(serviceClassId);
    }
}
