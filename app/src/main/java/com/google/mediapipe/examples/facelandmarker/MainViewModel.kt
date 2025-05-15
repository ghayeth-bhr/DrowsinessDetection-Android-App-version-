package com.google.mediapipe.examples.facelandmarker

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow // Use StateFlow for exposing
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {
    private val _blinkCount = MutableStateFlow(0)
    private val _yawnCount = MutableStateFlow(0)
    private val _drowsinessAlerts = MutableStateFlow(0)

    // _drowsinessAlerts removed as alerts are now handled by time duration

    val blinkCount: StateFlow<Int> = _blinkCount.asStateFlow()
    val yawnCount: StateFlow<Int> = _yawnCount.asStateFlow()
    val drowsinessAlerts: StateFlow<Int> = _drowsinessAlerts.asStateFlow()


    fun incrementBlinkCount() {
        _blinkCount.value++
    }

    fun incrementYawnCount() {
        _yawnCount.value++
    }



}