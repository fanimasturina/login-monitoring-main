<c:set var="pageTitle" value="Model and data" scope="request"/>
<%@ include file="../_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Model and data</h1>
        <p>Machine learning model versions, and how data arrives.</p>
    </div>
</div>
<div class="row g-3 mb-3">
    <div class="col-6 col-lg-3"><div class="figure"><div class="label">Login events stored</div><div class="value"><fmt:formatNumber value="${loginCount}"/></div>
        <div class="sub">first model trains at <fmt:formatNumber value="${minSamples}"/></div></div></div>
    <div class="col-6 col-lg-3"><div class="figure"><div class="label">Waiting to be scored</div><div class="value"><fmt:formatNumber value="${pending}"/></div>
        <div class="sub">normally 0</div></div></div>
    <div class="col-6 col-lg-3"><div class="figure"><div class="label">Data arrives by</div><div class="value">${ingestMode == 'realtime' ? 'Real-time' : 'Upload'}</div>
        <div class="sub">ingest.mode in ilm.properties<c:if test="${ingestMode == 'realtime'}">; last poll
            <c:out value="${empty realtimeLastRun ? 'not yet' : realtimeLastRun}"/></c:if></div></div></div>
    <div class="col-6 col-lg-3"><div class="figure"><div class="label">Email alerts</div><div class="value">${mailOn ? 'On' : 'Off'}</div>
        <div class="sub">mail.enabled in ilm.properties</div></div></div>
</div>
<c:if test="${not empty realtimeError}"><div class="alert alert-warning" role="alert">The last real-time poll failed: <c:out value="${realtimeError}"/>. It retries automatically from the last saved position.</div></c:if>
<div class="row g-3">
    <div class="col-lg-5">
        <div class="panel mb-3">
            <h2>Train a new model</h2>
            <div class="hint">Learns normal login behaviour from the stored history (Isolation Forest, no labels needed), then scores every event again.
                Do this after a new application is added, or at least every 3 months.</div>
            <form method="post" action="${ctx}/admin/model/train" data-confirm="Train a new model version and score all events again? This can take a few minutes.">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                <button class="btn btn-primary" type="submit">Train new model version</button>
            </form>
        </div>
        <div class="panel">
            <h2>Score all events again</h2>
            <div class="hint">Applies the current settings and the active model to the whole history. Existing alerts and investigation results are kept.</div>
            <form method="post" action="${ctx}/admin/model/rescore" data-confirm="Score all stored events again with the current settings?">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
                <button class="btn btn-outline-primary" type="submit">Score all events again</button>
            </form>
        </div>
    </div>
    <div class="col-lg-7">
        <div class="panel">
            <h2>Model versions</h2>
            <div class="hint">Each risk assessment stores the model version that produced it, so results can be traced in an audit.</div>
            <c:choose>
                <c:when test="${empty models}"><div class="empty">No model yet. Until the first model is trained, scores come from the rules only.</div></c:when>
                <c:otherwise>
                    <table class="table table-sm table-hover">
                        <thead><tr><th>Version</th><th>Type</th><th>Trained</th><th>By</th><th class="num">Logins used</th><th class="num">Trees</th><th>State</th></tr></thead>
                        <tbody>
                        <c:forEach var="m" items="${models}">
                            <tr><td>v${m.version}</td><td><c:out value="${m.model_type}"/></td>
                                <td class="text-nowrap"><fmt:formatDate value="${m.trained_at}" pattern="d MMM yyyy HH:mm"/></td><td><c:out value="${m.trained_by}"/></td>
                                <td class="num"><fmt:formatNumber value="${m.sample_count}"/></td><td class="num">${m.tree_count}</td><td>${m.active ? 'Active' : 'Replaced'}</td></tr>
                        </c:forEach>
                        </tbody>
                    </table>
                </c:otherwise>
            </c:choose>
        </div>
    </div>
</div>
<%@ include file="../_bottom.jspf" %>
