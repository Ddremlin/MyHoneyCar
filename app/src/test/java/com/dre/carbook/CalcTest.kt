package com.dre.carbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CalcTest {
    private val d = LocalDate.of(2026, 1, 1)
    private fun fuel(km: Int, l: Double, full: Boolean = true, rub: Long = 3000_00) =
        Expense(carId = "c", date = d, category = Category.FUEL, amount = rub, mileage = km, liters = l, fullTank = full)

    @Test fun parseMoneyVariants() {
        assertEquals(123450L, parseMoney("1 234,5"))
        assertEquals(123400L, parseMoney("1234"))
        assertEquals(99L, parseMoney("0.99"))
        assertNull(parseMoney("abc"))
        assertNull(parseMoney("-5"))
        assertNull(parseMoney(""))
    }

    @Test fun moneyFormat() {
        assertEquals("1 234 ₽", money(123400))
        assertEquals("1 234,05 ₽", money(123405))
        assertEquals("1234,05", moneyInput(123405))
    }

    @Test fun fuelFullToFull() {
        val s = fuelStats(listOf(
            fuel(10_000, 40.0),            // старт
            fuel(10_300, 20.0, full = false),
            fuel(10_600, 25.0),            // 45 л на 600 км = 7.5
            fuel(11_100, 40.0),            // 40 л на 500 км = 8.0
        ))
        assertEquals(85.0 / 1100 * 100, s.avgPer100!!, 1e-9)
        assertEquals(8.0, s.lastPer100!!, 1e-9)
        assertEquals(125.0, s.totalLiters, 1e-9)
    }

    @Test fun fuelNotEnough() {
        assertNull(fuelStats(listOf(fuel(1000, 30.0))).avgPer100)
    }

    @Test fun serviceDue() {
        val today = LocalDate.of(2026, 6, 1)
        val s = ServiceItem(carId = "c", name = "Масло", intervalKm = 10_000, intervalMonths = 12, lastKm = 50_000,
            lastDate = LocalDate.of(2025, 9, 1))
        assertEquals(DueState.OK, serviceStatus(s, 55_000, today).state)
        assertEquals(DueState.SOON, serviceStatus(s, 59_500, today).state)
        assertEquals(DueState.OVERDUE, serviceStatus(s, 60_001, today).state)
        assertEquals(DueState.OVERDUE, serviceStatus(s, 51_000, LocalDate.of(2026, 9, 2)).state)
        assertEquals(DueState.UNKNOWN, serviceStatus(s.copy(lastKm = null, lastDate = null), 1, today).state)
    }

    @Test fun jsonRoundTrip() {
        val car = Car(brand = "Lada", model = "Vesta", generation = "NG (2022–)", plate = "А123ВС", year = 2018, odometer = 5000, tankLiters = 50,
            tripKm = 12.5, lastTripEnd = 1_700_000_000_000, obdSyncAt = 1_700_000_000_001, dtc = listOf("P0300", "P0171"),
            dtcAt = 5)
        val data = AppData(
            cars = listOf(car),
            expenses = listOf(fuel(5100, 33.5, full = false).copy(carId = car.id, note = "Лукойл \"92\"", photos = listOf("a.jpg", "b.jpg"))),
            services = defaultServices(car.id),
            selectedCarId = car.id,
        )
        assertEquals(data, Json.decode(Json.encode(data)))
    }

    @Test fun currentKmIncludesTrips() {
        val car = Car(brand = "x", odometer = 1000, tripKm = 55.7)
        assertEquals(1055, AppData(cars = listOf(car)).currentKm(car))
    }

    @Test fun summaryPerKm() {
        val s = summary(listOf(fuel(1000, 10.0, rub = 1000_00), fuel(2000, 10.0, rub = 1000_00)))
        assertEquals(1000, s.distance)
        assertEquals(2.0, s.perKm!!, 1e-9)
    }

    @Test fun catalogSuggests() {
        assertEquals("Toyota", Catalog.suggestBrands("тойо").first())
        assertEquals("Toyota", Catalog.suggestBrands("toy").first())
        assertTrue(Catalog.suggestModels("toyota", "cam").contains("Camry"))
        assertTrue(Catalog.suggestGenerations("Toyota", "Camry", "").any { it.startsWith("XV70") })
        assertTrue(Catalog.suggestModels("Lada", "").contains("2107"))
        assertTrue(Catalog.suggestModels("Неизвестная", "x").isEmpty())
        assertTrue(Catalog.brands.all { it.models.isNotEmpty() })
    }

    @Test fun legacyNameSplit() {
        assertEquals("Mercedes-Benz" to "E-Class", Catalog.splitName("Mercedes-Benz E-Class"))
        assertEquals("Land Rover" to "Discovery", Catalog.splitName("Land Rover Discovery"))
        assertEquals("Lada" to "Vesta", Catalog.splitName("лада Vesta"))
        assertEquals("Москвич-412" to "", Catalog.splitName("Москвич-412"))
        assertEquals("Foo" to "Bar Baz", Catalog.splitName("Foo Bar Baz"))
    }

    @Test fun migratesV1() {
        val v1 = """{"version":1,"selectedCarId":"c","cars":[{"id":"c","name":"Toyota Camry","odometer":1}],
            "expenses":[],"services":[{"id":"s","carId":"c","name":"ОСАГО","intervalMonths":12}]}"""
        val d = Json.decode(v1)
        assertEquals("Toyota", d.cars[0].brand)
        assertEquals("Camry", d.cars[0].model)
        assertEquals(listOf("Страховка", "Автокаско"), d.services.map { it.name })
        assertTrue(d.services.all { it.isInsurance() })
        // повторный разбор уже сохранённого v2 ничего не добавляет
        assertEquals(d, Json.decode(Json.encode(d)))
    }

    @Test fun currencyStoredAndFormatted() {
        val d = AppData(currency = Currency.EUR)
        assertEquals(Currency.EUR, Json.decode(Json.encode(d)).currency)
        assertEquals(Currency.RUB, Json.decode("""{"version":2}""").currency)
        val old = currencySymbol
        try {
            currencySymbol = Currency.USD.symbol
            assertEquals("1\u00a0234,50\u00a0$", money(123450))
        } finally { currencySymbol = old }
    }
}
