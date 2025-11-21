package com.fairkeepinventory.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
    private static Database instance;

    private Connection connection;

    private Database() {
    }

    public static synchronized Database getInstance() {
        if (instance == null) {
            instance = new Database();
        }
        return instance;
    }

    /**
     * Initialize the SQLite connection.
     * Call this once in your plugin's onEnable().
     */
    public synchronized void init(JavaPlugin plugin) throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return; // already initialized
        }

        // Ensure plugin data folder exists
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        File dbFile = new File(plugin.getDataFolder(), "fairkeepinventory.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        connection = DriverManager.getConnection(url);
        // Optional: set pragmas here if you want (foreign_keys, journal_mode, etc.)
    }

    /**
     * Get the active connection. Throws if not initialized.
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Database connection is not initialized");
        }
        return connection;
    }

    /**
     * Close the connection. Call from onDisable().
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            } finally {
                connection = null;
            }
        }
    }
}
