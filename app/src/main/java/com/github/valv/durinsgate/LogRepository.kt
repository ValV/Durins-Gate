package com.github.valv.durinsgate

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRepository {
    private val _logs = MutableSharedFlow<String>(replay = 50)
    val logs = _logs.asSharedFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    suspend fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        _logs.emit("[$timestamp] $message")
    }
}
