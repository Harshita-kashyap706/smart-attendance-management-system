package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AccountStore {

    private static final Path FILE = Paths.get(
        System.getenv().getOrDefault("ACCOUNTS_FILE", "accounts.db")
    ).toAbsolutePath();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Account> ACCOUNTS = new HashMap<>();

    public static class Account {
        public final String username;
        public final String role;
        public final String passwordHash;
        public final String linkedRoll;

        private Account(
                String username, String role,
                String passwordHash, String linkedRoll) {
            this.username = username;
            this.role = role;
            this.passwordHash = passwordHash;
            this.linkedRoll = linkedRoll;
        }
    }

    private AccountStore() {}

    public static synchronized void load() throws IOException {
        ACCOUNTS.clear();

        if (!Files.exists(FILE)) {
            return;
        }

        for (String line : Files.readAllLines(
                FILE, StandardCharsets.UTF_8)) {
            String[] p = line.split("\\t", -1);

            if (p.length == 4) {
                String username = decode(p[0]);
                String role = p[1];
                String hash = p[2];
                String roll = decode(p[3]);

                if (validRole(role) && !username.isBlank()) {
                    ACCOUNTS.put(
                        username.toLowerCase(),
                        new Account(username, role, hash, roll)
                    );
                }
            }
        }
    }

    public static synchronized void create(
            String username, String password,
            String role, String linkedRoll) throws Exception {

        if (username == null || !username.matches(
                "[A-Za-z0-9_.-]{3,40}")) {
            throw new IllegalArgumentException(
                "Username must be 3-40 letters, digits, _, . or -."
            );
        }

        if (!validRole(role)) {
            throw new IllegalArgumentException("Invalid account role.");
        }

        String key = username.toLowerCase();

        if (ACCOUNTS.containsKey(key)) {
            throw new IllegalArgumentException("Username already exists.");
        }

        String hash = AuthService.hashPassword(password);
        Account account = new Account(
            username, role, hash,
            linkedRoll == null ? "" : linkedRoll
        );

        ACCOUNTS.put(key, account);

        try {
            save();
        } catch (Exception e) {
            ACCOUNTS.remove(key);
            throw e;
        }
    }

    public static synchronized Account authenticate(
            String username, String password) throws Exception {

        if (username == null || password == null) {
            return null;
        }

        Account account = ACCOUNTS.get(username.toLowerCase());

        if (account == null) {
            return null;
        }

        return AuthService.verifyPassword(
            password, account.passwordHash
        ) ? account : null;
    }

    public static synchronized Account find(String username) {
        if (username == null) {
            return null;
        }
        return ACCOUNTS.get(username.toLowerCase());
    }

    public static synchronized List<Account> list() {
        return new ArrayList<>(ACCOUNTS.values());
    }

    private static boolean validRole(String role) {
        return "ADMIN".equals(role)
            || "TEACHER".equals(role)
            || "STUDENT".equals(role);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String decode(String value) {
        return new String(
            Base64.getDecoder().decode(value),
            StandardCharsets.UTF_8
        );
    }

    private static void save() throws IOException {
        List<String> lines = new ArrayList<>();

        for (Account a : ACCOUNTS.values()) {
            lines.add(
                encode(a.username) + "\t"
                + a.role + "\t"
                + a.passwordHash + "\t"
                + encode(a.linkedRoll)
            );
        }

        Path parent = FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");

        Files.write(
            temp, lines, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );

        try {
            Files.move(
                temp, FILE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(
                temp, FILE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
