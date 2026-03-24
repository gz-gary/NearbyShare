package com.example.nearbyshare.services

object ServiceRegistry {
    private val services = mutableMapOf<Class<*>, ApplicationService>()

    fun <T : ApplicationService> registerService(serviceClass: Class<T>, service: T) {
        services[serviceClass] = service
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ApplicationService> getService(serviceClass: Class<T>): T {
        return services[serviceClass] as T
    }
}