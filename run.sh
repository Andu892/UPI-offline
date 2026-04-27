#!/bin/bash

# UPI Offline Mesh Demo Runner Script
# This script sets up the environment and runs the Spring Boot application

echo "Setting up environment for UPI Offline Mesh Demo..."

# Set Java 21 as the active Java version
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.10-ms
export PATH=$JAVA_HOME/bin:$PATH

# Verify Java version
echo "Java version:"
java -version
echo ""

# Make Maven wrapper executable
chmod +x mvnw

# Check if port 8080 is in use and kill any existing process
if lsof -Pi :8080 -sTCP:LISTEN -t >/dev/null ; then
    echo "Port 8080 is already in use. Stopping existing process..."
    lsof -ti:8080 | xargs kill -9 2>/dev/null || true
    sleep 2
fi

echo "Starting Spring Boot application..."
echo "Once started, open http://localhost:8080 in your browser"
echo ""

# Run the application
./mvnw spring-boot:run