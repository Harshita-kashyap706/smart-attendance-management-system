package org.example;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.regex.*;

public class App {

    static List<Map<String, String>> students = new ArrayList<>();
    static final Path DATA_FILE = Paths.get(
        System.getenv().getOrDefault("DATA_FILE", "students.json")
    ).toAbsolutePath();

    public static void main(String[] args) throws Exception {
        initDatabase();

        int port = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "8081")
        );
        HttpServer server = HttpServer.create(
            new InetSocketAddress("0.0.0.0", port), 0
        );

        server.createContext("/api/status", exchange -> {
            addCors(exchange);
            send(exchange, 200, "{\"message\":\"Backend is running!\"}");
        });

        server.createContext("/api/students", exchange -> {
            addCors(exchange);

            if (exchange.getRequestMethod().equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            SessionManager.Session session = AuthApi.requireSession(exchange);
            if (session == null) {
                return;
            }

            String method = exchange.getRequestMethod();

            if (method.equals("POST") || method.equals("PUT")
                    || method.equals("DELETE")) {
                if (!"ADMIN".equals(session.role)
                        && !"TEACHER".equals(session.role)) {
                    AuthApi.sendError(exchange, 403, "Forbidden");
                    return;
                }
            }

            if (method.equals("GET") && "STUDENT".equals(session.role)) {
                Map<String, String> own = null;

                synchronized (students) {
                    for (Map<String, String> student : students) {
                        if (student.get("roll").equals(session.linkedRoll)) {
                            own = student;
                            break;
                        }
                    }
                }

                send(exchange, 200, own == null ? "[]" : toJson(List.of(own)));
                return;
            }

            if (exchange.getRequestMethod().equals("GET")) {
                synchronized (students) {
                    send(exchange, 200, toJson(students));
                }
                return;
            }

            if (exchange.getRequestMethod().equals("POST")) {
                String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
                );

                Map<String, String> student = new HashMap<>();
                student.put("name", getValue(body, "name"));
                student.put("roll", getValue(body, "roll"));
                student.put("days", getValue(body, "days", "0"));
                student.put("present", getValue(body, "present", "0"));
                student.put("today", getValue(body, "today", ""));
                
                String name = student.get("name").trim();
                String roll = student.get("roll").trim();

                if (name.isEmpty() || roll.isEmpty()) {
                    send(exchange, 400,
                        "{\"error\":\"Name and roll are required\"}");
                    return;
                }

                synchronized (students) {
                    for (Map<String, String> existing : students) {
                        if (existing.get("roll").equals(roll)) {
                            send(exchange, 409,
                                "{\"error\":\"Roll number already exists\"}");
                            return;
                        }
                    }

                    students.add(student);
                    saveStudents();
                }

                send(exchange, 201,
                    "{\"message\":\"Student saved successfully!\"}");
                return;
            }

            if (exchange.getRequestMethod().equals("PUT")) {
                String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
                );

                String roll = getValue(body, "roll");
                boolean found = false;

                synchronized (students) {
                    for (Map<String, String> student : students) {
                        if (student.get("roll").equals(roll)) {
                            String newName = getValue(body, "name",
                                student.get("name")).trim();
                            String newRoll = getValue(body, "newRoll",
                                student.get("roll")).trim();

                            if (newName.isEmpty() || newRoll.isEmpty()) {
                                send(exchange, 400,
                                    "{\"error\":\"Name and roll are required\"}");
                                return;
                            }

                            boolean duplicate = students.stream().anyMatch(other ->
                                other != student &&
                                other.get("roll").equals(newRoll));

                            if (duplicate) {
                                send(exchange, 409,
                                    "{\"error\":\"Roll number already exists\"}");
                                return;
                            }

                            student.put("name", newName);
                            student.put("roll", newRoll);
                            student.put("days", getValue(body, "days",
                                student.get("days")));
                            student.put("present", getValue(body, "present",
                                student.get("present")));
                            student.put("today", getValue(body, "today",
                                student.get("today")));
                            found = true;
                            break;
                        }
                    }

                    if (found) saveStudents();
                }

                if (found) {
                    send(exchange, 200,
                        "{\"message\":\"Attendance updated!\"}");
                } else {
                    send(exchange, 404,
                        "{\"error\":\"Student not found\"}");
                }
                return;
            }

            if (exchange.getRequestMethod().equals("DELETE")) {
                String roll = exchange.getRequestURI().getQuery();
                if (roll != null && roll.startsWith("roll=")) {
                    roll = java.net.URLDecoder.decode(
                        roll.substring(5), StandardCharsets.UTF_8);
                } else {
                    roll = "";
                }

                boolean removed = false;
                synchronized (students) {
                    Iterator<Map<String, String>> iterator = students.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().get("roll").equals(roll)) {
                            iterator.remove();
                            removed = true;
                            break;
                        }
                    }
                    if (removed) saveStudents();
                }

                if (removed) {
                    send(exchange, 200, "{\"message\":\"Student deleted successfully!\"}");
                } else {
                    send(exchange, 404, "{\"error\":\"Student not found\"}");
                }
                return;
            }

            send(exchange, 405, "{\"error\":\"Method not allowed\"}");
        });

        AuthRoutes.initializeAccounts();
        AuthRoutes.register(server);

        server.start();
        System.out.println("Smart Attendance Backend started on port 8081");
    }

    static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    static String getValue(String json, String key) {
        return getValue(json, key, "");
    }

    static String getValue(String json, String key, String fallback) {
        Pattern pattern = Pattern.compile(
            "\"" + Pattern.quote(key) +
            "\"\\s*:\\s*(?:\"((?:\\\\.|[^\"])*)\"|(-?\\d+))"
        );

        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) return fallback;

        String value = matcher.group(1) != null
            ? matcher.group(1) : matcher.group(2);

        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }

    static String toJson(List<Map<String, String>> list) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Map<String, String> s = list.get(i);
            if (i > 0) json.append(",");

            json.append("{")
                .append("\"name\":\"").append(escape(s.get("name"))).append("\",")
                .append("\"roll\":\"").append(escape(s.get("roll"))).append("\",")
                .append("\"days\":").append(number(s.get("days"))).append(",")
                .append("\"present\":").append(number(s.get("present"))).append(",")
                .append("\"today\":\"").append(escape(s.get("today"))).append("\"")
                .append("}");
        }
        return json.append("]").toString();
    }

    static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }


    static Connection databaseConnection() throws SQLException {
        String databaseUrl = System.getenv("DATABASE_URL");
        String databaseUser = System.getenv("DATABASE_USER");
        String databasePassword = System.getenv("DATABASE_PASSWORD");

        if (databaseUrl == null || databaseUrl.isBlank()
                || databaseUser == null || databaseUser.isBlank()
                || databasePassword == null) {
            throw new SQLException(
                "DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD set karo."
            );
        }

        try {
            URI uri = URI.create(databaseUrl);
            String path = uri.getPath();

            if (uri.getHost() == null || path == null || path.equals("/")) {
                throw new SQLException("DATABASE_URL format galat hai.");
            }

            int port = uri.getPort() == -1 ? 5432 : uri.getPort();

            String jdbcUrl = "jdbc:postgresql://"
                + uri.getHost() + ":" + port + path;

            return DriverManager.getConnection(
                jdbcUrl, databaseUser, databasePassword
            );
        } catch (IllegalArgumentException e) {
            throw new SQLException("DATABASE_URL format galat hai.", e);
        }
    }

    static void initDatabase() throws SQLException {
        try (Connection connection = databaseConnection();
             Statement statement = connection.createStatement()) {

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS attendance_students (" +
                "roll TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "days INTEGER NOT NULL DEFAULT 0, " +
                "present INTEGER NOT NULL DEFAULT 0, " +
                "today TEXT NOT NULL DEFAULT '')"
            );

            students.clear();

            try (ResultSet result = statement.executeQuery(
                    "SELECT name, roll, days, present, today " +
                    "FROM attendance_students ORDER BY roll")) {

                while (result.next()) {
                    Map<String, String> student = new HashMap<>();
                    student.put("name", result.getString("name"));
                    student.put("roll", result.getString("roll"));
                    student.put("days", String.valueOf(result.getInt("days")));
                    student.put("present", String.valueOf(result.getInt("present")));
                    student.put("today", result.getString("today"));

                    students.add(student);
                }
            }
        }

        System.out.println(
            "PostgreSQL connected. Loaded " + students.size() + " students."
        );
    }

    static synchronized void saveStudents() {
        try (Connection connection = databaseConnection()) {
            connection.setAutoCommit(false);

            try {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM attendance_students");
                }

                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO attendance_students " +
                        "(roll, name, days, present, today) VALUES (?, ?, ?, ?, ?)")) {

                    for (Map<String, String> student : students) {
                        statement.setString(1, student.get("roll"));
                        statement.setString(2, student.get("name"));
                        statement.setInt(3, number(student.get("days")));
                        statement.setInt(4, number(student.get("present")));
                        statement.setString(5, student.getOrDefault("today", ""));
                        statement.addBatch();
                    }

                    statement.executeBatch();
                }

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Could not save students to PostgreSQL: "
                + e.getMessage());
        }
    }

    static void send(HttpExchange exchange, int status, String response)
        throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
            "Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
