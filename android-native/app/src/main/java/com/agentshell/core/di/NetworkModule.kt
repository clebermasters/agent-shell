package com.agentshell.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import com.agentshell.data.remote.ServerDns
import com.agentshell.core.diagnostics.BatteryDiagnostics
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(dns: ServerDns): OkHttpClient {
        return OkHttpClient.Builder()
            .eventListenerFactory(BatteryDiagnostics.httpListenerFactory)
            .dns(dns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
