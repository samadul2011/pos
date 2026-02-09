package pos.data

import java.sql.Connection
import java.sql.PreparedStatement

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
    private data class FilterConfig(val clause: String, val args: List<String>)

    private fun createFilterConfig(filter: String, createdBy: String?): FilterConfig {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        when (filter) {
            "Today" -> conditions += "date(s.created_at) = date('now')"
            "This Month" -> conditions += "strftime('%Y-%m', s.created_at) = strftime('%Y-%m', 'now')"
        }
        if (!createdBy.isNullOrBlank()) {
            conditions += "s.created_by = ?"
            args += createdBy
        }
        val clause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return FilterConfig(clause, args)
    }

    private fun mergeCondition(clause: String, condition: String): String {
        return if (clause.isBlank()) "WHERE $condition" else "$clause AND $condition"
    }

    fun dailySales(createdBy: String? = null): List<SaleSummary> {
        val filter = createFilterConfig("Today", createdBy)
        return conn.useStatement(
            """
            SELECT s.id, c.name AS customer_name, s.total, s.paid, (s.total - s.paid) AS balance, s.created_at
            FROM sales s
            LEFT JOIN customers c ON c.phone = s.customer_phone
            ${filter.clause}
            ORDER BY s.created_at DESC
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filter.args)
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

    fun getStatsForPeriod(filter: String, createdBy: String? = null): ReportStats {
        val filterConfig = createFilterConfig(filter, createdBy)
        val whereClause = filterConfig.clause

        val totalSales = conn.useStatement(
            """
            SELECT COALESCE(SUM(total), 0.0) as total_sales
            FROM sales s
            $whereClause
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filterConfig.args)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getDouble("total_sales") else 0.0
        }

        val totalPaid = conn.useStatement(
            """
            SELECT COALESCE(SUM(paid), 0.0) as total_paid
            FROM sales s
            $whereClause
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filterConfig.args)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getDouble("total_paid") else 0.0
        }

        val invoiceCount = conn.useStatement(
            """
            SELECT COUNT(*) as invoice_count
            FROM sales s
            $whereClause
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filterConfig.args)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getInt("invoice_count") else 0
        }

        val paymentTotals = conn.useStatement(
            """
            SELECT 
                COALESCE(SUM(CASE WHEN p.method = 'CASH' THEN p.amount ELSE 0 END), 0.0) as cash_sales,
                COALESCE(SUM(CASE WHEN p.method = 'CREDIT' THEN p.amount ELSE 0 END), 0.0) as credit_sales,
                COALESCE(SUM(CASE WHEN p.method = 'MOBILE_BANKING' THEN p.amount ELSE 0 END), 0.0) as mobile_sales,
                COALESCE(SUM(CASE WHEN p.method = 'CARD' THEN p.amount ELSE 0 END), 0.0) as card_sales
            FROM payments p
            INNER JOIN sales s ON s.id = p.sale_id
            $whereClause
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filterConfig.args)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                Triple(
                    rs.getDouble("cash_sales"),
                    rs.getDouble("credit_sales"),
                    rs.getDouble("mobile_sales")
                ) to rs.getDouble("card_sales")
            } else {
                Triple(0.0, 0.0, 0.0) to 0.0
            }
        }

        val (cashSales, creditSales, mobileSales) = paymentTotals.first
        val cardSales = paymentTotals.second

        return ReportStats(
            totalSales = totalSales,
            totalPaid = totalPaid,
            totalBalance = totalSales - totalPaid,
            invoiceCount = invoiceCount,
            cashSales = cashSales,
            creditSales = creditSales,
            mobileSales = mobileSales,
            cardSales = cardSales
        )
    }

    fun cashTotalForUser(filter: String, username: String?): Double {
        if (username.isNullOrBlank()) return 0.0
        val filterConfig = createFilterConfig(filter, username)
        val clause = mergeCondition(filterConfig.clause, "p.method = 'CASH'")
        return conn.useStatement(
            """
            SELECT COALESCE(SUM(p.amount), 0.0) as cash_total
            FROM payments p
            INNER JOIN sales s ON s.id = p.sale_id
            $clause
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filterConfig.args)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getDouble("cash_total") else 0.0
        }
    }

    fun monthlySales(createdBy: String? = null): List<SaleSummary> {
        val filter = createFilterConfig("This Month", createdBy)
        return conn.useStatement(
            """
            SELECT s.id, c.name AS customer_name, s.total, s.paid, (s.total - s.paid) AS balance, s.created_at
            FROM sales s
            LEFT JOIN customers c ON c.phone = s.customer_phone
            ${filter.clause}
            ORDER BY s.created_at DESC
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filter.args)
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

    fun allSales(limit: Int = 100, createdBy: String? = null): List<SaleSummary> {
        val filter = createFilterConfig("All", createdBy)
        val sql = """
            SELECT s.id, c.name AS customer_name, s.total, s.paid, (s.total - s.paid) AS balance, s.created_at
            FROM sales s
            LEFT JOIN customers c ON c.phone = s.customer_phone
            ${filter.clause}
            ORDER BY s.created_at DESC
            LIMIT ?
            """.trimIndent()
        return conn.useStatement(sql) { stmt ->
            stmt.applyParameters(filter.args)
            stmt.setInt(filter.args.size + 1, limit)
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

    fun salesByDateRange(startDate: String, endDate: String): List<SaleSummary> = conn.useStatement(
        """
        SELECT s.id, c.name AS customer_name, s.total, s.paid, (s.total - s.paid) AS balance, s.created_at
        FROM sales s
        LEFT JOIN customers c ON c.phone = s.customer_phone
        WHERE date(s.created_at) BETWEEN date(?) AND date(?)
        ORDER BY s.created_at DESC
        """.trimIndent()
    ) { stmt ->
        stmt.setString(1, startDate)
        stmt.setString(2, endDate)
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

    fun monthlyStats(): MonthlyStats {
        val currentMonth = java.time.YearMonth.now().toString()
        
        // Get total sales for current month
        val totalSales = conn.useStatement(
            """
            SELECT COALESCE(SUM(total), 0.0) as total
            FROM sales
            WHERE strftime('%Y-%m', created_at) = strftime('%Y-%m', 'now')
            """.trimIndent()
        ) { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getDouble("total") else 0.0
        }

        // Get invoice count
        val invoiceCount = conn.useStatement(
            """
            SELECT COUNT(*) as count
            FROM sales
            WHERE strftime('%Y-%m', created_at) = strftime('%Y-%m', 'now')
            """.trimIndent()
        ) { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getInt("count") else 0
        }

        // Get cash sales (where paid = total)
        val cashSales = conn.useStatement(
            """
            SELECT COALESCE(SUM(s.total), 0.0) as cash_total
            FROM sales s
            INNER JOIN payments p ON p.sale_id = s.id
            WHERE strftime('%Y-%m', s.created_at) = strftime('%Y-%m', 'now')
            AND p.method = 'CASH'
            AND s.paid >= s.total
            """.trimIndent()
        ) { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getDouble("cash_total") else 0.0
        }

        // Credit sales = total - cash
        val creditSales = totalSales - cashSales

        return MonthlyStats(
            totalSales = totalSales,
            cashSales = cashSales,
            creditSales = creditSales,
            invoiceCount = invoiceCount,
            month = currentMonth
        )
    }

    fun paymentMethodSummary(): List<PaymentMethodSummary> = conn.useStatement(
        """
        SELECT p.method, SUM(p.amount) as total_amount, COUNT(*) as count
        FROM payments p
        INNER JOIN sales s ON s.id = p.sale_id
        WHERE strftime('%Y-%m', s.created_at) = strftime('%Y-%m', 'now')
        GROUP BY p.method
        ORDER BY total_amount DESC
        """.trimIndent()
    ) { stmt ->
        stmt.executeQuery().mapRows { rs ->
            PaymentMethodSummary(
                method = rs.getString("method"),
                totalAmount = rs.getDouble("total_amount"),
                count = rs.getInt("count")
            )
        }
    }

    fun userSalesSummaries(filter: String): List<UserSalesSummary> {
        val filterConfig = createFilterConfig(filter, null)

        data class TotalsRow(
            val username: String,
            val displayName: String,
            val totalSales: Double,
            val totalPaid: Double,
            val invoiceCount: Int
        )

        val totals = conn.useStatement(
            """
            SELECT
                s.created_by as username,
                COALESCE(u.display_name, s.created_by) as display_name,
                COALESCE(SUM(s.total), 0.0) as total_sales,
                COALESCE(SUM(s.paid), 0.0) as total_paid,
                COUNT(*) as invoice_count
            FROM sales s
            LEFT JOIN users u ON u.username = s.created_by
            ${filterConfig.clause}
            GROUP BY s.created_by
            ORDER BY total_sales DESC
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filterConfig.args)
            stmt.executeQuery().mapRows { rs ->
                TotalsRow(
                    username = rs.getString("username"),
                    displayName = rs.getString("display_name"),
                    totalSales = rs.getDouble("total_sales"),
                    totalPaid = rs.getDouble("total_paid"),
                    invoiceCount = rs.getInt("invoice_count")
                )
            }
        }

        data class PaymentRow(
            val cash: Double,
            val credit: Double,
            val mobile: Double,
            val card: Double
        )

        val paymentsByUser: Map<String, PaymentRow> = conn.useStatement(
            """
            SELECT
                s.created_by as username,
                COALESCE(SUM(CASE WHEN p.method = 'CASH' THEN p.amount ELSE 0 END), 0.0) as cash_sales,
                COALESCE(SUM(CASE WHEN p.method = 'CREDIT' THEN p.amount ELSE 0 END), 0.0) as credit_sales,
                COALESCE(SUM(CASE WHEN p.method = 'MOBILE_BANKING' THEN p.amount ELSE 0 END), 0.0) as mobile_sales,
                COALESCE(SUM(CASE WHEN p.method = 'CARD' THEN p.amount ELSE 0 END), 0.0) as card_sales
            FROM payments p
            INNER JOIN sales s ON s.id = p.sale_id
            ${filterConfig.clause}
            GROUP BY s.created_by
            """.trimIndent()
        ) { stmt ->
            stmt.applyParameters(filterConfig.args)
            stmt.executeQuery().mapRows { rs ->
                rs.getString("username") to PaymentRow(
                    cash = rs.getDouble("cash_sales"),
                    credit = rs.getDouble("credit_sales"),
                    mobile = rs.getDouble("mobile_sales"),
                    card = rs.getDouble("card_sales")
                )
            }.toMap()
        }

        return totals.map { t ->
            val p = paymentsByUser[t.username] ?: PaymentRow(0.0, 0.0, 0.0, 0.0)
            UserSalesSummary(
                username = t.username,
                displayName = t.displayName,
                totalSales = t.totalSales,
                totalPaid = t.totalPaid,
                totalBalance = t.totalSales - t.totalPaid,
                invoiceCount = t.invoiceCount,
                cashSales = p.cash,
                creditSales = p.credit,
                mobileSales = p.mobile,
                cardSales = p.card
            )
        }
    }
}

