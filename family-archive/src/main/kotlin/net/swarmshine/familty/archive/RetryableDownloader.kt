package net.swarmshine.familty.archive

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient

sealed class DownloadResult
class SuccessDownloadResult(): DownloadResult()
class TooManyRequestsDownloadResult(val timeout: Long): DownloadResult()
class ErrorDownloadResult(): DownloadResult()


class RetryableDownloader (
        private val httpClientFactory: ()-> CloseableHttpClient
): AutoCloseable {
    companion object {
        private val ATTEMPTS_COUNT = 5
    }

    private var previousDownloadWasSuccessful = true

    private var httpClient: CloseableHttpClient? = null

    fun download(
            downloadImageAction: (CloseableHttpClient)->DownloadResult,
            switchFilmViewerToFirstPageAction: ()->Unit){

        if (httpClient == null) {
            httpClient = httpClientFactory()
        }

        for (attempt in 1..ATTEMPTS_COUNT) {
            when (val downloadResult = downloadImageAction(httpClient!!)) {
                is SuccessDownloadResult -> {
                    previousDownloadWasSuccessful = true
                    return
                }
                is TooManyRequestsDownloadResult -> {
                    if (previousDownloadWasSuccessful) {
                        restartHttpClient()
                        switchFilmViewerToFirstPageAction()
                    } else {
                        Thread.sleep(downloadResult.timeout)
                        restartHttpClient()
                    }
                }
                is ErrorDownloadResult -> {
                    previousDownloadWasSuccessful = false
                }
            }
        }
        throw Exception("Failed to download image after $ATTEMPTS_COUNT attempts")
    }

    private fun restartHttpClient() {
        httpClient?.close()
        httpClient = httpClientFactory()
    }


    override fun close() {
        httpClient?.close()
    }
}