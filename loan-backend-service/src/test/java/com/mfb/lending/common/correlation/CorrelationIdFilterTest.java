package com.mfb.lending.common.correlation;

import java.util.UUID;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void preservesASafeIncomingCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HEADER, "it-correlation-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(CorrelationIds.current()).isEqualTo("it-correlation-123");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIds.HEADER)).isEqualTo("it-correlation-123");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeCorrelationIds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HEADER, "bad id\ninjected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIds.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(generated).doesNotContain("bad id");
        verify(chain).doFilter(request, response);
    }

    @Test
    void propagatorCopiesTheCurrentCorrelationIdOntoOutboundHeaders() {
        MDC.put(CorrelationIds.MDC_KEY, "outbound-123");
        HttpHeaders headers = new HttpHeaders();

        CorrelationPropagator.apply(headers);

        assertThat(headers.getFirst(CorrelationIds.HEADER)).isEqualTo("outbound-123");
        CorrelationPropagator.apply(headers);
        assertThat(headers.get(CorrelationIds.HEADER)).containsExactly("outbound-123");
    }
}
