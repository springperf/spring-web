#!/bin/bash
mvn clean deploy -P release -DskipTests \
  -pl spring-web,spring-web-websocket,spring-web-support,spring-web-batch,spring-boot-starter-web \
  "$@"