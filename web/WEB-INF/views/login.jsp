<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Sign in - Login Monitoring</title>
    <link rel="stylesheet" href="${ctx}/assets/vendor/bootstrap.min.css">
    <link rel="stylesheet" href="${ctx}/assets/css/app.css">
</head>
<body>
<div class="signin">
    <section class="story">
        <h1>Every login, scored and explained.</h1>
        <ol>
            <li>Login events from the Audit Trail Integrator and application logs are put into one standard format.</li>
            <li>Each login is compared with that user's own history and scored from 0 to 100.</li>
            <li>High and Critical logins become alerts, with the reasons listed for the analyst.</li>
        </ol>
    </section>
    <section class="form">
        <form method="post" action="${ctx}/login" autocomplete="off">
            <h2 class="h4 mb-1">Sign in</h2>
            <p class="text-secondary mb-4">Use your Windows (Active Directory) user ID and password.</p>
            <c:if test="${param.error != null}"><div class="alert alert-danger" role="alert">User ID or password is not correct, or you are not registered for this application.</div></c:if>
            <c:if test="${param.locked != null}"><div class="alert alert-danger" role="alert">Too many failed attempts. Try again in 15 minutes.</div></c:if>
            <c:if test="${param.unavailable != null}"><div class="alert alert-warning" role="alert">The sign-in service cannot be reached. Contact the administrator.</div></c:if>
            <c:if test="${param.logout != null}"><div class="alert alert-success" role="status">You are signed out.</div></c:if>
            <div class="mb-3">
                <label class="form-label" for="username">User ID</label>
                <input class="form-control" id="username" name="username" maxlength="64" required autofocus autocapitalize="none" spellcheck="false">
            </div>
            <div class="mb-4">
                <label class="form-label" for="password">Password</label>
                <input class="form-control" id="password" name="password" type="password" maxlength="256" required>
            </div>
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
            <button class="btn btn-primary w-100" type="submit">Sign in</button>
            <p class="text-secondary small mt-4 mb-0">Access is recorded in the audit trail. For authorized staff only.</p>
        </form>
    </section>
</div>
</body>
</html>
