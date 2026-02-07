package pos.data

import java.sql.Connection

class ItemRepository(private val conn: Connection = DatabaseFactory.connection) {
    fun list(): List<Item> = conn.useStatement(
        """
        SELECT id, name, sku, price, stock, unit, reorder_level
        FROM items
        ORDER BY name
        """.trimIndent()
    ) { stmt ->
        stmt.executeQuery().mapRows { rs ->
            Item(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                sku = rs.getString("sku"),
                price = rs.getDouble("price"),
                stock = rs.getDouble("stock"),
                unit = rs.getString("unit"),
                reorderLevel = rs.getDouble("reorder_level")
            )
        }
    }

    fun upsert(item: Item): Item {
        return if (item.id == null) insert(item) else update(item)
    }

    fun findBySku(sku: String): Item? = conn.useStatement(
        """
        SELECT id, name, sku, price, stock, unit, reorder_level
        FROM items
        WHERE sku = ?
        LIMIT 1
        """.trimIndent()
    ) { stmt ->
        stmt.setString(1, sku)
        val rs = stmt.executeQuery()
        if (rs.next()) {
            Item(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                sku = rs.getString("sku"),
                price = rs.getDouble("price"),
                stock = rs.getDouble("stock"),
                unit = rs.getString("unit"),
                reorderLevel = rs.getDouble("reorder_level")
            )
        } else null
    }

    fun getById(id: Long): Item? = conn.useStatement(
        """
        SELECT id, name, sku, price, stock, unit, reorder_level
        FROM items
        WHERE id = ?
        LIMIT 1
        """.trimIndent()
    ) { stmt ->
        stmt.setLong(1, id)
        val rs = stmt.executeQuery()
        if (rs.next()) {
            Item(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                sku = rs.getString("sku"),
                price = rs.getDouble("price"),
                stock = rs.getDouble("stock"),
                unit = rs.getString("unit"),
                reorderLevel = rs.getDouble("reorder_level")
            )
        } else null
    }

    private fun insert(item: Item): Item = conn.useStatementWithKeys(
        """
        INSERT INTO items(name, sku, price, stock, unit, reorder_level)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
    ) { stmt ->
        stmt.apply {
            setString(1, item.name)
            setString(2, item.sku)
            setDouble(3, item.price)
            setDouble(4, item.stock)
            setString(5, item.unit)
            setDouble(6, item.reorderLevel)
            executeUpdate()
        }
        val generatedId = stmt.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else 0L }
        item.copy(id = generatedId)
    }

    private fun update(item: Item): Item {
        conn.useStatement(
            """
            UPDATE items
            SET name = ?, sku = ?, price = ?, stock = ?, unit = ?, reorder_level = ?
            WHERE id = ?
            """.trimIndent()
        ) { stmt ->
            stmt.setString(1, item.name)
            stmt.setString(2, item.sku)
            stmt.setDouble(3, item.price)
            stmt.setDouble(4, item.stock)
            stmt.setString(5, item.unit)
            stmt.setDouble(6, item.reorderLevel)
            stmt.setLong(7, item.id!!)
            stmt.executeUpdate()
        }
        return item
    }
}

class CustomerRepository(private val conn: Connection = DatabaseFactory.connection) {
    fun list(): List<Customer> = conn.useStatement(
        """
        SELECT phone, name, address, dob, email, status, credit_limit
        FROM customers
        ORDER BY name
        """.trimIndent()
    ) { stmt ->
        stmt.executeQuery().mapRows { rs ->
            Customer(
                phone = rs.getString("phone"),
                name = rs.getString("name"),
                address = rs.getString("address"),
                dob = rs.getString("dob"),
                email = rs.getString("email"),
                status = rs.getString("status"),
                creditLimit = rs.getDouble("credit_limit")
            )
        }
    }

    fun upsert(customer: Customer): Customer {
        // Try update first, if no rows affected, insert
        val updated = conn.useStatement(
            """
            UPDATE customers SET name=?, address=?, dob=?, email=?, status=?, credit_limit=? WHERE phone=?
            """.trimIndent()
        ) { stmt ->
            stmt.setString(1, customer.name)
            stmt.setString(2, customer.address)
            stmt.setString(3, customer.dob)
            stmt.setString(4, customer.email)
            stmt.setString(5, customer.status)
            stmt.setDouble(6, customer.creditLimit)
            stmt.setString(7, customer.phone)
            stmt.executeUpdate()
        }
        if (updated > 0) return customer
        // Insert if not exists
        conn.useStatement(
            """
            INSERT INTO customers(phone, name, address, dob, email, status, credit_limit)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ) { stmt ->
            stmt.setString(1, customer.phone)
            stmt.setString(2, customer.name)
            stmt.setString(3, customer.address)
            stmt.setString(4, customer.dob)
            stmt.setString(5, customer.email)
            stmt.setString(6, customer.status)
            stmt.setDouble(7, customer.creditLimit)
            stmt.executeUpdate()
        }
        return customer
    }

    fun findByPhone(phone: String): Customer? = conn.useStatement(
        """
        SELECT phone, name, address, dob, email, status, credit_limit
        FROM customers
        WHERE phone = ?
        LIMIT 1
        """.trimIndent()
    ) { stmt ->
        stmt.setString(1, phone)
        val rs = stmt.executeQuery()
        if (rs.next()) {
            Customer(
                phone = rs.getString("phone"),
                name = rs.getString("name"),
                address = rs.getString("address"),
                dob = rs.getString("dob"),
                email = rs.getString("email"),
                status = rs.getString("status"),
                creditLimit = rs.getDouble("credit_limit")
            )
        } else null
    }
}

