<c:set var="pageTitle" value="Dashboard" scope="request"/>
<%@ include file="_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Dashboard</h1>
        <p>Login activity and risk, <c:out value="${from}"/> to <c:out value="${to}"/> (ends at the newest stored event)</p>
    </div>
    <form method="get" class="d-flex align-items-center gap-2">
        <label for="days" class="text-secondary">Period</label>
        <select id="days" name="days" class="form-select form-select-sm w-auto" data-autosubmit>
            <option value="7" ${days == 7 ? 'selected' : ''}>Last 7 days</option>
            <option value="30" ${days == 30 ? 'selected' : ''}>Last 30 days</option>
            <option value="90" ${days == 90 ? 'selected' : ''}>Last 90 days</option>
            <option value="365" ${days == 365 ? 'selected' : ''}>Last 12 months</option>
        </select>
        <noscript><button class="btn btn-sm btn-primary">Show</button></noscript>
    </form>
</div>

<c:if test="${not hasData}">
    <div class="panel mb-4">
        <h2>No login events yet</h2>
        <p class="mb-2">Upload an Audit Trail Integrator export (.xlsx or .csv) or an application log (web-user.log, web-security.log) to start.</p>
        <c:if test="${canUpload}"><a class="btn btn-primary" href="${ctx}/upload">Upload data</a></c:if>
    </div>
</c:if>

<div class="row g-3 mb-3">
    <div class="col-6 col-xl">
        <div class="figure ${alertSummary.critical_open > 0 ? 'alarm' : ''}">
            <div class="label">Alerts waiting</div>
            <div class="value"><fmt:formatNumber value="${alertSummary.open}"/></div>
            <div class="sub"><fmt:formatNumber value="${alertSummary.critical_open}"/> critical, <fmt:formatNumber value="${alertSummary.in_progress}"/> in progress. <a href="${ctx}/alerts?status=OPEN">Review</a></div>
        </div>
    </div>
    <div class="col-6 col-xl">
        <div class="figure">
            <div class="label">Login events</div>
            <div class="value"><fmt:formatNumber value="${kpi.logins}"/></div>
            <div class="sub"><fmt:formatNumber value="${kpi.users}"/> users, <fmt:formatNumber value="${kpi.apps}"/> applications</div>
        </div>
    </div>
    <div class="col-6 col-xl">
        <div class="figure">
            <div class="label">Successful</div>
            <div class="value"><fmt:formatNumber value="${kpi.success}"/></div>
            <div class="sub"><fmt:formatNumber value="${kpi.unknown}"/> with unknown result</div>
        </div>
    </div>
    <div class="col-6 col-xl">
        <div class="figure">
            <div class="label">Failed</div>
            <div class="value"><fmt:formatNumber value="${kpi.failed}"/></div>
            <div class="sub"><fmt:formatNumber value="${kpi.unauthorized}"/> unauthorized-access events</div>
        </div>
    </div>
    <div class="col-6 col-xl">
        <div class="figure">
            <div class="label">Suspicious (Medium and above)</div>
            <div class="value"><fmt:formatNumber value="${kpi.suspicious}"/></div>
            <div class="sub"><fmt:formatNumber value="${kpi.high_risk}"/> High or Critical</div>
        </div>
    </div>
</div>

<c:set var="tLabels" value=""/><c:set var="tLogins" value=""/><c:set var="tFlagged" value=""/>
<c:forEach var="t" items="${trend}" varStatus="s">
    <c:set var="tLabels" value="${tLabels}${s.first ? '' : ','}${fn:substring(t.day, 8, 10)}/${fn:substring(t.day, 5, 7)}"/>
    <c:set var="tLogins" value="${tLogins}${s.first ? '' : ','}${t.logins}"/>
    <c:set var="tFlagged" value="${tFlagged}${s.first ? '' : ','}${t.flagged}"/>
</c:forEach>
<c:set var="hValues" value=""/>
<c:forEach var="i" begin="0" end="23">
    <c:set var="hv" value="0"/>
    <c:forEach var="h" items="${hours}"><c:if test="${h.h == i}"><c:set var="hv" value="${h.n}"/></c:if></c:forEach>
    <c:set var="hValues" value="${hValues}${i == 0 ? '' : ','}${hv}"/>
</c:forEach>
<c:set var="dLow" value="0"/><c:set var="dMed" value="0"/><c:set var="dHigh" value="0"/><c:set var="dCrit" value="0"/>
<c:forEach var="d" items="${dist}">
    <c:if test="${d.risk_level == 'LOW'}"><c:set var="dLow" value="${d.n}"/></c:if>
    <c:if test="${d.risk_level == 'MEDIUM'}"><c:set var="dMed" value="${d.n}"/></c:if>
    <c:if test="${d.risk_level == 'HIGH'}"><c:set var="dHigh" value="${d.n}"/></c:if>
    <c:if test="${d.risk_level == 'CRITICAL'}"><c:set var="dCrit" value="${d.n}"/></c:if>
</c:forEach>

