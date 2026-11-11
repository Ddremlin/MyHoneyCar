package com.dre.carbook

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AiTest {
    private val sensor = SensorStat(0x05, "Температура ОЖ", "°C", 20.0, 61.5, 90.0, 90.0, 120, 0, 600_000)

    @Test fun promptHasDataButNoVinOrPlate() {
        val car = Car(id = "c", brand = "Toyota", model = "Camry", year = 2018, vin = "JTNB11HK103000001", plate = "А123ВС77",
            odometer = 120_000, dtc = listOf("P0171", "P0420"), dtcAt = 0, mil = true,
            sensors = listOf(sensor, SensorStat(-1, "Напряжение бортсети (адаптер)", "В", 13.9, 14.1, 14.3, 14.2, 50, 0, 600_000)),
            sensorsAt = 600_000)
        val d = AppData(cars = listOf(car), services = listOf(
            ServiceItem(carId = "c", name = "Свечи зажигания", intervalKm = 30_000, lastKm = 80_000),
            ServiceItem(carId = "c", name = INSURANCE_NAME, intervalMonths = 12),
        ), expenses = listOf(
            Expense(carId = "c", date = LocalDate.of(2026, 5, 1), category = Category.REPAIR, amount = 100, mileage = 115_000, note = "Замена лямбда-зонда"),
        ), selectedCarId = "c")
        val p = Ai.buildPrompt(d, car, LocalDate.of(2026, 11, 10), ZoneOffset.UTC)
        assertTrue(p.contains("Toyota Camry"))
        assertTrue(p.contains("Пробег: 120000 км"))
        assertTrue(p.contains("P0171, P0420"))
        assertTrue(p.contains("Check Engine: горит"))
        assertTrue(p, p.contains("Температура ОЖ [05]: 20 / 61.5 / 90 / 90 °C, n=120"))
        assertTrue(p, p.contains("Напряжение бортсети (адаптер): 13.9 / 14.1 / 14.3 / 14.2 В"))
        assertTrue(p.contains("Свечи зажигания") && p.contains("ПРОСРОЧЕНО"))
        assertTrue(p.contains("Замена лямбда-зонда"))
        assertFalse(p.contains(INSURANCE_NAME))
        assertFalse(p.contains(car.vin))
        assertFalse(p.contains(car.plate))
    }

    @Test fun sensorsRoundTripAndValidation() {
        val a = Json.encodeSensors(listOf(sensor))
        a.put(JSONObject().put("pid", 0x0C).put("name", "bad").put("min", Double.NaN.toString()).put("avg", 1).put("max", 1).put("last", 1))
        a.put(JSONObject().put("pid", 999).put("name", "bad pid").put("min", 1).put("avg", 1).put("max", 1).put("last", 1))
        a.put(JSONObject().put("pid", 0x0D).put("name", "").put("min", 1).put("avg", 1).put("max", 1).put("last", 1))
        val back = Json.decodeSensors(JSONArray(a.toString()))
        assertEquals(listOf(sensor), back)
    }

    @Test fun carWithSensorsSurvivesJson() {
        val car = Car(id = "c", brand = "Lada", sensors = listOf(sensor), sensorsAt = 5, mil = false, aiReport = "Всё хорошо", aiReportAt = 7)
        val back = Json.decode(Json.encode(AppData(cars = listOf(car)))).cars.single()
        assertEquals(car, back)
    }
}
