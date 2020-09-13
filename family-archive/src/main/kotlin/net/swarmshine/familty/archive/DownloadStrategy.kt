package net.swarmshine.familty.archive

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient

class DownloadStrategy (
        private val httpClientFactory: ()-> CloseableHttpClient
): AutoCloseable {
    companion object {
        private val ATTEMPTS_COUNT = 5
    }

    private var previousDownloadWasSuccessful = true
    private var httpClient = httpClientFactory()

    fun downloadImage(){
        for (attempt in 1..ATTEMPTS_COUNT) {
            when (val downloadResult = downloadImageByUrl()) {
                is Success -> {
                    previousDownloadWasSuccessful = true
                    return
                }
                is TooManyRequests -> {
                    if (previousDownloadWasSuccessful) {
                        restartHttpClient()
                    } else {
                        Thread.sleep(downloadResult.timeout)
                        restartHttpClient()
                    }
                }
                is Error -> {
                    previousDownloadWasSuccessful = false
                }
            }
        }
        throw Exception("Failed to download image after $ATTEMPTS_COUNT attempts")
    }

    private fun restartHttpClient() {
        httpClient.close()
        httpClient = httpClientFactory()
    }

    open class DownloadResult
    sealed class Success: DownloadResult()
    sealed class TooManyRequests(val timeout: Long): DownloadResult()
    sealed class Error: DownloadResult()

    fun downloadImageByUrl(): DownloadResult {
        TODO()
    }

    override fun close() {
        httpClient.close()
    }
}