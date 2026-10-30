package com.nepse.topturnover.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

/** Opens the SQLite file and creates/upgrades the schema. */
public final class Database {
    public static final int SCHEMA_VERSION = 1;

    private Database() {}

    public static Connection open(Path file) throws SQLException, IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        applySchema(c);
        return c;
    }

    private static void applySchema(Connection c) throws SQLException, IOException {
        String sql;
        try (InputStream in = Database.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IOException("schema.sql missing from the jar");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // strip comment lines, then run statement by statement
        String body = sql.lines().filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n"));
        try (Statement st = c.createStatement()) {
            for (String stmt : body.split(";")) {
                if (!stmt.isBlank()) st.execute(stmt);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO schema_version(version, applied_at) VALUES (?, ?)")) {
            ps.setInt(1, SCHEMA_VERSION);
            ps.setString(2, OffsetDateTime.now().toString());
            ps.executeUpdate();
        }
    }
}
