package dev.hookrelay.server.config;

import dev.hookrelay.server.delivery.DelayTier;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker layout:
 * <pre>
 * hookrelay.work  --work-->  deliveries.work        (quorum; the workers consume this)
 * hookrelay.delay --5s-->    deliveries.delay.5s    (TTL 5s, then dead-letters into hookrelay.work)
 *                 ...one queue per {@link DelayTier}
 * hookrelay.dead  --dead-->  deliveries.dead        (quorum; DLQ consumer emits notifications)
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopology {

    public static final String WORK_EXCHANGE = "hookrelay.work";
    public static final String DELAY_EXCHANGE = "hookrelay.delay";
    public static final String DEAD_EXCHANGE = "hookrelay.dead";
    public static final String WORK_QUEUE = "deliveries.work";
    public static final String DEAD_QUEUE = "deliveries.dead";
    public static final String WORK_KEY = "work";
    public static final String DEAD_KEY = "dead";

    @Bean
    Declarables deliveryTopology() {
        List<Declarable> all = new ArrayList<>();
        DirectExchange work = new DirectExchange(WORK_EXCHANGE);
        DirectExchange delay = new DirectExchange(DELAY_EXCHANGE);
        DirectExchange dead = new DirectExchange(DEAD_EXCHANGE);
        Queue workQueue = QueueBuilder.durable(WORK_QUEUE).quorum().build();
        Queue deadQueue = QueueBuilder.durable(DEAD_QUEUE).quorum().build();
        all.addAll(List.of(work, delay, dead, workQueue, deadQueue,
                BindingBuilder.bind(workQueue).to(work).with(WORK_KEY),
                BindingBuilder.bind(deadQueue).to(dead).with(DEAD_KEY)));
        for (DelayTier tier : DelayTier.values()) {
            Queue q = QueueBuilder.durable(tier.queueName())
                    .ttl((int) tier.duration().toMillis())
                    .deadLetterExchange(WORK_EXCHANGE)
                    .deadLetterRoutingKey(WORK_KEY)
                    .build();
            Binding b = BindingBuilder.bind(q).to(delay).with(tier.routingKey());
            all.add(q);
            all.add(b);
        }
        return new Declarables(all);
    }
}
