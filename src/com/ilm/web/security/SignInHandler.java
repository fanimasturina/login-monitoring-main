package com.ilm.web.security;

import com.ilm.web.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** Records sign-ins to this application in the audit trail and redirects. */
@Component
public class SignInHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private final AuditService audit;

    public SignInHandler(AuditService audit) { this.audit = audit; }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        audit.log(authentication.getName(), "SIGN_IN", "app", "role " + authentication.getAuthorities(), request.getRemoteAddr());
        response.sendRedirect(request.getContextPath() + "/dashboard");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        String code = exception instanceof LockedException ? "locked" : exception instanceof AuthenticationServiceException ? "unavailable" : "error";
        response.sendRedirect(request.getContextPath() + "/login?" + code);
    }
}
