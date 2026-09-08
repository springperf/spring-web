#!/bin/bash
mvn clean deploy -P release -DskipTests  -pl .,spring-web,spring-web-view,spring-web-websocket,spring-web-servlet,spring-web-mvc-support,spring-web-batch,spring-boot-starter-web \
  "$@"