<div class="row g-3 mb-3">
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Logins per day</h2>
            <div class="hint">All login attempts, successful and failed</div>
            <div class="chart" data-chart="line" data-name="Logins" data-labels="${tLabels}" data-values="${tLogins}"></div>
        </div>
    </div>
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Suspicious events per day</h2>
            <div class="hint">Events with risk level Medium, High or Critical</div>
            <div class="chart" data-chart="bars" data-name="Suspicious events" data-labels="${tLabels}" data-values="${tFlagged}"></div>
        </div>
    </div>
</div>

<div class="row g-3 mb-3">
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Risk levels</h2>
            <div class="hint">Share of scored events: Low 0-25, Medium 26-50, High 51-75, Critical 76-100</div>
            <div data-split data-low="${dLow}" data-medium="${dMed}" data-high="${dHigh}" data-critical="${dCrit}"></div>
        </div>
    </div>
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Logins by hour of day</h2>
            <div class="hint">Hours 00 to 23. Logins far from the usual hours raise the risk score</div>
            <div class="chart" data-chart="bars" data-height="150" data-name="Logins" data-values="${hValues}"
                 data-labels="00,01,02,03,04,05,06,07,08,09,10,11,12,13,14,15,16,17,18,19,20,21,22,23"></div>
        </div>
    </div>
</div>

<div class="row g-3 mb-3">
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Users with the highest risk</h2>
            <div class="hint">Highest single score in the period</div>
            <c:choose>
                <c:when test="${empty topUsers}"><div class="empty">No user reached Medium risk in this period</div></c:when>
                <c:otherwise>
                    <table class="table table-sm">
                        <thead><tr><th>User ID</th><th>Highest score</th><th class="num">High/Critical</th><th class="num">Logins</th></tr></thead>
                        <tbody>
                        <c:forEach var="u" items="${topUsers}">
                            <tr>
                                <td><c:choose>
                                    <c:when test="${masked}"><c:out value="${u.user_id}"/></c:when>
                                    <c:otherwise><c:url var="userLink" value="/events"><c:param name="user" value="${u.user_id}"/></c:url><a href="${fn:escapeXml(userLink)}"><c:out value="${u.user_id}"/></a></c:otherwise>
                                </c:choose></td>
                                <td><span data-meter="${u.max_score}">${u.max_score}</span></td>
                                <td class="num">${u.high_risk}</td>
                                <td class="num">${u.events}</td>
                            </tr>
                        </c:forEach>
                        </tbody>
                    </table>
                </c:otherwise>
            </c:choose>
        </div>
    </div>
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Applications</h2>
            <div class="hint">Ordered by number of High or Critical events</div>
            <c:choose>
                <c:when test="${empty topApps}"><div class="empty">No events in this period</div></c:when>
                <c:otherwise>
                    <table class="table table-sm">
                        <thead><tr><th>Application</th><th class="num">Events</th><th class="num">Suspicious</th><th class="num">High/Critical</th></tr></thead>
                        <tbody>
                        <c:forEach var="a" items="${topApps}">
                            <tr>
                                <td><c:url var="appLink" value="/events"><c:param name="app" value="${a.app_code}"/></c:url><a href="${fn:escapeXml(appLink)}"><c:out value="${a.app_code}"/></a></td>
                                <td class="num"><fmt:formatNumber value="${a.events}"/></td>
                                <td class="num"><fmt:formatNumber value="${a.flagged}"/></td>
                                <td class="num"><fmt:formatNumber value="${a.high_risk}"/></td>
                            </tr>
                        </c:forEach>
                        </tbody>
                    </table>
                </c:otherwise>
            </c:choose>
        </div>
    </div>
</div>

<div class="panel">
    <h2>Alerts to review first</h2>
    <div class="hint">Open alerts first, then by risk score</div>
    <c:choose>
        <c:when test="${empty latestAlerts}"><div class="empty">No alerts. Logins that reach the alert threshold will appear here.</div></c:when>
        <c:otherwise>
            <table class="table table-hover">
                <thead><tr><th>Alert</th><th>Level</th><th>Score</th><th>User ID</th><th>Application</th><th>When</th><th>Main reason</th><th>Status</th></tr></thead>
                <tbody>
                <c:forEach var="a" items="${latestAlerts}">
                    <tr>
                        <td><a href="${ctx}/alerts/${a.id}">#${a.id}</a></td>
                        <td><span class="lvl lvl-${a.severity}">${fn:substring(a.severity, 0, 1)}${fn:toLowerCase(fn:substring(a.severity, 1, 20))}</span></td>
                        <td><span data-meter="${a.max_risk_score}">${a.max_risk_score}</span></td>
                        <td><c:out value="${a.user_id}"/></td>
                        <td class="text-nowrap"><c:out value="${a.app_code}"/></td>
                        <td class="text-nowrap"><fmt:formatDate value="${a.last_event_time}" pattern="d MMM yyyy HH:mm"/></td>
                        <td><c:out value="${a.top_factor}"/></td>
                        <td><span class="state state-${a.status}">${a.status == 'IN_PROGRESS' ? 'In progress' : a.status == 'OPEN' ? 'Open' : 'Closed'}</span></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
        </c:otherwise>
    </c:choose>
</div>
<%@ include file="_bottom.jspf" %>