class ReportsRepository(private val conn: Connection = DatabaseFactory.connection) {
    fun dailySales(): List<SaleSummary> = conn.useStatement(
        """
        SELECT s.id, c.name AS customer_name, s.total, s.paid, (s.total - s.paid) AS balance, s.created_at
        FROM sales s
        LEFT JOIN customers c ON c.phone = s.customer_phone
        WHERE date(s.created_at) = date('now')
        ORDER BY s.created_at DESC
        """.trimIndent()
    ) { stmt ->
        stmt.executeQuery().mapRows { rs ->
            SaleSummary(
                id = rs.getLong("id"),
                customerName = rs.getString("customer_name"),
                total = rs.getDouble("total"),
                paid = rs.getDouble("paid"),
                balance = rs.getDouble("balance"),
                createdAt = rs.getString("created_at")
            )
        }
    }
}

class ProductRepository(private val conn: Connection = DatabaseFactory.connection) {
    fun list(): List<Product> = conn.useStatement(
        """
        SELECT id, code, description, uom, buy_price, sell_price, default_number, stock, reorder_level
        FROM products
        ORDER BY code
        """.trimIndent()
    ) { stmt ->
        stmt.executeQuery().mapRows { rs ->
            Product(
                id = rs.getLong("id"),
                code = rs.getString("code"),
                description = rs.getString("description"),
                uom = rs.getString("uom"),
                buyPrice = rs.getDouble("buy_price"),
                sellPrice = rs.getDouble("sell_price"),
                defaultNumber = rs.getDouble("default_number"),
                stock = rs.getDouble("stock"),
                reorderLevel = rs.getDouble("reorder_level")
            )
        }
    }

    fun upsert(product: Product): Product {
        return if (product.id == null) insert(product) else update(product)
    }

