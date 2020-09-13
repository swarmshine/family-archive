package net.swarmshine.familty.archive

import io.github.bonigarcia.wdm.WebDriverManager
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.BasicCookieStore
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager
import org.apache.hc.client5.http.protocol.HttpClientContext
import org.apache.hc.client5.http.socket.ConnectionSocketFactory
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory
import org.apache.hc.core5.http.config.RegistryBuilder
import org.apache.hc.core5.http.protocol.HttpContext
import org.apache.hc.core5.ssl.SSLContexts
import org.apache.hc.core5.util.TimeValue
import org.apache.http.cookie.ClientCookie
import org.apache.logging.log4j.kotlin.Logging
import org.openqa.selenium.Keys
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import java.io.FileOutputStream
import java.lang.IllegalStateException
import java.lang.Thread.sleep
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import kotlin.streams.toList


object Browser : Logging {
    var driver: ChromeDriver? = null

    private var downloadingThread = AtomicReference<Thread?>()
    private var totalPages = AtomicInteger()
    private var currentPage = AtomicInteger()
    private var foundFiles = AtomicInteger()
    private var saveToDirectory = AtomicReference<Path>(Paths.get("download"))
    private var delayBeforeDownload = AtomicLong()

    private var socksProxy: InetSocketAddress? = null

    private val retryableDownloader = RetryableDownloader(httpClientFactory = ::buildHttpClient)

    fun launch(socksProxy: String, startUrl: String) {
        WebDriverManager.chromedriver().apply {
            setup()
        }

        driver = ChromeDriver(ChromeOptions().apply {
            if (socksProxy.isNotBlank()) {
                addArguments("--proxy-server=socks5://$socksProxy")
            }
        });
        driver!!.navigate().to(startUrl)

        this.socksProxy = InetSocketAddress(
                socksProxy.split(":")[0],
                socksProxy.split(":")[1].toInt())
    }

    data class CurrentPageMeta(
            var currentPage: Int = -1,
            var totalPages: Int = -1,
            var imageSrc: String = ""
    )

    fun detectCurrentPageImageToDownload(): CurrentPageMeta? {
        try {
            val driver = this.driver ?: return null

            val meta = CurrentPageMeta()

            val pageInput = driver.findElementByXPath("//input[@name='currentTileNumber']")
            meta.currentPage = pageInput.getAttribute("value").toInt()

            val totalPageText = driver.findElementByXPath(
                    "//*[@id='openSDPagerInputContainer2']/label[@class='afterInput']").text

            meta.totalPages = "\\d+".toRegex().find(totalPageText)!!.value.toInt()

            meta.imageSrc = driver.findElementByXPath("//img[@id='printImage']").getAttribute("src")

            return meta
        } catch (exc: Exception) {
            logger.warn(exc)
        }
        return null
    }

    fun flipToPage(page: Int) {
        try {
            logger.info("Flip to page: $page")
            val pageInput = driver!!.findElementByXPath("//input[@name='currentTileNumber']")
            pageInput.clear()
            pageInput.sendKeys((page).toString(), Keys.ENTER)
        } catch (exc: Exception) {
            logger.warn(exc)
        }
    }

    val httpSocketRegistry = RegistryBuilder.create<ConnectionSocketFactory>()
            .register("http", PlainConnectionSocketFactory.INSTANCE)
            .register("https", MyConnectionSocketFactory(SSLContexts.createSystemDefault()))
            .build();

    fun buildHttpClient(): CloseableHttpClient {
        val httpConnectionManager = BasicHttpClientConnectionManager(httpSocketRegistry);
        val httpClient = HttpClients.custom()
                .setConnectionManager(httpConnectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(10, TimeUnit.SECONDS)
                        .setConnectionRequestTimeout(30, TimeUnit.SECONDS)
                        .setResponseTimeout(60, TimeUnit.SECONDS)
                        .build())
                .setRetryStrategy(DefaultHttpRequestRetryStrategy(-1, TimeValue.ofSeconds(1)))
                .build();
        return httpClient
    }


    fun downloadImage(imageSrc: String, page: Int, httpClient: CloseableHttpClient): DownloadResult {
        val context = HttpClientContext.create();
        context.setAttribute("socks.address", socksProxy);

        val request = HttpGet(imageSrc)
        request.addHeader("accept", "image/webp,image/apng,image/*,*/*;q=0.9")

        val cookieStore = BasicCookieStore()
        driver!!.manage().cookies.map { driverCookie ->
            BasicClientCookie(driverCookie.name, driverCookie.value).apply {
                domain = driverCookie.domain
                path = driverCookie.path
                expiryDate = driverCookie.expiry
                isSecure = driverCookie.isSecure
                setAttribute(ClientCookie.DOMAIN_ATTR, "true");
            }
        }.forEach { cookieStore.addCookie(it) }
        context.cookieStore = cookieStore

        httpClient.execute(request, context).use { response ->
            logger.info("response: ${response.code}")

            if (response.code == 429) {
                return TooManyRequestsDownloadResult(
                        DefaultHttpRequestRetryStrategy.INSTANCE
                                .getRetryInterval(response, 0, context)
                                .toMilliseconds()
                )
            }

            if (response.code != 200) {
                return ErrorDownloadResult()
            }

            val contentType = response.getHeader("content-type")?.value ?: ""
            val fileExtension = when {
                contentType.contains("jpg") -> ".jpg"
                contentType.contains("jpeg") -> ".jpg"
                contentType.contains("png") -> ".png"
                contentType.contains("gif") -> ".gif"
                contentType.contains("gif") -> ".gif"
                contentType.contains("tiff") -> ".tiff"
                contentType.contains("webp") -> ".webp"
                else -> ".jpg"
            }

            Files.createDirectories(saveToDirectory.get())
            val imageFile = saveToDirectory.get()
                    .resolve(FileNameMatcher.fileName(page, fileExtension))
                    .toFile()

            FileOutputStream(imageFile).use { file ->
                response.entity.writeTo(file)
            }

            return SuccessDownloadResult()
        }
    }

