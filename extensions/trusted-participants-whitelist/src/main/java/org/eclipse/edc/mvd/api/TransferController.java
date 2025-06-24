package org.eclipse.edc.mvd.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.mvd.service.ServiceRegistry;
import org.eclipse.edc.mvd.storage.AssetStore;
import org.eclipse.edc.mvd.transfer.TransferRegistry;
import org.eclipse.edc.mvd.context.ExchangeContext;
import org.eclipse.edc.mvd.service.PullService;
import org.eclipse.edc.mvd.service.PushService;
import java.io.InputStream;

import java.io.IOException;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.io.UncheckedIOException;
import org.eclipse.edc.spi.monitor.Monitor;

@Path("/transfers")
public class TransferController {

    public record PushRequest(String assetId, String targetUrl){};

    private final AssetStore store;
    private final HttpClient http;
    private final PullService pullService;
    private final PushService pushService;
    private final Monitor monitor;

    @Inject
    public TransferController(AssetStore store, HttpClient http, Monitor monitor) {
        this.store = store;
        this.http = http;
        this.monitor = monitor;
        this.pullService = new PullService(http, monitor);
        this.pushService = new PushService(http, new ObjectMapper());
    }

    //push
    @POST @Path("/push")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response push(PushRequest req) throws IOException{
        if(!store.exists(req.assetId())){
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "asset not found")).build();
        }

        HttpRequest r = HttpRequest.newBuilder()
                .uri(URI.create(req.targetUrl() + "/assets/" + req.assetId() + "/binary"))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return store.load(req.assetId());
                    }catch (IOException e){throw new UncheckedIOException(e);}
                }))
                .build();
        String id = UUID.randomUUID().toString();
        CompletableFuture<HttpResponse<Void>> fut = http.sendAsync(
                r, HttpResponse.BodyHandlers.<Void>discarding());
        TransferRegistry.register(id, fut);
        return Response.accepted(Map.of("transferId", id)).build();
    }

    private void ensureAssetRegistered(String id) {
        try {
            var mgmt = "http://localhost:9193/management/v3";
            var head = HttpRequest.newBuilder()
                    .uri(URI.create(mgmt + "/assets/" + id))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            if (http.send(head, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                return;
            }
            String binaryUrl = "http://localhost:9193/api/assets/" + id + "/binary";


            var asset = Map.of(
                    "@context",  Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                    "@id",       id,
                    "@type",     "Asset",
                    "properties", Map.of(
                            "name",        "Raw-" + id,
                            "contenttype", "application/json"
                    ),
                    "dataAddress", Map.of(
                            "type",    "HttpData",
                            "baseUrl", binaryUrl
                    )
            );
            String body = new ObjectMapper().writeValueAsString(asset);

            var post = HttpRequest.newBuilder()
                    .uri(URI.create(mgmt + "/assets"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.send(post, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            monitor.warning("Could not register asset " + id + " on trustee: " + e);
        }
    }

    //status
    @GET @Path("/status/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("id") String id){
        return Response.ok(Map.of("state", TransferRegistry.stateOf(id))).build();
    }


    private String findPrefixedAssetIdInContext(String originalAssetId) {
        List<String> allContextAssetIds = ExchangeContext.allAssets(); // Assuming allAssets() returns List<String>
        for (String ctxAssetId : allContextAssetIds) {
            // Assuming entryId does not contain "::" and assetId is the suffix
            if (ctxAssetId.endsWith("::" + originalAssetId)) {
                return ctxAssetId;
            }
        }
        return null;
    }

    @POST @Path("/pull-transfer")
    public Response pullTransfer(@QueryParam("assetId") String originalAssetId, // This is the raw asset ID, e.g., "3"
                                 @QueryParam("transform") String transform,
                                 @QueryParam("serviceId") String serviceId) {

        String prefixedAssetId = findPrefixedAssetIdInContext(originalAssetId);

        if (prefixedAssetId == null) {
            monitor.warning("No exchange context found for asset " + originalAssetId + ". Available context keys: " + String.join(", ", ExchangeContext.allAssets()));
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No exchange context found for asset " + originalAssetId))
                    .build();
        }

        String providerUrl;
        String consumerUrl;
        try {
            providerUrl = ExchangeContext.provider(prefixedAssetId);
            consumerUrl = ExchangeContext.consumer(prefixedAssetId);
        } catch (IllegalStateException e) {
            // This should ideally not happen if findPrefixedAssetIdInContext found a valid key
            monitor.severe("Error retrieving context for " + prefixedAssetId + " even after finding it: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal error retrieving context for asset " + originalAssetId))
                    .build();
        }

        monitor.info("Pull transfer for originalAssetId=" + originalAssetId + ", prefixedAssetId=" + prefixedAssetId + ", providerUrl=" + providerUrl + ", consumerUrl=" + consumerUrl);

        /* 1 ─ fetch the original bytes from the provider -------------- */
        // pullService.fetch needs the asset ID as the *original provider* knows it, which is originalAssetId
        InputStream originalPayloadStream;
        try {
            originalPayloadStream = pullService.fetch(originalAssetId, providerUrl);
        } catch (Exception e) {
            monitor.severe("Failed to fetch asset " + originalAssetId + " from provider " + providerUrl + ": " + e.getMessage(), e);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "Failed to fetch asset from original provider", "detail", e.getMessage()))
                    .build();
        }


        /* 2 ─ keep a copy on trustee so the UI can list & download ---- */
        // The trustee stores/caches the asset using its original ID (e.g., "3")
        // as this matches the ContractAgreement.assetId shown in the UI.
        byte[] bytes;
        try {
            bytes = originalPayloadStream.readAllBytes(); // readAllBytes closes the stream
            if (!store.exists(originalAssetId)) {
                store.save(originalAssetId, new ByteArrayInputStream(bytes));
                ensureAssetRegistered(originalAssetId); // Registers this asset with trustee's own management API
            }
        } catch (IOException ex) {
            monitor.severe("Could not cache original asset " + originalAssetId + " on trustee: " + ex.getMessage(), ex);
            return Response.serverError()
                    .entity(Map.of("error", "Could not cache original asset on trustee", "detail", ex.getMessage()))
                    .build();
        }

        /* 3 ─ apply optional transform ------------------------------- */
        InputStream toSend = new ByteArrayInputStream(bytes); // Use a new stream from the cached bytes

        if (serviceId != null && !serviceId.isBlank()) {
            var proc = ServiceRegistry.processor(serviceId);
            if (proc.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "unknown serviceId")).build();
            }
            try {                     // may throw if the JSON is not as expected
                toSend = proc.get().apply(toSend);
            } catch (Exception ex){
                return Response.serverError()
                        .entity(Map.of("error","service processing failed",
                                "detail", ex.getMessage()))
                        .build();
            }
        }

        /* PUSH to consumer ----------------------- */
        // pushService.start needs the asset ID as the *consumer* will expect it.
        // This is typically the originalAssetId, even if transformed content.
        CompletableFuture<HttpResponse<Void>> fut = pushService.start(
                originalAssetId, consumerUrl, toSend);

        String transferProcessId = UUID.randomUUID().toString();
        TransferRegistry.register(transferProcessId, fut);
        monitor.info("Transfer process " + transferProcessId + " initiated for asset " + originalAssetId + " to consumer " + consumerUrl);

        return Response.accepted(Map.of(
                "message", "Transfer started",
                "transferId", transferProcessId
        )).build();
    }

    @POST @Path("/merge")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response merge(@QueryParam("entryId") String entryId,
                          @QueryParam("mode")     @DefaultValue("array") String mode,
                          @QueryParam("serviceId") String serviceId) throws JsonProcessingException {

        List<String> assetIds;
        if (entryId == null || entryId.isBlank()) {
            assetIds = ExchangeContext.allAssets();   // helper shown below
            if (assetIds.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "no assets available")).build();
            }
            entryId = "all";                          // cosmetic, for merged-id
        } else {
            assetIds = ExchangeContext.assetsOfEntry(entryId);
            if (assetIds.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "no assets for entry")).build();
            }
        }

        /* 1 ─ pull each JSON from its provider -------------------------------- */
        ObjectMapper om = new ObjectMapper();
        ArrayNode list = new ArrayNode(JsonNodeFactory.instance);

        for (String id : assetIds) {
            try (InputStream original = pullService.fetch(id, ExchangeContext.provider(id))) {
                InputStream in = original;
                if(serviceId != null && !serviceId.isBlank()) {
                    var processor = ServiceRegistry.processor(serviceId);
                    if(processor.isEmpty()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(Map.of("error", "unknown serviceId")).build();
                    }
                    in = processor.get().apply(in);
                }
                list.add(om.readTree(in));
            } catch (Exception ex) {
                return Response.serverError()
                        .entity(Map.of("error", "failed on asset " + id,
                                "detail", ex.getMessage()))
                        .build();
            }
        }

        /* 2 ─ build merged JSON ------------------------------------------------ */
        JsonNode merged = "object".equalsIgnoreCase(mode)
                ? listToObject(list, assetIds)
                : list;                 // default = array

        /* 3 ─ push to consumer & respond -------------------------------------- */
        String mergedId = "merged-" + entryId;
        InputStream payload = new ByteArrayInputStream(om.writeValueAsBytes(merged));
        pushService.start(mergedId, ExchangeContext.consumer(assetIds.get(0)), payload);
        monitor.info("New Asset Id:" + mergedId);

        return Response.ok(merged).build();
    }

    private static ObjectNode listToObject(ArrayNode list, List<String> ids){
        var obj = JsonNodeFactory.instance.objectNode();
        for (int i=0;i<list.size();i++){
            obj.set(ids.get(i).split("::")[1], list.get(i));   // strip entryId::
        }
        return obj;
    }

}