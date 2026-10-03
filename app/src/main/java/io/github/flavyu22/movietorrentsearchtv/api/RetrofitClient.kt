package io.github.flavyu22.movietorrentsearchtv.api

import android.content.Context
import io.github.flavyu22.movietorrentsearchtv.di.NetworkManager
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import com.google.gson.Gson
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private const val BASE_URL = RemoteHosts.YTS_API_BASE_URL

    @Volatile
    private var apiService: MovieApiService? = null

    fun getInstance(context: Context): MovieApiService {
        return apiService ?: synchronized(this) {
            apiService ?: Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(NetworkManager.getOkHttpClient(context.applicationContext))
                .addConverterFactory(GsonConverterFactory.create(Gson()))
                .build()
                .create(MovieApiService::class.java)
                .also { apiService = it }
        }
    }
}