    fun startDownloading(saveToDirectory: Path, delayBeforeDownload: Long) {
        val newThread = Thread(::downloadingProcess)

        if (downloadingThread.compareAndSet(null, newThread)) {
            this.saveToDirectory.set(saveToDirectory)
            this.delayBeforeDownload.set(delayBeforeDownload)
            newThread.start()
        }
    }

    fun stopDownloading() {
        val activeThread = downloadingThread.getAndSet(null) ?: return
        activeThread.interrupt()
        activeThread.join()
    }

    private fun findDownloadedFiles(): MutableSet<Int> {
        try {
            return Files.list(saveToDirectory.get())
                    .map { FileNameMatcher.extractFileNumber(it) }
                    .toList()
                    .filterNotNullTo(HashSet())
        } catch (exc: Exception) {
            return HashSet()
        }

    }

    private fun downloadingProcess() {
        logger.info("Start downloading to ${saveToDirectory.get()}")
        Files.createDirectories(saveToDirectory.get())

        val downloadedPages = findDownloadedFiles()
        this.foundFiles.set(downloadedPages.size)
        logger.info("Downloaded pages: $downloadedPages")



        while (!Thread.currentThread().isInterrupted && downloadingThread.get() != null) {
            try {
                var meta: CurrentPageMeta? = null
                for (attempt in 1..10) {
                    meta = detectCurrentPageImageToDownload()
                    if (meta != null)
                        break

                    logger.info("Failed to detect current page at attempt $attempt, sleep 1s.")
                    sleep(1000)
                }
                if (meta == null) {
                    logger.error("Failed to detect current page, abort downloading.")
                    break
                }

                currentPage.set(meta.currentPage)
                totalPages.set(meta.totalPages)

                if (!downloadedPages.contains(meta.currentPage)) {
                    delayBeforeDownload.get().let {
                        if (it > 0) {
                            logger.info("Sleep for $it before download image")
                            sleep(it)
                        }
                    }

                    retryableDownloader.download(
                            downloadImageAction = { httpClient ->
                                downloadImage(meta.imageSrc, meta.currentPage, httpClient)
                            },
                            switchFilmViewerToFirstPageAction = {
                                flipToPage(1)
                            })

                    downloadedPages.add(meta.currentPage)
                    this.foundFiles.set(downloadedPages.size)
                }

                if (downloadedPages.size < meta.totalPages) {
                    val flipToPage = ((1..meta.totalPages) - downloadedPages).min()!!
                    flipToPage(flipToPage)
                    sleep(1000)
                }
            } catch (interruptedException: InterruptedException) {
                break
            } catch (exc: Exception) {
                logger.warn(exc)
                sleep(1000)
            }
        }


        this.downloadingThread.set(null)
        this.currentPage.set(-1)
        this.totalPages.set(-1)
        this.foundFiles.set(-1)
    }

    data class Status(
            val isDownloading: Boolean,
            val filmViewIsOpen: Boolean,
            val foundDownloadedFiles: Int,
            val currentPage: Int,
            val totalPages: Int)


    fun requestStatus(): Status {
        if (downloadingThread.get() != null) {
            return Status(
                    isDownloading = true,
                    filmViewIsOpen = true,
                    currentPage = currentPage.get(),
                    totalPages = totalPages.get(),
                    foundDownloadedFiles = foundFiles.get()

            )
        } else {
            val meta = detectCurrentPageImageToDownload()
            if (meta != null) {
                return Status(
                        isDownloading = false,
                        filmViewIsOpen = true,
                        currentPage = meta.currentPage,
                        totalPages = meta.totalPages,
                        foundDownloadedFiles = findDownloadedFiles().size)
            } else {
                return Status(
                        isDownloading = false,
                        filmViewIsOpen = false,
                        currentPage = -1,
                        totalPages = -1,
                        foundDownloadedFiles = findDownloadedFiles().size)
            }
        }
    }

    fun close() {
        if (driver != null) {
            driver!!.quit()
            driver = null
        }
    }
}


internal class MyConnectionSocketFactory(sslContext: SSLContext) : SSLConnectionSocketFactory(sslContext) {
    override fun createSocket(context: HttpContext): Socket {
        val socksaddr = context.getAttribute("socks.address") as InetSocketAddress
        val proxy = Proxy(Proxy.Type.SOCKS, socksaddr)
        return Socket(proxy)
    }
}