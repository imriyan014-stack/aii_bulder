package com.example.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CodingSession(
    val id: String,
    val title: String,
    val fileName: String,
    val language: String,
    val code: String,
    val savedPath: String = "",
    val isSaved: Boolean = false
)

/** Drives the full-screen "Argon 4 live typing" page. */
object CodingSessionBus {
    private val _session = MutableStateFlow<CodingSession?>(null)
    val session: StateFlow<CodingSession?> = _session.asStateFlow()

    fun start(s: CodingSession) { _session.value = s }
    fun markSaved(path: String) { _session.value = _session.value?.copy(savedPath = path, isSaved = true) }
    fun dismiss() { _session.value = null }
}
