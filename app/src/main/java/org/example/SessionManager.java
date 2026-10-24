package org.example;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SessionManager {

    private static final long SESSION_SECONDS = 8 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ConcurrentMap<String, Session> SESSIONS =
        new ConcurrentHashMap<>();

    public static final class Session {
        public final String username;
        public final String role;
        public final String linkedRoll;
        public final long expiresAt;

        private Session(
                String username, String role,
                String linkedRoll, long expiresAt) {
            this.username = username;
            this.role = role;
            this.linkedRoll = linkedRoll;
            this.expiresAt = expiresAt;
        }
    }

    private SessionManager() {}

    public static String create(AccountStore.Account account) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);

        String token = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes);

        long expiry = Instant.now().getEpochSecond()
            + SESSION_SECONDS;

        SESSIONS.put(token, new Session(
            account.username,
            account.role,
            account.linkedRoll,
            expiry
        ));

        return token;
    }

    public static Session get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        Session session = SESSIONS.get(token);

        if (session == null) {
            return null;
        }

        if (Instant.now().getEpochSecond() >= session.expiresAt) {
            SESSIONS.remove(token, session);
            return null;
        }

        return session;
    }

    public static void logout(String token) {
        if (token != null) {
            SESSIONS.remove(token);
        }
    }

    public static void removeExpired() {
        long now = Instant.now().getEpochSecond();

        SESSIONS.entrySet().removeIf(
            entry -> now >= entry.getValue().expiresAt
        );
    }
}
