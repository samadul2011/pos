# POS Desktop (Kotlin + Compose + SQLite)

Local-first POS desktop app scaffold using Kotlin, Compose Desktop, and SQLite. Future Android support can reuse Kotlin code and sync over Wi-Fi.

## Features in this scaffold
- SQLite schema for items, customers, sales, sale lines, and payments.
- Basic repositories for items, customers, and a daily sales report.
- Compose Desktop UI with Dashboard, Inventory, Customers, and Reports placeholders.

## Prerequisites
- JDK 17+
- Gradle 8.x (or use the provided Gradle wrapper once generated)

## Run
```bash
gradle run
```
If you do not have Gradle installed, run `gradle wrapper` once, then use `./gradlew run` (or `gradlew.bat` on Windows).

## Next steps
- Flesh out POS flow: cart, payments, receipts, stock deductions.
- Add more reports (date range, customer-wise, item-wise, cash summary).
- Add role-based access and audit logging.
- Prepare Ktor/REST sync endpoint for Android client.

## Local mobile testing (quick)

1. The app starts a local sync server on port `8080` by default. Ensure the desktop app is running (`./gradlew.bat run`).
2. Find your PC IP address (for Windows run `ipconfig` and look for the IPv4 address on your Wi-Fi adapter, e.g. `192.168.1.42`).
3. From your mobile (connected to the same Wi‑Fi), call endpoints like:

```bash
curl http://<PC_IP>:8080/products
```

4. Example POST to create a product (use Postman or curl):

```bash
curl -X POST http://<PC_IP>:8080/products \
	-H "Content-Type: application/json" \
	-d '{"code":"P001","description":"Sample","uom":"pcs","buyPrice":5.0,"sellPrice":10.0,"defaultNumber":1.0,"stock":10.0,"reorderLevel":2.0}'
```

5. To POST a sale:

```bash
curl -X POST http://<PC_IP>:8080/sales \
	-H "Content-Type: application/json" \
	-d '{"customerId":null,"lines":[{"itemId":1,"quantity":1.0,"price":10.0}],"paid":10.0,"method":"CASH"}'
```

Note: For production or broader testing add authentication and restrict access; this server is a minimal sync API for local testing.
