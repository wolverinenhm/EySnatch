package com.eysnatch

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.eysnatch.core.Gesture
import com.eysnatch.core.PhoneAction

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = Ui.column(this)
        root.add(Ui.heading(this, "Gestures and settings"))
        root.add(
            Ui.dim(Ui.text(this, "Choose what each eye movement does. \"Do nothing\" switches a gesture off.", 14f)),
            2,
        )

        val actions = PhoneAction.values()
        var group = ""
        for (g in Gesture.values()) {
            if (g.group != group) {
                group = g.group
                root.add(Ui.section(this, if (group == "Head") "Head (optional)" else group), 20)
            }
            root.add(Ui.text(this, g.label, 16f, bold = true), 10)
            root.add(Ui.dim(Ui.text(this, g.how, 12f)))
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actions.map { it.label })
            spinner.setSelection(actions.indexOf(Prefs.action(g)))
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (actions[position] != Prefs.action(g)) Prefs.setAction(g, actions[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            root.add(spinner)
        }

        root.add(Ui.section(this, "Tuning"), 24)
        slider(
            root, "Sensitivity", "Higher = smaller movements are enough", 0.5f, 1.8f,
            Prefs.sensitivity,
        ) { Prefs.sensitivity = it }
        slider(
            root, "Hold time", "Higher = hold a look or wink longer before it counts", 0.6f, 2.0f,
            Prefs.holdScale,
        ) { Prefs.holdScale = it }

        root.add(Ui.section(this, "Feedback and power"), 24)
        switch(root, "Vibrate when an action runs", Prefs.vibrate) { Prefs.vibrate = it }
        switch(root, "Show a label on screen when an action runs", Prefs.hud) { Prefs.hud = it }
        switch(root, "Keep the screen on while eye control runs", Prefs.keepAwake) { Prefs.keepAwake = it }
        switch(root, "Battery saver (checks your eyes less often; double blink may be missed)", Prefs.batterySaver) {
            Prefs.batterySaver = it
        }

        root.add(Ui.section(this, "Reset"), 24)
        root.add(
            Ui.button(this, "Reset gestures to the defaults") {
                Prefs.resetMapping()
                Toast.makeText(this, "Gestures reset", Toast.LENGTH_SHORT).show()
                recreate()
            },
            6,
        )
        root.add(
            Ui.button(this, "Forget my calibration") {
                Prefs.resetCalibration()
                Toast.makeText(this, "Calibration cleared", Toast.LENGTH_SHORT).show()
            },
        )
        setContentView(Ui.scroll(this, root))
    }

    private fun switch(root: android.widget.LinearLayout, label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        root.add(
            SwitchCompat(this).apply {
                text = label
                isChecked = value
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            },
            8,
        )
    }

    private fun slider(
        root: android.widget.LinearLayout,
        name: String,
        hint: String,
        min: Float,
        max: Float,
        value: Float,
        onSave: (Float) -> Unit,
    ) {
        val label: TextView = root.add(Ui.text(this, "", 15f, bold = true), 12)
        root.add(Ui.dim(Ui.text(this, hint, 12f)))
        fun valueOf(progress: Int) = min + (max - min) * progress / 100f
        fun show(progress: Int) {
            label.text = "$name: ${"%.2f".format(valueOf(progress))}"
        }

        val bar = SeekBar(this)
        bar.max = 100
        bar.progress = (((value - min) / (max - min)) * 100f).toInt().coerceIn(0, 100)
        show(bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = show(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onSave(valueOf(bar.progress))
            }
        })
        root.add(bar)
    }
}
