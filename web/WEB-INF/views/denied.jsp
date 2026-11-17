<%@ page isErrorPage="true" %>
<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>No access - Login Monitoring</title>
    <link rel="stylesheet" href="${ctx}/assets/vendor/bootstrap.min.css">
    <link rel="stylesheet" href="${ctx}/assets/css/app.css">
</head>
<body>
<main class="container py-5">
    <div class="panel mx-auto" role="alert">
        <h1 class="h4">Your role cannot open this page</h1>
        <p>If you need this access for your work, ask an administrator to change your role. If you were filling in a form, your session may have ended: go back, reload the page and try again.</p>
        <a class="btn btn-primary" href="${ctx}/dashboard">Back to dashboard</a>
    </div>
</main>
</body>
</html>
