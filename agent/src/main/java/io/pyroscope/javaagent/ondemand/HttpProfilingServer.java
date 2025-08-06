package io.pyroscope.javaagent.ondemand;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Simple HTTP server that exposes endpoints to start, stop, and check
 * the status of on‑demand profiling sessions.  It uses the provided
 * {@link OnDemandProfilingController} to control profiling.
 */
public class HttpProfilingServer {
    private final OnDemandProfilingController controller;
    private final int port;
    private HttpServer server;
    private final Gson gson = new Gson();

    public HttpProfilingServer(OnDemandProfilingController controller, int port) {
        this.controller = controller;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Endpoint to start profiling.  Accepts JSON payload with an
        // optional "duration" field specifying how long to run the session.
        server.createContext("/profile/start", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, createErrorResponse("Method not allowed"));
                    return;
                }
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    JsonObject request = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                    long duration = request.has("duration") ? request.get("duration").getAsLong() : 30;
                    OnDemandProfilingController.ProfilingResult result = controller.startProfiling(duration);
                    if (result.success) {
                        sendResponse(exchange, 200, createSuccessResponse(result.message));
                    } else {
                        sendResponse(exchange, 400, createErrorResponse(result.message));
                    }
                } catch (Exception e) {
                    sendResponse(exchange, 500, createErrorResponse("Internal error: " + e.getMessage()));
                }
            }
        });

        // Endpoint to stop profiling
        server.createContext("/profile/stop", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, createErrorResponse("Method not allowed"));
                    return;
                }
                OnDemandProfilingController.ProfilingResult result = controller.stopProfiling();
                if (result.success) {
                    sendResponse(exchange, 200, createSuccessResponse(result.message));
                } else {
                    sendResponse(exchange, 400, createErrorResponse(result.message));
                }
            }
        });

        // Endpoint to check status
        server.createContext("/profile/status", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                JsonObject status = new JsonObject();
                status.addProperty("active", controller.isProfilingActive());
                sendResponse(exchange, 200, gson.toJson(status));
            }
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }

    private String createSuccessResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", message);
        return gson.toJson(response);
    }

    private String createErrorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("success", false);
        response.addProperty("error", message);
        return gson.toJson(response);
    }

    /** Stops the HTTP server. */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}