    private fun insert(product: Product): Product = conn.useStatementWithKeys(
        """
        INSERT INTO products(code, description, uom, buy_price, sell_price, default_number, stock, reorder_level)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    ) { stmt ->
        stmt.setString(1, product.code)
        stmt.setString(2, product.description)
        stmt.setString(3, product.uom)
        stmt.setDouble(4, product.buyPrice)
        stmt.setDouble(5, product.sellPrice)
        stmt.setDouble(6, product.defaultNumber)
        stmt.setDouble(7, product.stock)
        stmt.setDouble(8, product.reorderLevel)
        stmt.executeUpdate()
        val generatedId = stmt.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else 0L }
        product.copy(id = generatedId)
    }

    private fun update(product: Product): Product {
        conn.useStatement(
            """
            UPDATE products
            SET code = ?, description = ?, uom = ?, buy_price = ?, sell_price = ?, default_number = ?, stock = ?, reorder_level = ?
            WHERE id = ?
            """.trimIndent()
        ) { stmt ->
            stmt.setString(1, product.code)
            stmt.setString(2, product.description)
            stmt.setString(3, product.uom)
            stmt.setDouble(4, product.buyPrice)
            stmt.setDouble(5, product.sellPrice)
            stmt.setDouble(6, product.defaultNumber)
            stmt.setDouble(7, product.stock)
            stmt.setDouble(8, product.reorderLevel)
            stmt.setLong(9, product.id!!)
            stmt.executeUpdate()
        }
        return product
    }

    fun findByCode(code: String): Product? = conn.useStatement(
        """
        SELECT id, code, description, uom, buy_price, sell_price, default_number, stock, reorder_level
        FROM products
        WHERE code = ?
        LIMIT 1
        """.trimIndent()
    ) { stmt ->
        stmt.setString(1, code)
        val rs = stmt.executeQuery()
        if (rs.next()) {
            Product(
                id = rs.getLong("id"),
                code = rs.getString("code"),
                description = rs.getString("description"),
                uom = rs.getString("uom"),
                buyPrice = rs.getDouble("buy_price"),
                sellPrice = rs.getDouble("sell_price"),
                defaultNumber = rs.getDouble("default_number"),
                stock = rs.getDouble("stock"),
                reorderLevel = rs.getDouble("reorder_level")
            )
        } else null
    }

    fun getById(id: Long): Product? = conn.useStatement(
        """
        SELECT id, code, description, uom, buy_price, sell_price, default_number, stock, reorder_level
        FROM products
        WHERE id = ?
        LIMIT 1
        """.trimIndent()
    ) { stmt ->
        stmt.setLong(1, id)
        val rs = stmt.executeQuery()
        if (rs.next()) {
            Product(
                id = rs.getLong("id"),
                code = rs.getString("code"),
                description = rs.getString("description"),
                uom = rs.getString("uom"),
                buyPrice = rs.getDouble("buy_price"),
                sellPrice = rs.getDouble("sell_price"),
                defaultNumber = rs.getDouble("default_number"),
                stock = rs.getDouble("stock"),
                reorderLevel = rs.getDouble("reorder_level")
            )
        } else null
    }
}

class SaleRepository(private val conn: Connection = DatabaseFactory.connection) {
    fun createSale(customerPhone: String?, lines: List<SaleLine>, paidAmount: Double, paymentMethod: String = "CASH"): Long {
        if (lines.isEmpty()) throw IllegalArgumentException("Sale must have at least one line")

        val total = lines.sumOf { it.quantity * it.price }
        
        // insert sale
        val saleId = conn.useStatementWithKeys(
            """
            INSERT INTO sales(customer_phone, total, paid)
            VALUES (?, ?, ?)
            """.trimIndent()
        ) { stmt ->
            stmt.setObject(1, customerPhone)
            stmt.setDouble(2, total)
            stmt.setDouble(3, paidAmount)
            stmt.executeUpdate()
            val keys = stmt.generatedKeys
            if (keys.next()) keys.getLong(1) else throw IllegalStateException("Failed to create sale")
        }

        // insert lines and update product stock
        conn.prepareStatement(
            """
            INSERT INTO sale_lines(sale_id, item_id, quantity, price)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        ).use { stmtLine ->
            for (line in lines) {
                stmtLine.setLong(1, saleId)
                stmtLine.setLong(2, line.itemId)
                stmtLine.setDouble(3, line.quantity)
                stmtLine.setDouble(4, line.price)
                stmtLine.addBatch()
            }
            stmtLine.executeBatch()
        }

        // Update product stock - deduct sold quantities
        conn.prepareStatement("UPDATE products SET stock = stock - ? WHERE id = ?").use { stmtStock ->
            for (line in lines) {
                stmtStock.setDouble(1, line.quantity)
                stmtStock.setLong(2, line.itemId)
                stmtStock.addBatch()
            }
            stmtStock.executeBatch()
        }

        // insert payment
        conn.useStatement(
            """
            INSERT INTO payments(sale_id, method, amount, reference)
            VALUES (?, ?, ?, NULL)
            """.trimIndent()
        ) { stmt ->
            stmt.setLong(1, saleId)
            stmt.setString(2, paymentMethod)
            stmt.setDouble(3, paidAmount)
            stmt.executeUpdate()
        }

        return saleId
    }
}
