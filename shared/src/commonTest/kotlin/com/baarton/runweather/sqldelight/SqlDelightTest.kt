package com.baarton.runweather.sqldelight

import com.baarton.runweather.db.PersistedWeather
import com.baarton.runweather.emptyLogger
import com.baarton.runweather.mock.BRNO1
import com.baarton.runweather.mock.BRNO2
import com.baarton.runweather.mock.BRNO3
import com.baarton.runweather.mock.BRNO4
import com.baarton.runweather.model.Height.Companion.mm
import com.baarton.runweather.model.Humidity.Companion.percent
import com.baarton.runweather.model.Pressure.Companion.hpa
import com.baarton.runweather.model.Temperature.Companion.kelvin
import com.baarton.runweather.model.weather.WeatherData
import com.baarton.runweather.model.weather.WeatherId.CLEAR_SKY
import com.baarton.runweather.model.weather.WeatherId.HEAVY_INTENSITY_RAIN
import com.baarton.runweather.model.weather.WeatherId.LIGHT_RAIN
import com.baarton.runweather.testDbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightTest {

    private lateinit var dbHelper: DatabaseHelper

    @BeforeTest
    fun setup() = runTest {
        dbHelper = DatabaseHelper(
            testDbConnection(),
            Dispatchers.Default,
            emptyLogger()
        )
        dbHelper.nuke()
        dbHelper.insert(BRNO1.data)
    }

    @Test
    fun `Select first from all items`() = runTest {
        val firstItem = dbHelper.getAll().first()
        assertNotNull(firstItem, "Could not retrieve Weather")

        with(firstItem) {
            assertTrue { locationName == "Brno1" }
            assertTrue { weatherList.size == 1 }
            assertTrue { weatherList[0].description == "clear sky" }
            assertTrue { weatherList[0].weatherId == CLEAR_SKY }
            assertTrue { weatherList[0].title == "Clear" }
            assertTrue { weatherList[0].iconId == "01d" }
            assertTrue { mainData.pressure == 1021.hpa }
            assertTrue { mainData.humidity == 45.percent }
            assertTrue { mainData.temperature == 265.90.kelvin }
            assertTrue { rain == WeatherData.Rain() }
            assertTrue { sys.sunrise == Instant.fromEpochSeconds(1646803774) }
            assertTrue { sys.sunset == Instant.fromEpochSeconds(1646844989) }
        }
    }

    @Test
    fun `Select first from all with Rain`() = runTest {
        dbHelper.insert(BRNO4.data)
        val firstItem = dbHelper.getAll().first()
        assertNotNull(firstItem, "Could not retrieve Weather")

        with(firstItem) {
            assertTrue { locationName == "Brno Rain" }
            assertTrue { weatherList.size == 2 }
            assertTrue { weatherList[0].description == "heavy rain" }
            assertTrue { weatherList[0].weatherId == HEAVY_INTENSITY_RAIN }
            assertTrue { weatherList[0].title == "Rain" }
            assertTrue { weatherList[0].iconId == "05d" }
            assertTrue { weatherList[1].description == "light rain" }
            assertTrue { weatherList[1].weatherId == LIGHT_RAIN }
            assertTrue { weatherList[1].title == "Light Rain" }
            assertTrue { weatherList[1].iconId == "08d" }
            assertTrue { mainData.pressure == 1020.hpa }
            assertTrue { mainData.humidity == 35.percent }
            assertTrue { mainData.temperature == 268.90.kelvin }
            assertTrue { rain.oneHour == 1.mm }
            assertTrue { rain.threeHour == 3.mm }
            assertTrue { sys.sunrise == Instant.fromEpochSeconds(1646800774) }
            assertTrue { sys.sunset == Instant.fromEpochSeconds(1646849989) }
        }
    }

    @Test
    fun `Delete all`() = runTest {
        dbHelper.insert(BRNO2.data)
        dbHelper.insert(BRNO3.data)

        assertEquals(
            dbHelper.getAll().first(),
            with(BRNO3.data) {
                PersistedWeather(
                    weatherList,
                    locationName,
                    mainData,
                    wind,
                    rain,
                    sys
                )
            })

        dbHelper.nuke()

        assertTrue(
            dbHelper.getAll().isEmpty(),
            "Delete All did not work"
        )
    }
}