package org.example;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class App {

    static List<Map<String, String>> students = new ArrayList<>();
    static final Path DATA_FILE = Paths.get(
        System.getenv().getOrDefault("DATA_FILE", "students.json")
    ).toAbsolutePath();

    public static void main(String[] args) throws IOException {
        loadStudents();

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

        server.start();
        System.out.println("Smart Attendance Backend started on port 8081");
    }

    static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Headers", "Content-Type");
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

    static void saveStudents() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            Files.writeString(DATA_FILE, toJson(students),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not save students: " + e.getMessage());
        }
    }

    static void loadStudents() {
        if (!Files.exists(DATA_FILE)) return;

        try {
            String json = Files.readString(DATA_FILE, StandardCharsets.UTF_8);
            Pattern objectPattern = Pattern.compile("\\{([^{}]*)\\}");
            Matcher matcher = objectPattern.matcher(json);

            while (matcher.find()) {
                String object = matcher.group(1);
                Map<String, String> student = new HashMap<>();
                student.put("name", getValue(object, "name"));
                student.put("roll", getValue(object, "roll"));
                student.put("days", getValue(object, "days", "0"));
                student.put("present", getValue(object, "present", "0"));
                student.put("today", getValue(object, "today", ""));

                if (!student.get("roll").isEmpty()) {
                    students.add(student);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not load students: " + e.getMessage());
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
