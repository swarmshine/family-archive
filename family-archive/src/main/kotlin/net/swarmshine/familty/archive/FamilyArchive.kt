package net.swarmshine.familty.archive

import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.*

class FamilyArchive : JFrame() {

    private val pollingBrowserStateExecutor = Executors.newSingleThreadScheduledExecutor()

    val socksProxy = PreferenceWidget(
            identity = "socksProxy",
            toolTip = "my.http.proxy.com:1234",
            defaultValue = "127.0.0.1:9150")

    val startUrl = PreferenceWidget(
            identity = "startUrl",
            toolTip = "https://my.site/start.html",
            defaultValue = "https://www.familysearch.org/search/catalog/738565")

    val saveToDirectory = PreferenceWidget(
            identity = "saveToDirectory",
            toolTip = "/some/directory",
            defaultValue = Paths.get("family-archive").toAbsolutePath().toString())

    val delayBetweenRequests = PreferenceWidget(
            identity = "delayBetweenRequest",
            toolTip = "milliseconds",
            defaultValue = "1000"
    )


    val launchBrowserBtn = JButton("Launch browser").apply {
        addActionListener {
            Browser.launch(
                    socksProxy = socksProxy.value,
                    startUrl = startUrl.value
            )
        }
    }

    val startDownloadingBtn: JButton = JButton("Start downloading").apply {
        isEnabled = false
    }
    val stopDownloadingBtn: JButton = JButton("Stop downloading").apply {
        isEnabled = false
    }

    val status = JLabel("")

    val resetPreferences = JButton("Reset preferences and close").apply {
        addActionListener {
            PreferenceWidget.resetPreferences()
            releaseResources()
            isVisible = false
            dispose()
        }
    }


    init {
        startDownloadingBtn.addActionListener {
            Browser.startDownloading(Paths.get(saveToDirectory.value), delayBetweenRequests.value.toLong())
            startDownloadingBtn.isEnabled = false
        }

        stopDownloadingBtn.addActionListener {
            Browser.stopDownloading()
            stopDownloadingBtn.isEnabled = false
        }

        isVisible = true
        title = "Family Archive"

        contentPane.apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            add(socksProxy)
            add(startUrl)
            add(saveToDirectory)
            add(delayBetweenRequests)
            add(launchBrowserBtn)
            add(startDownloadingBtn)
            add(stopDownloadingBtn)
            add(status)
            add(resetPreferences)
        }

        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                releaseResources()
                super.windowClosed(e)
            }
        })

        setSize(500, 600)

        pollingBrowserStateExecutor.scheduleAtFixedRate({
            val browserStatus = Browser.requestStatus()
            EventQueue.invokeAndWait {
                startDownloadingBtn.isEnabled = !browserStatus.isDownloading && browserStatus.filmViewIsOpen
                stopDownloadingBtn.isEnabled = browserStatus.isDownloading
                status.text = "Downloaded: ${browserStatus.foundDownloadedFiles}" +
                        ", Total: ${browserStatus.totalPages}" +
                        ", Current: ${browserStatus.currentPage}"
            }
        }, 3, 3, TimeUnit.SECONDS)
    }

    fun releaseResources() {
        pollingBrowserStateExecutor.shutdown()
        pollingBrowserStateExecutor.awaitTermination(10, TimeUnit.SECONDS)
        Browser.close()
    }

    //TODO UI log appender
    // https://stackoverflow.com/questions/24005748/how-to-output-logs-to-a-jtextarea-using-log4j2
}


fun main() {
    EventQueue.invokeLater {
        FamilyArchive()
    }
}