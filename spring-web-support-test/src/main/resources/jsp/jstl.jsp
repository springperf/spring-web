<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<html>
<head><title>jstl test</title></head>
<body>
<p>static-ok</p>
<ul>
<c:forEach items="${items}" var="item">
  <li>${item}</li>
</c:forEach>
</ul>
<c:if test="${flag}">flag-on</c:if>
<c:if test="${!flag}">flag-off</c:if>
</body>
</html>
