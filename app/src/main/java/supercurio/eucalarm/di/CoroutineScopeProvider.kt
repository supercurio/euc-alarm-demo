package supercurio.eucalarm.di

import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoroutineScopeProvider @Inject constructor() {

    private var _appScope: CoroutineScope? = null

    val app
        get() = _appScope ?: (CoroutineScope(Dispatchers.Default) + CoroutineName("AppScope"))
            .also { _appScope = it }

    fun cancelAll() {
        _appScope?.cancel()
        _appScope = null
    }

}
