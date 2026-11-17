<c:set var="pageTitle" value="Login event" scope="request"/>
<%@ include file="_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Login event #${e.id}</h1>
        <p><c:out value="${e.user_id}"/> on <c:out value="${e.app_code}"/>, <fmt:formatDate value="${e.event_time}" pattern="EEEE d MMMM yyyy HH:mm:ss"/></p>
    </div>
    <div class="d-flex gap-2">
        <c:if test="${not empty e.alert_id}"><a class="btn btn-primary" href="${ctx}/alerts/${e.alert_id}">Open alert #${e.alert_id}</a></c:if>
        <a class="btn btn-outline-secondary" href="${ctx}/events">All events</a>
    </div>
</div>
<div class="row g-3 mb-3">
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Why this score</h2>
            <c:choose>
                <c:when test="${empty e.risk_level}"><div class="empty">This event is not scored yet. It is scored right after upload; if this stays, ask an administrator to run Score all events again.</div></c:when>
                <c:otherwise>
                    <div class="d-flex align-items-center gap-3 my-2">
                        <span class="meter big" data-meter="${e.risk_score}">${e.risk_score}</span>
                        <span class="lvl lvl-${e.risk_level}">${fn:substring(e.risk_level, 0, 1)}${fn:toLowerCase(fn:substring(e.risk_level, 1, 20))}</span>
                    </div>
                    <div class="hint">Rules: ${e.rule_points} points. Machine learning: ${e.ml_points} points<c:if test="${not empty e.anomaly_score}">
                        (anomaly score <fmt:formatNumber value="${e.anomaly_score}" maxFractionDigits="2" minFractionDigits="2"/>, model version ${e.model_version})</c:if><c:if test="${empty e.anomaly_score}">
                        (no model used)</c:if>. The total is capped at 100.</div>
                    <c:forEach var="fa" items="${factors}">
                        <div class="factor">
                            <div class="pts ${fa.points == 0 ? 'zero' : ''}"><c:if test="${fa.points == 0}">note</c:if><c:if test="${fa.points != 0}">+${fa.points}</c:if></div>
                            <div><c:out value="${fa.text}"/></div>
                        </div>
                    </c:forEach>
                    <c:if test="${empty factors}"><div class="empty">Nothing unusual was found for this login.</div></c:if>
                </c:otherwise>
            </c:choose>
        </div>
    </div>
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Event details</h2>
            <table class="table table-sm mt-2">
                <tbody>
                <tr><th scope="row">User ID</th><td><c:out value="${e.user_id}"/><c:if test="${not empty e.display_name}"> (<c:out value="${e.display_name}"/>)</c:if></td></tr>
                <tr><th scope="row">Application</th><td><c:out value="${e.app_code}"/></td></tr>
                <tr><th scope="row">Event</th><td>${e.event_type == 'LOGIN' ? 'Login' : e.event_type == 'LOGOUT' ? 'Logout' : 'Unauthorized access'},
                    result ${e.auth_result == 'SUCCESS' ? 'Success' : e.auth_result == 'FAILED' ? 'Failed' : 'Unknown'}</td></tr>
                <tr><th scope="row">Source</th><td>${e.source_type == 'ATI' ? 'Audit Trail Integrator' : e.source_type == 'WEB_USER' ? 'Application login log (web-user.log)' : 'Application security log (web-security.log)'},
                    reference <c:out value="${e.source_ref}"/></td></tr>
                <tr><th scope="row">IP address</th><td><c:out value="${empty e.ip_address ? '-' : e.ip_address}"/>
                    <c:if test="${e.ip_class == 'LOOPBACK'}"><span class="text-secondary">(proxy or same server, not the user's device)</span></c:if></td></tr>
                <tr><th scope="row">PC name</th><td><c:out value="${empty e.pc_name ? '-' : e.pc_name}"/></td></tr>
                <tr><th scope="row">Endpoint</th><td><c:out value="${empty e.endpoint ? '-' : e.endpoint}"/></td></tr>
                <tr><th scope="row">Original record</th><td class="mono"><c:out value="${e.raw_text}"/></td></tr>
                </tbody>
            </table>
        </div>
    </div>
</div>
<c:if test="${e.event_type != 'UNAUTHORIZED_ACCESS'}">
    <div class="row g-3 mb-3"><div class="col-12"><%@ include file="_profile.jspf" %></div></div>
    <%@ include file="_history.jspf" %>
</c:if>
<%@ include file="_bottom.jspf" %>
