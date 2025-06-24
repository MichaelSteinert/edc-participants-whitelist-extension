/*
 *  Copyright (c) 2024 Fraunhofer Institute for Software and Systems Engineering
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Fraunhofer Institute for Software and Systems Engineering - initial implementation
 *
 */

package org.eclipse.edc.mvd;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import org.eclipse.edc.mvd.model.DataExchangeEntry;
import org.eclipse.edc.mvd.model.DataExchangeState;
import org.eclipse.edc.mvd.model.DataTrusteeRequest;
import org.eclipse.edc.mvd.model.InMemoryMonitor;
import org.eclipse.edc.mvd.model.NegotiationRequest;
import org.eclipse.edc.mvd.model.NegotiationResponse;
import org.eclipse.edc.mvd.model.Participant;
import org.eclipse.edc.mvd.model.TrustedParticipantsResponse;
import org.eclipse.edc.mvd.service.DataExchangeQueueManager;
import org.eclipse.edc.mvd.util.HashUtil;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.mvd.service.PushService;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * TrustedParticipantsWhitelistApiController provides endpoints
 * to maintain the whitelist for selecting data trustees
 */
@Consumes({ MediaType.APPLICATION_JSON })
@Produces({ MediaType.APPLICATION_JSON })
@Path("/trusted-participants")
public class TrustedParticipantsWhitelistApiController {

  private NegotiationResponse negotiationResponse;
  private final Monitor monitor;
  private final TrustedParticipantsWhitelist trustedList;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final DataExchangeQueueManager queueManager;
  private final PushService pushService;

  /**
   * Constructor for TrustedParticipantsWhitelistApiController.
   *
   * @param monitor The monitor used for logging and monitoring.
   */
  @Inject
  public TrustedParticipantsWhitelistApiController(Monitor monitor, PushService pushService, ObjectMapper objectMapper, HttpClient httpClient) {
    this.monitor = monitor;
    this.pushService= pushService;
    this.trustedList = TrustedParticipantsWhitelist.getInstance();
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.queueManager = new DataExchangeQueueManager(objectMapper, httpClient, monitor);
  }

  /**
   * Checks the health of the service.
   *
   * @return A string indicating the health status.
   */
  @GET
  @Path("health")
  public String checkHealth() {
    monitor.info("Received a health request");
    return "{\"response\":\"Web server running on Connector and ready for requests\"}";
  }

  /**
   * Adds a trusted participant to the whitelist.
   *
   * @return A response indicating the outcome.
   */
  @POST
  @Path("add")
  public String addTrustedParticipant(Participant participant) {
    monitor.info("Adding trusted participant: " + participant.getName());
    boolean isAdded = trustedList.addTrustedParticipant(participant);
    if (isAdded) {
      return "{\"response\":\"Participant added successfully\"}";
    } else {
      return "{\"response\":\"Participant already exists\"}";
    }
  }

  /**
   * Retrieves a list of trusted participants.
   *
   * @return A list of trusted participants.
   */
  @GET
  @Path("list")
  public TrustedParticipantsResponse getTrustedParticipants() {
    monitor.info("Retrieving trusted participants");
    List<Participant> participants = trustedList.getTrustedParticipants();
    String hash = "";
    try {
      hash = HashUtil.computeHash(participants);
    } catch (NoSuchAlgorithmException e) {
      monitor.warning("Failed to compute Hash: " + e.getMessage());
    }
    return new TrustedParticipantsResponse(participants, hash);
  }

  /**
   * Removes a trusted participant from the whitelist.
   *
   * @return A response indicating the outcome.
   */
  @DELETE
  @Path("remove")
  public String removeTrustedParticipant(Participant participant) {
    monitor.info("Removing trusted participant: " + participant.getName());
    if (trustedList.removeTrustedParticipant(participant)) {
      return "{\"response\":\"Participant removed successfully\"}";
    } else {
      return "{\"response\":\"Participant not found\"}";
    }
  }

  /**
   * Initiates a negotiation with another system to determine common trusted
   * participants.
   */

