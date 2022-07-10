package com.baarton.runweather.models

import co.touchlab.kermit.Logger
import com.baarton.runweather.db.CurrentWeather
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WeatherViewModel(
    private val weatherRepository: WeatherRepository,
    log: Logger
) : ViewModel() {
    private val log = log.withTag("WeatherViewModel")

    private val mutableWeatherState: MutableStateFlow<WeatherViewState> =
        MutableStateFlow(WeatherViewState(isLoading = true))

    val weatherState: StateFlow<WeatherViewState> = mutableWeatherState

    init {
        observeWeather()
    }

    override fun onCleared() {
        log.v("Clearing WeatherViewModel")
    }

    private fun observeWeather() {
        // Refresh breeds, and emit any exception that was thrown so we can handle it downstream
        val refreshFlow = flow<Throwable?> {
            try {
                weatherRepository.refreshWeatherIfStale()
                emit(null)
            } catch (exception: Exception) {
                emit(exception)
            }
        }

        viewModelScope.launch {
            combine(refreshFlow, weatherRepository.getWeather()) { throwable, weather -> throwable to weather }
                .collect { (error, weather) ->
                    mutableWeatherState.update { previousState ->
                        val errorMessage = if (error != null) {
                            "Unable to download weather list"
                        } else {
                            previousState.error
                        }
                        WeatherViewState(
                            isLoading = false,
                            weather = weather.takeIf { it.isNotEmpty() },
                            error = errorMessage.takeIf { weather.isEmpty() },
                            isEmpty = weather.isEmpty() && errorMessage == null
                        )
                    }
                }
        }
    }

    fun refreshWeather(): Job {
        // Set loading state, which will be cleared when the repository re-emits
        mutableWeatherState.update { it.copy(isLoading = true) }
        return viewModelScope.launch {
            log.v { "refreshWeather" }
            try {
                weatherRepository.refreshWeather()
            } catch (exception: Exception) {
                handleWeatherError(exception)
            }
        }
    }

    // fun updateBreedFavorite(breed: Breed): Job {
    //     return viewModelScope.launch {
    //         breedRepository.updateBreedFavorite(breed)
    //     }
    // }

    private fun handleWeatherError(throwable: Throwable) {
        log.e(throwable) { "Error downloading weather list" }
        mutableWeatherState.update {
            if (it.weather.isNullOrEmpty()) {
                WeatherViewState(error = "Unable to refresh weather list")
            } else {
                // Just let it fail silently if we have a cache
                it.copy(isLoading = false)
            }
        }
    }
}

data class WeatherViewState(
    val weather: List<CurrentWeather>? = null,
    val error: String? = null,
    val isLoading: Boolean = false,
    val isEmpty: Boolean = false
)
