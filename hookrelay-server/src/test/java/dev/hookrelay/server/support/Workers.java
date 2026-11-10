package dev.hookrelay.server.support;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

/** Stops/starts every RabbitMQ listener so a test can read queues directly. */
public final class Workers {

    private Workers() { }

    public static void stop(RabbitListenerEndpointRegistry registry) {
        registry.stop();
    }

    public static void start(RabbitListenerEndpointRegistry registry) {
        registry.start();
    }
}
