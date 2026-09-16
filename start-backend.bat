@echo off
chcp 65001 >nul
echo Starting Rag Backend...
echo.

REM 设置JVM参数，解决中文乱码问题
set JAVA_OPTS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dclient.encoding.override=UTF-8

REM 使用Maven启动Spring Boot应用
mvnw spring-boot:run -Dspring-boot.run.jvmArguments="%JAVA_OPTS%"

pause
