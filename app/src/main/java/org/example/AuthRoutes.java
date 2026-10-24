package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class AuthRoutes {

    private AuthRoutes() {}

    public static void initializeAccounts() throws Exception {
        AccountStore.load();

        String username = System.getenv("ADMIN_USERNAME");
        String password = System.getenv("ADMIN_PASSWORD");

        if (username == null || username.isBlank()
                || password == null || password.length() < 8) {
            throw new IllegalStateException(
                "Set ADMIN_USERNAME and ADMIN_PASSWORD (at least 8 characters)."
            );
        }

        AccountStore.Account existing = AccountStore.find(username);

        if (existing == null) {
            AccountStore.create(username, password, "ADMIN", "");
        } else if (!"ADMIN".equals(existing.role)) {
            throw new IllegalStateException(
                "Configured admin username belongs to a non-admin account."
            );
        }
    }

    public static void register(HttpServer server) {
        server.createContext("/api/login", exchange -> {
            cors(exchange);

            if (options(exchange)) return;

            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }

            try {
                String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
                );

                String username = App.getValue(body, "username").trim();
                String password = App.getValue(body, "password");

                AccountStore.Account account =
                    AccountStore.authenticate(username, password);

                if (account == null) {
                    send(exchange, 401,
                        "{\"error\":\"Invalid username or password\"}");
                    return;
                }

                String token = SessionManager.create(account);

                send(exchange, 200,
                    "{\"token\":\"" + escape(token)
                    + "\",\"username\":\"" + escape(account.username)
                    + "\",\"role\":\"" + escape(account.role) + "\"}");

            } catch (Exception e) {
                send(exchange, 500,
                    "{\"error\":\"Login could not be completed\"}");
            }
        });

        server.createContext("/api/logout", exchange -> {
            cors(exchange);

            if (options(exchange)) return;

            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }

            String token = AuthApi.bearerToken(exchange);

            if (token == null || SessionManager.get(token) == null) {
                send(exchange, 401, "{\"error\":\"Login required\"}");
                return;
            }

            SessionManager.logout(token);
            send(exchange, 200, "{\"message\":\"Logged out\"}");
        });

        server.createContext("/api/admin/teachers", exchange -> {
            cors(exchange);

            if (options(exchange)) return;

            SessionManager.Session session =
                AuthApi.requireSession(exchange);

            if (session == null) return;

            if (!AuthApi.requireRole(exchange, session, "ADMIN")) {
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }

            try {
                String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
                );

                String username = App.getValue(body, "username").trim();
                String password = App.getValue(body, "password");

                AccountStore.create(username, password, "TEACHER", "");

                send(exchange, 201,
                    "{\"message\":\"Teacher account created\"}");

            } catch (IllegalArgumentException e) {
                send(exchange, 400,
                    "{\"error\":\"Invalid details or username already exists\"}");
            } catch (Exception e) {
                send(exchange, 500,
                    "{\"error\":\"Account could not be created\"}");
            }
        });

        server.createContext("/api/teacher/student-account", exchange -> {
            cors(exchange);

            if (options(exchange)) return;

            SessionManager.Session session =
                AuthApi.requireSession(exchange);

            if (session == null) return;

            if (!AuthApi.requireRole(exchange, session, "TEACHER")) {
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }

            try {
                String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
                );

                String username = App.getValue(body, "username").trim();
                String password = App.getValue(body, "password");
                String roll = App.getValue(body, "roll").trim();

                if (roll.isEmpty()) {
                    send(exchange, 400,
                        "{\"error\":\"Student roll is required\"}");
                    return;
                }

                synchronized (App.students) {
                    boolean exists = App.students.stream().anyMatch(
                        s -> roll.equals(s.get("roll"))
                    );

                    if (!exists) {
                        send(exchange, 404,
                            "{\"error\":\"Student roll not found\"}");
                        return;
                    }
                }

                AccountStore.create(username, password, "STUDENT", roll);

                send(exchange, 201,
                    "{\"message\":\"Student account created\"}");

            } catch (IllegalArgumentException e) {
                send(exchange, 400,
                    "{\"error\":\"Invalid details or username already exists\"}");
            } catch (Exception e) {
                send(exchange, 500,
                    "{\"error\":\"Account could not be created\"}");
            }
        });
    }

    private static void cors(HttpExchange exchange) {
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static boolean options(HttpExchange exchange)
            throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
            return false;
        }

        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private static void send(
            HttpExchange exchange, int status, String response)
            throws IOException {

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
            "Content-Type", "application/json; charset=UTF-8");

        exchange.sendResponseHeaders(status, bytes.length);

        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }
}
