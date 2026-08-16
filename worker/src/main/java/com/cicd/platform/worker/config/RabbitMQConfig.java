package com.cicd.platform.worker.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the RabbitMQ topology:
 *
 * <pre>
 *   cicd.jobs.exchange  (direct)
 *      |-- cicd.jobs          <- main job queue        (rk: cicd.job.submitted)
 *      |-- cicd.jobs.delay    <- TTL delay queue       (rk: cicd.job.delay)
 *      |-- cicd.jobs.dlq      <- dead-letter queue     (rk: cicd.job.dead)
 *
 *   cicd.results.exchange (direct) -> cicd.result
 * </pre>
 *
 * <p>Retry: a transient failure is re-published to the delay queue. After
 * {@code x-message-ttl} the broker dead-letters it back into the main queue
 * (the delay queue's DLX is the jobs exchange, DL-RK is the job routing key).
 * Messages that exhaust their retry budget are rejected from the main queue,
 * whose DLX routes them to the dead-letter queue.</p>
 *
 * <p>At-least-once semantics: messages are acknowledged only after the
 * pipeline result has been published (see {@link com.cicd.platform.worker.messaging.PipelineJobConsumer}).</p>
 */
@Configuration
public class RabbitMQConfig {

    private final WorkerProperties props;

    public RabbitMQConfig(WorkerProperties props) {
        this.props = props;
    }

    @Bean
    public DirectExchange jobsExchange() {
        return ExchangeBuilder.directExchange(props.getRabbit().getJobsExchange()).durable(true).build();
    }

    @Bean
    public DirectExchange resultsExchange() {
        return ExchangeBuilder.directExchange(props.getRabbit().getResultsExchange()).durable(true).build();
    }

    @Bean
    public Queue jobQueue() {
        return QueueBuilder.durable(props.getRabbit().getJobQueue())
                .withArgument("x-dead-letter-exchange", props.getRabbit().getJobsExchange())
                .withArgument("x-dead-letter-routing-key", props.getRabbit().getDeadRoutingKey())
                .build();
    }

    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(props.getRabbit().getDelayQueue())
                .withArgument("x-message-ttl", props.getRetryDelayMs())
                .withArgument("x-dead-letter-exchange", props.getRabbit().getJobsExchange())
                .withArgument("x-dead-letter-routing-key", props.getRabbit().getJobRoutingKey())
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(props.getRabbit().getDeadLetterQueue()).build();
    }

    @Bean
    public Binding jobBinding(Queue jobQueue, DirectExchange jobsExchange) {
        return BindingBuilder.bind(jobQueue).to(jobsExchange).with(props.getRabbit().getJobRoutingKey());
    }

    @Bean
    public Binding delayBinding(Queue delayQueue, DirectExchange jobsExchange) {
        return BindingBuilder.bind(delayQueue).to(jobsExchange).with(props.getRabbit().getDelayRoutingKey());
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange jobsExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(jobsExchange).with(props.getRabbit().getDeadRoutingKey());
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(props.getMaxConcurrency());
        factory.setMaxConcurrentConsumers(props.getMaxConcurrency());
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setMessageConverter(new org.springframework.amqp.support.converter.SimpleMessageConverter());
        return factory;
    }
}
