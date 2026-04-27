#!/bin/bash

# Docker build and run script for UPI Offline Mesh
# Usage: ./docker-build.sh [tag]

set -e

TAG=${1:-"latest"}
IMAGE_NAME="upi-offline-mesh"

echo "Building Docker image: ${IMAGE_NAME}:${TAG}"

# Build the Docker image
docker build -t "${IMAGE_NAME}:${TAG}" .

echo "✓ Docker image built successfully!"
echo ""
echo "To run the container, use one of the following:"
echo ""
echo "1. Using 'docker run' directly:"
echo "   docker run -p 8080:8080 ${IMAGE_NAME}:${TAG}"
echo ""
echo "2. Using 'docker-compose':"
echo "   docker-compose up"
echo ""
echo "Application will be available at: http://localhost:8080"
echo "Swagger UI: http://localhost:8080/swagger-ui.html"
echo "H2 Console: http://localhost:8080/h2-console"
