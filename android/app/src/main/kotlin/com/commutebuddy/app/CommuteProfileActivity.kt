package com.commutebuddy.app

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class CommuteProfileActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_COMMUTE = "commute_prefs"

        private val DIRECTIONS = listOf(
            "Manhattan-bound",
            "Queens-bound",
            "Uptown",
            "Downtown",
            "Bronx-bound",
            "Brooklyn-bound"
        )
    }

    private lateinit var profileRepository: CommuteProfileRepository
    private lateinit var toWorkLegsContainer: LinearLayout
    private lateinit var toHomeLegsContainer: LinearLayout
    private lateinit var alternatesField: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_commute_profile)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        title = getString(R.string.title_configure_commute)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rootScrollView = findViewById<NestedScrollView>(R.id.rootScrollView)
        ViewCompat.setOnApplyWindowInsetsListener(rootScrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val prefs = getSharedPreferences(PREFS_COMMUTE, Context.MODE_PRIVATE)
        profileRepository = CommuteProfileRepository(prefs)

        toWorkLegsContainer = findViewById(R.id.toWorkLegsContainer)
        toHomeLegsContainer = findViewById(R.id.toHomeLegsContainer)
        alternatesField = findViewById(R.id.alternatesField)

        val addToWorkLegButton: MaterialButton = findViewById(R.id.addToWorkLegButton)
        val addToHomeLegButton: MaterialButton = findViewById(R.id.addToHomeLegButton)
        val saveButton: MaterialButton = findViewById(R.id.saveButton)

        addToWorkLegButton.setOnClickListener { addLegCard(toWorkLegsContainer, null) }
        addToHomeLegButton.setOnClickListener { addLegCard(toHomeLegsContainer, null) }
        saveButton.setOnClickListener { onSaveClicked() }

        val profile = profileRepository.load()
        for (leg in profile.toWorkLegs) addLegCard(toWorkLegsContainer, leg)
        for (leg in profile.toHomeLegs) addLegCard(toHomeLegsContainer, leg)
        alternatesField.setText(profile.alternates.joinToString(","))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun addLegCard(container: LinearLayout, leg: CommuteLeg?) {
        val view = layoutInflater.inflate(R.layout.item_commute_leg, container, false)

        val directionSpinner = view.findViewById<Spinner>(R.id.directionSpinner)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, DIRECTIONS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        directionSpinner.adapter = adapter

        if (leg != null) {
            view.findViewById<TextInputEditText>(R.id.linesField)
                .setText(leg.lines.joinToString(","))
            val dirIndex = DIRECTIONS.indexOf(leg.direction)
            if (dirIndex >= 0) directionSpinner.setSelection(dirIndex)
            view.findViewById<TextInputEditText>(R.id.fromStationField)
                .setText(leg.fromStation)
            view.findViewById<TextInputEditText>(R.id.toStationField)
                .setText(leg.toStation)
        }

        view.findViewById<MaterialButton>(R.id.removeButton).setOnClickListener {
            container.removeView(view)
        }

        container.addView(view)
    }

    private fun parseLinesField(text: String): List<String> =
        text.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    private fun collectLegs(container: LinearLayout): List<CommuteLeg>? {
        val legs = mutableListOf<CommuteLeg>()
        var isValid = true

        for (i in 0 until container.childCount) {
            val view = container.getChildAt(i)

            val linesInputLayout = view.findViewById<TextInputLayout>(R.id.linesInputLayout)
            val linesField = view.findViewById<TextInputEditText>(R.id.linesField)
            val directionSpinner = view.findViewById<Spinner>(R.id.directionSpinner)
            val fromStationInputLayout = view.findViewById<TextInputLayout>(R.id.fromStationInputLayout)
            val fromStationField = view.findViewById<TextInputEditText>(R.id.fromStationField)
            val toStationInputLayout = view.findViewById<TextInputLayout>(R.id.toStationInputLayout)
            val toStationField = view.findViewById<TextInputEditText>(R.id.toStationField)

            val lines = parseLinesField(linesField.text?.toString() ?: "")
            val direction = directionSpinner.selectedItem as String
            val fromStation = fromStationField.text?.toString()?.trim() ?: ""
            val toStation = toStationField.text?.toString()?.trim() ?: ""

            linesInputLayout.error = if (lines.isEmpty()) getString(R.string.error_lines_required) else null
            fromStationInputLayout.error = if (fromStation.isEmpty()) getString(R.string.error_station_required) else null
            toStationInputLayout.error = if (toStation.isEmpty()) getString(R.string.error_station_required) else null

            if (lines.isEmpty() || fromStation.isEmpty() || toStation.isEmpty()) {
                isValid = false
            } else {
                legs.add(CommuteLeg(lines, direction, fromStation, toStation))
            }
        }

        return if (isValid) legs else null
    }

    private fun onSaveClicked() {
        val toWorkLegs = collectLegs(toWorkLegsContainer)
        if (toWorkLegs == null) return
        if (toWorkLegs.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_leg_required_work), Toast.LENGTH_SHORT).show()
            return
        }

        val toHomeLegs = collectLegs(toHomeLegsContainer)
        if (toHomeLegs == null) return
        if (toHomeLegs.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_leg_required_home), Toast.LENGTH_SHORT).show()
            return
        }

        val alternates = parseLinesField(alternatesField.text?.toString() ?: "")
        val profile = CommuteProfile(toWorkLegs, toHomeLegs, alternates)
        profileRepository.save(profile)
        finish()
    }
}
