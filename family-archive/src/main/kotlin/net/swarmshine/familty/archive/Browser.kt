package net.swarmshine.familty.archive

import io.github.bonigarcia.wdm.WebDriverManager
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.BasicCookieStore
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.apache.hc.client5.http.protocol.HttpClientContext
import org.apache.hc.client5.http.socket.ConnectionSocketFactory
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory
import org.apache.hc.core5.http.config.RegistryBuilder
import org.apache.hc.core5.http.protocol.HttpContext
import org.apache.hc.core5.ssl.SSLContexts
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
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import kotlin.streams.toList


object Browser : Logging {
    var driver: ChromeDriver? = null

    private var dowloadingThread: Thread? = null

    private var totalPages = 0
    private var currentPage = 0
    private var foundFiles = 0
    private var saveToDirectory: Path = Paths.get("download")
    private var delayBetweenRequests = 1000L
    private var socksProxy: InetSocketAddress? = null

    @Synchronized
    fun launch(socksProxy: String,
               startUrl: String) {
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

    val httpConnectionManager = PoolingHttpClientConnectionManager(httpSocketRegistry);
    val httpClient = HttpClients.custom()
            .setConnectionManager(httpConnectionManager)
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(10, TimeUnit.SECONDS)
                    .setConnectionRequestTimeout(30, TimeUnit.SECONDS)
                    .setResponseTimeout(60, TimeUnit.SECONDS)
                    .build())
            .build();

    fun downloadImage(imageSrc: String, page: Int) {
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
            if (response.code != 200) {
                throw IllegalStateException("Failed to download image")
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

            Files.createDirectories(saveToDirectory)
            val imageFile = saveToDirectory
                    .resolve(FileNameMatcher.fileName(page, fileExtension))
                    .toFile()

            FileOutputStream(imageFile).use { file ->
                response.entity.writeTo(file)
            }
        }
    }

    @Synchronized
    fun startDownloading(saveToDirectory: Path, delayBetweenRequests: Long) {
        if (dowloadingThread == null) {
            this.saveToDirectory = saveToDirectory
            this.delayBetweenRequests = delayBetweenRequests
            dowloadingThread = Thread(::downloadingProcess)
            dowloadingThread!!.start()
        }
    }

    @Synchronized
    fun stopDownloading() {
        if (dowloadingThread != null) {
            dowloadingThread!!.interrupt()
            dowloadingThread!!.join()
            dowloadingThread = null
        }
    }

    private fun findDownloadedFiles(): MutableSet<Int> {
        try {
            return Files.list(saveToDirectory)
                    .map { FileNameMatcher.extractFileNumber(it) }
                    .toList()
                    .filterNotNullTo(HashSet())
        } catch (exc: Exception) {
            return HashSet()
        }

    }

    private fun downloadingProcess() {
        val saveToDirectory = synchronized(this) {
            saveToDirectory
        }
        logger.info("Start downloading to $saveToDirectory")
        Files.createDirectories(saveToDirectory)

        val downloadedPages = findDownloadedFiles()
        this.foundFiles = downloadedPages.size
        logger.info("Downloaded pages: $downloadedPages")

        while (!Thread.currentThread().isInterrupted) {
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

                synchronized(this) {
                    currentPage = meta.currentPage
                    totalPages = meta.totalPages
                }

                if (!downloadedPages.contains(meta.currentPage)) {
                    downloadImage(meta.imageSrc, meta.currentPage)
                    downloadedPages.add(meta.currentPage)
                    this.foundFiles = downloadedPages.size
                }

                if (downloadedPages.size < meta.totalPages) {
                    val flipToPage = ((1..meta.totalPages) - downloadedPages).min()!!
                    flipToPage(flipToPage)
                }
            } catch (interruptedException: InterruptedException) {
                break
            } catch (exc: Exception) {
                logger.warn(exc)
            }

            val delayBetweenRequests = synchronized(this) { delayBetweenRequests }
            logger.info("Sleep for $delayBetweenRequests to work around 429 Too Many Request")
            sleep(delayBetweenRequests)
        }
        synchronized(this) {
            this.dowloadingThread = null
            this.currentPage = -1
            this.totalPages = -1
            this.foundFiles = -1
        }
    }

    data class Status(
            val isDownloading: Boolean,
            val filmViewIsOpen: Boolean,
            val foundDownloadedFiles: Int,
            val currentPage: Int,
            val totalPages: Int)


    @Synchronized
    fun requestStatus(): Status {
        if (dowloadingThread != null) {
            return Status(
                    isDownloading = dowloadingThread != null,
                    filmViewIsOpen = true,
                    currentPage = currentPage,
                    totalPages = totalPages,
                    foundDownloadedFiles = foundFiles

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

    @Synchronized
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