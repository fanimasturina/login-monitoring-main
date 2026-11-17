<c:set var="pageTitle" value="Login events" scope="request"/>
<%@ include file="_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Login events</h1>
        <p><fmt:formatNumber value="${total}"/> events match. Every event is stored in one standard format, whatever the source.</p>
    </div>
</div>
<form method="get" class="panel mb-3">
    <div class="row g-2 align-items-end">
        <c:if test="${not masked}">
            <div class="col-md-2"><label class="form-label" for="user">User ID contains</label>
                <input class="form-control form-control-sm" id="user" name="user" maxlength="100" value="${fn:escapeXml(f.user())}"></div>
        </c:if>
        <div class="col-md-2"><label class="form-label" for="app">Application</label>
            <select class="form-select form-select-sm" id="app" name="app">
                <option value="">All</option>
                <c:forEach var="a" items="${apps}"><option value="${fn:escapeXml(a.app_code)}" ${a.app_code == f.app() ? 'selected' : ''}><c:out value="${a.app_code}"/></option></c:forEach>
            </select></div>
        <div class="col-md-2"><label class="form-label" for="level">Risk level</label>
            <select class="form-select form-select-sm" id="level" name="level">
                <option value="">All</option>
                <c:forEach var="l" items="CRITICAL,HIGH,MEDIUM,LOW"><option value="${l}" ${l == f.level() ? 'selected' : ''}>${fn:substring(l, 0, 1)}${fn:toLowerCase(fn:substring(l, 1, 20))}</option></c:forEach>
            </select></div>
        <div class="col-md-2"><label class="form-label" for="result">Result</label>
            <select class="form-select form-select-sm" id="result" name="result">
                <option value="">All</option>
                <option value="SUCCESS" ${f.result() == 'SUCCESS' ? 'selected' : ''}>Success</option>
                <option value="FAILED" ${f.result() == 'FAILED' ? 'selected' : ''}>Failed</option>
                <option value="UNKNOWN" ${f.result() == 'UNKNOWN' ? 'selected' : ''}>Unknown</option>
            </select></div>
        <div class="col-md-2"><label class="form-label" for="type">Event type</label>
            <select class="form-select form-select-sm" id="type" name="type">
                <option value="">All</option>
                <option value="LOGIN" ${f.type() == 'LOGIN' ? 'selected' : ''}>Login</option>
                <option value="UNAUTHORIZED_ACCESS" ${f.type() == 'UNAUTHORIZED_ACCESS' ? 'selected' : ''}>Unauthorized access</option>
                <option value="LOGOUT" ${f.type() == 'LOGOUT' ? 'selected' : ''}>Logout</option>
            </select></div>
        <div class="col-md-1"><label class="form-label" for="from">From</label>
            <input class="form-control form-control-sm" id="from" name="from" type="date" value="${fn:escapeXml(fFrom)}"></div>
        <div class="col-md-1"><label class="form-label" for="to">To</label>
            <input class="form-control form-control-sm" id="to" name="to" type="date" value="${fn:escapeXml(fTo)}"></div>
    </div>
    <div class="mt-3 d-flex gap-2">
        <button class="btn btn-sm btn-primary" type="submit">Apply filter</button>
        <a class="btn btn-sm btn-outline-secondary" href="${ctx}/events">Clear filter</a>
    </div>
</form>
<div class="panel">
    <c:choose>
        <c:when test="${empty rows}"><div class="empty">No events match this filter. Clear the filter or choose a wider date range.</div></c:when>
        <c:otherwise>
            <div class="table-responsive">
            <table class="table table-hover">
                <thead><tr><th>Time</th><th>User ID</th><th>Application</th><th>Source</th><th>Event</th><th>Result</th><th>Risk score</th><th>Level</th><th></th></tr></thead>
                <tbody>
                <c:forEach var="r" items="${rows}">
                    <tr>
                        <td class="text-nowrap"><fmt:formatDate value="${r.event_time}" pattern="d MMM yyyy HH:mm:ss"/></td>
                        <td><c:out value="${r.user_id}"/></td>
                        <td class="text-nowrap"><c:out value="${r.app_code}"/></td>
                        <td>${r.source_type == 'ATI' ? 'ATI' : r.source_type == 'WEB_USER' ? 'App login log' : 'App security log'}</td>
                        <td>${r.event_type == 'LOGIN' ? 'Login' : r.event_type == 'LOGOUT' ? 'Logout' : 'Unauthorized access'}</td>
                        <td><span class="state state-${r.auth_result}">${r.auth_result == 'SUCCESS' ? 'Success' : r.auth_result == 'FAILED' ? 'Failed' : 'Unknown'}</span></td>
                        <td><span data-meter="${r.risk_score}">${r.risk_score}</span></td>
                        <td><c:if test="${not empty r.risk_level}"><span class="lvl lvl-${r.risk_level}">${fn:substring(r.risk_level, 0, 1)}${fn:toLowerCase(fn:substring(r.risk_level, 1, 20))}</span></c:if>
                            <c:if test="${empty r.risk_level}"><span class="text-secondary">Not scored</span></c:if></td>
                        <td class="text-nowrap"><a href="${ctx}/events/${r.id}">Details</a><c:if test="${not empty r.alert_id}"> | <a href="${ctx}/alerts/${r.alert_id}">Alert #${r.alert_id}</a></c:if></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
            <c:url var="self" value=""><c:param name="user" value="${f.user()}"/><c:param name="app" value="${f.app()}"/><c:param name="level" value="${f.level()}"/>
                <c:param name="result" value="${f.result()}"/><c:param name="type" value="${f.type()}"/><c:param name="from" value="${fFrom}"/><c:param name="to" value="${fTo}"/></c:url>
            <c:set var="qs" value="${fn:escapeXml(fn:substringAfter(self, '?'))}&amp;"/>
            <%@ include file="_pager.jspf" %>
        </c:otherwise>
    </c:choose>
</div>
<%@ include file="_bottom.jspf" %>
