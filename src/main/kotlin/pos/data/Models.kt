package pos.data

data class Item(
    val id: Long? = null,
    val name: String,
    val sku: String,
    val price: Double,
    val stock: Double = 0.0,
    val unit: String = "pcs",
    val reorderLevel: Double = 0.0
)

data class Customer(
    val phone: String, // used as ID
    val name: String,
    val address: String? = null,
    val dob: String? = null,
    val email: String? = null,
    val status: String = "Active",
    val creditLimit: Double = 0.0
)

data class SaleSummary(
    val id: Long,
    val customerName: String?,
    val total: Double,
    val paid: Double,
    val balance: Double,
    val createdAt: String
)

data class SaleLine(
    val itemId: Long,
    val quantity: Double,
    val price: Double
)

data class Sale(
    val id: Long? = null,
    val customerId: Long? = null,
    val total: Double,
    val paid: Double
)

data class Product(
    val id: Long? = null,
    val code: String,
    val description: String? = null,
    val uom: String = "pcs",
    val buyPrice: Double = 0.0,
    val sellPrice: Double = 0.0,
    val defaultNumber: Double = 0.0,
    val stock: Double = 0.0,
    val reorderLevel: Double = 0.0
)
