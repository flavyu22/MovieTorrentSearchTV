package com.example.movietorrentsearchtv.api

import android.content.Context
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://yts.bz/api/v2/"
    // Token-ul TMDB extras din JWT-ul furnizat
    private const val TMDB_BEARER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1YmM3NThhZDJhZTkwYzY3ZDIzNjFmM2Q2MzAyNDM1NyIsIm5iZiI6MTc2OTI5NzIzNS43OTUsInN1YiI6IjY5NzU1NTUzMjQ5ZjAzODQwOTY5NDFmNyIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.4_GK5l2yciscpclk1FABIxB3064AYNgVUVwILivq5tk"

    private var apiService: MovieApiService? = null

    fun getInstance(context: Context): MovieApiService {
        if (apiService == null) {
            val cache = Cache(File(context.cacheDir, "http_cache"), 64 * 1024 * 1024)
            
            val headerInterceptor = Interceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")

                // Daca cererea este catre TMDB, adaugam Bearer Token-ul pentru securitate sporita
                if (originalRequest.url.host.contains("themoviedb.org")) {
                    requestBuilder.header("Authorization", "Bearer $TMDB_BEARER_TOKEN")
                }

                chain.proceed(requestBuilder.build())
            }

            val client = OkHttpClient.Builder()
                .cache(cache)
                .addInterceptor(headerInterceptor)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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
