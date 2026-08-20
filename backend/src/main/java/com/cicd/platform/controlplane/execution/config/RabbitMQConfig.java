package com.cicd.platform.controlplane.execution.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    private final WorkspaceConfig workspaceConfig;
    private final RabbitProperties rabbitProperties;

    public RabbitMQConfig(WorkspaceConfig workspaceConfig, RabbitProperties rabbitProperties) {
        this.workspaceConfig = workspaceConfig;
        this.rabbitProperties = rabbitProperties;
    }

    @Bean
    public DirectExchange jobDispatchExchange() {
        return new DirectExchange(ExecutionConstants.JOB_DISPATCH_EXCHANGE);
    }

    @Bean
    public Queue jobDispatchQueue() {
        return QueueBuilder.durable(ExecutionConstants.JOB_DISPATCH_QUEUE)
                .withArgument("x-dead-letter-exchange", ExecutionConstants.JOB_RESULT_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ExecutionConstants.JOB_RESULT_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding jobDispatchBinding(Queue jobDispatchQueue, DirectExchange jobDispatchExchange) {
        return BindingBuilder.bind(jobDispatchQueue)
                .to(jobDispatchExchange)
                .with(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY);
    }

    @Bean
    public DirectExchange jobResultExchange() {
        return new DirectExchange(ExecutionConstants.JOB_RESULT_EXCHANGE);
    }

    @Bean
    public Queue jobResultQueue() {
        return QueueBuilder.durable(ExecutionConstants.JOB_RESULT_QUEUE).build();
    }

    @Bean
    public Binding jobResultBinding(Queue jobResultQueue, DirectExchange jobResultExchange) {
        return BindingBuilder.bind(jobResultQueue)
                .to(jobResultExchange)
                .with(ExecutionConstants.JOB_RESULT_ROUTING_KEY);
    }

    @Bean
    public DirectExchange outboxExchange() {
        return new DirectExchange("outbox.exchange");
    }

    @Bean
    public Queue outboxQueue() {
        return QueueBuilder.durable("outbox.queue").build();
    }

    @Bean
    public Binding outboxBinding(Queue outboxQueue, DirectExchange outboxExchange) {
        return BindingBuilder.bind(outboxQueue)
                .to(outboxExchange)
                .with("outbox.event");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setPrefetchCount(workspaceConfig.getPrefetch());
        factory.setConcurrentConsumers(workspaceConfig.getConcurrency());
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setAutoStartup(rabbitProperties.getListener().getSimple().isAutoStartup());
        return factory;
    }
}
