package com.commutebuddy.app

import android.content.Context
import android.text.SpannableStringBuilder
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
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
    private lateinit var alternatesSummaryText: TextView

    private val selectedAlternates = mutableListOf<String>()

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
        alternatesSummaryText = findViewById(R.id.alternatesSummaryText)

        val addToWorkLegButton: MaterialButton = findViewById(R.id.addToWorkLegButton)
        val addToHomeLegButton: MaterialButton = findViewById(R.id.addToHomeLegButton)
        val saveButton: MaterialButton = findViewById(R.id.saveButton)
        val selectAlternatesButton: MaterialButton = findViewById(R.id.selectAlternatesButton)

        addToWorkLegButton.setOnClickListener { addLegCard(toWorkLegsContainer, null) }
        addToHomeLegButton.setOnClickListener { addLegCard(toHomeLegsContainer, null) }
        saveButton.setOnClickListener { onSaveClicked() }

        selectAlternatesButton.setOnClickListener {
            val sheet = LinePickerBottomSheet.newInstance(selectedAlternates) { lines ->
                selectedAlternates.clear()
                selectedAlternates.addAll(lines)
                updateAlternatesSummary()
            }
            sheet.show(supportFragmentManager, "line_picker_alternates")
        }

        val profile = profileRepository.load()
        for (leg in profile.toWorkLegs) addLegCard(toWorkLegsContainer, leg)
        for (leg in profile.toHomeLegs) addLegCard(toHomeLegsContainer, leg)
        selectedAlternates.addAll(profile.alternates)
        updateAlternatesSummary()
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

        val selectedLines = mutableListOf<String>()
        if (leg != null) selectedLines.addAll(leg.lines)
        view.tag = selectedLines
        updateLinesSummary(view, selectedLines)

        val directionSpinner = view.findViewById<Spinner>(R.id.directionSpinner)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, DIRECTIONS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        directionSpinner.adapter = adapter

        if (leg != null) {
            val dirIndex = DIRECTIONS.indexOf(leg.direction)
            if (dirIndex >= 0) directionSpinner.setSelection(dirIndex)
            view.findViewById<TextInputEditText>(R.id.fromStationField).setText(leg.fromStation)
            view.findViewById<TextInputEditText>(R.id.toStationField).setText(leg.toStation)
        }

        view.findViewById<MaterialButton>(R.id.selectLinesButton).setOnClickListener {
            @Suppress("UNCHECKED_CAST")
            val current = view.tag as MutableList<String>
            val sheet = LinePickerBottomSheet.newInstance(current) { lines ->
                current.clear()
                current.addAll(lines)
                updateLinesSummary(view, lines)
            }
            sheet.show(supportFragmentManager, "line_picker_leg")
        }

        view.findViewById<MaterialButton>(R.id.removeButton).setOnClickListener {
            container.removeView(view)
        }

        container.addView(view)
    }

    private fun updateLinesSummary(cardView: android.view.View, lines: List<String>) {
        val summary = cardView.findViewById<TextView>(R.id.linesSummaryText)
        summary.text = if (lines.isEmpty()) {
            getString(R.string.label_lines_none)
        } else {
            val badgeSize = resources.displayMetrics.density * 22f
            SpannableStringBuilder("Lines: ")
                .append(MtaLineColors.buildRouteBadges(lines.joinToString(","), badgeSize))
        }
    }

    private fun updateAlternatesSummary() {
        alternatesSummaryText.text = if (selectedAlternates.isEmpty()) {
            getString(R.string.label_alternates_none)
        } else {
            val badgeSize = resources.displayMetrics.density * 22f
            SpannableStringBuilder("Alternates: ")
                .append(MtaLineColors.buildRouteBadges(selectedAlternates.joinToString(","), badgeSize))
        }
    }

    private fun collectLegs(container: LinearLayout): List<CommuteLeg>? {
        val legs = mutableListOf<CommuteLeg>()
        var isValid = true
        var hasLinesError = false

        for (i in 0 until container.childCount) {
            val view = container.getChildAt(i)

            @Suppress("UNCHECKED_CAST")
            val lines = (view.tag as? MutableList<String>) ?: mutableListOf()

            val directionSpinner = view.findViewById<Spinner>(R.id.directionSpinner)
            val fromStationInputLayout = view.findViewById<TextInputLayout>(R.id.fromStationInputLayout)
            val fromStationField = view.findViewById<TextInputEditText>(R.id.fromStationField)
            val toStationInputLayout = view.findViewById<TextInputLayout>(R.id.toStationInputLayout)
            val toStationField = view.findViewById<TextInputEditText>(R.id.toStationField)

            val direction = directionSpinner.selectedItem as String
            val fromStation = fromStationField.text?.toString()?.trim() ?: ""
            val toStation = toStationField.text?.toString()?.trim() ?: ""

            if (lines.isEmpty()) hasLinesError = true
            fromStationInputLayout.error = if (fromStation.isEmpty()) getString(R.string.error_station_required) else null
            toStationInputLayout.error = if (toStation.isEmpty()) getString(R.string.error_station_required) else null

            if (lines.isEmpty() || fromStation.isEmpty() || toStation.isEmpty()) {
                isValid = false
            } else {
                legs.add(CommuteLeg(lines, direction, fromStation, toStation))
            }
        }

        if (hasLinesError) {
            Toast.makeText(this, getString(R.string.error_lines_required), Toast.LENGTH_SHORT).show()
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

        val profile = CommuteProfile(toWorkLegs, toHomeLegs, selectedAlternates.toList())
        profileRepository.save(profile)
        finish()
    }
}
