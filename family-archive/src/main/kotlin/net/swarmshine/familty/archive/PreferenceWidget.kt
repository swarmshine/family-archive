package net.swarmshine.familty.archive

import java.awt.Dimension
import java.util.prefs.Preferences
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PreferenceWidget(
        val identity: String,
        val toolTip: String = "",
        val defaultValue: String = ""): JPanel() {

    companion object {
        private val prefs = Preferences.userNodeForPackage(PreferenceWidget::class.java)
        fun resetPreferences(){
            prefs.clear()
        }
    }

    private val label = JLabel(identity)
    private val edit = JTextField().apply {
        text = prefs.get(identity, defaultValue)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        toolTipText = toolTip
        document.addDocumentListener(object: DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onChange()
            override fun removeUpdate(e: DocumentEvent?) = onChange()
            override fun changedUpdate(e: DocumentEvent?) = onChange()
            fun onChange(){
                prefs.put(identity, text)
            }
        })
    }

    val value: String get() = edit.text


    init {
        layout = BoxLayout(this, BoxLayout.LINE_AXIS)
        add(label)
        add(edit)
    }
}