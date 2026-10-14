#!/bin/bash
# Wanda AI Assistant - build (first time only) and start the server.
# Opens at http://localhost:8080 (change the port with PORT=9090 ./run.sh).
# Keys go in wanda.properties next to this script.
# Author: Saksham Johri
cd "$(dirname "$0")"

command -v java >/dev/null 2>&1 || { echo "Java 17 or newer is required. Install it, then try again (java -version)."; exit 1; }

if [ ! -f target/wanda-ai.jar ] || [ -n "$(find src pom.xml -newer target/wanda-ai.jar 2>/dev/null | head -1)" ]; then
  command -v mvn >/dev/null 2>&1 || { echo "Apache Maven is required to build Wanda (mvn -v)."; exit 1; }
  echo "Building Wanda AI (the first build downloads dependencies and takes a minute)..."
  mvn -q clean package -DskipTests || { echo "Build failed. Check that Java 17+ and Maven are installed (java -version, mvn -v)."; exit 1; }
fi
exec java -jar target/wanda-ai.jar
