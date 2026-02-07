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
import io.ktor.http.*
import pos.data.CustomerRepository
import pos.data.DatabaseFactory
import pos.data.ProductRepository
import pos.data.SaleLine
import pos.data.SaleRepository
import pos.data.ReportsRepository
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

object SyncServer {
    private val productRepo = ProductRepository()
    private val saleRepo = SaleRepository()
    private val reportsRepo = ReportsRepository()
    private val customerRepo = CustomerRepository()

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
            routing {
                get("/health") {
                    call.respondText("OK")
                }

                                get("/") {
                                        call.respondRedirect("/mobile", permanent = false)
                                }

                                get("/mobile") {
                                        val html = """
                                                <!doctype html>
                                                <html lang="en">
                                                <head>
                                                    <meta charset="utf-8" />
                                                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                                                    <title>POS Mobile</title>
                                                </head>
                                                <body>
                                                    <h2>POS Mobile</h2>
                                                    <p>
                                                        <a href="/mobile/products">Products</a> |
                                                        <a href="/mobile/product">Add/Update Product</a> |
                                                        <a href="/mobile/sale">New Sale</a> |
                                                        <a href="/mobile/invoices">Today Invoices</a>
                                                    </p>
                                                    <p>Tip: Bookmark this page on your phone.</p>
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
                                        val sales = reportsRepo.dailySales()
                                        val rows = sales.joinToString("\n") { s ->
                                                val cust = (s.customerName ?: "Walk-in").replace("<", "&lt;").replace(">", "&gt;")
                                                "<tr><td>#${s.id}</td><td>${cust}</td><td>${"%.2f".format(s.total)}</td><td>${"%.2f".format(s.paid)}</td><td>${s.createdAt}</td></tr>"
                                        }
                                        val html = """
                                                <!doctype html>
                                                <html lang="en">
                                                <head>
                                                    <meta charset="utf-8" />
                                                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                                                    <title>Today Invoices</title>
                                                </head>
                                                <body>
                                                    <p><a href="/mobile">← Back</a></p>
                                                    <h3>Today Invoices</h3>
                                                    <table border="1" cellpadding="6" cellspacing="0">
                                                        <thead><tr><th>ID</th><th>Customer</th><th>Total</th><th>Paid</th><th>Time</th></tr></thead>
                                                        <tbody>
                                                            ${rows}
                                                        </tbody>
                                                    </table>
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
                                                        <title>Sales Invoice</title>
                                                    </head>
                                                    <body>
                                                        <p><a href="/mobile">← Back</a></p>
                                                        <h3>Sales Invoice</h3>

                                                        <div>
                                                            <label>Customer (Phone)</label><br />
                                                            <input id="customerPhone" list="customers" placeholder="Walk-in" />
                                                            <datalist id="customers"></datalist>
                                                            <div id="customerName" style="margin-top:4px;"></div>
                                                        </div>

                                                        <div style="margin-top:10px;">
                                                            <table border="1" cellpadding="6" cellspacing="0" style="width:100%; max-width:700px;">
                                                                <thead>
                                                                    <tr>
                                                                        <th style="text-align:left;">Product</th>
                                                                        <th style="text-align:right;">Qty</th>
                                                                        <th style="text-align:right;">Price</th>
                                                                        <th style="text-align:right;">Total</th>
                                                                        <th></th>
                                                                    </tr>
                                                                </thead>
                                                                <tbody id="lines"></tbody>
                                                            </table>
                                                            <button style="margin-top:8px;" onclick="addLine()">+ Add Item</button>
                                                        </div>

                                                        <div style="margin-top:10px; max-width:700px;">
                                                            <div style="display:flex; justify-content:space-between;"><b>Subtotal</b><b id="subtotal">0.00</b></div>
                                                            <div style="display:flex; justify-content:space-between;"><b>Total</b><b id="total">0.00</b></div>
                                                        </div>

                                                        <div style="margin-top:10px;">
                                                            <label>Paid Amount</label><br />
                                                            <input id="paid" value="0" />
                                                        </div>

                                                        <div style="margin-top:12px;">
                                                            <button onclick="saveSale()">Save Invoice</button>
                                                        </div>

                                                        <pre id="result"></pre>
                                                        <datalist id="products"></datalist>

                                                        <script>
                                                            let products = [];
                                                            let customers = [];

                                                            function money(n) {
                                                                const v = Number(n || 0);
                                                                return v.toFixed(2);
                                                            }

                                                            async function loadData() {
                                                                const prodResp = await fetch('/products');
                                                                products = await prodResp.json();
                                                                const prodList = document.getElementById('products');
                                                                prodList.innerHTML = '';
                                                                products.forEach(p => {
                                                                    const opt = document.createElement('option');
                                                                    opt.value = p.code;
                                                                    opt.label = (p.description || '') + ' (Stock ' + money(p.stock) + ')';
                                                                    prodList.appendChild(opt);
                                                                });

                                                                const custResp = await fetch('/customers');
                                                                customers = await custResp.json();
                                                                const custList = document.getElementById('customers');
                                                                custList.innerHTML = '';
                                                                customers.forEach(c => {
                                                                    const opt = document.createElement('option');
                                                                    opt.value = c.phone;
                                                                    opt.label = c.name;
                                                                    custList.appendChild(opt);
                                                                });
                                                            }

                                                            function findProduct(code) {
                                                                return products.find(p => (p.code || '').toLowerCase() === (code || '').toLowerCase());
                                                            }

                                                            function updateCustomerName() {
                                                                const phone = document.getElementById('customerPhone').value.trim();
                                                                const el = document.getElementById('customerName');
                                                                const c = customers.find(x => x.phone === phone);
                                                                el.textContent = c ? ('Name: ' + c.name) : (phone ? 'Name: (not found)' : '');
                                                            }

                                                            function recalc() {
                                                                let subtotal = 0;
                                                                document.querySelectorAll('tr[data-line]').forEach(tr => {
                                                                    const qty = Number(tr.querySelector('input[data-qty]').value || '0');
                                                                    const price = Number(tr.querySelector('input[data-price]').value || '0');
                                                                    const lineTotal = qty * price;
                                                                    tr.querySelector('td[data-total]').textContent = money(lineTotal);
                                                                    subtotal += lineTotal;
                                                                });
                                                                document.getElementById('subtotal').textContent = money(subtotal);
                                                                document.getElementById('total').textContent = money(subtotal);
                                                            }

                                                            function onCodeChanged(tr) {
                                                                const code = tr.querySelector('input[data-code]').value.trim();
                                                                const p = findProduct(code);
                                                                if (p) {
                                                                    tr.querySelector('input[data-price]').value = money(p.sellPrice);
                                                                    if (!tr.querySelector('input[data-qty]').value) {
                                                                        tr.querySelector('input[data-qty]').value = '1';
                                                                    }
                                                                }
                                                                recalc();
                                                            }

                                                            function addLine() {
                                                                const tbody = document.getElementById('lines');
                                                                const tr = document.createElement('tr');
                                                                tr.setAttribute('data-line', '1');
                                                                tr.innerHTML = `
                                                                    <td><input data-code list="products" placeholder="Code" style="width:120px;" /></td>
                                                                    <td style="text-align:right;"><input data-qty value="1" style="width:60px; text-align:right;" /></td>
                                                                    <td style="text-align:right;"><input data-price value="0" style="width:80px; text-align:right;" /></td>
                                                                    <td data-total style="text-align:right;">0.00</td>
                                                                    <td><button onclick="removeLine(this)">x</button></td>
                                                                `;
                                                                tbody.appendChild(tr);
                                                                tr.querySelector('input[data-code]').addEventListener('input', () => onCodeChanged(tr));
                                                                tr.querySelector('input[data-qty]').addEventListener('input', recalc);
                                                                tr.querySelector('input[data-price]').addEventListener('input', recalc);
                                                                recalc();
                                                            }

                                                            function removeLine(btn) {
                                                                const tr = btn.closest('tr');
                                                                tr.parentNode.removeChild(tr);
                                                                recalc();
                                                            }

                                                            async function saveSale() {
                                                                const resultEl = document.getElementById('result');
                                                                resultEl.textContent = 'Saving...';
                                                                const customerPhone = document.getElementById('customerPhone').value.trim();
                                                                const paid = Number(document.getElementById('paid').value || '0');
                                                                const lines = [];

                                                                document.querySelectorAll('tr[data-line]').forEach(tr => {
                                                                    const code = tr.querySelector('input[data-code]').value.trim();
                                                                    const qty = Number(tr.querySelector('input[data-qty]').value || '0');
                                                                    if (!code) return;
                                                                    if (qty <= 0) return;
                                                                    lines.push({ code: code, quantity: qty });
                                                                });

                                                                if (lines.length === 0) {
                                                                    resultEl.textContent = 'Add at least one item.';
                                                                    return;
                                                                }

                                                                try {
                                                                    const resp = await fetch('/salesByCode', {
                                                                        method: 'POST',
                                                                        headers: { 'Content-Type': 'application/json' },
                                                                        body: JSON.stringify({ customerPhone: customerPhone || null, paid: paid, method: 'CASH', lines: lines })
                                                                    });

                                                                    let body = null;
                                                                    const text = await resp.text();
                                                                    try { body = JSON.parse(text); } catch (_) { body = null; }

                                                                    if (!resp.ok) {
                                                                        resultEl.textContent = 'Error: ' + (body && body.error ? body.error : text);
                                                                        return;
                                                                    }
                                                                    const id = body && body.id ? body.id : null;
                                                                    if (!id) {
                                                                        resultEl.textContent = 'Saved but no invoice id returned: ' + text;
                                                                        return;
                                                                    }
                                                                    window.location.href = '/mobile/invoice/' + id;
                                                                } catch (e) {
                                                                    resultEl.textContent = 'Network error: ' + e;
                                                                }
                                                            }

                                                            document.getElementById('customerPhone').addEventListener('input', updateCustomerName);
                                                            loadData().then(() => {
                                                                addLine();
                                                                updateCustomerName();
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
                                            "Invoice #${id} Total ${String.format("%.2f", data.total)}\n${invoiceUrl}\nPDF: ${pdfUrl}",
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
                    val req = call.receive<SaleRequest>()
                    val id = saleRepo.createSale(req.customerPhone, req.lines, req.paid, req.method)
                    call.respond(mapOf("id" to id))
                }

                post("/salesByCode") {
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
                        val id = saleRepo.createSale(req.customerPhone, resolvedLines, req.paid, req.method)
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
