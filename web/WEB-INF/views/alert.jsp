<c:set var="pageTitle" value="Alert" scope="request"/>
<%@ include file="_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Alert #${a.id}</h1>
        <p><c:out value="${a.user_id}"/> on <c:out value="${a.app_code}"/>. ${a.event_count} event(s) from
            <fmt:formatDate value="${a.first_event_time}" pattern="EEE d MMM yyyy HH:mm:ss"/> to <fmt:formatDate value="${a.last_event_time}" pattern="HH:mm:ss"/></p>
    </div>
    <a class="btn btn-outline-secondary" href="${ctx}/alerts">All alerts</a>
</div>

<div class="row g-3 mb-3">
    <div class="col-lg-5">
        <div class="panel h-100">
            <h2>Risk</h2>
            <div class="d-flex align-items-center gap-3 my-2">
                <span class="meter big" data-meter="${a.max_risk_score}">${a.max_risk_score}</span>
                <span class="lvl lvl-${a.severity}">${fn:substring(a.severity, 0, 1)}${fn:toLowerCase(fn:substring(a.severity, 1, 20))}</span>
                <span class="state state-${a.status}">${a.status == 'IN_PROGRESS' ? 'In progress' : a.status == 'OPEN' ? 'Open' : 'Closed'}</span>
            </div>
            <div class="hint mb-2">Highest score among the events of this alert. The score is a signal to investigate, not proof of misuse.</div>
            <p class="mb-1"><strong>Main reason:</strong> <c:out value="${a.top_factor}"/></p>
            <c:if test="${not empty a.analyst}"><p class="mb-1"><strong>Analyst:</strong> <c:out value="${a.analyst}"/>,
                started <fmt:formatDate value="${a.inv_started}" pattern="d MMM yyyy HH:mm"/><c:if test="${a.inv_status == 'ESCALATED'}">, escalated to IT Security Manager</c:if></p></c:if>
            <c:if test="${a.status == 'CLOSED'}"><p class="mb-0"><strong>Closed:</strong> ${fn:toLowerCase(fn:replace(a.resolution, '_', ' '))} by <c:out value="${a.closed_by}"/>,
                <fmt:formatDate value="${a.closed_at}" pattern="d MMM yyyy HH:mm"/></p></c:if>
        </div>
    </div>
    <div class="col-lg-7">
        <div class="panel h-100">
            <h2>Investigation</h2>
            <c:choose>
                <c:when test="${not isAnalyst}">
                    <div class="hint">Only Security Analysts can change an alert. Your role can read it.</div>
                </c:when>
                <c:when test="${a.status == 'OPEN'}">
                    <div class="hint">Start the investigation to take this alert, or close it now if it is a duplicate or not relevant.</div>
                    <form method="post" action="${ctx}/alerts/${a.id}/start" class="mb-3">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                        <button class="btn btn-primary" type="submit">Start investigation</button>
                    </form>
                    <form method="post" action="${ctx}/alerts/${a.id}/close">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                        <div class="row g-2">
                            <div class="col-md-4"><label class="form-label" for="resolution">Close without investigation</label>
                                <select class="form-select" id="resolution" name="resolution" required>
                                    <option value="DUPLICATE">Duplicate</option>
                                    <option value="NOT_RELEVANT">Not relevant</option>
                                </select></div>
                            <div class="col-md-8"><label class="form-label" for="note">Reason</label>
                                <input class="form-control" id="note" name="note" maxlength="4000" required></div>
                        </div>
                        <button class="btn btn-outline-secondary mt-2" type="submit">Close alert</button>
                    </form>
                </c:when>
                <c:when test="${a.status == 'IN_PROGRESS'}">
                    <div class="hint">Check the events and the user's normal pattern below, then record what you found.</div>
                    <form method="post" action="${ctx}/alerts/${a.id}/close" class="mb-3">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                        <div class="row g-2">
                            <div class="col-md-4"><label class="form-label" for="resolution">Result</label>
                                <select class="form-select" id="resolution" name="resolution" required>
                                    <option value="">Choose...</option>
                                    <option value="FALSE_POSITIVE">False positive (normal activity)</option>
                                    <option value="CONFIRMED_RISK">Confirmed risk</option>
                                </select></div>
                            <div class="col-md-8"><label class="form-label" for="note">Closing note: what you checked and why</label>
                                <textarea class="form-control" id="note" name="note" rows="2" maxlength="4000" required></textarea></div>
                        </div>
                        <button class="btn btn-primary mt-2" type="submit">Close alert with this result</button>
                    </form>
                    <c:if test="${a.inv_status != 'ESCALATED'}">
                        <form method="post" action="${ctx}/alerts/${a.id}/escalate" data-confirm="Escalate this alert to the IT Security Manager?">
                            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                            <button class="btn btn-outline-primary" type="submit">Escalate to IT Security Manager</button>
                        </form>
                    </c:if>
                </c:when>
                <c:otherwise><div class="hint">This alert is closed. The notes below are kept as audit evidence.</div></c:otherwise>
            </c:choose>
        </div>
    </div>
</div>

<div class="panel mb-3">
    <h2>Events in this alert</h2>
    <div class="hint">Each event with its own score and reasons</div>
    <c:forEach var="e" items="${events}">
        <div class="factor">
            <div class="flex-grow-1">
                <div class="d-flex flex-wrap align-items-center gap-3">
                    <strong class="text-nowrap"><fmt:formatDate value="${e.event_time}" pattern="d MMM yyyy HH:mm:ss"/></strong>
                    <span>${e.event_type == 'LOGIN' ? 'Login' : e.event_type == 'LOGOUT' ? 'Logout' : 'Unauthorized access'}</span>
                    <span class="state state-${e.auth_result}">${e.auth_result == 'SUCCESS' ? 'Success' : e.auth_result == 'FAILED' ? 'Failed' : 'Unknown'}</span>
                    <span data-meter="${e.risk_score}">${e.risk_score}</span>
                    <a href="${ctx}/events/${e.id}">Event #${e.id}</a>
                </div>
                <ul class="mb-0 mt-1 small">
                    <c:forEach var="fa" items="${e.factorList}"><c:if test="${fa.points > 0}"><li>+${fa.points} <c:out value="${fa.text}"/></li></c:if></c:forEach>
                </ul>
            </div>
        </div>
    </c:forEach>
</div>

<div class="row g-3 mb-3">
    <div class="col-lg-6"><%@ include file="_profile.jspf" %></div>
    <div class="col-lg-6">
        <div class="panel h-100">
            <h2>Investigation notes</h2>
            <c:if test="${empty notes}"><div class="empty">No notes yet.</div></c:if>
            <c:forEach var="n" items="${notes}">
                <div class="factor">
                    <div>
                        <div class="small text-secondary"><c:out value="${n.author}"/>, <fmt:formatDate value="${n.created_at}" pattern="d MMM yyyy HH:mm"/></div>
                        <div><c:out value="${n.note}"/></div>
                    </div>
                </div>
            </c:forEach>
            <c:if test="${isAnalyst and a.status != 'CLOSED'}">
                <form method="post" action="${ctx}/alerts/${a.id}/note" class="mt-3">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                    <label class="form-label" for="newNote">Add a note</label>
                    <textarea class="form-control" id="newNote" name="note" rows="2" maxlength="4000" required></textarea>
                    <button class="btn btn-outline-primary mt-2" type="submit">Save note</button>
                </form>
            </c:if>
        </div>
    </div>
</div>
<%@ include file="_history.jspf" %>
<%@ include file="_bottom.jspf" %>
