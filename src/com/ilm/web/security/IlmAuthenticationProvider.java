package com.ilm.web.security;

import com.ilm.core.auth.AdAuthenticator;
import com.ilm.core.auth.PasswordHasher;
import com.ilm.web.config.AppProps;
import com.ilm.web.dao.AdminDao;
import com.ilm.web.service.AuditService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Sign-in to this application.
 *  - AD accounts: password is checked by Active Directory (LDAP bind); the role comes from table app_user.
 *  - LOCAL accounts: only when auth.local.enabled=true (development, or emergency access without AD).
 * A user who is not registered in app_user cannot sign in, even with a valid AD password.
 */
@Component
public class IlmAuthenticationProvider implements AuthenticationProvider {
    private static final Logger LOG = Logger.getLogger(IlmAuthenticationProvider.class.getName());
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MS = 15 * 60_000L;

    private final AppProps props;
    private final AdminDao dao;
    private final AuditService audit;
    private final AdAuthenticator ad;
    /** username -> {failures, firstFailureTime}: slows down password guessing against this app. */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();

    public IlmAuthenticationProvider(AppProps props, AdminDao dao, AuditService audit) {
        this.props = props;
        this.dao = dao;
        this.audit = audit;
        AdAuthenticator a = null;
        if (props.get("auth.ad.url") != null) {
            a = new AdAuthenticator(props.get("auth.ad.url"), props.get("auth.ad.domain"), props.get("auth.ad.search.base"),
                    props.bool("auth.ad.allow.insecure", false), props.i("auth.ad.connect.timeout.ms", 5000), props.i("auth.ad.read.timeout.ms", 8000));
        } else {
            LOG.warning("auth.ad.url is not set: Active Directory sign-in is disabled");
        }
        this.ad = a;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    @Override
    public Authentication authenticate(Authentication auth) {
        String user = AdAuthenticator.normalize(auth.getName());
        Object cred = auth.getCredentials();
        char[] password = cred == null ? new char[0] : cred.toString().toCharArray();
        try {
            if (user == null || password.length == 0) throw new BadCredentialsException("Invalid user ID or password");
            long[] f = failures.get(user);
            long now = System.currentTimeMillis();
            if (f != null && now - f[1] > LOCK_MS) { failures.remove(user); f = null; }
            if (f != null && f[0] >= MAX_FAILURES) {
                audit.log(user, "SIGN_IN_BLOCKED", "app", "too many failed sign-ins; locked for 15 minutes");
                throw new LockedException("Too many failed attempts. Try again in 15 minutes.");
            }
            String role = check(user, password);
            failures.remove(user);
            return UsernamePasswordAuthenticationToken.authenticated(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        } catch (BadCredentialsException e) {
            if (user != null) failures.compute(user, (k, v) -> v == null ? new long[] {1, System.currentTimeMillis()} : new long[] {v[0] + 1, v[1]});
            throw e;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Returns the role, or throws. The message shown to the user never says which part was wrong. */
    private String check(String user, char[] password) {
        Map<String, Object> row = dao.user(user);
        boolean localEnabled = props.bool("auth.local.enabled", false);
        // Bootstrap local admin from ilm.properties (no row needed): for first setup without AD.
        if (row == null && localEnabled && user.equals(props.get("auth.local.admin.user")) && props.get("auth.local.admin.hash") != null) {
            if (!PasswordHasher.verify(password, props.get("auth.local.admin.hash"))) return reject(user, "wrong password (local bootstrap admin)");
            dao.saveUser(user, "Local administrator", null, "ADMIN", "LOCAL", null, true, "bootstrap");
            return "ADMIN";
        }
        if (row != null && "LOCAL".equals(row.get("auth_source"))) {
            if (!localEnabled) return reject(user, "local account but auth.local.enabled=false");
            if (!Boolean.TRUE.equals(row.get("active"))) return reject(user, "account inactive");
            String hash = (String) row.get("password_hash");
            if (hash == null) hash = user.equals(props.get("auth.local.admin.user")) ? props.get("auth.local.admin.hash") : null;
            if (!PasswordHasher.verify(password, hash)) return reject(user, "wrong password (local)");
            dao.touchLogin(user, null, null);
            return (String) row.get("role");
        }
        if (ad == null) return reject(user, "Active Directory is not configured");
        boolean bootstrapAdmin = Arrays.asList(props.get("auth.bootstrap.admins", "").toLowerCase().split("\\s*,\\s*")).contains(user);
        if (row == null && !bootstrapAdmin) return reject(user, "not registered in this application");
        if (row != null && !Boolean.TRUE.equals(row.get("active"))) return reject(user, "account inactive");
        AdAuthenticator.AdUser adUser;
        try {
            adUser = ad.authenticate(user, password);
        } catch (AdAuthenticator.Rejected e) {
            return reject(user, "Active Directory: " + e.getMessage());
        } catch (javax.naming.NamingException e) {
            LOG.log(Level.SEVERE, "Active Directory is not reachable: " + e.getMessage(), e);
            throw new AuthenticationServiceException("Sign-in service is not available. Contact the administrator.");
        }
        if (row == null) {
            dao.saveUser(user, adUser.displayName(), adUser.email(), "ADMIN", "AD", null, true, "bootstrap");
            audit.log(user, "USER_BOOTSTRAP", user, "first administrator created from auth.bootstrap.admins");
            return "ADMIN";
        }
        dao.touchLogin(user, adUser.displayName(), adUser.email());
        return (String) row.get("role");
    }

    private String reject(String user, String reason) {
        audit.log(user, "SIGN_IN_FAILED", "app", reason);
        throw new BadCredentialsException("Invalid user ID or password");
    }
}
