package com.ilm.web.config;

import com.ilm.web.security.IlmAuthenticationProvider;
import com.ilm.web.security.SignInHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Role-based access control (NFR-S01, NFR-S04, NFR-S06):
 *   ANALYST  monitoring, investigation, upload
 *   ADMIN    settings, users, model, upload (cannot change investigation results - BR-06/BR-07)
 *   MANAGER  dashboard and reports (personal data masked)
 *   AUDITOR  read-only, including the audit trail
 * CSRF protection, session fixation protection and security headers are on (Spring Security defaults).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, IlmAuthenticationProvider provider, SignInHandler handler) throws Exception {
        http.authenticationProvider(provider)
            .authorizeHttpRequests(a -> a
                // JSP views and error pages are rendered by an internal forward; the original request was already checked.
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                .requestMatchers("/assets/**", "/login").permitAll()
                .requestMatchers("/admin/audit", "/admin/audit/**").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/upload", "/upload/**").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/alerts/**").hasRole("ANALYST")
                .anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login").loginProcessingUrl("/login").successHandler(handler).failureHandler(handler))
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()).maximumSessions(2))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(
                    "default-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }
}
