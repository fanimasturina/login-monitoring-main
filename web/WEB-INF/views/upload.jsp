<c:set var="pageTitle" value="Upload data" scope="request"/>
<%@ include file="_top.jspf" %>
<div class="page-head">
    <div>
        <h1>Upload data</h1>
        <p>Add login events from a file. Uploading the same file twice is safe: records already stored are skipped.</p>
    </div>
</div>
<c:if test="${realtimeOn}"><div class="alert alert-info">Real-time collection is on, so new events arrive automatically. Upload is still available for older files.</div></c:if>
<c:if test="${not empty result}">
    <div class="alert alert-success" role="status">
        <strong><c:out value="${result.fileName}"/></strong> was read:
        ${result.accepted} new events stored, ${result.duplicates} already stored, ${result.rejected} lines rejected, ${result.skipped} rows skipped (not login events).
        ${result.scored} events were scored and ${result.newAlerts} new alerts created.
        <c:if test="${result.trained}">The first machine learning model (version ${result.modelVersion}) was trained from the stored history.</c:if>
        <c:if test="${not empty result.unknownLocations}"><br>Note: lines from <c:out value="${result.unknownLocations}"/> never contain the user's name, so their result is stored as Unknown instead of Success or Failed.</c:if>
        <c:if test="${result.rejected > 0}"><br><a href="${ctx}/upload?errors=${result.batchId}">See the rejected lines</a></c:if>
    </div>
</c:if>
<div class="row g-3 mb-3">
    <div class="col-lg-5">
        <div class="panel h-100">
            <h2>Choose a file</h2>
            <div class="hint">Maximum 25 MB per file</div>
            <form method="post" action="${ctx}/upload?${_csrf.parameterName}=${_csrf.token}" enctype="multipart/form-data">
                <div class="mb-3">
                    <label class="form-label" for="file">File</label>
                    <input class="form-control" type="file" id="file" name="file" accept=".xlsx,.csv,.log,.txt" required>
                </div>
                <div class="mb-3">
                    <label class="form-label" for="appCode">Application code (needed for .log files only)</label>
                    <input class="form-control" id="appCode" name="appCode" list="knownApps" maxlength="50" placeholder="APP-REGIS" autocapitalize="characters">
                    <datalist id="knownApps"><c:forEach var="k" items="${knownApps}"><option value="${fn:escapeXml(k)}"></option></c:forEach></datalist>
                    <div class="form-text">Log files do not say which application wrote them. ATI exports already contain the App Code.</div>
                </div>
                <button class="btn btn-primary" type="submit">Upload and score</button>
            </form>
        </div>
    </div>
    <div class="col-lg-7">
        <div class="panel h-100">
            <h2>Files this page understands</h2>
            <table class="table table-sm mt-2">
                <thead><tr><th>File</th><th>Source</th><th>What is read</th></tr></thead>
                <tbody>
                <tr><td>user_activity_*.xlsx or .csv</td><td>Audit Trail Integrator</td><td>Rows with Action Code login or logout: App Code, User Id, Date Activity, Endpoint</td></tr>
                <tr><td>web-user.log</td><td>Application</td><td>Login lines: time, IP, PC name, User ID, Name</td></tr>
                <tr><td>web-security.log</td><td>Application</td><td>Unauthorized access attempts: time, IP, PC name</td></tr>
                </tbody>
            </table>
        </div>
    </div>
</div>
<c:if test="${not empty errorsOf}">
    <div class="panel mb-3">
        <h2>Rejected lines of upload #${errorsOf}</h2>
        <div class="hint">First 100 lines that could not be processed. Fix the source file and upload it again; good lines are not stored twice.</div>
        <c:if test="${empty errors}"><div class="empty">No rejected lines for this upload.</div></c:if>
        <c:if test="${not empty errors}">
            <table class="table table-sm">
                <thead><tr><th class="num">Line</th><th>Problem</th><th>Content</th></tr></thead>
                <tbody><c:forEach var="er" items="${errors}"><tr><td class="num">${er.line_no}</td><td><c:out value="${er.message}"/></td><td class="mono"><c:out value="${er.raw_text}"/></td></tr></c:forEach></tbody>
            </table>
        </c:if>
    </div>
</c:if>
<div class="panel">
    <h2>Recent uploads and real-time batches</h2>
    <c:choose>
        <c:when test="${empty batches}"><div class="empty">Nothing uploaded yet. Choose a file above to add the first login events.</div></c:when>
        <c:otherwise>
            <div class="table-responsive">
            <table class="table table-sm table-hover mt-2">
                <thead><tr><th>#</th><th>When</th><th>By</th><th>Origin</th><th>File</th><th>Type</th><th class="num">Stored</th><th class="num">Already stored</th><th class="num">Rejected</th><th class="num">Skipped</th></tr></thead>
                <tbody>
                <c:forEach var="b" items="${batches}">
                    <tr>
                        <td>${b.id}</td>
                        <td class="text-nowrap"><fmt:formatDate value="${b.uploaded_at}" pattern="d MMM yyyy HH:mm"/></td>
                        <td><c:out value="${b.uploaded_by}"/></td>
                        <td>${b.origin == 'REALTIME' ? 'Real-time' : 'Upload'}</td>
                        <td><c:out value="${b.file_name}"/></td>
                        <td><c:out value="${b.source_kind}"/><c:if test="${not empty b.app_code}"> (<c:out value="${b.app_code}"/>)</c:if></td>
                        <td class="num">${b.accepted}</td><td class="num">${b.duplicates}</td>
                        <td class="num"><c:choose><c:when test="${b.rejected > 0}"><a href="${ctx}/upload?errors=${b.id}">${b.rejected}</a></c:when><c:otherwise>0</c:otherwise></c:choose></td>
                        <td class="num">${b.skipped}</td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
        </c:otherwise>
    </c:choose>
</div>
<%@ include file="_bottom.jspf" %>