private fun PreparedStatement.applyParameters(params: List<String>) {
    params.forEachIndexed { index, value ->
        setString(index + 1, value)
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
    fun createSale(customerPhone: String?, lines: List<SaleLine>, paidAmount: Double, paymentMethod: String = "CASH", createdBy: String = "SYSTEM"): Long {
        if (lines.isEmpty()) throw IllegalArgumentException("Sale must have at least one line")

        val total = lines.sumOf { it.quantity * it.price }
        
        // insert sale
        val saleId = conn.useStatementWithKeys(
            """
            INSERT INTO sales(customer_phone, total, paid, created_by)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        ) { stmt ->
            stmt.setObject(1, customerPhone)
            stmt.setDouble(2, total)
            stmt.setDouble(3, paidAmount)
            stmt.setString(4, createdBy.ifBlank { "SYSTEM" })
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
            INSERT INTO payments(sale_id, method, amount, reference, created_by)
            VALUES (?, ?, ?, NULL, ?)
            """.trimIndent()
        ) { stmt ->
            stmt.setLong(1, saleId)
            stmt.setString(2, paymentMethod)
            stmt.setDouble(3, paidAmount)
            stmt.setString(4, createdBy.ifBlank { "SYSTEM" })
            stmt.executeUpdate()
        }

        return saleId
    }
}
