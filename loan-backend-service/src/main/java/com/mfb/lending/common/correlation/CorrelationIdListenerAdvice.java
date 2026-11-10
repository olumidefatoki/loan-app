package com.mfb.lending.common.correlation;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

public class CorrelationIdListenerAdvice implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = findMessage(invocation.getArguments());
        String correlationId = message == null ? null : correlationId(message);
        if (correlationId == null) {
            return invocation.proceed();
        }

        String previous = MDC.get(CorrelationIds.MDC_KEY);
        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        try {
            return invocation.proceed();
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationIds.MDC_KEY);
            } else {
                MDC.put(CorrelationIds.MDC_KEY, previous);
            }
        }
    }

    private static Message findMessage(Object[] arguments) {
        if (arguments == null) {
            return null;
        }
        for (Object argument : arguments) {
            if (argument instanceof Message message) {
                return message;
            }
        }
        return null;
    }

    private static String correlationId(Message message) {
        Object header = message.getMessageProperties().getHeaders().get(CorrelationIds.HEADER);
        if (header != null && CorrelationIds.isValid(header.toString())) {
            return header.toString();
        }
        String correlationId = message.getMessageProperties().getCorrelationId();
        if (CorrelationIds.isValid(correlationId)) {
            return correlationId;
        }
        return null;
    }
}
