package dev.hookrelay.server.delivery;

import dev.hookrelay.server.config.HookRelayProperties;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DeliveryBeans {

    @Bean
    WebhookSender webhookSender(HookRelayProperties props) {
        return new WebhookSender(props.delivery().connectTimeout(), props.delivery().sendTimeout());
    }

    @Bean
    RetryPolicy retryPolicy(HookRelayProperties props) {
        return new RetryPolicy(props.delivery().retrySchedule());
    }

    @Bean
    SimpleRabbitListenerContainerFactory workerContainerFactory(ConnectionFactory cf, HookRelayProperties props) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        f.setConnectionFactory(cf);
        f.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        f.setConcurrentConsumers(props.delivery().consumerConcurrency());
        f.setPrefetchCount(props.delivery().prefetch());
        f.setDefaultRequeueRejected(false);
        return f;
    }
}
