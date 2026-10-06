package app.ultraslim.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import app.ultraslim.UltraSlimApp

/**
 * Tiny factory for ViewModels that need the [UltraSlimApp] singletons.
 * Usage: `val vm: HomeViewModel = viewModel(factory = appViewModelFactory { HomeViewModel(it) })`
 */
fun <VM : ViewModel> appViewModelFactory(create: (UltraSlimApp) -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = checkNotNull(
                extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
            ) as UltraSlimApp
            return create(app) as T
        }
    }
