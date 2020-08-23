package net.swarmshine.familty.archive

import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Paths
import java.util.prefs.Preferences
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame

class FamilyArchive : JFrame() {

    private val prefs = Preferences.userNodeForPackage(FamilyArchive::class.java)

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


    val launchBrowserBtn = JButton("Launch browser").apply {
        addActionListener {
            Browser.launch(
                    socksProxy = socksProxy.value,
                    startUrl = startUrl.value
            )
        }
    }

    val downloadBtn = JButton("Downlaoad").apply {
        addActionListener {
            Browser.download(saveToDirectory.value)
        }
    }

    val resetPreferences = JButton("Reset preferences and close").apply {
        addActionListener {
            PreferenceWidget.resetPreferences()
            Browser.close()
            isVisible = false
            dispose()
        }
    }

    init {
        isVisible = true
        title = "Family Archive"

        contentPane.apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            add(socksProxy)
            add(startUrl)
            add(saveToDirectory)
            add(launchBrowserBtn)
            add(downloadBtn)
            add(resetPreferences)
        }

        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                Browser.close()
                super.windowClosed(e)
            }
        })

        setSize(500, 600)
    }

    //TODO UI log appender
    // https://stackoverflow.com/questions/24005748/how-to-output-logs-to-a-jtextarea-using-log4j2
}


fun main() {
    EventQueue.invokeLater {
        FamilyArchive()
    }
}