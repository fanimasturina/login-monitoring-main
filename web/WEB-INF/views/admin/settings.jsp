<c:set var="pageTitle" value="Settings" scope="request"/>
<%@ include file="../_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Settings</h1>
        <p>Alert threshold, working hours and the points of each rule. Every change is recorded in the audit trail.</p>
    </div>
</div>
<c:set var="lastGroup" value=""/>
<c:forEach var="r" items="${rows}">
    <c:if test="${r.group != lastGroup}">
        <c:if test="${not empty lastGroup}"></tbody></table></div></c:if>
        <div class="panel mb-3">
        <h2><c:out value="${r.group}"/></h2>
        <table class="table table-sm align-middle mt-2">
            <thead><tr><th>Setting</th><th>Value</th><th class="num">Default</th></tr></thead>
            <tbody>
        <c:set var="lastGroup" value="${r.group}"/>
    </c:if>
    <tr>
        <td><label for="s-${r.key}"><c:out value="${r.desc}"/></label><div class="mono text-secondary"><c:out value="${r.key}"/></div></td>
        <td>
            <form method="post" action="${ctx}/admin/settings" class="d-flex gap-2">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                <input type="hidden" name="key" value="${fn:escapeXml(r.key)}">
                <input class="form-control form-control-sm" id="s-${r.key}" name="value" value="${fn:escapeXml(r.value)}" maxlength="300" required>
                <button class="btn btn-sm btn-outline-primary" type="submit">Save</button>
            </form>
        </td>
        <td class="num"><c:out value="${r.def}"/></td>
    </tr>
</c:forEach>
<c:if test="${not empty lastGroup}"></tbody></table></div></c:if>
<%@ include file="../_bottom.jspf" %>
