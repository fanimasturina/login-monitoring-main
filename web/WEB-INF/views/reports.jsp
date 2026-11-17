<c:set var="pageTitle" value="Reports" scope="request"/>
<%@ include file="_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Reports</h1>
        <p>Monthly summary for management review and audit (OJK, BI, Internal Audit).</p>
    </div>
</div>
<div class="row g-3 mb-3">
    <div class="col-6 col-lg-3"><div class="figure"><div class="label">Alerts created</div><div class="value"><fmt:formatNumber value="${alertSummary.total}"/></div><div class="sub">since the start</div></div></div>
    <div class="col-6 col-lg-3"><div class="figure"><div class="label">Closed</div><div class="value"><fmt:formatNumber value="${alertSummary.closed}"/></div>
        <div class="sub">average ${empty alertSummary.avg_close_hours ? '-' : alertSummary.avg_close_hours} hours to close</div></div></div>
    <div class="col-6 col-lg-3"><div class="figure"><div class="label">Confirmed risk</div><div class="value"><fmt:formatNumber value="${alertSummary.confirmed}"/></div><div class="sub">after investigation</div></div></div>
    <div class="col-6 col-lg-3"><div class="figure"><div class="label">False positive</div><div class="value"><fmt:formatNumber value="${alertSummary.false_positive}"/></div>
        <div class="sub">use this to tune the thresholds</div></div></div>
</div>
<div class="panel mb-3">
    <h2>Export alerts for audit</h2>
    <div class="hint">CSV file with every alert whose first event is in the period, including status, result and analyst. The export is recorded in the audit trail.</div>
    <form method="get" action="${ctx}/reports/alerts.csv" class="row g-2 align-items-end">
        <div class="col-auto"><label class="form-label" for="from">From</label><input class="form-control" type="date" id="from" name="from" value="${monthAgo}" required></div>
        <div class="col-auto"><label class="form-label" for="to">To</label><input class="form-control" type="date" id="to" name="to" value="${today}" required></div>
        <div class="col-auto"><button class="btn btn-primary" type="submit">Download CSV</button></div>
    </form>
</div>
<div class="panel">
    <h2>Events per month</h2>
    <div class="hint">By the month of the event, newest first</div>
    <c:choose>
        <c:when test="${empty monthly}"><div class="empty">No events stored yet.</div></c:when>
        <c:otherwise>
            <table class="table table-sm table-hover">
                <thead><tr><th>Month</th><th class="num">Logins</th><th class="num">Failed</th><th class="num">Unauthorized access</th><th class="num">Medium</th><th class="num">High</th><th class="num">Critical</th></tr></thead>
                <tbody>
                <c:forEach var="m" items="${monthly}">
                    <tr><td>${m.month}</td><td class="num"><fmt:formatNumber value="${m.logins}"/></td><td class="num"><fmt:formatNumber value="${m.failed}"/></td>
                        <td class="num"><fmt:formatNumber value="${m.unauthorized}"/></td><td class="num"><fmt:formatNumber value="${m.medium}"/></td>
                        <td class="num"><fmt:formatNumber value="${m.high}"/></td><td class="num"><fmt:formatNumber value="${m.critical}"/></td></tr>
                </c:forEach>
                </tbody>
            </table>
        </c:otherwise>
    </c:choose>
</div>
<%@ include file="_bottom.jspf" %>
