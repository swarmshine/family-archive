package net.swarmshine.familty.archive

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.logging.log4j.kotlin.Logging

sealed class DownloadResult
class SuccessDownloadResult(): DownloadResult()
class TooManyRequestsDownloadResult(val timeout: Long): DownloadResult()
class ErrorDownloadResult(): DownloadResult()


class RetryableDownloader (
        private val httpClientFactory: ()-> CloseableHttpClient
): AutoCloseable {
    companion object: Logging {
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
                        logger.warn("TooManyRequests, restarting http client")
                        restartHttpClient()
                        switchFilmViewerToFirstPageAction()
                    } else {
                        logger.warn("TooManyRequests, sleeping for ${downloadResult.timeout} and restarting http client")
                        Thread.sleep(downloadResult.timeout)
                        restartHttpClient()
                        switchFilmViewerToFirstPageAction()
                    }
                    previousDownloadWasSuccessful = false
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