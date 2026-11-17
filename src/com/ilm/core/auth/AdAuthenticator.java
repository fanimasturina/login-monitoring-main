package com.ilm.core.auth;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;
import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

/**
 * Microsoft Active Directory login by LDAP bind (JDK JNDI only, no extra library).
 * The user's own credentials are used for the bind, so no service-account password is stored.
 */
public final class AdAuthenticator {
    public record AdUser(String username, String displayName, String email, List<String> groups) {}

    /** Wrong password, unknown user, locked/disabled/expired account. Reason is for the audit trail only. */
    public static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;
        public Rejected(String reason) { super(reason); }
    }

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");

    private final String urls;
    private final String domain;
    private final String searchBase;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    /**
     * @param urls one or more LDAP URLs separated by space, e.g. "ldaps://dc01.corp.local:636 ldaps://dc02.corp.local:636"
     * @param domain AD domain used for the bind name, e.g. corp.local (bind as user@corp.local)
     * @param searchBase e.g. DC=corp,DC=local; when blank it is derived from the domain
     */
    public AdAuthenticator(String urls, String domain, String searchBase, boolean allowInsecure, int connectTimeoutMs, int readTimeoutMs) {
        if (urls == null || urls.isBlank() || domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("auth.ad.url and auth.ad.domain are required");
        }
        for (String u : urls.trim().split("\\s+")) {
            if (!u.toLowerCase().startsWith("ldaps://") && !allowInsecure) {
                throw new IllegalArgumentException("auth.ad.url must use ldaps:// (set auth.ad.allow.insecure=true only for testing): " + u);
            }
        }
        this.urls = urls.trim();
        this.domain = domain.trim();
        this.searchBase = searchBase == null || searchBase.isBlank()
                ? "DC=" + String.join(",DC=", this.domain.split("\\.")) : searchBase.trim();
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Strip DOMAIN\ prefix or @domain suffix and lower-case. Returns null when the name is not acceptable. */
    public static String normalize(String input) {
        if (input == null) return null;
        String u = input.trim();
        int bs = u.lastIndexOf('\\');
        if (bs >= 0) u = u.substring(bs + 1);
        int at = u.indexOf('@');
        if (at > 0) u = u.substring(0, at);
        return USERNAME.matcher(u).matches() ? u.toLowerCase() : null;
    }

    public AdUser authenticate(String usernameInput, char[] password) throws Rejected, NamingException {
        String username = normalize(usernameInput);
        if (username == null) throw new Rejected("invalid username format");
        // An empty password would be an anonymous bind that AD may accept: always reject it.
        if (password == null || password.length == 0) throw new Rejected("empty password");
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, urls);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, username + "@" + domain);
        env.put(Context.SECURITY_CREDENTIALS, new String(password));
        env.put(Context.REFERRAL, "ignore");
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(connectTimeoutMs));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(readTimeoutMs));
        LdapContext ctx;
        try {
            ctx = new InitialLdapContext(env, null);
        } catch (AuthenticationException ex) {
            throw new Rejected(reason(ex.getMessage()));
        }
        try {
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setReturningAttributes(new String[] {"displayName", "mail", "memberOf", "sAMAccountName"});
            sc.setCountLimit(2);
            sc.setTimeLimit(readTimeoutMs);
            // {0} is passed as an argument, so JNDI escapes it (no LDAP injection).
            NamingEnumeration<SearchResult> res = ctx.search(searchBase, "(&(objectCategory=person)(objectClass=user)(sAMAccountName={0}))",
                    new Object[] {username}, sc);
            String display = username, mail = null;
            List<String> groups = new ArrayList<>();
            try {
                if (res.hasMore()) {
                    Attributes at = res.next().getAttributes();
                    display = text(at.get("displayName"), username);
                    mail = text(at.get("mail"), null);
                    Attribute mo = at.get("memberOf");
                    if (mo != null) {
                        for (int i = 0; i < mo.size(); i++) {
                            String dn = String.valueOf(mo.get(i));
                            int eq = dn.indexOf('='), comma = dn.indexOf(',');
                            groups.add(eq >= 0 && comma > eq ? dn.substring(eq + 1, comma) : dn);
                        }
                    }
                }
            } catch (javax.naming.PartialResultException ignored) {
                // AD returns referrals at the domain root; the user entry was already read
            } finally {
                res.close();
            }
            return new AdUser(username, display, mail, groups);
        } finally {
            ctx.close();
        }
    }

    private static String text(Attribute a, String fallback) throws NamingException {
        return a == null || a.get() == null ? fallback : String.valueOf(a.get());
    }

    /** AD puts the real reason in a sub-code, e.g. "... data 52e, ...". */
    private static String reason(String msg) {
        if (msg == null) return "bind rejected";
        if (msg.contains("data 52e")) return "wrong password";
        if (msg.contains("data 525")) return "user not found";
        if (msg.contains("data 530") || msg.contains("data 531")) return "logon not permitted at this time or from this workstation";
        if (msg.contains("data 532")) return "password expired";
        if (msg.contains("data 533")) return "account disabled";
        if (msg.contains("data 701")) return "account expired";
        if (msg.contains("data 773")) return "user must reset password";
        if (msg.contains("data 775")) return "account locked";
        return "bind rejected";
    }
}
