package org.eclipse.edc.mvd.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.mvd.model.DataExchangeEntry;
import org.eclipse.edc.mvd.model.DataExchangeState;
import org.eclipse.edc.mvd.model.Participant;
import org.eclipse.edc.mvd.context.ExchangeContext;

import org.eclipse.edc.mvd.model.ServiceDescriptor;
import org.eclipse.edc.spi.monitor.Monitor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class DataExchangeQueueManager {
    private List<DataExchangeEntry> queue = new ArrayList<>();
    private static final Duration TIMEOUT_DURATION = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Monitor monitor;

    public DataExchangeQueueManager(ObjectMapper objectMapper, HttpClient httpClient, Monitor monitor){
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.monitor = monitor;
    }

    public List<DataExchangeEntry> getEntries() {
        return new ArrayList<>(queue);
    }

    public String addProviderNotification(Participant provider, List<String> assets) {
        DataExchangeEntry entry = findOrCreateEntry(provider, null, assets);
        entry.setProvider(provider);
        updateEntryState(entry);
        return entry.getId();
    }

    public String addConsumerNotification(Participant consumer, List<String> assets) {
        DataExchangeEntry entry = findOrCreateEntry(null, consumer, assets);
        entry.setConsumer(consumer);
        updateEntryState(entry);
        return entry.getId();
    }

    private DataExchangeEntry findOrCreateEntry(Participant provider, Participant consumer, List<String> assets) {
        // Search for an existing entry with matching provider or consumer and assets
        for (DataExchangeEntry entry : queue) {

            /* ➊ assets must match (ignore ordering) */
            boolean sameAssets =
                    new HashSet<>(entry.getAssets()).equals(new HashSet<>(assets));

        /* ➋ treat NULL like a wildcard so the second notification
              can attach to the first row that was created               */
            boolean providerMatches =
                    provider == null || entry.getProvider() == null ||
                            entry.getProvider().equals(provider);

            boolean consumerMatches =
                    consumer == null || entry.getConsumer() == null ||
                            entry.getConsumer().equals(consumer);

            if (sameAssets && providerMatches && consumerMatches &&
                    entry.getState() != DataExchangeState.FAILED) {

                return entry;                                     // ← reuse
            }
        }

        // nothing matched – create a brand-new queue row
        DataExchangeEntry newEntry = new DataExchangeEntry(provider, consumer, assets);
        monitor.info("[DataExchangeQueueManager] Creating new DataExchangeEntry ID: " + newEntry.getId());
        queue.add(newEntry);
        return newEntry;
    }

    private void updateEntryState(DataExchangeEntry entry) {
        if (entry.getProvider() != null && entry.getConsumer() != null) {
            entry.setState(DataExchangeState.READY);
            monitor.info("[DataExchangeQueueManager] Entry ID: " + entry.getId() + " is READY. Provider: " + entry.getProvider().getName() + ", Consumer: " + entry.getConsumer().getName());

            String providerBase = entry.getProvider().getUrl()
                    .replace("/api/trusted-participants", "");

            String actualConsumerBase;
            String consumerReportedUrl = entry.getConsumer().getUrl();
            String consumerName = entry.getConsumer().getName();

            monitor.info("[DataExchangeQueueManager] updateEntryState for Entry ID: " + entry.getId() + " - Consumer Reported URL: " + consumerReportedUrl + ", Consumer Name: " + consumerName);

            boolean isConsumerActualButMisaddressedViaTrusteeUrl = "consumer".equals(consumerName) &&
                    consumerReportedUrl != null &&
                    (
                            consumerReportedUrl.startsWith("http://localhost:39191") ||
                                    consumerReportedUrl.startsWith("http://trustee-connector:39191") ||
                                    consumerReportedUrl.startsWith("http://trustee-connector:9191") ||
                                    consumerReportedUrl.startsWith("http://localhost:19191")
                    );

            if (isConsumerActualButMisaddressedViaTrusteeUrl) {
                actualConsumerBase = "http://consumer-connector:9191"; // Correct internal Docker address for consumer's default API
                monitor.info("[DataExchangeQueueManager] OVERRIDE for Entry ID: " + entry.getId() + ". Consumer is '" + consumerName + "' and URL '" + consumerReportedUrl +
                        "' appears to be misaddressed (points to localhost from trustee or trustee itself). Setting actualConsumerBase to: " + actualConsumerBase);
            } else if (consumerReportedUrl != null) {
                actualConsumerBase = consumerReportedUrl.replace("/api/trusted-participants", "");
                monitor.info("[DataExchangeQueueManager] NO OVERRIDE for Entry ID: " + entry.getId() + ". Using consumer base URL as derived: " + actualConsumerBase +
                        " (Consumer Name: " + consumerName + ", URL: " + consumerReportedUrl + ")");
            } else {
                monitor.severe("[DataExchangeQueueManager] CRITICAL for Entry ID: " + entry.getId() + ": Consumer URL is null in DataExchangeEntry. Cannot set ExchangeContext for consumer.");
                entry.setState(DataExchangeState.FAILED);
                return;
            }

            final String finalActualConsumerBase = actualConsumerBase;
            entry.getAssets()
                    .forEach(a -> {
                        String contextKey = entry.getId() + "::" + a;
                        monitor.info("[DataExchangeQueueManager] Storing in ExchangeContext for Entry ID " + entry.getId() + ": Key=" + contextKey +
                                ", ProviderBase=" + providerBase +
                                ", ConsumerBase=" + finalActualConsumerBase);
                        ExchangeContext.put(contextKey, providerBase, finalActualConsumerBase);
                        // Verification step
                        try {
                            String retrievedProvider = ExchangeContext.provider(contextKey);
                            String retrievedConsumer = ExchangeContext.consumer(contextKey);
                            monitor.info("[DataExchangeQueueManager] VERIFIED ExchangeContext for Key=" + contextKey +
                                    ", Retrieved ProviderBase=" + retrievedProvider + ", Retrieved ConsumerBase=" + retrievedConsumer);
                            if (!finalActualConsumerBase.equals(retrievedConsumer)) {
                                monitor.severe("[DataExchangeQueueManager] !!! CONSUMER MISMATCH for Key=" + contextKey + " !!! Expected ConsumerBase " + finalActualConsumerBase +
                                        " but got " + retrievedConsumer + " from ExchangeContext.");
                            }
                            if (!providerBase.equals(retrievedProvider)) {
                                monitor.severe("[DataExchangeQueueManager] !!! PROVIDER MISMATCH for Key=" + contextKey + " !!! Expected ProviderBase " + providerBase +
                                        " but got " + retrievedProvider + " from ExchangeContext.");
                            }
                        } catch (Exception e) {
                            monitor.severe("[DataExchangeQueueManager] !!! ERROR VERIFYING ExchangeContext for Key=" + contextKey + " !!!: " + e.getMessage());
                        }
                    });
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(providerBase + "/api/services"))
                        .GET()
                        .build();

                String json = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();
                List<ServiceDescriptor> remote =
                        objectMapper.readValue(json, new TypeReference<>(){});

                // cache them locally so the UI can show a dropdown
                remote.forEach(ServiceRegistry::add);

                monitor.info("[ServiceSync] fetched " + remote.size()
                        + " services from " + providerBase);

            } catch (Exception ex) {
                monitor.warning("[ServiceSync] could not fetch services: " + ex.getMessage());
            }
        } else {
            monitor.info("[DataExchangeQueueManager] Entry ID: " + entry.getId() + " - First notification received from " +
                    (entry.getProvider() != null ? entry.getProvider().getName() : (entry.getConsumer() != null ? entry.getConsumer().getName() : "Unknown Participant")) +
                    ", waiting for second notification…");
            entry.setState(DataExchangeState.NOT_READY);
        }
    }

    private String guessTrusteePortFromProvider(Participant provider) {
        if (provider != null && provider.getUrl() != null) {
            try {
                URI uri = new URI(provider.getUrl());
                if ("trustee-connector".equals(uri.getHost()) || "localhost".equals(uri.getHost())) {
                    return String.valueOf(uri.getPort());
                }
            } catch (Exception e) {

            }
        }
        return "39191";
    }


    public void processEntries() {
        Iterator<DataExchangeEntry> iterator = queue.iterator();
        while (iterator.hasNext()) {
            DataExchangeEntry entry = iterator.next();
            switch (entry.getState()) {
                case NOT_READY:
                    if (hasTimedOut(entry)) {
                        entry.setState(DataExchangeState.FAILED);
                        monitor.warning("Entry " + entry.getId() + " has FAILED due to timeout (stuck in NOT_READY).");
                    }
                    break;
                case READY:
                    // Stays in READY until manually triggered or by another process
                    break;
                case IN_PROGRESS:
                    monitor.info("Data exchange IN_PROGRESS for entry: " + entry.getId());
                    break;
                case COMPLETED:
                    monitor.info("Data exchange COMPLETED for entry: " + entry.getId());
                    sendCompletionNotification(entry);
                    iterator.remove();
                    break;
                case FAILED:
                    monitor.warning("Entry FAILED: " + entry.getId());
                    iterator.remove();
                    break;
                default:
                    monitor.warning("Entry " + entry.getId() + " in unknown state: " + entry.getState());
                    break;
            }
        }
    }

    private boolean hasTimedOut(DataExchangeEntry entry) {
        Duration duration = Duration.between(entry.getLastUpdatedAt(), LocalDateTime.now());
        return duration.compareTo(TIMEOUT_DURATION) > 0;
    }

    public boolean updateEntryStateManually(String entryId, DataExchangeState newState) {
        for (DataExchangeEntry entry : queue) {
            if (entry.getId().equals(entryId)) {
                if ( (entry.getState() == DataExchangeState.READY || entry.getState() == DataExchangeState.IN_PROGRESS) ||
                        newState == DataExchangeState.FAILED) {
                    entry.setState(newState);
                    monitor.info("State manually updated to " + newState + " for entry: " + entry.getId());
                    if(newState == DataExchangeState.COMPLETED){
                        processEntries();
                    } else if (newState == DataExchangeState.FAILED) {
                        processEntries();
                    }
                    return true;
                } else {
                    monitor.warning("Cannot manually update entry " + entryId + " from state " + entry.getState() + " to " + newState);
                    return false;
                }
            }
        }
        monitor.warning("Entry " + entryId + " not found for manual state update.");
        return false;
    }

    public void sendCompletionNotification(DataExchangeEntry entry){
        Participant provider = entry.getProvider();
        Participant consumer = entry.getConsumer();
        if (provider == null || consumer == null) {
            monitor.warning("Cannot send completion notification for entry " + entry.getId() + " due to missing provider/consumer details.");
            return;
        }
        String notificationMessage = "Data exchange has been completed for assets: " + String.join(", ", entry.getAssets()) + " (Entry ID: " + entry.getId() + ")";

        try {
            if(provider.getUrl() != null){
                String providerNotificationUrl = provider.getUrl().replace("/api/trusted-participants", "") + "/api/trusted-participants/notify-completion";
                Map<String, String> notification = new HashMap<>();
                notification.put("message", notificationMessage);
                notification.put("role", "provider");
                String requestBody = objectMapper.writeValueAsString(notification);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(providerNotificationUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> monitor.info("Completion Notification sent to provider: " + provider.getName() + "; Response: " + response.statusCode() + " " + response.body()))
                        .exceptionally(ex -> {
                            monitor.warning("Failed to send completion notification to provider " + provider.getName() + ": " + ex.getMessage());
                            return null;
                        });
            }

            if(consumer.getUrl() != null){
                String actualConsumerNotificationUrl;
                String reportedConsumerApiEndpoint = entry.getConsumer().getUrl(); // e.g. http://localhost:39191/api/trusted-participants

                if (reportedConsumerApiEndpoint != null &&
                        (reportedConsumerApiEndpoint.startsWith("http://localhost:" + guessTrusteePortFromProvider(entry.getProvider())) || reportedConsumerApiEndpoint.startsWith("http://trustee-connector:")) &&
                        "consumer".equals(entry.getConsumer().getName())) {
                    actualConsumerNotificationUrl = "http://consumer-connector:9191/api/trusted-participants/notify-completion";
                } else if (reportedConsumerApiEndpoint != null) {
                    actualConsumerNotificationUrl = reportedConsumerApiEndpoint.replace("/api/trusted-participants", "") + "/api/trusted-participants/notify-completion";
                } else {
                    monitor.warning("Consumer URL for notification is null for entry " + entry.getId());
                    return;
                }

                Map<String, String> notification = new HashMap<>();
                notification.put("message", notificationMessage);
                notification.put("role", "consumer");
                String requestBody = objectMapper.writeValueAsString(notification);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(actualConsumerNotificationUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> monitor.info("Completion Notification sent to consumer: " + consumer.getName() + "; Response: " + response.statusCode() + " " + response.body()))
                        .exceptionally(ex -> {
                            monitor.warning("Failed to send completion notification to consumer " + consumer.getName() + ": " + ex.getMessage());
                            return null;
                        });
            }
        }catch (Exception e){
            monitor.warning("Failed to prepare or send completion notifications for entry " + entry.getId() + ": " + e.getMessage());
        }
    }

}