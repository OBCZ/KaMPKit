package com.baarton.runweather.model

import app.cash.turbine.FlowTurbine
import app.cash.turbine.test
import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig
import com.baarton.runweather.Config
import com.baarton.runweather.TestConfig
import com.baarton.runweather.db.PersistedWeather
import com.baarton.runweather.mock.BRNO1
import com.baarton.runweather.mock.BRNO2
import com.baarton.runweather.mock.CORRUPT
import com.baarton.runweather.mock.ClockMock
import com.baarton.runweather.mock.WeatherApiMock
import com.baarton.runweather.models.WeatherViewModel
import com.baarton.runweather.models.WeatherViewState
import com.baarton.runweather.models.weather.Weather
import com.baarton.runweather.models.weather.WeatherData
import com.baarton.runweather.repo.WeatherRepository
import com.baarton.runweather.sqldelight.DatabaseHelper
import com.baarton.runweather.testDbConnection
import com.russhwolf.settings.MockSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WeatherViewModelTest {

    private var logger = Logger(StaticConfig())
    private var testDbConnection = testDbConnection()
    private var dbHelper = DatabaseHelper(testDbConnection, logger, Dispatchers.Default)
    private var dataTimestamp: Instant? = null

    private val settingsMock = MockSettings()
    private val apiMock = WeatherApiMock()
    private val clockMock = ClockMock()
    private val testConfig: Config = TestConfig
    private val repository: WeatherRepository = WeatherRepository(dbHelper, settingsMock, testConfig, apiMock, logger, clockMock)

    private val viewModel by lazy { WeatherViewModel(testConfig, repository, clockMock, logger) }

    companion object {

        private val weatherSuccessStateBrno1 = WeatherViewState(
            weather = PersistedWeather(
                listOf(Weather("800", "Clear", "clear sky", "01d")),
                "Brno1",
                WeatherData.MainData("265.90", "1021", "45"),
                WeatherData.Wind("4.6", "345"),
                null,
                WeatherData.Sys("1646803774", "1646844989")
            )
        )

        private val weatherSuccessStateBrno2 = WeatherViewState(
            weather = PersistedWeather(
                listOf(Weather("800", "Clear", "clear sky", "01d")),
                "Brno2",
                WeatherData.MainData("260.90", "1025", "55"),
                WeatherData.Wind("4.7", "355"),
                null,
                WeatherData.Sys("1646806774", "1646842989")
            )
        )

    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        setDataAge(Clock.System.now() - 2.hours)
        clockMock.mockClock(Clock.System.now())
    }

    @AfterTest
    fun tearDown() {
        dataTimestamp = null
        clockMock.mockClock(null)
        apiMock.reset()

        runBlocking {
            dbHelper.nuke()
            testDbConnection.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Get weather without cache`() = runBlocking {
        apiMock.prepareResults(BRNO1.data)

        viewModel.weatherState.test {
            assertEquals(
                weatherSuccessStateBrno1.copy(lastUpdated = 0.seconds),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            cancel()
        }
    }

    @Test
    fun `Get weather from DB and update from API`() = runBlocking {
        apiMock.prepareResults(BRNO2.data)
        dbHelper.insert(BRNO1.data)
        setDataAge(Clock.System.now() - 1.seconds)

        assertEquals(0, apiMock.calledCount)

        viewModel.weatherState.test(2000) {
            assertEquals(
                weatherSuccessStateBrno1.copy(lastUpdated = calculateMockedTimestamp()),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            assertEquals(0, apiMock.calledCount)

            setDataAge(Clock.System.now() - 2.hours)

            assertEquals(
                weatherSuccessStateBrno2.copy(lastUpdated = 0.seconds),
                awaitItemAfterLast(
                    weatherSuccessStateBrno1.copy(
                        lastUpdated = calculateMockedTimestamp(),
                        isLoading = true
                    )
                )
            )
            assertEquals(1, apiMock.calledCount)
            cancel()
        }
    }

    @Test
    fun `Get weather from API and refresh from API`() = runBlocking {
        apiMock.prepareResults(BRNO1.data, BRNO2.data)

        viewModel.weatherState.test(2000) {
            assertEquals(
                weatherSuccessStateBrno1.copy(lastUpdated = 0.seconds),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            assertEquals(1, apiMock.calledCount)

            setDataAge(Clock.System.now() - 2.hours)

            assertEquals(
                weatherSuccessStateBrno2.copy(lastUpdated = 0.seconds),
                awaitItemAfter(
                    weatherSuccessStateBrno1.copy(
                        lastUpdated = 0.seconds,
                        isLoading = true
                    )
                )
            )
            assertEquals(2, apiMock.calledCount)
            cancel()
        }
    }

    @Test
    fun `Get weather from API and don't refresh from API`() = runBlocking {
        apiMock.prepareResults(BRNO1.data, BRNO2.data)

        viewModel.weatherState.test {
            assertEquals(
                weatherSuccessStateBrno1.copy(lastUpdated = 0.seconds),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            assertEquals(1, apiMock.calledCount)
            expectNoEvents()
            cancel()
        }
    }

    @Test
    fun `Show Data Error on initial run with no connection`() = runBlocking {
        apiMock.prepareResults(RuntimeException("Test error"))

        viewModel.weatherState.test {
            assertEquals(
                WeatherViewState(error = WeatherViewState.ErrorType.DATA_PROVIDER),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            cancel()
        }
    }

    @Test
    fun `Show API Error with outdated cache`() = runBlocking {
        dbHelper.insert(BRNO1.data)
        apiMock.prepareResults(RuntimeException("Test error"))

        viewModel.weatherState.test {
            assertEquals(
                WeatherViewState(error = WeatherViewState.ErrorType.DATA_PROVIDER),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            assertEquals(0, apiMock.calledCount)
            cancel()
        }
    }

    @Test
    fun `Show API Error with outdated DB and then refresh from API`() = runBlocking {
        dbHelper.insert(BRNO1.data)
        apiMock.prepareResults(RuntimeException("Test error"), BRNO2.data)

        viewModel.weatherState.test(2000) {
            assertEquals(
                WeatherViewState(error = WeatherViewState.ErrorType.DATA_PROVIDER),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            assertEquals(0, apiMock.calledCount)

            assertEquals(
                weatherSuccessStateBrno2.copy(lastUpdated = 0.seconds),
                awaitItemAfter(
                    WeatherViewState(
                        error = WeatherViewState.ErrorType.DATA_PROVIDER,
                        isLoading = true
                    )
                )
            )
            assertEquals(1, apiMock.calledCount)
            cancel()
        }
    }

    @Test
    fun `Ignore API Error on refresh with up-to-date cache`() = runBlocking {
        dbHelper.insert(BRNO1.data)
        apiMock.prepareResults(RuntimeException("Test error"), BRNO2.data)
        setDataAge(Clock.System.now() - 1.seconds)

        viewModel.weatherState.test {
            assertEquals(
                weatherSuccessStateBrno1.copy(lastUpdated = calculateMockedTimestamp()),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            assertEquals(0, apiMock.calledCount)
            cancel()
        }
    }

    @Test
    fun `Show Data Error on corrupt data from DB`() = runBlocking {
        dbHelper.insert(CORRUPT.data)
        setDataAge(Clock.System.now() - 1.seconds)

        viewModel.weatherState.test {
            assertEquals(
                WeatherViewState(error = WeatherViewState.ErrorType.DATA_CONSISTENCY),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            cancel()
        }
    }

    @Test
    fun `Show Data Error on corrupt data from DB and then refresh API`() = runBlocking {
        dbHelper.insert(CORRUPT.data)
        apiMock.prepareResults(BRNO2.data)
        setDataAge(Clock.System.now() - 1.seconds)

        assertEquals(0, apiMock.calledCount)

        viewModel.weatherState.test(2000) {
            assertEquals(
                WeatherViewState(error = WeatherViewState.ErrorType.DATA_CONSISTENCY),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )

            assertEquals(0, apiMock.calledCount)

            setDataAge(Clock.System.now() - 2.hours)

            assertEquals(
                weatherSuccessStateBrno2.copy(lastUpdated = 0.seconds),
                awaitItemAfter(
                    WeatherViewState(
                        error = WeatherViewState.ErrorType.DATA_CONSISTENCY,
                        isLoading = true
                    )
                )
            )
            assertEquals(1, apiMock.calledCount)
            cancel()
        }
    }

    @Test
    fun `Show Data Error on corrupt data from API`() = runBlocking {
        apiMock.prepareResults(CORRUPT.data)

        viewModel.weatherState.test {
            assertEquals(
                WeatherViewState(error = WeatherViewState.ErrorType.DATA_CONSISTENCY),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )
            cancel()
        }
    }

    @Test
    fun `Show correct data and refresh from API with corrupt data`() = runBlocking {
        apiMock.prepareResults(BRNO2.data, CORRUPT.data)

        assertEquals(0, apiMock.calledCount)

        viewModel.weatherState.test(2000) {
            assertEquals(
                weatherSuccessStateBrno2.copy(lastUpdated = 0.seconds),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )

            assertEquals(1, apiMock.calledCount)

            setDataAge(Clock.System.now() - 2.hours)

            assertEquals(
                WeatherViewState(error = WeatherViewState.ErrorType.DATA_CONSISTENCY),
                awaitItemAfterLast(weatherSuccessStateBrno2.copy(isLoading = true))
            )

            assertEquals(2, apiMock.calledCount)
            cancel()
        }
    }

    @Test
    fun `Show correct data and refresh from API with error`() = runBlocking {
        apiMock.prepareResults(BRNO2.data, RuntimeException("Test error"))

        assertEquals(0, apiMock.calledCount)

        viewModel.weatherState.test(2000) {
            assertEquals(
                weatherSuccessStateBrno2.copy(lastUpdated = 0.seconds),
                awaitItemAfter(WeatherViewState(isLoading = true))
            )

            assertEquals(1, apiMock.calledCount)

            setDataAge(Clock.System.now() - 2.hours)

            assertEquals(
                WeatherViewState(error = WeatherViewState.ErrorType.DATA_PROVIDER),
                awaitItemAfterLast(weatherSuccessStateBrno2.copy(isLoading = true))
            )

            assertEquals(1, apiMock.calledCount)
            cancel()
        }
    }

    private fun setDataAge(instant: Instant) {
        dataTimestamp = instant
        settingsMock.putLong(WeatherRepository.DB_TIMESTAMP_KEY, instant.toEpochMilliseconds())
    }

    private fun calculateMockedTimestamp(): Duration {
        return (clockMock.getMockedClock()!!
            .toEpochMilliseconds() - dataTimestamp!!.toEpochMilliseconds()).milliseconds
    }

    private suspend fun FlowTurbine<WeatherViewState>.awaitItemAfter(vararg items: WeatherViewState): WeatherViewState {
        var nextItem = awaitItem()
        for (item in items) {
            if (item == nextItem) {
                nextItem = awaitItem()
            }
        }
        return nextItem
    }

    private suspend fun FlowTurbine<WeatherViewState>.awaitItemAfterLast(item: WeatherViewState): WeatherViewState {
        val nextItem = awaitItem()
        if (item == nextItem) {
            awaitItemAfterLast(nextItem)
        }
        return awaitItem()
    }
}