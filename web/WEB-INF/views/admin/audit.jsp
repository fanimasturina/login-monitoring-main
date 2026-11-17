<c:set var="pageTitle" value="Audit trail" scope="request"/>
<%@ include file="../_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Audit trail</h1>
        <p><fmt:formatNumber value="${total}"/> records of who did what in this application. Records cannot be edited.</p>
    </div>
    <form method="get" class="d-flex align-items-end gap-2">
        <div><label class="form-label mb-0 small" for="actor">User ID</label><input class="form-control form-control-sm" id="actor" name="actor" maxlength="100" value="${fn:escapeXml(actor)}"></div>
        <div><label class="form-label mb-0 small" for="action">Action</label><input class="form-control form-control-sm" id="action" name="action" maxlength="40" value="${fn:escapeXml(action)}" placeholder="ALERT_STATUS"></div>
        <button class="btn btn-sm btn-primary" type="submit">Apply filter</button>
    </form>
</div>
<div class="panel">
    <c:choose>
        <c:when test="${empty rows}"><div class="empty">No audit records match this filter.</div></c:when>
        <c:otherwise>
            <div class="table-responsive">
            <table class="table table-sm table-hover">
                <thead><tr><th>Time</th><th>User ID</th><th>Action</th><th>Target</th><th>Detail</th><th>From IP</th></tr></thead>
                <tbody>
                <c:forEach var="r" items="${rows}">
                    <tr><td class="text-nowrap"><fmt:formatDate value="${r.at}" pattern="d MMM yyyy HH:mm:ss"/></td><td><c:out value="${r.actor}"/></td>
                        <td><c:out value="${r.action}"/></td><td><c:out value="${r.target}"/></td><td><c:out value="${r.detail}"/></td><td><c:out value="${r.ip}"/></td></tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
            <c:url var="self" value=""><c:param name="actor" value="${actor}"/><c:param name="action" value="${action}"/></c:url>
            <c:set var="qs" value="${fn:escapeXml(fn:substringAfter(self, '?'))}&amp;"/>
            <%@ include file="../_pager.jspf" %>
        </c:otherwise>
    </c:choose>
</div>
<%@ include file="../_bottom.jspf" %>
