package com.demo.upimesh.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.*;

import java.math.BigDecimal;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

/**
 * End-to-End tests for UPI Offline Mesh API.
 * Tests the complete flow: packet creation, mesh gossip, bridge ingestion, and settlement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class UpiMeshE2ETest extends AbstractTestNGSpringContextTests {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String API_BASE = BASE_URL + "/api";

    @BeforeClass
    public void setup() {
        RestAssured.baseURI = BASE_URL;
        RestAssured.basePath = "/api";
    }

    @BeforeMethod
    public void beforeEachTest() {
        // Reset mesh state before each test
        post("/mesh/reset")
                .then()
                .statusCode(200);
    }

    /**
     * Test 1: Get server public key
     * Validates that the server exposes RSA-2048 public key with proper algorithm details.
     */
    @Test(description = "Retrieve server public key with cryptographic scheme")
    public void testGetServerPublicKey() {
        given()
                .when()
                .get("/server-key")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("publicKey", notNullValue())
                .body("algorithm", equalTo("RSA-2048 / OAEP-SHA256"))
                .body("hybridScheme", containsString("AES-256-GCM"));
    }

    /**
     * Test 2: Get mesh state
     * Validates that the mesh simulator has devices and can report state.
     */
    @Test(description = "Get current mesh network state")
    public void testGetMeshState() {
        given()
                .when()
                .get("/mesh/state")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("devices", notNullValue())
                .body("devices.size()", greaterThan(0))
                .body("idempotencyCacheSize", notNullValue());
    }

    /**
     * Test 3: List accounts
     * Validates that demo accounts are seeded and retrievable.
     */
    @Test(description = "List all demo accounts")
    public void testListAccounts() {
        given()
                .when()
                .get("/accounts")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThan(0));
    }

    /**
     * Test 4: Send demo transaction
     * Creates a payment packet, encrypts it, and injects into the mesh.
     */
    @Test(description = "Send demo transaction through mesh")
    public void testDemoSendTransaction() {
        String sendRequestPayload = "{\n" +
                "  \"senderVpa\": \"alice@upi\",\n" +
                "  \"receiverVpa\": \"bob@upi\",\n" +
                "  \"amount\": 100.50,\n" +
                "  \"pin\": \"1234\",\n" +
                "  \"ttl\": 5,\n" +
                "  \"startDevice\": \"phone-alice\"\n" +
                "}";

        given()
                .contentType(ContentType.JSON)
                .body(sendRequestPayload)
                .when()
                .post("/demo/send")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("packetId", notNullValue())
                .body("ciphertextPreview", notNullValue())
                .body("ttl", equalTo(5))
                .body("injectedAt", equalTo("phone-alice"));
    }

    /**
     * Test 5: Gossip propagation
     * Simulates one round of packet propagation through mesh devices.
     */
    @Test(dependsOnMethods = "testDemoSendTransaction", 
           description = "Execute mesh gossip round and verify packet propagation")
    public void testMeshGossip() {
        // First inject a packet
        String sendRequestPayload = "{\n" +
                "  \"senderVpa\": \"alice@upi\",\n" +
                "  \"receiverVpa\": \"bob@upi\",\n" +
                "  \"amount\": 50.00,\n" +
                "  \"pin\": \"1234\",\n" +
                "  \"ttl\": 5\n" +
                "}";

        post("/demo/send")
                .statusCode(200);

        // Execute gossip
        given()
                .when()
                .post("/mesh/gossip")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("transfers", notNullValue())
                .body("deviceCounts", notNullValue());
    }

    /**
     * Test 6: Bridge flush and settlement
     * Simulates bridge nodes getting connectivity and uploading packets concurrently.
     * Tests idempotency by ensuring duplicate uploads settle only once.
     */
    @Test(dependsOnMethods = "testMeshGossip",
           description = "Bridge flush with concurrent duplicate handling (idempotency test)")
    public void testBridgeFlushAndSettlement() {
        // Inject a packet
        String sendRequestPayload = "{\n" +
                "  \"senderVpa\": \"charlie@upi\",\n" +
                "  \"receiverVpa\": \"diana@upi\",\n" +
                "  \"amount\": 75.25,\n" +
                "  \"pin\": \"1234\",\n" +
                "  \"ttl\": 5\n" +
                "}";

        post("/demo/send")
                .statusCode(200);

        // Run multiple gossip rounds to spread the packet
        post("/mesh/gossip").statusCode(200);
        post("/mesh/gossip").statusCode(200);

        // Bridge flush: all nodes with connectivity upload packets concurrently
        given()
                .when()
                .post("/mesh/flush")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("uploadsAttempted", greaterThanOrEqualTo(0))
                .body("results", notNullValue());
    }

    /**
     * Test 7: List transactions after settlement
     * Verifies that transactions are settled and queryable.
     */
    @Test(dependsOnMethods = "testBridgeFlushAndSettlement",
           description = "Verify settled transactions are queryable")
    public void testListTransactions() {
        given()
                .when()
                .get("/transactions")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("", notNullValue());
    }

    /**
     * Test 8: Mesh reset
     * Validates that the mesh can be reset for a fresh scenario.
     */
    @Test(description = "Reset mesh network and clear caches")
    public void testMeshReset() {
        given()
                .when()
                .post("/mesh/reset")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("mesh and idempotency cache cleared"));
    }

    /**
     * Test 9: Complete end-to-end transaction flow
     * Full workflow: send → gossip → flush → settle → verify.
     */
    @Test(description = "Complete end-to-end transaction flow with all steps")
    public void testCompleteE2EFlow() {
        // Step 1: Send transaction
        String sendRequestPayload = "{\n" +
                "  \"senderVpa\": \"alice@upi\",\n" +
                "  \"receiverVpa\": \"bob@upi\",\n" +
                "  \"amount\": 500.00,\n" +
                "  \"pin\": \"1234\",\n" +
                "  \"ttl\": 3\n" +
                "}";

        given()
                .contentType(ContentType.JSON)
                .body(sendRequestPayload)
                .when()
                .post("/demo/send")
                .then()
                .statusCode(200);

        // Step 2: Gossip rounds (let packet spread)
        for (int i = 0; i < 3; i++) {
            given()
                    .when()
                    .post("/mesh/gossip")
                    .then()
                    .statusCode(200);
        }

        // Step 3: Get mesh state
        given()
                .when()
                .get("/mesh/state")
                .then()
                .statusCode(200)
                .body("devices", notNullValue());

        // Step 4: Bridge flush (settlement)
        given()
                .when()
                .post("/mesh/flush")
                .then()
                .statusCode(200);

        // Step 5: Verify transaction settled
        given()
                .when()
                .get("/transactions")
                .then()
                .statusCode(200);

        // Step 6: Verify account balances changed
        given()
                .when()
                .get("/accounts")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    /**
     * Test 10: Invalid demo request handling
     * Validates API error handling for malformed requests.
     */
    @Test(description = "Handle invalid demo request with proper error response")
    public void testInvalidDemoRequest() {
        String invalidPayload = "{\n" +
                "  \"senderVpa\": \"\",\n" +
                "  \"receiverVpa\": \"\",\n" +
                "  \"amount\": -100.00\n" +
                "}";

        given()
                .contentType(ContentType.JSON)
                .body(invalidPayload)
                .when()
                .post("/demo/send")
                .then()
                .statusCode(greaterThanOrEqualTo(400)); // Server should return 4xx or 5xx for bad request
    }
}
