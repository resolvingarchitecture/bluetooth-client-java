package ra.bluetooth;

import ra.common.Client;
import ra.common.Envelope;
import ra.common.messaging.MessageProducer;

import java.util.logging.Logger;

public class MockProducer implements MessageProducer {

    private static Logger LOG = Logger.getLogger(MockProducer.class.getName());

    @Override
    public boolean send(Envelope envelope) {
        LOG.info("Env sent...");
        return true;
    }

    @Override
    public boolean send(Envelope envelope, Client client) {
        LOG.info("Env sent, client waiting...");
        return true;
    }
}
