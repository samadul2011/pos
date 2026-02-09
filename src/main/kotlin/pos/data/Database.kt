package pos.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

object DatabaseFactory {
    private val dbPath: String = File("pos.db").absolutePath
    val connection: Connection by lazy {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
            createStatement().execute("PRAGMA foreign_keys = ON;")
        }
    }

    init {
        createTables()
    }

    private fun createTables() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    sku TEXT NOT NULL UNIQUE,
                    price REAL NOT NULL DEFAULT 0,
                    stock REAL NOT NULL DEFAULT 0,
                    unit TEXT NOT NULL DEFAULT 'pcs',
                    reorder_level REAL NOT NULL DEFAULT 0
                );
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS customers (
                    phone TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    address TEXT,
                    dob TEXT,
                    email TEXT,
                    status TEXT NOT NULL DEFAULT 'Active',
                    credit_limit REAL NOT NULL DEFAULT 0
                );
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS products (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code TEXT NOT NULL UNIQUE,
                    description TEXT,
                    uom TEXT NOT NULL DEFAULT 'pcs',
                    buy_price REAL NOT NULL DEFAULT 0,
                    sell_price REAL NOT NULL DEFAULT 0,
                    default_number REAL NOT NULL DEFAULT 0,
                    stock REAL NOT NULL DEFAULT 0,
                    reorder_level REAL NOT NULL DEFAULT 0
                );
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS sales (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer_phone TEXT,
                    total REAL NOT NULL DEFAULT 0,
                    paid REAL NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    FOREIGN KEY(customer_phone) REFERENCES customers(phone)
                );
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS sale_lines (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sale_id INTEGER NOT NULL,
                    item_id INTEGER NOT NULL,
                    quantity REAL NOT NULL,
                    price REAL NOT NULL,
                    FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
                );
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS payments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sale_id INTEGER NOT NULL,
                    method TEXT NOT NULL,
                    amount REAL NOT NULL,
                    reference TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
                );
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'CASHIER',
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                );
                """.trimIndent()
            )
        }
        ensureColumn("sales", "created_by", "created_by TEXT NOT NULL DEFAULT 'SYSTEM'")
        ensureColumn("payments", "created_by", "created_by TEXT NOT NULL DEFAULT 'SYSTEM'")
    }

    private fun ensureColumn(table: String, columnName: String, columnDefinition: String) {
        if (columnExists(table, columnName)) return
        connection.createStatement().use { stmt ->
            stmt.execute("ALTER TABLE $table ADD COLUMN $columnDefinition;")
        }
    }

    private fun columnExists(table: String, columnName: String): Boolean {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table);").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name").equals(columnName, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}

inline fun <T> Connection.useStatement(sql: String, block: (PreparedStatement) -> T): T {
    return this.prepareStatement(sql).use(block)
}

inline fun <T> Connection.useStatementWithKeys(sql: String, block: (PreparedStatement) -> T): T {
    return this.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use(block)
}

inline fun <T> ResultSet.mapRows(mapper: (ResultSet) -> T): List<T> {
    val results = mutableListOf<T>()
    while (next()) {
        results.add(mapper(this))
    }
    return results
}
