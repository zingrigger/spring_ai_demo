package com.example.weather.service.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserContextLoggingFilterTest {

    private final FilterChain filterChain = mock(FilterChain.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(UserContextLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(UserContextLoggingFilter.class);
        logger.detachAppender(appender);
    }

    @Test
    void logsReceivedUserContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "1001");
        request.addHeader("X-User-Tenant", "acme");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UserContextLoggingFilter().doFilter(request, response, filterChain);

        assertThat(appender.list).anyMatch(event ->
                event.getLevel().toString().equals("INFO")
                        && event.getFormattedMessage().contains("1001")
                        && event.getFormattedMessage().contains("acme"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotLogWithoutHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UserContextLoggingFilter().doFilter(request, response, filterChain);

        assertThat(appender.list).isEmpty();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotLogBlankUserId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UserContextLoggingFilter().doFilter(request, response, filterChain);

        assertThat(appender.list).isEmpty();
        verify(filterChain).doFilter(request, response);
    }
}
