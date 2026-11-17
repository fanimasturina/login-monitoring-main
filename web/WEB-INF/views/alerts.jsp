<c:set var="pageTitle" value="Alerts" scope="request"/>
<%@ include file="_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Alerts</h1>
        <p><fmt:formatNumber value="${total}"/> alerts. Risky logins of one user on one application within 30 minutes are grouped into one alert.</p>
    </div>
    <form method="get" class="d-flex align-items-end gap-2">
        <div><label class="form-label mb-0 small" for="status">Status</label>
            <select class="form-select form-select-sm" id="status" name="status" data-autosubmit>
                <option value="">All</option>
                <option value="OPEN" ${status == 'OPEN' ? 'selected' : ''}>Open</option>
                <option value="IN_PROGRESS" ${status == 'IN_PROGRESS' ? 'selected' : ''}>In progress</option>
                <option value="CLOSED" ${status == 'CLOSED' ? 'selected' : ''}>Closed</option>
            </select></div>
        <div><label class="form-label mb-0 small" for="severity">Level</label>
            <select class="form-select form-select-sm" id="severity" name="severity" data-autosubmit>
                <option value="">All</option>
                <option value="CRITICAL" ${severity == 'CRITICAL' ? 'selected' : ''}>Critical</option>
                <option value="HIGH" ${severity == 'HIGH' ? 'selected' : ''}>High</option>
                <option value="MEDIUM" ${severity == 'MEDIUM' ? 'selected' : ''}>Medium</option>
            </select></div>
        <noscript><button class="btn btn-sm btn-primary">Show</button></noscript>
    </form>
</div>
<div class="panel">
    <c:choose>
        <c:when test="${empty rows}"><div class="empty">No alerts match. An alert is created when a login reaches the alert threshold (set by the administrator).</div></c:when>
        <c:otherwise>
            <div class="table-responsive">
            <table class="table table-hover">
                <thead><tr><th>Alert</th><th>Level</th><th>Score</th><th>User ID</th><th>Application</th><th>First event</th><th class="num">Events</th><th>Main reason</th><th>Status</th><th>Analyst</th></tr></thead>
                <tbody>
                <c:forEach var="a" items="${rows}">
                    <tr>
                        <td><a href="${ctx}/alerts/${a.id}">#${a.id}</a></td>
                        <td><span class="lvl lvl-${a.severity}">${fn:substring(a.severity, 0, 1)}${fn:toLowerCase(fn:substring(a.severity, 1, 20))}</span></td>
                        <td><span data-meter="${a.max_risk_score}">${a.max_risk_score}</span></td>
                        <td><c:out value="${a.user_id}"/></td>
                        <td class="text-nowrap"><c:out value="${a.app_code}"/></td>
                        <td class="text-nowrap"><fmt:formatDate value="${a.first_event_time}" pattern="d MMM yyyy HH:mm"/></td>
                        <td class="num">${a.event_count}</td>
                        <td><c:out value="${a.top_factor}"/></td>
                        <td><span class="state state-${a.status}">${a.status == 'IN_PROGRESS' ? 'In progress' : a.status == 'OPEN' ? 'Open' : 'Closed'}</span>
                            <c:if test="${not empty a.resolution}"><div class="small text-secondary">${fn:toLowerCase(fn:replace(a.resolution, '_', ' '))}</div></c:if></td>
                        <td><c:out value="${empty a.analyst ? '-' : a.analyst}"/></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
            <c:set var="qs" value="status=${status}&amp;severity=${severity}&amp;"/>
            <%@ include file="_pager.jspf" %>
        </c:otherwise>
    </c:choose>
</div>
<%@ include file="_bottom.jspf" %>
