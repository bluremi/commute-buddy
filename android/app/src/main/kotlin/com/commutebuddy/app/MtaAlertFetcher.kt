package com.commutebuddy.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object MtaAlertFetcher {

    const val MTA_SUBWAY_ALERTS_URL =
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json"

    suspend fun fetchAlerts(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(MTA_SUBWAY_ALERTS_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(IOException("HTTP $responseCode"))
                }
                val body = connection.inputStream.bufferedReader().readText()
                Result.success(body)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
