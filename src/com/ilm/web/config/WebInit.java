package com.ilm.web.config;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionTrackingMode;
import java.util.Set;
import org.springframework.web.context.request.RequestContextListener;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

/** Starts Spring MVC inside Tomcat (replaces the servlet section of web.xml). */
public class WebInit extends AbstractAnnotationConfigDispatcherServletInitializer {
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    @Override
    protected Class<?>[] getRootConfigClasses() {
        // One application context for MVC and security keeps the configuration simple.
        return new Class<?>[] {WebConfig.class, SecurityConfig.class};
    }

    @Override
    protected Class<?>[] getServletConfigClasses() { return new Class<?>[0]; }

    @Override
    protected String[] getServletMappings() { return new String[] {"/"}; }

    @Override
    protected void customizeRegistration(ServletRegistration.Dynamic registration) {
        registration.setMultipartConfig(new MultipartConfigElement(null, MAX_UPLOAD_BYTES, MAX_UPLOAD_BYTES + 1024 * 1024, 1024 * 1024));
    }

    @Override
    public void onStartup(ServletContext ctx) throws ServletException {
        ctx.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE)); // never put the session id in the URL
        ctx.getSessionCookieConfig().setHttpOnly(true);
        ctx.setSessionTimeout(20); // minutes
        // Makes the caller's IP address available to the audit trail during sign-in (before Spring MVC runs).
        ctx.addListener(RequestContextListener.class);
        super.onStartup(ctx);
    }
}
