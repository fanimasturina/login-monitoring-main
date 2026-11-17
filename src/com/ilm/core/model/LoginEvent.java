package com.ilm.core.model;

import java.time.LocalDateTime;

/** One standardized authentication event (FR-06..FR-10). Same shape for every source. */
public final class LoginEvent {
    public static final String SRC_ATI = "ATI";
    public static final String SRC_WEB_USER = "WEB_USER";
    public static final String SRC_WEB_SECURITY = "WEB_SECURITY";

    public static final String TYPE_LOGIN = "LOGIN";
    public static final String TYPE_LOGOUT = "LOGOUT";
    public static final String TYPE_UNAUTHORIZED = "UNAUTHORIZED_ACCESS";

    public static final String RES_SUCCESS = "SUCCESS";
    public static final String RES_FAILED = "FAILED";
    public static final String RES_UNKNOWN = "UNKNOWN";

    public static final String ANONYMOUS = "(anonymous)";

    public long id;
    public String sourceType;
    public String appCode;
    public String userId;
    public String displayName;
    public LocalDateTime eventTime;
    public String eventType;
    public String result;
    public String ipAddress;
    /** LOOPBACK (proxy / same host, not the real client), PRIVATE, PUBLIC or NONE. */
    public String ipClass;
    public String pcName;
    public String endpoint;
    public String threadName;
    /** e.g. LoginController:doLogin:95 - used to learn how each app version logs. */
    public String codeLocation;
    /** Aua Id for ATI rows, line number for log files. */
    public String sourceRef;
    public String rawText;
    /** SHA-256 identity of the source record; makes re-uploading the same file harmless. */
    public String eventHash;

    public boolean isLogin() { return TYPE_LOGIN.equals(eventType); }
    public boolean isFailed() { return RES_FAILED.equals(result); }
    public boolean isSuccess() { return RES_SUCCESS.equals(result); }
    public boolean isUnauthorized() { return TYPE_UNAUTHORIZED.equals(eventType); }
}
