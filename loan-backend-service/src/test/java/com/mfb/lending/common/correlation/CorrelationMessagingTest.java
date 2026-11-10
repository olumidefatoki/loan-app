package com.mfb.lending.common.correlation;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrelationMessagingTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void messagePostProcessorCopiesTheCorrelationId() {
        MDC.put(CorrelationIds.MDC_KEY, "msg-123");
        Message message = MessageBuilder.withBody("{}".getBytes()).build();

        Message processed = new CorrelationIdMessagePostProcessor().postProcessMessage(message);
        String header = processed.getMessageProperties().getHeader(CorrelationIds.HEADER);

        assertThat(processed.getMessageProperties().getCorrelationId()).isEqualTo("msg-123");
        assertThat(header).isEqualTo("msg-123");
    }

    @Test
    void listenerAdviceAppliesAndClearsTheCorrelationId() throws Throwable {
        Message message = MessageBuilder.withBody("{}".getBytes())
                .setHeader(CorrelationIds.HEADER, "listener-123")
                .build();
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[] {message});
        when(invocation.proceed()).thenAnswer(call -> {
            assertThat(CorrelationIds.current()).isEqualTo("listener-123");
            return "handled";
        });

        Object result = new CorrelationIdListenerAdvice().invoke(invocation);

        assertThat(result).isEqualTo("handled");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        verify(invocation).proceed();
    }
}