  @POST
  @Path("negotiate")
  public String initiateNegotiation(NegotiationRequest negotiationRequest) {
    try {
      monitor.info("Initiating trustee negotiation");
      // Get the list of trusted participants from whitelist
      List<Participant> trustedDataTrustees = trustedList.getTrustedParticipants();
      // Compute the hash of the trusted participants
      String hash = HashUtil.computeHash(trustedDataTrustees);

      Participant dataSource = negotiationRequest.dataSource();
      Participant dataSink   = negotiationRequest.dataSink();
      if (dataSource == null || dataSink == null ||
              dataSource.getUrl() == null || dataSink.getUrl() == null) {
        return "{\"error\":\"dataSource / dataSink missing in request\"}";
      }

      List<String> assets = negotiationRequest.assets();

      negotiationRequest = new NegotiationRequest(
              dataSource,
              dataSink,
              trustedDataTrustees,
              assets,
              hash);

      String receiveNegotiationEndpoint = dataSource.getUrl() + "/receive-negotiation";
      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(receiveNegotiationEndpoint))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(negotiationRequest)))
              .build();

      // Send the request and get the response
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      monitor.info(
              "Received Response from Provider: Status Code = " + response.statusCode() + ", Body = " + response.body());

      // Deserialize negotiation response
      NegotiationResponse negotiationResponse = objectMapper.readValue(response.body(), NegotiationResponse.class);

      this.negotiationResponse = negotiationResponse;

      // Check if a trusted data trustee was selected
      Participant chosenDataTrustee = negotiationResponse.trustedDataTrustee();
      if(chosenDataTrustee == null || chosenDataTrustee.getUrl() == null || chosenDataTrustee.getUrl().isEmpty()){
        monitor.warning("No commonly trusted data trustee found");
        return "{\"message\":\"No commonly trusted data trustee found\"}";
      }
      // Prepare the notification request
      String notificationUrl = chosenDataTrustee.getUrl() + "/notify";
      DataTrusteeRequest dataTrusteeRequest = new DataTrusteeRequest(
              negotiationResponse.dataSource(),
              negotiationResponse.dataSink(),
              negotiationResponse.assets(),
              "consumer");
      String notificationBody = objectMapper.writeValueAsString(dataTrusteeRequest);

      // Send the notification
      HttpRequest notificationRequest = HttpRequest.newBuilder()
              .uri(URI.create(notificationUrl))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(notificationBody))
              .build();

      HttpResponse<String> notificationResponse = httpClient.send(notificationRequest, HttpResponse.BodyHandlers.ofString());
      monitor.info("Notification sent to " + chosenDataTrustee.getName() + "; Response: " + notificationResponse.body());

      if(chosenDataTrustee != null && !assets.isEmpty()) {
        assets.forEach(id -> {
          pushService.start(
                  id,
                  chosenDataTrustee.getUrl()
          );
        });
      }
      return response.body();
    } catch (Exception e) {
      monitor.severe("Failed to initiate negotiation with provider-connector", e);
      return "{\"error\":\"Failed to send negotiation request: " + e.getMessage() + "\"}";
    }
  }

  private void pushAssets(Participant target, List<String> assets){
    assets.forEach(id -> {pushService.start(id, target.getUrl());});
  }

  /**
   * Receives a negotiation request from another participant, matches trusted
   * participants, and chooses one for data transfer.
   *
   * @param negotiationRequest The list of trusted participants from the
   *                           negotiation initiator.
   * @return A response with matched participants and the chosen participant.
   */
  @POST
  @Path("receive-negotiation")
  public String receiveNegotiation(NegotiationRequest negotiationRequest) {
    monitor.info("Received negotiation request");
    try {
      String receivedHash = negotiationRequest.hash();
      List<Participant> participants = negotiationRequest.trustedDataTrustees();
      String computedHash = HashUtil.computeHash(participants);
      if (!computedHash.equals(receivedHash)) {
        monitor.warning("Hash mismatch: possible data tampering detected.");
        return "{\"error\":\"Hash mismatch: possible data tampering detected.\"}";
      }
    } catch (NoSuchAlgorithmException e) {
      monitor.warning("Failed to compute hash: " + e.getMessage());
      return "{\"error\":\"Failed to compute hash: " + e.getMessage() + "\"}";
    }

    List<Participant> matches = trustedList.getTrustedParticipants().stream()
            .filter(p -> negotiationRequest.trustedDataTrustees().stream()
                    .anyMatch(nrp -> p.getName().equals(nrp.getName()) && p.getUrl().equals(nrp.getUrl())))
            .toList();
    // Select the first matched participant
    Participant chosenDataTrustee = matches.isEmpty() ? null : matches.get(0);
    if (chosenDataTrustee != null) {
      try {
        String trusteeNotificationUrl = chosenDataTrustee.getUrl() + "/notify";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        trusteeNotificationUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(new DataTrusteeRequest(
                        negotiationRequest.dataSource(),
                        negotiationRequest.dataSink(),
                        negotiationRequest.assets(),
                        "provider"))))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        monitor.info("Notification sent to " + chosenDataTrustee.getName() + "; Response: " + response.body());
      } catch (Exception e) {
        monitor.warning("Failed to send notification to " + chosenDataTrustee.getName() + ": " + e.getMessage());
      }
      var negotiationResponse = new NegotiationResponse(
              negotiationRequest.dataSource(),
              negotiationRequest.dataSink(),
              chosenDataTrustee,
              negotiationRequest.assets());
      try {
        // Serialize the negotiation response to JSON
        String responseBody = objectMapper.writeValueAsString(negotiationResponse);
        return responseBody;
      } catch (Exception e) {
        monitor.warning("Failed to serialize negotiation response: " + e.getMessage());
        return "{\"error\":\"Failed to serialize negotiation response: " + e.getMessage() + "\"}";
      }
    } else {
      return "{\"trustedDataTrustee\":[], \"message\":\"No commonly trusted data trustee found\"}";
    }
  }

  @POST
  @Path("notify")
  public Response receiveNotification(DataTrusteeRequest request) {
    monitor.info("Received notification: " + request);
    String entryId;

    // Determine the sender type from the request
    String senderType = request.senderType(); // "provider" or "consumer"

    if ("provider".equalsIgnoreCase(senderType)) {
      entryId = queueManager.addProviderNotification(request.dataSource(), request.assets());
    } else if ("consumer".equalsIgnoreCase(senderType)) {
      entryId = queueManager.addConsumerNotification(request.dataSink(), request.assets());
    } else {
      return Response.status(Response.Status.BAD_REQUEST)
              .entity("{\"error\":\"Invalid sender type\"}")
              .build();
    }

    queueManager.processEntries();
    Map<String, String> response = new HashMap<>();
    response.put("message", "Notification received");
    response.put("entryId", entryId);

    return Response.ok(response).build();
  }

  @POST
  @Path("notify-completion")
  public Response receiveNotificationCompletion(Map<String, String> notification) {
    String message = notification.get("message");
    String role = notification.get("role");
    monitor.info("Received completion notification for role: " + role + ". Message: " + message);
    return Response.ok("{\"message\":\"Completion notification received.\"}").build();
  }

  /**
   * Manually updates the state of a data exchange entry.
   *
   * @param entryId  The ID of the data exchange entry.
   * @param newState The new state to set ("IN_PROGRESS" or "COMPLETED").
   * @return A response indicating the outcome.
   */
  @POST
  @Path("update-entry-state")
  public Response updateDataExchangeEntryState(@QueryParam("entryId") String entryId,
                                               @QueryParam("newState") String newState) {
    monitor.info("Received request to update state of entry " + entryId + " to " + newState);
    DataExchangeState state;
    try {
      state = DataExchangeState.valueOf(newState);
      if (state != DataExchangeState.IN_PROGRESS && state != DataExchangeState.COMPLETED) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"Invalid state. Only IN_PROGRESS or COMPLETED allowed.\"}")
                .build();
      }
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST)
              .entity("{\"error\":\"Invalid state value.\"}")
              .build();
    }

    boolean success = queueManager.updateEntryStateManually(entryId, state);
    if (success) {
      return Response.ok("{\"message\":\"State updated successfully.\"}").build();
    } else {
      return Response.status(Response.Status.NOT_FOUND)
              .entity("{\"error\":\"Entry not found or not in READY state.\"}")
              .build();
    }
  }

  @GET
  @Path("data-exchange-entries")
  public Response getDataExchangeEntries() {
    monitor.info("Retrieving current DataExchangeEntries");
    List<DataExchangeEntry> entries = queueManager.getEntries();

    // Create a response object that contains entry information
    List<Map<String, Object>> responseEntries = entries.stream().map(entry -> {
      Map<String, Object> entryMap = new HashMap<>();
      entryMap.put("id", entry.getId());
      entryMap.put("provider", entry.getProvider());
      entryMap.put("consumer", entry.getConsumer());
      entryMap.put("assets", entry.getAssets());
      entryMap.put("state", entry.getState());
      entryMap.put("createdAt", entry.getCreatedAt());
      entryMap.put("lastUpdatedAt", entry.getLastUpdatedAt());
      return entryMap;
    }).collect(Collectors.toList());

    return Response.ok(responseEntries).build();
  }

  @GET
  @Path("logs")
  public Response getLogs() {
    if (monitor instanceof InMemoryMonitor) {
      InMemoryMonitor inMemoryMonitor = (InMemoryMonitor) monitor;
      List<InMemoryMonitor.LogEntry> entries = inMemoryMonitor.getLogEntries();
      return Response.ok(entries).build();
    } else {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
              .entity("{\"message\":\"Log service not available.\"}")
              .build();
    }
  }

  @GET
  @Path("negotiation-response")
  public Response getNegotiationResponse() {
    if (this.negotiationResponse != null) {
      return Response.ok(this.negotiationResponse).build();
    } else {
      return Response.status(Response.Status.NOT_FOUND)
              .entity("{\"message\":\"No negotiation response found.\"}")
              .build();
    }
  }

}