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
    private final WorkerResultProperties workerResultProperties;
    private final RabbitProperties rabbitProperties;

    public RabbitMQConfig(WorkspaceConfig workspaceConfig,
                          WorkerResultProperties workerResultProperties,
                          RabbitProperties rabbitProperties) {
        this.workspaceConfig = workspaceConfig;
        this.workerResultProperties = workerResultProperties;
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
    public DirectExchange workerResultExchange() {
        return ExchangeBuilder.directExchange(workerResultProperties.getExchange()).durable(true).build();
    }

    @Bean
    public Queue workerResultQueue() {
        return QueueBuilder.durable(workerResultProperties.getQueue()).build();
    }

    @Bean
    public Binding workerResultBinding(Queue workerResultQueue, DirectExchange workerResultExchange) {
        return BindingBuilder.bind(workerResultQueue)
                .to(workerResultExchange)
                .with(workerResultProperties.getRoutingKey());
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

    /**
     * Consumer pool for the job dispatch queue.
     *
     * <p>This is the setting that decides whether the platform can actually run two
     * independent jobs at once. Publishing messages quickly is not enough: a single
     * consumer thread would take one message, run it to completion, and only then
     * pick up the next — the queue would look parallel while the timeline stayed
     * strictly serial. {@code WORKER_CONCURRENCY} sizes the pool, and it is a hard
     * cap, not a hint: threads are never created beyond
     * {@code maxConcurrentConsumers}.
     *
     * <p>Prefetch is tied to the pool size. With {@code prefetch = 1} and four
     * consumers, a broker can still hand all queued work to a single thread, which
     * serialises execution while reporting a healthy connection. Matching prefetch
     * to the consumer count is what makes the pool effective.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        return buildFactory(connectionFactory, workspaceConfig.getConcurrency());
    }

    /**
     * Consumer pool for the (single-threaded) result and outbox consumers.
     *
     * <p>Deliberately separate from the job pool: a slow persistence handler must
     * never be able to starve job execution of consumer threads.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory controlPlaneListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        return buildFactory(connectionFactory, 1);
    }

    private SimpleRabbitListenerContainerFactory buildFactory(ConnectionFactory connectionFactory,
                                                             int concurrency) {
        int consumers = Math.max(1, concurrency);
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setConcurrentConsumers(consumers);
        factory.setMaxConcurrentConsumers(consumers);
        factory.setPrefetchCount(workspaceConfig.resolvePrefetch(consumers));
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setAutoStartup(rabbitProperties.getListener().getSimple().isAutoStartup());
        return factory;
    }
}
