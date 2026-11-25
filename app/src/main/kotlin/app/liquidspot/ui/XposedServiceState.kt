package app.liquidspot.ui

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object XposedServiceState : XposedServiceHelper.OnServiceListener {
    private val _service = MutableStateFlow<XposedService?>(null)
    val service: StateFlow<XposedService?> = _service.asStateFlow()

    private var registered = false

    @Synchronized
    fun init() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        _service.value = service
    }

    override fun onServiceDied(service: XposedService) {
        _service.compareAndSet(service, null)
    }
}
