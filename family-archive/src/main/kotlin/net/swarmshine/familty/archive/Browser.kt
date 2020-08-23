package net.swarmshine.familty.archive

import io.github.bonigarcia.wdm.WebDriverManager
import org.apache.hc.client5.http.classic.methods.HttpGet
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
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import javax.net.ssl.SSLContext


object Browser : Logging {
    lateinit var driver: ChromeDriver

    private var socksProxy: InetSocketAddress? = null

    fun launch(
            socksProxy: String,
            startUrl: String) {
        WebDriverManager.chromedriver().apply {
            setup()
        }

        driver = ChromeDriver(ChromeOptions().apply {
            if (socksProxy.isNotBlank()) {
                addArguments("--proxy-server=socks5://$socksProxy")
            }
        });
        driver.navigate().to(startUrl)

        this.socksProxy = InetSocketAddress(
                socksProxy.split(":")[0],
                socksProxy.split(":")[1].toInt())


    }

    fun download(saveToDirectory: String) {
        val pageInput = driver.findElementByXPath("//input[@name='currentTileNumber']")
        val page = pageInput.getAttribute("value").toInt()
        logger.info("page: $page")

        val totalPageText = driver.findElementByXPath(
                "//*[@id='openSDPagerInputContainer2']/label[@class='afterInput']").text
        logger.info("totalPageText: $totalPageText")

        val totalPages = "\\d+".toRegex().find(totalPageText)!!.value.toInt()
        logger.info("totalPages: $totalPages")

        val imageSrc = driver.findElementByXPath("//img[@id='printImage']").getAttribute("src")
        logger.info("imageSrc: $imageSrc")

        downloadImage(imageSrc, saveToDirectory, page)

        pageInput.clear()
        pageInput.sendKeys((page + 1).toString(), Keys.ENTER)

    }

    val httpSocketRegistry = RegistryBuilder.create<ConnectionSocketFactory>()
            .register("http", PlainConnectionSocketFactory.INSTANCE)
            .register("https", MyConnectionSocketFactory(SSLContexts.createSystemDefault()))
            .build();

    val httpConnectionManager = PoolingHttpClientConnectionManager(httpSocketRegistry);
    val httpClient = HttpClients.custom()
            .setConnectionManager(httpConnectionManager)
            .build();

    fun downloadImage(imageSrc: String, saveToDirectory: String, page: Int) {

        val context = HttpClientContext.create();
        context.setAttribute("socks.address", socksProxy);

        val httpGet = HttpGet(imageSrc)

        httpGet.addHeader("accept", "image/webp,image/apng,image/*,*/*;q=0.9")

        val cookieStore = BasicCookieStore()
        driver.manage().cookies.map { driverCookie ->
            BasicClientCookie(driverCookie.name, driverCookie.value).apply {
                domain = driverCookie.domain
                path = driverCookie.path
                expiryDate = driverCookie.expiry
                isSecure = driverCookie.isSecure
                setAttribute(ClientCookie.DOMAIN_ATTR, "true");
            }
        }.forEach { cookieStore.addCookie(it) }

        context.cookieStore = cookieStore

        val response = httpClient.execute(httpGet, context)

        logger.info("response: ${response.code}")

        Files.createDirectories(Paths.get(saveToDirectory))

        val imageFile = Paths.get(saveToDirectory).resolve("image-$page").toFile()

        FileOutputStream(imageFile).use { file ->
            response.entity.writeTo(file)
        }

//            val imageSrcUrl = URL(imageSrc)
//            val target = HttpHost(imageSrcUrl.authority, "https");
//            val request = HttpGet(imageSrcUrl.toURI());
//
//            logger.info("Executing request $request to $target via SOCKS proxy $socksProxy");
//            val response = httpclient.execute(target, request, context);
//            try {
//                logger.info("Response: ${response.code}")
//                EntityUtils.consume(response.getEntity());
//            } finally {
//                response.close();
//            }

    }

    fun close() {
        if (::driver.isInitialized) {
            driver.quit()
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