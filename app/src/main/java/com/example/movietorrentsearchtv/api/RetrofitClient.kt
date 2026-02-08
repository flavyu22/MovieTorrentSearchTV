package com.example.movietorrentsearchtv.api

import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import android.content.Context
import java.io.File

object RetrofitClient {
    private const val BASE_URL = "https://yts.bz/api/v2/"
    private var apiService: MovieApiService? = null

    fun getInstance(context: Context): MovieApiService {
        if (apiService == null) {
            val cache = Cache(File(context.cacheDir, "http_cache"), 50 * 1024 * 1024)
            
            val client = OkHttpClient.Builder()
                .cache(cache)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            apiService = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MovieApiService::class.java)
        }
        return apiService!!
    }
}
