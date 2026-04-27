# Testing and Deployment Guide

## E2E Testing

### Prerequisites
- Application running on `http://localhost:8080`
- Maven installed locally

### Running E2E Tests

#### Option 1: Using the test runner script
```bash
# Make the script executable
chmod +x run-e2e-tests.sh

# Run all E2E tests
./run-e2e-tests.sh
```

#### Option 2: Using Maven directly
```bash
# Run all E2E tests using Maven
./mvnw test -Dtest=UpiMeshE2ETest

# Run specific test method
./mvnw test -Dtest=UpiMeshE2ETest#testCompleteE2EFlow

# Run with verbose output
./mvnw test -Dtest=UpiMeshE2ETest -X
```

#### Option 3: Using TestNG configuration
```bash
# Run with TestNG configuration
./mvnw test -Dsuites=src/test/resources/testng.xml
```

### E2E Test Suite Overview

The test suite (`UpiMeshE2ETest`) contains 10 comprehensive test cases:

1. **testGetServerPublicKey** - Retrieves and validates server cryptographic key
2. **testGetMeshState** - Verifies mesh network devices and state
3. **testListAccounts** - Validates demo account list retrieval
4. **testDemoSendTransaction** - Creates and injects payment packet
5. **testMeshGossip** - Tests packet propagation through mesh
6. **testBridgeFlushAndSettlement** - Tests concurrent bridge uploads (idempotency)
7. **testListTransactions** - Verifies settled transactions are queryable
8. **testMeshReset** - Tests mesh state reset functionality
9. **testCompleteE2EFlow** - Full end-to-end transaction workflow
10. **testInvalidDemoRequest** - Error handling for malformed requests

### Test Framework
- **RestAssured** - REST API testing
- **TestNG** - Test framework with dependency management
- **Spring Boot Test** - Application context support

### Test Output
After running tests, you'll see:
```
===============================================
UPI Offline Mesh E2E Test Suite
===============================================
...test execution output...
===============================================
E2E Test Suite Completed!
===============================================
```

---

## Docker Deployment

### Prerequisites
- Docker installed and running
- Docker Compose (optional, for simpler deployment)

### Building Docker Image

#### Option 1: Using the build script
```bash
# Make the script executable
chmod +x docker-build.sh

# Build with default tag 'latest'
./docker-build.sh

# Build with custom tag
./docker-build.sh v1.0.0
```

#### Option 2: Using Docker directly
```bash
# Build the Docker image
docker build -t upi-offline-mesh:latest .

# Build with custom tag
docker build -t upi-offline-mesh:v1.0.0 .
```

### Running with Docker

#### Option 1: Using docker-compose (Recommended)
```bash
# Start the application
docker-compose up

# Start in detached mode
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the application
docker-compose down
```

#### Option 2: Using docker run directly
```bash
# Run the container
docker run -p 8080:8080 upi-offline-mesh:latest

# Run with custom ports
docker run -p 9000:8080 upi-offline-mesh:latest

# Run in detached mode
docker run -d -p 8080:8080 --name upi-mesh upi-offline-mesh:latest

# View logs
docker logs -f upi-mesh

# Stop the container
docker stop upi-mesh
docker rm upi-mesh
```

### Docker Configuration

The Docker setup includes:

**Dockerfile:**
- Multi-stage build for optimized image size
- Java 21 Eclipse Temurin JRE
- Non-root user for security
- Health checks
- Optimized JVM settings for containers

**docker-compose.yml:**
- Single service configuration
- Port mapping (8080:8080)
- Environment variables
- Health checks
- Restart policy
- Custom network

### Container Access

Once running, access the application at:
- **Dashboard:** http://localhost:8080
- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **OpenAPI Spec:** http://localhost:8080/v3/api-docs
- **H2 Console:** http://localhost:8080/h2-console

### Environment Variables

Customize the container behavior using environment variables:

```bash
docker run -e "JAVA_OPTS=-Xmx512m" -p 8080:8080 upi-offline-mesh:latest
```

Available options:
- `SERVER_PORT` - Application port (default: 8080)
- `LOGGING_LEVEL_COMDEMOPMESH` - Log level (default: INFO)
- `JAVA_OPTS` - JVM options

### Docker Image Details

**Image Size:** ~400MB (with Java 21 JRE)
**Base Image:** eclipse-temurin:21-jre-jammy
**Registry:** Docker Hub (ready to push)

### Pushing to Registry

```bash
# Tag the image for your registry
docker tag upi-offline-mesh:latest your-registry/upi-offline-mesh:latest

# Push to registry (Docker Hub, ECR, etc.)
docker push your-registry/upi-offline-mesh:latest
```

---

## Complete Workflow

### Local Development
```bash
# 1. Start application
./run.sh

# 2. Run E2E tests
./run-e2e-tests.sh

# 3. View results in console
```

### Docker Deployment
```bash
# 1. Build Docker image
./docker-build.sh v1.0.0

# 2. Run container
docker-compose up

# 3. Run E2E tests against Docker container
./run-e2e-tests.sh

# 4. Stop container
docker-compose down
```

### CI/CD Pipeline
```bash
# Build
docker build -t upi-offline-mesh:latest .

# Test
docker run --rm upi-offline-mesh:latest ./mvnw test

# Push to registry
docker push registry/upi-offline-mesh:latest
```

---

## Troubleshooting

### E2E Tests Fail to Connect
- Verify application is running: `curl http://localhost:8080/swagger-ui.html`
- Check port 8080 is not in use: `lsof -i :8080`
- Review application logs

### Docker Build Fails
- Ensure Docker daemon is running
- Check disk space: `docker system df`
- Clean up old images: `docker system prune`

### Container Health Check Failures
- Check logs: `docker logs upi-mesh`
- Verify port mapping: `docker port upi-mesh`
- Test endpoint directly: `curl http://localhost:8080/swagger-ui.html`

### Memory Issues
- Increase Docker resource limits
- Adjust JVM options: `JAVA_OPTS=-Xmx512m`

---

## Performance Notes

- E2E tests run with default thread pool (5 concurrent tests)
- Expected test duration: 30-60 seconds
- Docker container startup time: 10-15 seconds
- H2 database is in-memory (no persistence)

---

## Best Practices

1. **Always run E2E tests before deployment**
2. **Tag container images with version numbers**
3. **Use docker-compose for local development**
4. **Review test logs for any warnings**
5. **Keep Docker images secure - scan for vulnerabilities**
