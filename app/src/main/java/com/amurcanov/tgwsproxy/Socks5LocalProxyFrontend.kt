package com.amurcanov.tgwsproxy

internal interface Socks5NativeProxy {
    fun setPoolSize(size: Int)
    fun startProxy(host: String, port: Int, runtimeConfig: String, verbose: Int): Int
    fun stopProxy(): Int
    fun getAdaptiveRouteStats(): String?
}

internal object NativeSocks5Proxy : Socks5NativeProxy {
    override fun setPoolSize(size: Int) {
        NativeProxy.setPoolSize(size)
    }

    override fun startProxy(host: String, port: Int, runtimeConfig: String, verbose: Int): Int {
        return NativeProxy.startProxy(host, port, runtimeConfig, verbose)
    }

    override fun stopProxy(): Int {
        return NativeProxy.stopProxy()
    }

    override fun getAdaptiveRouteStats(): String? {
        return NativeProxy.getAdaptiveRouteStats()
    }
}

internal class Socks5LocalProxyFrontend(
    private val nativeProxy: Socks5NativeProxy = NativeSocks5Proxy,
) : LocalProxyFrontend {
    private var status = LocalProxyFrontendStatus.STOPPED

    override val type: LocalProxyFrontendType = LocalProxyFrontendType.SOCKS5

    override fun start(config: LocalProxyFrontendConfig): LocalProxyFrontendStartResult {
        nativeProxy.setPoolSize(config.poolSize)
        val resultCode = nativeProxy.startProxy(
            config.host, config.port, config.runtimeConfig, config.verbose,
        )
        status = if (resultCode == 0) LocalProxyFrontendStatus.RUNNING else LocalProxyFrontendStatus.FAILED
        return if (status == LocalProxyFrontendStatus.RUNNING) {
            LocalProxyFrontendStartResult(getState())
        } else {
            LocalProxyFrontendStartResult(
                state = getState(),
                message = "SOCKS5 native runtime refused to start.",
                errorCode = "NATIVE_START_REJECTED",
            )
        }
    }

    override fun stop(): String? {
        val exported = nativeProxy.getAdaptiveRouteStats()
        nativeProxy.stopProxy()
        status = LocalProxyFrontendStatus.STOPPED
        return exported
    }

    override fun getState(): LocalProxyFrontendState {
        return LocalProxyFrontendState(type = type, status = status)
    }
}
