<%@ page isErrorPage="true" %>
<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Login Monitoring</title>
    <link rel="stylesheet" href="${ctx}/assets/vendor/bootstrap.min.css">
    <link rel="stylesheet" href="${ctx}/assets/css/app.css">
</head>
<body>
<main class="container py-5">
    <div class="panel mx-auto" role="alert">
        <h1 class="h4"><c:out value="${empty title ? 'Page not available' : title}"/></h1>
        <p><c:out value="${empty message ? 'The page does not exist, or the server could not complete the request.' : message}"/></p>
        <a class="btn btn-primary" href="${ctx}/dashboard">Back to dashboard</a>
    </div>
</main>
</body>
</html>
