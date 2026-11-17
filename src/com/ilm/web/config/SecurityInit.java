package com.ilm.web.config;

import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;

/** Registers the Spring Security filter chain for every request. */
public class SecurityInit extends AbstractSecurityWebApplicationInitializer {
    @Override
    protected boolean enableHttpSessionEventPublisher() { return true; }
}
