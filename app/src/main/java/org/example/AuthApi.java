package org.example;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthApi {

    private AuthApi() {}

    public static SessionManager.Session requireSession(
            HttpExchange exchange) throws IOException {

        String header = exchange.getRequestHeaders()
            .getFirst("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            sendError(exchange, 401, "Login required.");
            return null;
        }

        String token = header.substring(7).trim();
        SessionManager.Session session = SessionManager.get(token);

        if (session == null) {
            sendError(exchange, 401, "Invalid or expired session.");
            return null;
        }

        return session;
    }

    public static boolean requireRole(
            HttpExchange exchange,
            SessionManager.Session session,
            String role) throws IOException {

        if (session == null) {
            return false;
        }

        if (!role.equals(session.role)) {
            sendError(exchange, 403, "Permission denied.");
            return false;
        }

        return true;
    }

    public static String bearerToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders()
            .getFirst("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }

        return header.substring(7).trim();
    }

    public static void sendError(
            HttpExchange exchange, int status, String message)
            throws IOException {

        String safe = message.replace("\\", "\\\\")
            .replace("\"", "\\\"");

        byte[] bytes = ("{\"error\":\"" + safe + "\"}")
            .getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
            "Content-Type", "application/json; charset=UTF-8");

        exchange.sendResponseHeaders(status, bytes.length);

        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
