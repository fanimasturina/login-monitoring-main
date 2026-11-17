<c:set var="pageTitle" value="Users" scope="request"/>
<%@ include file="../_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Users</h1>
        <p>Who may sign in to this application, and with which role. Passwords stay in Active Directory.</p>
    </div>
</div>
<div class="row g-3">
    <div class="col-lg-4">
        <div class="panel">
            <h2>Add or change a user</h2>
            <div class="hint">Type an existing user ID to change the role or switch access off.</div>
            <form method="post" action="${ctx}/admin/users" autocomplete="off">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                <div class="mb-3"><label class="form-label" for="userId">Active Directory user ID</label>
                    <input class="form-control" id="userId" name="userId" maxlength="64" required placeholder="nama.belakang" autocapitalize="none" spellcheck="false"></div>
                <div class="mb-3"><label class="form-label" for="role">Role</label>
                    <select class="form-select" id="role" name="role" required>
                        <option value="ANALYST">Security Analyst: monitor, investigate, upload</option>
                        <option value="MANAGER">IT Security Manager: dashboard and reports, personal data masked</option>
                        <option value="AUDITOR">Auditor: read only, with audit trail</option>
                        <option value="ADMIN">Administrator: settings, users, model</option>
                    </select></div>
                <c:if test="${localEnabled}">
                    <div class="mb-3"><label class="form-label" for="source">Account type</label>
                        <select class="form-select" id="source" name="source">
                            <option value="AD">Active Directory</option>
                            <option value="LOCAL">Local account (testing only)</option>
                        </select></div>
                    <div class="mb-3"><label class="form-label" for="password">Password for a local account (12 characters or more)</label>
                        <input class="form-control" id="password" name="password" type="password" maxlength="256" autocomplete="new-password"></div>
                </c:if>
                <div class="form-check mb-3">
                    <input class="form-check-input" type="checkbox" id="active" name="active" value="1" checked>
                    <label class="form-check-label" for="active">Access is on</label>
                </div>
                <button class="btn btn-primary" type="submit">Save user</button>
            </form>
        </div>
    </div>
    <div class="col-lg-8">
        <div class="panel">
            <h2>Registered users</h2>
            <c:choose>
                <c:when test="${empty rows}"><div class="empty">No users yet.</div></c:when>
                <c:otherwise>
                    <div class="table-responsive">
                    <table class="table table-sm table-hover mt-2">
                        <thead><tr><th>User ID</th><th>Name</th><th>Role</th><th>Type</th><th>Access</th><th>Last sign-in</th><th>Added by</th></tr></thead>
                        <tbody>
                        <c:forEach var="u" items="${rows}">
                            <tr>
                                <td><c:out value="${u.user_id}"/></td>
                                <td><c:out value="${empty u.display_name ? '-' : u.display_name}"/></td>
                                <td>${u.role == 'ANALYST' ? 'Security Analyst' : u.role == 'MANAGER' ? 'IT Security Manager' : u.role == 'AUDITOR' ? 'Auditor' : 'Administrator'}</td>
                                <td>${u.auth_source == 'AD' ? 'Active Directory' : 'Local'}</td>
                                <td>${u.active ? 'On' : 'Off'}</td>
                                <td class="text-nowrap"><c:choose><c:when test="${empty u.last_login_at}">Never</c:when>
                                    <c:otherwise><fmt:formatDate value="${u.last_login_at}" pattern="d MMM yyyy HH:mm"/></c:otherwise></c:choose></td>
                                <td><c:out value="${u.created_by}"/></td>
                            </tr>
                        </c:forEach>
                        </tbody>
                    </table>
                    </div>
                </c:otherwise>
            </c:choose>
        </div>
    </div>
</div>
<%@ include file="../_bottom.jspf" %>
