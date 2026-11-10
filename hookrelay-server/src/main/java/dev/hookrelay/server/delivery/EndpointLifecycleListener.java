package dev.hookrelay.server.delivery;

import dev.hookrelay.server.endpoint.EndpointLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves an endpoint's deliveries after the endpoint change commits, in a separate transaction,
 * so the endpoint row and delivery rows are never locked in the opposite order to the workers.
 * If the process dies in between, the worker's claim (deleted endpoint → dead) and the stuck
 * delivery sweeper (parked on an active endpoint → re-queued) finish the job.
 */
@Component
class EndpointLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(EndpointLifecycleListener.class);

    private final DeliveryStateService state;
    private final TransactionTemplate newTx;

    EndpointLifecycleListener(DeliveryStateService state, PlatformTransactionManager txm) {
        this.state = state;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    void on(EndpointLifecycle event) {
        switch (event) {
            case EndpointLifecycle.Resumed r -> {
                Integer n = newTx.execute(tx -> state.unparkAll(r.endpointId()));
                log.info("Endpoint {} resumed; {} parked deliveries re-queued", r.endpointId(), n);
            }
            case EndpointLifecycle.Deleted d -> {
                Integer n = newTx.execute(tx -> state.killPending(d.endpointId(), "endpoint_deleted"));
                log.info("Endpoint {} deleted; {} pending deliveries dead-lettered", d.endpointId(), n);
            }
        }
    }
}
