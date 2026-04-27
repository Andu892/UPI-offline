#!/bin/bash

# E2E Test Runner Script
# Runs the complete E2E test suite

set -e

echo "=========================================="
echo "UPI Offline Mesh - E2E Test Suite"
echo "=========================================="
echo ""

# Check if application is running
echo "Checking if application is running on port 8080..."
if ! curl -sf http://localhost:8080/swagger-ui.html > /dev/null 2>&1; then
    echo "✗ Error: Application is not running on http://localhost:8080"
    echo ""
    echo "Please start the application first using:"
    echo "  ./run.sh        (for local development)"
    echo "  OR"
    echo "  docker-compose up  (for Docker)"
    exit 1
fi

echo "✓ Application is running"
echo ""

# Run E2E tests
echo "Running E2E test suite..."
echo ""

./mvnw test -Dtest=UpiMeshE2ETest -DfailIfNoTests=false

echo ""
echo "=========================================="
echo "E2E Test Suite Completed!"
echo "=========================================="
