package pos.sync

import io.ktor.serialization.jackson.jackson
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.http.*
import pos.data.CustomerRepository
import pos.data.DatabaseFactory
import pos.data.ProductRepository
import pos.data.SaleLine
import pos.data.SaleRepository
import pos.data.ReportsRepository
import pos.data.UserRepository
import pos.data.UserRole
import pos.data.mapRows
import pos.data.useStatement
import pos.utils.InvoiceData
import pos.utils.InvoiceItem
import pos.utils.PdfGenerator
import java.io.File
import java.net.URLEncoder

// Simple DTO for incoming sale requests
data class SaleRequest(val customerPhone: String?, val lines: List<SaleLine>, val paid: Double, val method: String = "CASH")

data class SaleLineByCode(val code: String, val quantity: Double)
data class SaleRequestByCode(
    val customerPhone: String?,
    val lines: List<SaleLineByCode>,
    val paid: Double,
    val method: String = "CASH"
)

data class MobileSession(
    val username: String,
    val displayName: String,
    val role: String
)

object SyncServer {
    private val productRepo = ProductRepository()
    private val saleRepo = SaleRepository()
    private val reportsRepo = ReportsRepository()
    private val customerRepo = CustomerRepository()
    private val userRepo = UserRepository()

    private fun buildInvoiceData(saleId: Long): InvoiceData {
        val conn = DatabaseFactory.connection

        data class SaleRow(
            val id: Long,
            val customerPhone: String?,
            val total: Double,
            val paid: Double,
            val createdAt: String
        )

        val sale = conn.useStatement(
            """
            SELECT id, customer_phone, total, paid, created_at
            FROM sales
            WHERE id = ?
            LIMIT 1
            """.trimIndent()
        ) { stmt ->
            stmt.setLong(1, saleId)
            val rs = stmt.executeQuery()
            if (!rs.next()) throw IllegalArgumentException("Invoice not found: $saleId")
            SaleRow(
                id = rs.getLong("id"),
                customerPhone = rs.getString("customer_phone"),
                total = rs.getDouble("total"),
                paid = rs.getDouble("paid"),
                createdAt = rs.getString("created_at")
            )
        }

        val customer = sale.customerPhone?.let { phone -> customerRepo.findByPhone(phone) }
        val customerName = customer?.name ?: "Walk-in Customer"

        val paymentMethod = conn.useStatement(
            """
            SELECT method
            FROM payments
            WHERE sale_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent()
        ) { stmt ->
            stmt.setLong(1, saleId)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getString("method") else "CASH"
        }

        val items = conn.useStatement(
            """
            SELECT p.code, p.description, l.quantity, l.price
            FROM sale_lines l
            LEFT JOIN products p ON p.id = l.item_id
            WHERE l.sale_id = ?
            ORDER BY l.id ASC
            """.trimIndent()
        ) { stmt ->
            stmt.setLong(1, saleId)
            stmt.executeQuery().mapRows { rs ->
                val code = rs.getString("code") ?: ""
                val desc = rs.getString("description") ?: ""
                val qty = rs.getDouble("quantity")
                val price = rs.getDouble("price")
                InvoiceItem(
                    code = code,
                    description = desc,
                    qty = qty,
                    price = price,
                    total = qty * price
                )
            }
        }

        val subtotal = items.sumOf { it.total }
        return InvoiceData(
            invoiceNo = sale.id,
            customerName = customerName,
            customerPhone = sale.customerPhone,
            date = sale.createdAt,
            items = items,
            subtotal = subtotal,
            tax = 0.0,
            total = sale.total,
            paidAmount = sale.paid,
            paymentMethod = paymentMethod
        )
    }

    private fun invoicePdfPath(invoiceNo: Long): String {
        val invoicesDir = File("invoices")
        invoicesDir.mkdirs()
        return File(invoicesDir, "invoice-$invoiceNo.pdf").absolutePath
    }

    fun start(port: Int = 8080) {
        val server = embeddedServer(Netty, host = "0.0.0.0", port = port) {
            install(ContentNegotiation) {
                jackson()
            }

            install(Sessions) {
                cookie<MobileSession>("POS_MOBILE_SESSION") {
                    cookie.path = "/"
                    cookie.httpOnly = true
                    cookie.extensions["SameSite"] = "Lax"
                }
            }

            routing {
                get("/health") {
                    call.respondText("OK")
                }

                                get("/") {
                                        call.respondRedirect("/mobile", permanent = false)
                                }

                                // Mobile login (reuses desktop users)
                                get("/mobile/login") {
                                    val err = call.request.queryParameters["err"]
                                    val html = """
                                        <!doctype html>
                                        <html lang="en">
                                        <head>
                                            <meta charset="utf-8" />
                                            <meta name="viewport" content="width=device-width, initial-scale=1" />
                                            <title>POS Mobile Login</title>
                                            <style>
                                                * { margin:0; padding:0; box-sizing:border-box; }
                                                body {
                                                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                                    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                                    min-height: 100vh;
                                                    padding: 16px;
                                                    display: flex;
                                                    align-items: center;
                                                    justify-content: center;
                                                }
                                                .card {
                                                    width: 100%;
                                                    max-width: 420px;
                                                    background: white;
                                                    border-radius: 16px;
                                                    padding: 20px;
                                                    box-shadow: 0 8px 16px rgba(0,0,0,0.2);
                                                }
                                                h2 { margin-bottom: 6px; color:#333; }
                                                p { color:#666; font-size:0.9em; margin-bottom: 16px; }
                                                label { display:block; font-weight:600; color:#666; font-size:0.85em; margin: 10px 0 6px; }
                                                input {
                                                    width: 100%;
                                                    padding: 12px;
                                                    border: 1px solid #ddd;
                                                    border-radius: 10px;
                                                    font-size: 1em;
                                                }
                                                .btn {
                                                    width: 100%;
                                                    margin-top: 14px;
                                                    border: none;
                                                    padding: 12px;
                                                    border-radius: 12px;
                                                    font-weight: 700;
                                                    color: white;
                                                    background: #667eea;
                                                    cursor: pointer;
                                                }
                                                .err {
                                                    background: #ffebee;
                                                    color: #c62828;
                                                    padding: 10px 12px;
                                                    border-radius: 12px;
                                                    font-size: 0.9em;
                                                    margin-bottom: 12px;
                                                }
                                            </style>
                                        </head>
                                        <body>
                                            <div class="card">
                                                <h2>POS Mobile</h2>
                                                <p>Sign in using your POS username and password.</p>
                                                ${if (!err.isNullOrBlank()) "<div class='err'>${err.replace("<","&lt;").replace(">","&gt;")}</div>" else ""}
                                                <form method="post" action="/mobile/login">
                                                    <label>Username</label>
                                                    <input name="username" autocomplete="username" />
                                                    <label>Password</label>
                                                    <input name="password" type="password" autocomplete="current-password" />
                                                    <button class="btn" type="submit">Sign in</button>
                                                </form>
                                            </div>
                                        </body>
                                        </html>
                                    """.trimIndent()
                                    call.respondText(html, ContentType.Text.Html)
                                }

                                post("/mobile/login") {
                                    userRepo.ensureAdminAccount()
                                    val params = call.receiveParameters()
                                    val username = params["username"]?.trim().orEmpty()
                                    val password = params["password"]?.trim().orEmpty()
                                    if (username.isBlank() || password.isBlank()) {
                                        call.respondRedirect("/mobile/login?err=Missing%20username%20or%20password", permanent = false)
                                        return@post
                                    }
                                    val user = userRepo.authenticate(username, password)
                                    if (user == null) {
                                        call.respondRedirect("/mobile/login?err=Invalid%20username%20or%20password", permanent = false)
                                        return@post
                                    }
                                    call.sessions.set(MobileSession(user.username, user.displayName, user.role.name))
                                    call.respondRedirect("/mobile", permanent = false)
                                }

                                get("/mobile/logout") {
                                    call.sessions.clear<MobileSession>()
                                    call.respondRedirect("/mobile/login", permanent = false)
                                }

                                // Guard all mobile pages + mobile API endpoints
                                intercept(ApplicationCallPipeline.Plugins) {
                                    val path = call.request.path()
                                    val session = call.sessions.get<MobileSession>()

                                    val isMobileLogin = path == "/mobile/login"
                                    val isMobileLogout = path == "/mobile/logout"
                                    val isMobilePage = path == "/mobile" || path.startsWith("/mobile/")

                                    val isApiEndpoint =
                                        path == "/products" ||
                                        path == "/customers" ||
                                        path == "/sales" ||
                                        path == "/salesByCode" ||
                                        path.startsWith("/invoice/")

                                    val needsAuth = (isMobilePage && !isMobileLogin && !isMobileLogout) || isApiEndpoint

                                    if (needsAuth && session == null) {
                                        if (isMobilePage) {
                                            call.respondRedirect("/mobile/login", permanent = false)
                                        } else {
                                            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                                        }
                                        finish()
                                    }
                                }

                                get("/mobile") {
                                    val session = call.sessions.get<MobileSession>()!!
                                    val createdByScope = if (session.role == UserRole.ADMIN.name) null else session.username
                                    val labelPrefix = if (createdByScope == null) "All Sales" else "Your Sales"

                                    val todayStats = reportsRepo.getStatsForPeriod("Today", createdByScope)
                                    val monthStats = reportsRepo.getStatsForPeriod("This Month", createdByScope)
                                        val html = """
                                                <!doctype html>
                                                <html lang="en">
                                                <head>
                                                    <meta charset="utf-8" />
                                                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                                                    <title>POS Mobile</title>
                                                    <style>
                                                        * {
                                                            margin: 0;
                                                            padding: 0;
                                                            box-sizing: border-box;
                                                        }
                                                        body {
                                                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                                                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                                            min-height: 100vh;
                                                            padding: 20px;
                                                            color: #333;
                                                        }
                                                        .container {
                                                            max-width: 600px;
                                                            margin: 0 auto;
                                                        }
                                                        .header {
                                                            text-align: center;
                                                            color: white;
                                                            margin-bottom: 30px;
                                                        }
                                                        .header h1 {
                                                            font-size: 2em;
                                                            font-weight: 700;
                                                            margin-bottom: 8px;
                                                        }
                                                        .header p {
                                                            opacity: 0.9;
                                                            font-size: 0.95em;
                                                        }
                                                        .nav-grid {
                                                            display: grid;
                                                            grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
                                                            gap: 15px;
                                                            margin-bottom: 20px;
                                                        }
                                                        .nav-card {
                                                            background: white;
                                                            border-radius: 16px;
                                                            padding: 24px 16px;
                                                            text-align: center;
                                                            text-decoration: none;
                                                            color: #333;
                                                            box-shadow: 0 4px 6px rgba(0,0,0,0.1);
                                                            transition: all 0.3s ease;
                                                            display: flex;
                                                            flex-direction: column;
                                                            align-items: center;
                                                            gap: 8px;
                                                        }
                                                        .nav-card:active {
                                                            transform: scale(0.98);
                                                            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                                                        }
                                                        .nav-card .icon {
                                                            width: 48px;
                                                            height: 48px;
                                                            border-radius: 12px;
                                                            display: flex;
                                                            align-items: center;
                                                            justify-content: center;
                                                            font-size: 24px;
                                                            margin-bottom: 4px;
                                                        }
                                                        .nav-card.primary .icon { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); }
                                                        .nav-card.success .icon { background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%); }
                                                        .nav-card.warning .icon { background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%); }
                                                        .nav-card.info .icon { background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%); }
                                                        .nav-card .title {
                                                            font-weight: 600;
                                                            font-size: 0.95em;
                                                            color: #333;
                                                        }
                                                        .tip {
                                                            background: rgba(255,255,255,0.2);
                                                            backdrop-filter: blur(10px);
                                                            border-radius: 12px;
                                                            padding: 16px;
                                                            color: white;
                                                            text-align: center;
                                                            font-size: 0.9em;
                                                        }
                                                        .stats {
                                                            display: grid;
                                                            grid-template-columns: 1fr 1fr;
                                                            gap: 12px;
                                                            margin: 16px 0 18px;
                                                        }
                                                        .stat {
                                                            background: rgba(255,255,255,0.18);
                                                            backdrop-filter: blur(10px);
                                                            border-radius: 14px;
                                                            padding: 12px;
                                                            color: #fff;
                                                            text-align: left;
                                                        }
                                                        .stat .k { font-size: 0.82em; opacity: 0.92; margin-bottom: 6px; }
                                                        .stat .v { font-size: 1.25em; font-weight: 800; line-height: 1.1; }
                                                        .stat .s { font-size: 0.82em; opacity: 0.92; margin-top: 6px; }
                                                        .who {
                                                            margin-top: 10px;
                                                            font-size: 0.9em;
                                                            opacity: 0.95;
                                                        }
                                                        .who a { color: #fff; text-decoration: underline; }
                                                    </style>
                                                </head>
                                                <body>
                                                    <div class="container">
                                                        <div class="header">
                                                            <h1>📱 POS Mobile</h1>
                                                            <p>Point of Sale System</p>
                                                            <div class="who">Signed in: <b>${session.displayName.replace("<","&lt;").replace(">","&gt;")}</b> (${session.role}) · <a href="/mobile/logout">Logout</a></div>
                                                        </div>

                                                        <div class="stats">
                                                            <div class="stat">
                                                                <div class="k">${labelPrefix} · Today</div>
                                                                <div class="v">${String.format("%.2f", todayStats.totalSales)}</div>
                                                                <div class="s">Paid ${String.format("%.2f", todayStats.totalPaid)} · Bal ${String.format("%.2f", todayStats.totalBalance)} · ${todayStats.invoiceCount} invoices</div>
                                                            </div>
                                                            <div class="stat">
                                                                <div class="k">${labelPrefix} · This Month</div>
                                                                <div class="v">${String.format("%.2f", monthStats.totalSales)}</div>
                                                                <div class="s">Paid ${String.format("%.2f", monthStats.totalPaid)} · Bal ${String.format("%.2f", monthStats.totalBalance)} · ${monthStats.invoiceCount} invoices</div>
                                                            </div>
                                                        </div>
                                                        
                                                        <div class="nav-grid">
                                                            <a href="/mobile/products" class="nav-card primary">
                                                                <div class="icon">📦</div>
                                                                <div class="title">Products</div>
                                                            </a>
                                                            <a href="/mobile/product" class="nav-card success">
                                                                <div class="icon">➕</div>
                                                                <div class="title">Add Product</div>
                                                            </a>
                                                            <a href="/mobile/sale" class="nav-card warning">
                                                                <div class="icon">🛒</div>
                                                                <div class="title">New Sale</div>
                                                            </a>
                                                            <a href="/mobile/invoices" class="nav-card info">
                                                                <div class="icon">📄</div>
                                                                <div class="title">Invoices</div>
                                                            </a>
                                                        </div>
                                                        
                                                        <div class="tip">
                                                            💡 Tip: Bookmark this page on your phone for quick access
                                                        </div>
                                                    </div>
                                                </body>
                                                </html>
                                        """.trimIndent()
                                        call.respondText(html, ContentType.Text.Html)
                                }

                                get("/mobile/products") {
                                        val products = productRepo.list()
                                        val rows = products.joinToString("\n") { p ->
                                                val code = p.code
                                                val name = (p.description ?: "").replace("<", "&lt;").replace(">", "&gt;")
                                                "<tr><td>${code}</td><td>${name}</td><td>${"%.2f".format(p.sellPrice)}</td><td>${"%.2f".format(p.stock)}</td></tr>"
                                        }
                                        val html = """
                                                <!doctype html>
                                                <html lang="en">
                                                <head>
                                                    <meta charset="utf-8" />
                                                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                                                    <title>Products</title>
                                                </head>
                                                <body>
                                                    <p><a href="/mobile">← Back</a></p>
                                                    <h3>Products</h3>
                                                    <table border="1" cellpadding="6" cellspacing="0">
                                                        <thead><tr><th>Code</th><th>Description</th><th>Price</th><th>Stock</th></tr></thead>
                                                        <tbody>
                                                            ${rows}
                                                        </tbody>
                                                    </table>
                                                </body>
                                                </html>
                                        """.trimIndent()
                                        call.respondText(html, ContentType.Text.Html)
                                }

                                get("/mobile/invoices") {
                                        val session = call.sessions.get<MobileSession>()!!
                                        val createdByScope = if (session.role == UserRole.ADMIN.name) null else session.username
                                        val filter = call.request.queryParameters["filter"] ?: "all"
                                        val sales = when (filter) {
                                            "today" -> reportsRepo.dailySales(createdByScope)
                                            "month" -> reportsRepo.monthlySales(createdByScope)
                                            else -> reportsRepo.allSales(50, createdByScope)
                                        }
                                        
                                        val rows = if (sales.isEmpty()) {
                                            "<tr><td colspan='5' style='text-align:center; padding:40px; color:#999;'>No invoices found</td></tr>"
                                        } else {
                                            sales.joinToString("\n") { s ->
                                                val cust = (s.customerName ?: "Walk-in").replace("<", "&lt;").replace(">", "&gt;")
                                                val statusColor = if (s.paid >= s.total) "#43A047" else "#E53935"
                                                """<tr>
                                                    <td><strong>#${s.id}</strong></td>
                                                    <td>${cust}</td>
                                                    <td style="text-align:right;">${"%.2f".format(s.total)}</td>
                                                    <td style="text-align:right; color:${statusColor};">${"%.2f".format(s.paid)}</td>
                                                    <td style="font-size:0.85em; color:#666;">${s.createdAt}</td>
                                                </tr>"""
                                            }
                                        }
                                        
                                        val html = """
                                                <!doctype html>
                                                <html lang="en">
                                                <head>
                                                    <meta charset="utf-8" />
                                                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                                                    <title>Invoices</title>
                                                    <style>
                                                        * { margin: 0; padding: 0; box-sizing: border-box; }
                                                        body {
                                                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                                            min-height: 100vh;
                                                            padding: 16px;
                                                        }
                                                        .container {
                                                            max-width: 900px;
                                                            margin: 0 auto;
                                                            background: white;
                                                            border-radius: 16px;
                                                            padding: 20px;
                                                            box-shadow: 0 8px 16px rgba(0,0,0,0.2);
                                                        }
                                                        .header {
                                                            display: flex;
                                                            justify-content: space-between;
                                                            align-items: center;
                                                            margin-bottom: 20px;
                                                        }
                                                        .back-btn {
                                                            background: #667eea;
                                                            color: white;
                                                            padding: 8px 16px;
                                                            border-radius: 8px;
                                                            text-decoration: none;
                                                            font-size: 0.9em;
                                                        }
                                                        h2 { color: #333; margin-bottom: 16px; }
                                                        .filters {
                                                            display: flex;
                                                            gap: 8px;
                                                            margin-bottom: 20px;
                                                            flex-wrap: wrap;
                                                        }
                                                        .filter-btn {
                                                            padding: 10px 20px;
                                                            border-radius: 8px;
                                                            text-decoration: none;
                                                            font-weight: 600;
                                                            font-size: 0.9em;
                                                            transition: all 0.2s;
                                                        }
                                                        .filter-btn.active {
                                                            background: #667eea;
                                                            color: white;
                                                        }
                                                        .filter-btn:not(.active) {
                                                            background: #f0f0f0;
                                                            color: #666;
                                                        }
                                                        table {
                                                            width: 100%;
                                                            border-collapse: collapse;
                                                            background: white;
                                                        }
                                                        th {
                                                            background: #f8f9fa;
                                                            padding: 12px;
                                                            text-align: left;
                                                            font-weight: 600;
                                                            color: #333;
                                                            border-bottom: 2px solid #dee2e6;
                                                        }
                                                        td {
                                                            padding: 12px;
                                                            border-bottom: 1px solid #f0f0f0;
                                                        }
                                                        tr:hover {
                                                            background: #f8f9fa;
                                                        }
                                                        .count {
                                                            color: #666;
                                                            font-size: 0.9em;
                                                            margin-bottom: 12px;
                                                        }
                                                        @media (max-width: 600px) {
                                                            table { font-size: 0.85em; }
                                                            th, td { padding: 8px 4px; }
                                                        }
                                                    </style>
                                                </head>
                                                <body>
                                                    <div class="container">
                                                        <div class="header">
                                                            <h2>📄 Invoices</h2>
                                                            <a href="/mobile" class="back-btn">← Back</a>
                                                        </div>
                                                        
                                                        <div class="filters">
                                                            <a href="?filter=today" class="filter-btn ${if (filter == "today") "active" else ""}">Today</a>
                                                            <a href="?filter=month" class="filter-btn ${if (filter == "month") "active" else ""}">This Month</a>
                                                            <a href="?filter=all" class="filter-btn ${if (filter == "all") "active" else ""}">All</a>
                                                        </div>
                                                        
                                                        <div class="count">Showing ${sales.size} invoice(s)</div>
                                                        
                                                        <table>
                                                            <thead>
                                                                <tr>
                                                                    <th>ID</th>
                                                                    <th>Customer</th>
                                                                    <th style="text-align:right;">Total</th>
                                                                    <th style="text-align:right;">Paid</th>
                                                                    <th>Date</th>
                                                                </tr>
                                                            </thead>
                                                            <tbody>
                                                                ${rows}
                                                            </tbody>
                                                        </table>
                                                    </div>
                                                </body>
                                                </html>
                                        """.trimIndent()
                                        call.respondText(html, ContentType.Text.Html)
                                }

                                get("/mobile/sale") {
                                            val html = """
                                                    <!doctype html>
                                                    <html lang="en">
                                                    <head>
                                                        <meta charset="utf-8" />
                                                        <meta name="viewport" content="width=device-width, initial-scale=1" />
                                                        <title>New Sale</title>
                                                        <style>
                                                            * { margin:0; padding:0; box-sizing:border-box; }
                                                            body {
                                                                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                                                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                                                min-height: 100vh;
                                                                padding: 12px;
                                                                color: #333;
                                                            }
                                                            .container { max-width: 600px; margin: 0 auto; }
                                                            .header {
                                                                display: flex;
                                                                justify-content: space-between;
                                                                align-items: center;
                                                                margin-bottom: 20px;
                                                                color: white;
                                                            }
                                                            .back-btn {
                                                                color: white;
                                                                text-decoration: none;
                                                                background: rgba(255,255,255,0.2);
                                                                padding: 6px 12px;
                                                                border-radius: 8px;
                                                                font-size: 0.9em;
                                                            }
                                                            .card {
                                                                background: white;
                                                                border-radius: 16px;
                                                                padding: 16px;
                                                                margin-bottom: 16px;
                                                                box-shadow: 0 4px 6px rgba(0,0,0,0.1);
                                                            }
                                                            .section-title {
                                                                font-size: 1.1em;
                                                                font-weight: 700;
                                                                margin-bottom: 12px;
                                                                color: #333;
                                                                display: flex;
                                                                align-items: center;
                                                                gap: 8px;
                                                            }
                                                            .input-group { margin-bottom: 12px; }
                                                            .input-group label {
                                                                display: block;
                                                                font-size: 0.85em;
                                                                font-weight: 600;
                                                                color: #666;
                                                                margin-bottom: 4px;
                                                            }
                                                            input, select {
                                                                width: 100%;
                                                                padding: 10px;
                                                                border: 1px solid #ddd;
                                                                border-radius: 8px;
                                                                font-size: 1em;
                                                            }
                                                            .table-container { overflow-x: auto; }
                                                            table { width: 100%; border-collapse: collapse; margin-bottom: 12px; }
                                                            th { text-align: left; font-size: 0.8em; color: #999; padding: 4px; border-bottom: 1px solid #eee; }
                                                            td { padding: 4px; vertical-align: middle; }
                                                            .btn-add {
                                                                background: #f0f0f0;
                                                                border: none;
                                                                padding: 8px 16px;
                                                                border-radius: 8px;
                                                                font-weight: 600;
                                                                width: 100%;
                                                                cursor: pointer;
                                                            }
                                                            .summary {
                                                                border-top: 1px solid #eee;
                                                                padding-top: 12px;
                                                                margin-top: 12px;
                                                            }
                                                            .summary-row {
                                                                display: flex;
                                                                justify-content: space-between;
                                                                margin-bottom: 4px;
                                                                font-size: 0.95em;
                                                            }
                                                            .summary-row.total {
                                                                font-size: 1.25em;
                                                                font-weight: 700;
                                                                color: #1976D2;
                                                                margin-top: 8px;
                                                            }
                                                            .payment-grid {
                                                                display: grid;
                                                                grid-template-columns: repeat(2, 1fr);
                                                                gap: 8px;
                                                                margin-bottom: 12px;
                                                            }
                                                            .payment-btn {
                                                                background: #f8f9fa;
                                                                border: 2px solid transparent;
                                                                border-radius: 12px;
                                                                padding: 12px 8px;
                                                                text-align: center;
                                                                cursor: pointer;
                                                                transition: all 0.2s;
                                                            }
                                                            .payment-btn.active {
                                                                background: #e3f2fd;
                                                                border-color: #1976D2;
                                                            }
                                                            .payment-btn .icon { font-size: 1.4em; margin-bottom: 4px; }
                                                            .payment-btn .label { font-size: 0.85em; font-weight: 600; }
                                                            
                                                            .btn-save {
                                                                background: #11998e;
                                                                background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%);
                                                                color: white;
                                                                border: none;
                                                                width: 100%;
                                                                padding: 14px;
                                                                border-radius: 12px;
                                                                font-size: 1.1em;
                                                                font-weight: 700;
                                                                cursor: pointer;
                                                                box-shadow: 0 4px 12px rgba(56,239,125,0.3);
                                                                margin-top: 12px;
                                                            }
                                                            .btn-save:active { transform: scale(0.98); }
                                                            .remove-btn { color: #ff5252; border: none; background: none; font-size: 1.2em; cursor: pointer; }
                                                            #result { margin-top: 12px; font-size: 0.85em; text-align: center; color: #666; }
                                                        </style>
                                                    </head>
                                                    <body>
                                                        <div class="container">
                                                            <div class="header">
                                                                <h2>🛒 New Sale</h2>
                                                                <a href="/mobile" class="back-btn">← Back</a>
                                                            </div>

                                                            <div class="card">
                                                                <div class="section-title">👤 Customer</div>
                                                                <div class="input-group">
                                                                    <input id="customerPhone" list="customers" placeholder="Phone / Walk-in" autocomplete="off" />
                                                                    <datalist id="customers"></datalist>
                                                                    <div id="customerName" style="margin-top:6px; font-size:0.85em; color:#1976D2; font-weight:600;"></div>
                                                                </div>
                                                            </div>

                                                            <div class="card">
                                                                <div class="section-title">📦 Items</div>
                                                                <div class="table-container">
                                                                    <table>
                                                                        <thead>
                                                                            <tr>
                                                                                <th style="width:50%;">Product</th>
                                                                                <th style="width:20%; text-align:right;">Qty</th>
                                                                                <th style="width:30%; text-align:right;">Total</th>
                                                                                <th></th>
                                                                            </tr>
                                                                        </thead>
                                                                        <tbody id="lines"></tbody>
                                                                    </table>
                                                                </div>
                                                                <button class="btn-add" onclick="addLine()">+ Add Product</button>
                                                            </div>

                                                            <div class="card">
                                                                <div class="section-title">💳 Payment</div>
                                                                <div class="payment-grid">
                                                                    <div class="payment-btn" id="pay-CASH" onclick="selectMethod('CASH')">
                                                                        <div class="icon">💵</div>
                                                                        <div class="label">Cash</div>
                                                                    </div>
                                                                    <div class="payment-btn" id="pay-CREDIT" onclick="selectMethod('CREDIT')">
                                                                        <div class="icon">📝</div>
                                                                        <div class="label">Deo</div>
                                                                    </div>
                                                                    <div class="payment-btn" id="pay-MOBILE_BANKING" onclick="selectMethod('MOBILE_BANKING')">
                                                                        <div class="icon">📱</div>
                                                                        <div class="label">Mobile</div>
                                                                    </div>
                                                                    <div class="payment-btn" id="pay-CARD" onclick="selectMethod('CARD')">
                                                                        <div class="icon">💳</div>
                                                                        <div class="label">Card</div>
                                                                    </div>
                                                                </div>
                                                                
                                                                <div class="input-group">
                                                                    <label>Paid Amount</label>
                                                                    <input id="paid" type="number" step="0.01" value="0.00" oninput="onPaidChange()" />
                                                                </div>

                                                                <div class="summary">
                                                                    <div class="summary-row"><span>Subtotal</span><span id="subtotal">0.00</span></div>
                                                                    <div class="summary-row total"><span>Total</span><span id="total">0.00</span></div>
                                                                    <div class="summary-row" style="margin-top:10px; color:#666;"><span id="balanceLabel">Balance Due</span><span id="balance">0.00</span></div>
                                                                </div>
                                                            </div>

                                                            <button class="btn-save" onclick="saveSale()">Save & Share Invoice</button>
                                                            <div id="result"></div>
                                                        </div>

                                                        <datalist id="products"></datalist>

                                                        <script>
                                                            let products = [];
                                                            let customers = [];
                                                            let currentMethod = 'CASH';
                                                            let totalAmount = 0;

                                                            function money(n) {
                                                                return Number(n || 0).toFixed(2);
                                                            }

                                                            async function loadData() {
                                                                const [pResp, cResp] = await Promise.all([fetch('/products'), fetch('/customers')]);
                                                                if (pResp.status === 401 || cResp.status === 401) {
                                                                    window.location.href = '/mobile/login';
                                                                    return;
                                                                }
                                                                products = await pResp.json();
                                                                customers = await cResp.json();
                                                                
                                                                const pList = document.getElementById('products');
                                                                products.forEach(p => {
                                                                    const opt = document.createElement('option');
                                                                    opt.value = p.code;
                                                                    opt.label = p.description + ' (' + money(p.sellPrice) + ')';
                                                                    pList.appendChild(opt);
                                                                });

                                                                const cList = document.getElementById('customers');
                                                                customers.forEach(c => {
                                                                    const opt = document.createElement('option');
                                                                    opt.value = c.phone;
                                                                    opt.label = c.name;
                                                                    cList.appendChild(opt);
                                                                });
                                                            }

                                                            function updateCustomerName() {
                                                                const phone = document.getElementById('customerPhone').value.trim();
                                                                const c = customers.find(x => x.phone === phone);
                                                                document.getElementById('customerName').textContent = c ? 'Customer: ' + c.name : '';
                                                            }

                                                            function selectMethod(method) {
                                                                currentMethod = method;
                                                                document.querySelectorAll('.payment-btn').forEach(b => b.classList.remove('active'));
                                                                document.getElementById('pay-' + method).classList.add('active');
                                                                
                                                                if (method !== 'CREDIT') {
                                                                    document.getElementById('paid').value = money(totalAmount);
                                                                } else {
                                                                    document.getElementById('paid').value = "0.00";
                                                                }
                                                                updateBalance();
                                                            }

                                                            function onPaidChange() {
                                                                updateBalance();
                                                            }

                                                            function updateBalance() {
                                                                const paid = Number(document.getElementById('paid').value || 0);
                                                                const balance = totalAmount - paid;
                                                                const balEl = document.getElementById('balance');
                                                                const labelEl = document.getElementById('balanceLabel');
                                                                
                                                                if (balance >= 0) {
                                                                    labelEl.textContent = 'Balance Due';
                                                                    balEl.textContent = money(balance);
                                                                    balEl.style.color = balance > 0 ? '#E53935' : '#43A047';
                                                                } else {
                                                                    labelEl.textContent = 'Change';
                                                                    balEl.textContent = money(Math.abs(balance));
                                                                    balEl.style.color = '#43A047';
                                                                }
                                                            }

                                                            function recalc() {
                                                                let subtotal = 0;
                                                                document.querySelectorAll('tr[data-line]').forEach(tr => {
                                                                    const qty = Number(tr.querySelector('.qty').value || 0);
                                                                    const code = tr.querySelector('.code-input').value.trim();
                                                                    const p = products.find(x => x.code === code);
                                                                    const price = p ? p.sellPrice : 0;
                                                                    const lineTotal = qty * price;
                                                                    tr.querySelector('.line-total').textContent = money(lineTotal);
                                                                    subtotal += lineTotal;
                                                                });
                                                                totalAmount = subtotal;
                                                                document.getElementById('subtotal').textContent = money(subtotal);
                                                                document.getElementById('total').textContent = money(subtotal);
                                                                
                                                                if (currentMethod !== 'CREDIT') {
                                                                    document.getElementById('paid').value = money(subtotal);
                                                                }
                                                                updateBalance();
                                                            }

                                                            function addLine() {
                                                                const tbody = document.getElementById('lines');
                                                                const tr = document.createElement('tr');
                                                                tr.setAttribute('data-line', '1');
                                                                tr.innerHTML = `
                                                                    <td><input class="code-input" list="products" placeholder="Code" /></td>
                                                                    <td><input class="qty" type="number" value="1" style="text-align:right;" /></td>
                                                                    <td class="line-total" style="text-align:right; font-weight:600;">0.00</td>
                                                                    <td><button class="remove-btn" onclick="removeLine(this)">×</button></td>
                                                                `;
                                                                tbody.appendChild(tr);
                                                                tr.querySelector('.code-input').addEventListener('input', recalc);
                                                                tr.querySelector('.qty').addEventListener('input', recalc);
                                                                recalc();
                                                            }

                                                            function removeLine(btn) {
                                                                btn.closest('tr').remove();
                                                                recalc();
                                                            }

                                                            async function saveSale() {
                                                                const resEl = document.getElementById('result');
                                                                resEl.textContent = '⌛ Saving invoice...';
                                                                const lines = [];
                                                                document.querySelectorAll('tr[data-line]').forEach(tr => {
                                                                    const code = tr.querySelector('.code-input').value.trim();
                                                                    const qty = Number(tr.querySelector('.qty').value || 0);
                                                                    if (code && qty > 0) lines.push({ code, quantity: qty });
                                                                });

                                                                if (lines.length === 0) { resEl.textContent = '❌ Add items first!'; return; }

                                                                try {
                                                                    const resp = await fetch('/salesByCode', {
                                                                        method: 'POST',
                                                                        headers: { 'Content-Type': 'application/json' },
                                                                        body: JSON.stringify({
                                                                            customerPhone: document.getElementById('customerPhone').value.trim() || null,
                                                                            paid: Number(document.getElementById('paid').value || 0),
                                                                            method: currentMethod,
                                                                            lines: lines
                                                                        })
                                                                    });
                                                                    const data = await resp.json();
                                                                    if (!resp.ok) { throw new Error(data.error || 'Failed to save'); }
                                                                    window.location.href = '/mobile/invoice/' + data.id;
                                                                } catch (e) {
                                                                    resEl.textContent = '❌ Error: ' + e.message;
                                                                }
                                                            }

                                                            document.getElementById('customerPhone').addEventListener('input', updateCustomerName);
                                                            loadData().then(() => {
                                                                selectMethod('CASH');
                                                                addLine();
                                                            });
                                                        </script>
                                                    </body>
                                                    </html>
                                            """.trimIndent()
                                            call.respondText(html, ContentType.Text.Html)
                                }

                                get("/mobile/product") {
                                        val html = """
                                                <!doctype html>
                                                <html lang="en">
                                                <head>
                                                    <meta charset="utf-8" />
                                                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                                                    <title>Add/Update Product</title>
                                                </head>
                                                <body>
                                                    <p><a href="/mobile">← Back</a></p>
                                                    <h3>Add/Update Product</h3>
                                                    <div><label>Code</label><br /><input id="code" /></div>
                                                    <div style="margin-top:8px;"><label>Description</label><br /><input id="description" /></div>
                                                    <div style="margin-top:8px;"><label>UOM</label><br /><input id="uom" value="pcs" /></div>
                                                    <div style="margin-top:8px;"><label>Buy Price</label><br /><input id="buyPrice" value="0" /></div>
                                                    <div style="margin-top:8px;"><label>Sell Price</label><br /><input id="sellPrice" value="0" /></div>
                                                    <div style="margin-top:8px;"><label>Stock</label><br /><input id="stock" value="0" /></div>
                                                    <div style="margin-top:8px;"><label>Reorder Level</label><br /><input id="reorderLevel" value="0" /></div>
                                                    <div style="margin-top:8px;"><button onclick="saveProduct()">Save</button></div>
                                                    <pre id="result"></pre>
                                                    <script>
                                                        async function saveProduct() {
                                                            const resultEl = document.getElementById('result');
                                                            resultEl.textContent = 'Saving...';
                                                            const payload = {
                                                                code: document.getElementById('code').value.trim(),
                                                                description: document.getElementById('description').value.trim() || null,
                                                                uom: document.getElementById('uom').value.trim() || 'pcs',
                                                                buyPrice: Number(document.getElementById('buyPrice').value || '0'),
                                                                sellPrice: Number(document.getElementById('sellPrice').value || '0'),
                                                                defaultNumber: 0,
                                                                stock: Number(document.getElementById('stock').value || '0'),
                                                                reorderLevel: Number(document.getElementById('reorderLevel').value || '0')
                                                            };
                                                            if (!payload.code) {
                                                                resultEl.textContent = 'Code is required';
                                                                return;
                                                            }
                                                            try {
                                                                const resp = await fetch('/products', {
                                                                    method: 'POST',
                                                                    headers: { 'Content-Type': 'application/json' },
                                                                    body: JSON.stringify(payload)
                                                                });
                                                                const bodyText = await resp.text();
                                                                if (!resp.ok) {
                                                                    resultEl.textContent = 'Error: ' + bodyText;
                                                                    return;
                                                                }
                                                                resultEl.textContent = bodyText;
                                                            } catch (e) {
                                                                resultEl.textContent = 'Network error: ' + e;
                                                            }
                                                        }
                                                    </script>
                                                </body>
                                                </html>
                                        """.trimIndent()
                                        call.respondText(html, ContentType.Text.Html)
                                }

                                get("/mobile/invoice/{id}") {
                                        val id = call.parameters["id"]?.toLongOrNull()
                                        if (id == null) {
                                            call.respond(HttpStatusCode.BadRequest, "Invalid invoice id")
                                            return@get
                                        }

                                        val data = try {
                                            buildInvoiceData(id)
                                        } catch (e: Exception) {
                                            call.respond(HttpStatusCode.NotFound, e.message ?: "Invoice not found")
                                            return@get
                                        }

                                        val host = call.request.local.localAddress
                                        val port = call.request.local.localPort
                                        val baseUrl = "http://${host}:${port}"
                                        val invoiceUrl = "${baseUrl}/mobile/invoice/${id}"
                                        val pdfUrl = "${baseUrl}/invoice/${id}/pdf"
                                        val waText = URLEncoder.encode(
                                            "Invoice #${id}\nTotal ${String.format("%.2f", data.total)}\nPaid ${String.format("%.2f", data.paidAmount)}\nMethod ${data.paymentMethod}\n${invoiceUrl}\nPDF: ${pdfUrl}",
                                            "UTF-8"
                                        )
                                        val waUrl = "https://wa.me/?text=${waText}"

                                        val rows = data.items.joinToString("\n") { item ->
                                            val code = item.code.replace("<", "&lt;").replace(">", "&gt;")
                                            val desc = item.description.replace("<", "&lt;").replace(">", "&gt;")
                                            "<tr><td>${code}</td><td>${desc}</td><td style=\"text-align:right;\">${String.format("%.2f", item.qty)}</td><td style=\"text-align:right;\">${String.format("%.2f", item.price)}</td><td style=\"text-align:right;\">${String.format("%.2f", item.total)}</td></tr>"
                                        }

                                        val html = """
                                                <!doctype html>
                                                <html lang="en">
                                                <head>
                                                    <meta charset="utf-8" />
                                                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                                                    <title>Invoice #${id}</title>
                                                </head>
                                                <body>
                                                    <p><a href="/mobile">← Back</a></p>
                                                    <h3>Invoice #${id}</h3>
                                                    <div>
                                                        <div><b>Date:</b> ${data.date}</div>
                                                        <div><b>Customer:</b> ${data.customerName}</div>
                                                        <div><b>Phone:</b> ${data.customerPhone ?: "-"}</div>
                                                        <div><b>Payment:</b> ${data.paymentMethod}</div>
                                                    </div>

                                                    <div style="margin-top:10px;">
                                                        <table border="1" cellpadding="6" cellspacing="0" style="width:100%; max-width:800px;">
                                                            <thead>
                                                                <tr>
                                                                    <th style="text-align:left;">Code</th>
                                                                    <th style="text-align:left;">Description</th>
                                                                    <th style="text-align:right;">Qty</th>
                                                                    <th style="text-align:right;">Price</th>
                                                                    <th style="text-align:right;">Total</th>
                                                                </tr>
                                                            </thead>
                                                            <tbody>
                                                                ${rows}
                                                            </tbody>
                                                        </table>
                                                    </div>

                                                    <div style="margin-top:10px; max-width:800px;">
                                                        <div style="display:flex; justify-content:space-between;"><b>Total</b><b>${String.format("%.2f", data.total)}</b></div>
                                                        <div style="display:flex; justify-content:space-between;"><b>Paid</b><b>${String.format("%.2f", data.paidAmount)}</b></div>
                                                        <div style="display:flex; justify-content:space-between;"><b>Balance</b><b>${String.format("%.2f", (data.total - data.paidAmount))}</b></div>
                                                    </div>

                                                    <div style="margin-top:12px;">
                                                        <a href="/invoice/${id}/pdf">Download PDF</a>
                                                        <span> | </span>
                                                        <a href="${waUrl}" target="_blank" rel="noopener">Send on WhatsApp</a>
                                                    </div>
                                                </body>
                                                </html>
                                        """.trimIndent()

                                        call.respondText(html, ContentType.Text.Html)
                                }

                get("/products") {
                    call.respond(productRepo.list())
                }

                        get("/customers") {
                            call.respond(customerRepo.list())
                        }

                post("/products") {
                    val p = call.receive<pos.data.Product>()
                    val existing = if (p.id == null) productRepo.findByCode(p.code) else null
                    val saved = productRepo.upsert(if (existing != null) p.copy(id = existing.id) else p)
                    call.respond(saved)
                }

                post("/sales") {
                    val session = call.sessions.get<MobileSession>()!!
                    val req = call.receive<SaleRequest>()
                    val id = saleRepo.createSale(req.customerPhone, req.lines, req.paid, req.method, session.username)
                    call.respond(mapOf("id" to id))
                }

                post("/salesByCode") {
                    val session = call.sessions.get<MobileSession>()!!
                    val req = call.receive<SaleRequestByCode>()
                    if (req.lines.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Sale must have at least one line"))
                        return@post
                    }

                        val resolvedLines = try {
                            req.lines.map { line ->
                        val code = line.code.trim()
                        val qty = line.quantity
                        if (code.isBlank() || qty <= 0) {
                            throw IllegalArgumentException("Invalid line: code='$code', qty='$qty'")
                        }
                        val product = productRepo.findByCode(code)
                            ?: throw IllegalArgumentException("Product not found: $code")
                        val productId = product.id ?: throw IllegalStateException("Product has no id: $code")
                        SaleLine(itemId = productId, quantity = qty, price = product.sellPrice)
                        }
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid sale request")))
                            return@post
                        }

                    try {
                        val id = saleRepo.createSale(req.customerPhone, resolvedLines, req.paid, req.method, session.username)
                        call.respond(mapOf("id" to id))
                    } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Failed to create sale")))
                    }
                }

                    get("/invoice/{id}/pdf") {
                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null) {
                            call.respond(HttpStatusCode.BadRequest, "Invalid invoice id")
                            return@get
                        }

                        val data = try {
                            buildInvoiceData(id)
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.NotFound, e.message ?: "Invoice not found")
                            return@get
                        }

                        val outputPath = invoicePdfPath(id)
                        try {
                            PdfGenerator.generateInvoice(data, outputPath)
                            val file = File(outputPath)
                            if (!file.exists()) {
                                call.respond(HttpStatusCode.InternalServerError, "PDF generation failed")
                                return@get
                            }
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString()
                            )
                            call.respondFile(file)
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, e.message ?: "PDF generation error")
                        }
                    }

                get("/sales/today") {
                    call.respond(reportsRepo.dailySales())
                }
            }
        }
        server.start(false)
    }
}
