package com.example.meupdf.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meupdf.cloud.CloudApi
import kotlinx.coroutines.launch

class LoginViewModel(private val cloudApi: CloudApi) : ViewModel() {

    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun login(onSuccess: () -> Unit) {
        if (email.isBlank() || password.isBlank()) {
            error = "Enter your email and password."
            return
        }
        loading = true
        error = null
        viewModelScope.launch {
            cloudApi.login(email.trim(), password)
                .onSuccess { onSuccess() }
                .onFailure { error = it.message ?: "Falha no login." }
            loading = false
        }
    }

    class Factory(private val cloudApi: CloudApi) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(cloudApi) as T
    }
}
