@echo off
rem Wanda AI Assistant - build (first time only) and start the server on Windows.
rem Opens at http://localhost:8080. Keys go in wanda.properties next to this file.
cd /d "%~dp0"
if not exist target\wanda-ai.jar (
  echo Building Wanda AI ^(the first build downloads dependencies and takes a minute^)...
  call mvn -q clean package -DskipTests || (echo Build failed. Check that Java 17+ and Maven are installed. & exit /b 1)
)
java -jar target\wanda-ai.jar
