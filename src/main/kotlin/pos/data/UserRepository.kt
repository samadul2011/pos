package pos.data

import java.security.MessageDigest
import java.sql.Connection

class UserRepository(private val conn: Connection = DatabaseFactory.connection) {
    fun ensureAdminAccount() {
        if (findByUsername("admin") == null) {
            saveUser("admin", "Administrator", UserRole.ADMIN, "admin123")
        }
    }

    fun list(): List<User> = conn.useStatement(
        """
        SELECT id, username, password_hash, display_name, role, created_at
        FROM users
        ORDER BY username
        """.trimIndent()
    ) { stmt ->
        stmt.executeQuery().mapRows { rs ->
            User(
                id = rs.getLong("id"),
                username = rs.getString("username"),
                passwordHash = rs.getString("password_hash"),
                displayName = rs.getString("display_name"),
                role = UserRole.valueOf(rs.getString("role")),
                createdAt = rs.getString("created_at")
            )
        }
    }

    fun findByUsername(username: String): User? {
        val normalized = username.trim().lowercase()
        if (normalized.isBlank()) return null
        return conn.useStatement(
            """
            SELECT id, username, password_hash, display_name, role, created_at
            FROM users
            WHERE username = ?
            LIMIT 1
            """.trimIndent()
        ) { stmt ->
            stmt.setString(1, normalized)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                User(
                    id = rs.getLong("id"),
                    username = rs.getString("username"),
                    passwordHash = rs.getString("password_hash"),
                    displayName = rs.getString("display_name"),
                    role = UserRole.valueOf(rs.getString("role")),
                    createdAt = rs.getString("created_at")
                )
            } else null
        }
    }

    fun authenticate(username: String, password: String): User? {
        val user = findByUsername(username) ?: return null
        return if (user.passwordHash == hashPassword(password)) user else null
    }

    fun saveUser(username: String, displayName: String, role: UserRole, password: String?): User {
        val normalized = username.trim().lowercase()
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Username cannot be empty")
        }

        val existing = findByUsername(normalized)
        val passwordHash = when {
            password.isNullOrBlank() && existing != null -> existing.passwordHash
            password.isNullOrBlank() -> throw IllegalArgumentException("Password cannot be empty for new user")
            else -> hashPassword(password.trim())
        }

        val user = User(
            id = existing?.id,
            username = normalized,
            passwordHash = passwordHash,
            displayName = displayName.trim().ifBlank { normalized },
            role = role
        )

        return if (existing == null) insert(user) else update(user)
    }

    private fun insert(user: User): User = conn.useStatementWithKeys(
        """
        INSERT INTO users (username, password_hash, display_name, role)
        VALUES (?, ?, ?, ?)
        """.trimIndent()
    ) { stmt ->
        stmt.setString(1, user.username)
        stmt.setString(2, user.passwordHash)
        stmt.setString(3, user.displayName)
        stmt.setString(4, user.role.name)
        stmt.executeUpdate()
        val generatedId = stmt.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else 0L }
        user.copy(id = generatedId)
    }

    private fun update(user: User): User {
        conn.useStatement(
            """
            UPDATE users
            SET password_hash = ?, display_name = ?, role = ?
            WHERE username = ?
            """.trimIndent()
        ) { stmt ->
            stmt.setString(1, user.passwordHash)
            stmt.setString(2, user.displayName)
            stmt.setString(3, user.role.name)
            stmt.setString(4, user.username)
            stmt.executeUpdate()
        }
        return user
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
