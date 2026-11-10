package com.mfb.lending.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.correlation.CorrelationIdListenerAdvice;
import com.mfb.lending.common.correlation.CorrelationIdMessagePostProcessor;

@Configuration
public class RabbitMqConfig {

    public static final String PLATFORM_EXCHANGE = "lending.platform";
    public static final String PLATFORM_DEAD_LETTER_EXCHANGE = "lending.platform.dlx";

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            RabbitTemplateConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter,
            CorrelationIdMessagePostProcessor correlationIdMessagePostProcessor) {
        RabbitTemplate template = new RabbitTemplate();
        configurer.configure(template, connectionFactory);
        template.setMessageConverter(messageConverter);
        template.addBeforePublishPostProcessors(correlationIdMessagePostProcessor);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter,
            CorrelationIdListenerAdvice correlationIdListenerAdvice) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAdviceChain(correlationIdListenerAdvice);
        return factory;
    }

    @Bean
    public DirectExchange platformExchange() {
        return new DirectExchange(PLATFORM_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange platformDeadLetterExchange() {
        return new DirectExchange(PLATFORM_DEAD_LETTER_EXCHANGE, true, false);
    }
}
