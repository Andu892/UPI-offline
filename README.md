# UPI Offline Mesh

**Offline-first payment infrastructure** — payments composed with zero connectivity, routed peer-to-peer through a Bluetooth-style mesh, and settled exactly once when any node eventually reaches the internet.

> *Built to demonstrate that the hardest problems in distributed systems — tamper-proof transport over untrusted relays, idempotent settlement under concurrent delivery, and replay attack prevention — are solvable with production-grade cryptography, even without a server in the loop.*

---

## The Problem This Solves

India has ~800 million UPI users. Tens of millions are in areas with intermittent connectivity — basements, rural zones, crowded stadiums. When connectivity drops, payments stop. This project explores what "UPI without the internet" actually looks like at the protocol level.

**The challenge isn't UI. It's trust.** If your phone hands a ₹500 payment to a stranger's phone to relay, how do you guarantee:
- The stranger can't read the amount or alter the destination?
- The payment settles exactly once, even if 10 strangers all upload it simultaneously?
- A captured packet can't be replayed a week later?

This project answers all three.

---

## Demo — What You'll See in 4 Clicks

```
Compose payment → Gossip spreads across mesh → Bridge walks outside → Money moves, exactly once
```

1. **Inject** — pick sender, receiver, amount. Server simulates the sender's phone encrypting and injecting a `MeshPacket`.
2. **Gossip** — click twice. Watch the packet propagate across 5 virtual devices via Bluetooth-style flooding.
3. **Bridge uploads** — the one device with internet POSTs to `/api/bridge/ingest`. Account balances update live.
4. **Idempotency** — run the concurrency test: 3 threads deliver the same packet simultaneously. Exactly one settles. Two are dropped. Sender debited once.

---

## How to Run (2 commands)

**Prerequisites:** JDK 17+ only. No database, no Redis, no Docker required.

```bash
# Mac / Linux
./mvnw spring-boot:run

# Windows
mvnw.cmd spring-boot:run
```

First run downloads dependencies (~90 MB, ~2 min). Subsequent starts take ~5 seconds.

Open **http://localhost:8080** — interactive dashboard loads immediately.

```bash
# Run the concurrency test (the interesting one)
./mvnw test -Dtest=IdempotencyConcurrencyTest
```

---

## The Three Hard Problems — and the Solutions

### 1. Untrusted Relay Nodes

A stranger's phone carries your transaction. They must not be able to read or alter it.

**Solution: Hybrid RSA-OAEP + AES-256-GCM encryption** (same scheme as TLS).

- Sender generates a fresh AES-256 key per packet.
- Payload is encrypted with **AES-GCM** — fast, and *authenticated* (any bit-flip in transit = decryption exception, not silent corruption).
- The AES key is wrapped with the server's **RSA-2048-OAEP** public key — only the server can unwrap it.
- Wire format: `[256B RSA-wrapped AES key][12B IV][ciphertext + 16B GCM auth tag]`

Intermediaries see opaque bytes. They cannot read, modify, or forge a packet. See [`HybridCryptoService.java`](src/main/java/com/demo/upimesh/crypto/HybridCryptoService.java).

---

### 2. The Duplicate-Storm Problem

Three bridges walk outside simultaneously and POST the same packet. Naïve processing debits the sender ₹1,500 instead of ₹500.

**Solution: Atomic compare-and-set on `SHA-256(ciphertext)` before any work.**

```java
// IdempotencyService.java
Instant prev = seen.putIfAbsent(packetHash, now);
return prev == null; // exactly one thread gets true
```

`ConcurrentHashMap.putIfAbsent` is atomic — 100 concurrent threads, exactly one "wins." The rest return `DUPLICATE_DROPPED` before decryption even runs. Defense-in-depth: `transactions.packet_hash` also has a `UNIQUE` index at the DB layer.

**Why hash the ciphertext, not the packet ID?**
- Packet IDs can be rewritten by a malicious relay.
- The ciphertext is GCM-authenticated — two legitimate copies of the same packet are byte-for-byte identical.
- Hashing before decryption keeps the hot path cheap.

In production, `ConcurrentHashMap` → `Redis SET NX EX 86400`. Same semantics, distributed.

---

### 3. Replay Attacks

An attacker captures a valid ciphertext and replays it later.

**Solution: Two independent layers.**

- **Freshness check:** `signedAt` timestamp is inside the encrypted payload (tamper-proof). Server rejects packets older than 24 hours.
- **Nonce:** Also inside the payload. Alice sending Bob ₹100 twice produces two *different* nonces → different ciphertexts → different hashes → both settle. A *replay* of one packet is byte-identical → caught by the idempotency cache.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│            SENDER PHONE (offline)           │
│  PaymentInstruction { to, amount, nonce,    │
│                       pinHash, signedAt }   │
│         │                                   │
│         ▼  RSA-OAEP + AES-GCM encrypt       │
│   MeshPacket { packetId, ttl, ciphertext }  │
└──────────────────────┬──────────────────────┘
                       │ Bluetooth gossip (BLE / Wi-Fi Direct)
          ┌────────────┼────────────┐
          ▼            ▼            ▼
      stranger1    stranger2    bridge  ──── gets 4G ──▶ HTTPS POST
                                                              │
