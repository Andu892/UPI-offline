package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Public REST surface.
 *
 * The endpoints split into three groups:
 *   /api/server-key      → so simulated senders can fetch the server's public key
 *   /api/mesh/*          → simulator endpoints (inject, gossip, flush)
 *   /api/bridge/ingest   → THE real production endpoint a real bridge node would hit
 *   /api/accounts, /api/transactions → for the dashboard
 */
@RestController
@RequestMapping("/api")
@Tag(name = "UPI Offline Mesh API", description = "REST endpoints for offline UPI mesh simulation, bridge ingestion, and transaction management")
public class ApiController {

    @Autowired private ServerKeyHolder serverKey;
    @Autowired private DemoService demo;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private IdempotencyService idempotency;

    // ------------------------------------------------------------------ key

    @GetMapping("/server-key")
    @Operation(summary = "Get server public key", description = "Retrieves the server's RSA-2048 public key and cryptographic scheme details used for hybrid encryption")
    @ApiResponse(responseCode = "200", description = "Server public key details")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "algorithm", "RSA-2048 / OAEP-SHA256",
                "hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
        );
    }

    // ---------------------------------------------------------------- demo

    /**
     * Demo helper: build a packet on the server (simulating a sender phone)
     * and inject it into the mesh at the given device.
     */
    @PostMapping("/demo/send")
    @Operation(summary = "Send a demo transaction", description = "Creates and injects a payment packet into the mesh simulator at the specified device. The packet is encrypted and circulated across the mesh network.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Packet successfully created and injected"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Encryption or injection error")
    })
    public ResponseEntity<?> demoSend(@RequestBody DemoSendRequest req) throws Exception {
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin,
                req.ttl == null ? 5 : req.ttl);

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);

        return ResponseEntity.ok(Map.of(
                "packetId", packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        ));
    }

    public static class DemoSendRequest {
        @Schema(description = "Sender's Virtual Payment Address (e.g., 'alice@upi')", example = "alice@upi")
        public String senderVpa;
        
        @Schema(description = "Receiver's Virtual Payment Address (e.g., 'bob@upi')", example = "bob@upi")
        public String receiverVpa;
        
        @Schema(description = "Amount to transfer in decimal format", example = "100.50")
        public BigDecimal amount;
        
        @Schema(description = "Sender's UPI PIN for authorization", example = "1234")
        public String pin;
        
        @Schema(description = "Time-to-live: maximum hops through mesh network. Default: 5", example = "5")
        public Integer ttl;
        
        @Schema(description = "Virtual device to start packet injection. Default: 'phone-alice'", example = "phone-alice")
        public String startDevice;
    }

    // -------------------------------------------------------------- mesh sim

    @GetMapping("/mesh/state")
    @Operation(summary = "Get mesh network state", description = "Returns the current state of all virtual devices in the mesh, including their internet connectivity status, held packets, and idempotency cache size")
    @ApiResponse(responseCode = "200", description = "Current mesh state with device information")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            deviceData.add(Map.of(
                    "deviceId", d.getDeviceId(),
                    "hasInternet", d.hasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds", d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8))
                            .toList()
            ));
        }
        return Map.of(
                "devices", deviceData,
                "idempotencyCacheSize", idempotency.size()
        );
    }

    @PostMapping("/mesh/gossip")
    @Operation(summary = "Execute one gossip round", description = "Simulates one round of packet gossip between nearby mesh devices. Packets propagate to adjacent devices with decreasing TTL.")
    @ApiResponse(responseCode = "200", description = "Gossip result with packet transfers and device states")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return Map.of(
                "transfers", r.transfers(),
                "deviceCounts", r.deviceCounts()
        );
    }

    /**
     * "All bridge nodes simultaneously walk outside and get 4G."
     * They all upload everything they hold to /api/bridge/ingest.
     *
     * THIS is the moment the duplicate-storm idempotency case is tested:
     * if multiple bridge nodes hold the same packet, the server gets multiple
     * concurrent POSTs of the same ciphertext, and only one should settle.
     */
    @PostMapping("/mesh/flush")
    @Operation(summary = "Bridge nodes upload packets", description = "Simulates all bridge nodes getting connectivity and uploading held packets concurrently. This tests duplicate handling and idempotency.")
    @ApiResponse(responseCode = "200", description = "Upload results for all bridges with settlement outcomes")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();

        List<Map<String, Object>> results = new ArrayList<>();
        // Upload them in parallel to actually exercise concurrent idempotency.
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl());
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", up.packet().getPacketId().substring(0, 8),
                        "outcome", r.outcome(),
                        "reason", r.reason() == null ? "" : r.reason(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
            }
        });

        return Map.of(
                "uploadsAttempted", uploads.size(),
                "results", results
        );
    }

    @PostMapping("/mesh/reset")
    @Operation(summary = "Reset mesh network", description = "Clears all held packets from mesh devices and resets the idempotency cache. Used to start a fresh simulation scenario.")
    @ApiResponse(responseCode = "200", description = "Confirmation that mesh and cache have been cleared")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh and idempotency cache cleared");
    }

    // -------------------------------------------------------------- bridge

    /**
     * THE PRODUCTION ENDPOINT.
     * In a real deployment, the Android app's bridge logic POSTs here whenever
     * the device has internet and is holding mesh packets.
     */
    @PostMapping("/bridge/ingest")
    @Operation(summary = "Bridge node ingests packet", description = "THE PRODUCTION ENDPOINT. Bridge nodes POST mesh packets here when they gain internet connectivity. Handles packet validation, decryption, settlement, and idempotency.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Packet ingested with settlement outcome"),
        @ApiResponse(responseCode = "400", description = "Invalid packet format or decryption failure"),
        @ApiResponse(responseCode = "409", description = "Duplicate packet detected (idempotency handling)")
    })
    public ResponseEntity<?> ingest(
            @RequestBody MeshPacket packet,
            @Parameter(description = "Identifier of the bridge node uploading the packet") @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @Parameter(description = "Number of hops the packet took through the mesh") @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    @Operation(summary = "List all accounts", description = "Retrieves all demo accounts in the system with their balances and details")
    @ApiResponse(responseCode = "200", description = "List of all accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    @GetMapping("/transactions")
    @Operation(summary = "List recent transactions", description = "Retrieves the 20 most recent settled transactions in descending order by ID")
    @ApiResponse(responseCode = "200", description = "List of recent transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }
}
