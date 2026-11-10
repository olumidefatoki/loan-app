package com.mfb.lending.common.correlation;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

public class CorrelationIdMessagePostProcessor implements MessagePostProcessor {

    @Override
    public Message postProcessMessage(Message message) {
        String correlationId = CorrelationIds.current();
        if (correlationId != null) {
            message.getMessageProperties().setCorrelationId(correlationId);
            message.getMessageProperties().setHeader(CorrelationIds.HEADER, correlationId);
        }
        return message;
    }
}