┌─────────────────────────────────────────────────────────── ▼ ──┐
│                   SPRING BOOT BACKEND                           │
│                                                                 │
│  /api/bridge/ingest                                             │
│    [1] SHA-256(ciphertext)                                      │
│    [2] IdempotencyService.claim(hash)  →  DUPLICATE_DROPPED     │
│    [3] HybridCryptoService.decrypt()   →  INVALID if tampered   │
│    [4] Freshness check (signedAt < 24h)→  INVALID if stale      │
│    [5] SettlementService.settle()      →  SETTLED               │
│         @Transactional: debit + credit + ledger                 │
│         @Version on Account = optimistic lock                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 3.3 |
| Persistence | H2 in-memory (swap-ready for PostgreSQL) |
| Cryptography | JCE — `RSA/ECB/OAEPWithSHA-256`, `AES/GCM/NoPadding` |
| Concurrency | `ConcurrentHashMap`, `@Version` optimistic locking, `@Transactional` |
| Build | Maven Wrapper (no install needed) |
| Tests | JUnit 5, 3-thread concurrency test |

---

## Project Structure

```
src/main/java/com/demo/upimesh/
├── crypto/
│   ├── ServerKeyHolder.java          RSA-2048 keypair lifecycle
│   └── HybridCryptoService.java      Encrypt / decrypt / hash
├── service/
│   ├── BridgeIngestionService.java   ← The core pipeline
│   ├── IdempotencyService.java       ConcurrentHashMap SETNX
│   ├── SettlementService.java        @Transactional debit/credit
│   ├── MeshSimulatorService.java     Gossip protocol simulation
│   └── DemoService.java              Seed data + sender simulation
├── model/
│   ├── MeshPacket.java               Wire format (outer envelope)
│   ├── PaymentInstruction.java       Decrypted payload
│   ├── Account.java                  JPA entity with @Version
│   └── Transaction.java              Settled ledger with unique hash index
└── controller/
    └── ApiController.java            REST endpoints
src/test/
└── IdempotencyConcurrencyTest.java   3-bridge concurrency + tamper tests
src/main/resources/templates/
└── dashboard.html                    Interactive demo UI
```

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | Interactive demo dashboard |
| `GET` | `/api/accounts` | Live account balances |
| `GET` | `/api/transactions` | Settlement ledger |
| `GET` | `/api/mesh/state` | Virtual device states |
| `POST` | `/api/demo/send` | Simulate sender encrypting + injecting packet |
| `POST` | `/api/mesh/gossip` | Run one gossip round |
| `POST` | `/api/mesh/flush` | Bridge nodes upload to backend |
| `POST` | `/api/bridge/ingest` | **Production endpoint** — real bridges POST here |
| `POST` | `/api/mesh/reset` | Reset mesh + idempotency cache |
| `GET` | `/h2-console` | In-memory DB browser |

### `POST /api/bridge/ingest`

```json
// Request
{
  "packetId": "550e8400-e29b-41d4-a716-446655440000",
  "ttl": 2,
  "createdAt": 1730000000000,
  "ciphertext": "<base64>"
}

// Response
{
  "outcome": "SETTLED",           // | "DUPLICATE_DROPPED" | "INVALID"
  "packetHash": "a3f8c9...",
  "reason": null,                 // populated on INVALID
  "transactionId": 42             // populated on SETTLED
}
```

---

## Tests

```bash
./mvnw test
```

Three tests ship with the project:

| Test | What it proves |
|---|---|
| `encryptDecryptRoundTrip` | Hybrid encryption is symmetric |
| `tamperedCiphertextIsRejected` | One flipped byte → `INVALID`, never silently settled |
| `singlePacketDeliveredByThreeBridgesSettlesExactlyOnce` | 3 concurrent threads, 1 packet → exactly 1 `SETTLED`, 2 `DUPLICATE_DROPPED`, sender debited once |

---

## Deployment

This is a standard Spring Boot JAR — runs anywhere with Java.

```bash
# Docker
docker build -t upi-mesh .
docker run -p 8080:8080 upi-mesh

# Railway (one command)
./deploy-railway.sh

# Any JAR host (Render, Fly.io, DigitalOcean)
./mvnw package
java -jar target/upi-mesh-*.jar
```

> **Note:** Vercel and similar serverless platforms are not compatible — this application requires a persistent JVM process.

---

## 🐳 Docker Deployment

### Quick Start with Docker Compose

```bash
# Start the application
docker-compose up

# or in detached mode
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the application
docker-compose down
```

### Building and Running with Docker

```bash
# Option 1: Using build script (easiest)
chmod +x docker-build.sh
./docker-build.sh

# Option 2: Manual build
docker build -t upi-offline-mesh:latest .

# Run the container
docker run -p 8080:8080 upi-offline-mesh:latest
```

### Access Application in Docker

- **Dashboard:** http://localhost:8080
- **Swagger API Docs:** http://localhost:8080/swagger-ui.html
- **OpenAPI Spec:** http://localhost:8080/v3/api-docs
- **H2 Console:** http://localhost:8080/h2-console

### Docker Features

- ✅ Multi-stage build for optimized size (~400MB)
- ✅ Non-root user for security
- ✅ Health checks
- ✅ Optimized JVM settings for containers
- ✅ Ready for production registry push

### Pushing to Container Registry

```bash
# Docker Hub
docker tag upi-offline-mesh:latest your-username/upi-offline-mesh:latest
docker push your-username/upi-offline-mesh:latest

# Amazon ECR
aws ecr get-login-password | docker login --username AWS --password-stdin YOUR_ECR_URI
docker tag upi-offline-mesh:latest YOUR_ECR_URI/upi-offline-mesh:latest
docker push YOUR_ECR_URI/upi-offline-mesh:latest
```

---

## 🧪 End-to-End Testing

### Quick Start

```bash
# Start the application
./run.sh

# In another terminal, run E2E tests
chmod +x run-e2e-tests.sh
./run-e2e-tests.sh
```

### Test Suite

The project includes a comprehensive E2E test suite with 10 test cases covering:

✅ **Server Key Management** — RSA-2048 public key retrieval  
✅ **Mesh State** — Device and network state queries  
✅ **Account Management** — Demo account listing  
✅ **Transaction Creation** — Packet generation and injection  
✅ **Mesh Gossip** — Packet propagation across devices  
✅ **Bridge Uploads** — Concurrent packet uploads  
✅ **Idempotency** — Duplicate detection and handling  
✅ **Settlement** — Transaction finalization  
✅ **Complete Workflow** — Full end-to-end flow  
✅ **Error Handling** — Invalid request handling  

### Running Tests

```bash
# Run all E2E tests
./mvnw test -Dtest=UpiMeshE2ETest

# Run specific test
./mvnw test -Dtest=UpiMeshE2ETest#testCompleteE2EFlow

# Run with TestNG configuration
./mvnw test -Dsuites=src/test/resources/testng.xml

# Run all tests (unit + E2E)
./mvnw test
```

### Test Framework Stack

- **RestAssured** - REST API testing
- **TestNG** - Advanced test framework with dependency support
- **Spring Boot Test** - Application context
- **JUnit 5** - Unit testing

### Test Results

Expected output:
```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.demo.upimesh.e2e.UpiMeshE2ETest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] -------------------------------------------------------
[INFO] BUILD SUCCESS
```

### CI/CD Integration

```yaml
# GitHub Actions example
- name: Run E2E Tests
  run: |
    docker-compose up -d
    ./mvnw test -Dtest=UpiMeshE2ETest
    docker-compose down
```

### Detailed Testing Guide

See [TESTING_AND_DEPLOYMENT.md](TESTING_AND_DEPLOYMENT.md) for:
- Advanced test configuration
- Troubleshooting guide
- Performance considerations
- Best practices

---

## Honest Limitations

This is a protocol demo. These are inherent constraints of offline payments, not implementation bugs:

- **No offline balance verification.** The receiver has an IOU until the packet settles. If the sender's account is empty on arrival, settlement is rejected. (Real offline UPI — UPI Lite — solves this with a pre-funded hardware-backed wallet.)
- **Double-spend window.** A sender can issue two packets before either settles. Whichever arrives first wins; the other is rejected.
- **Mesh simulation only.** BLE in the background on Android 8+ and iOS is heavily restricted. The real P2P transport layer is a separate, non-trivial engineering problem.

The cryptography and idempotency logic in this repo is production-shaped. The infrastructure wrappers (Redis, HSM, real BLE, NPCI integration) are what a production system would add.

---

## What Would Change for Production

| Demo | Production |
|---|---|
| H2 in-memory DB | PostgreSQL with replicas |
| `ConcurrentHashMap` | Redis `SET NX EX 86400` |
| RSA keypair regenerated on startup | Private key in HSM (AWS KMS / Vault) |
| Software mesh simulation | Real BLE GATT or Wi-Fi Direct |
| No auth on ingest endpoint | Mutual TLS + signed bridge certificates |
| In-memory seed accounts | KYC'd users, real VPAs, bank PIN verification |
| Console logs | Structured SIEM logs, alerts on `INVALID` spikes |
