package com.commutebuddy.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.ShapeAppearanceModel

class LinePickerBottomSheet : BottomSheetDialogFragment() {

    fun interface Callback {
        fun onLinesSelected(lines: List<String>)
    }

    companion object {
        private const val ARG_SELECTED = "selected_lines"

        // Grouped by trunk line — order determines display order
        private val LINE_GROUPS = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7"),
            listOf("A", "C", "E"),
            listOf("B", "D", "F", "M"),
            listOf("G"),
            listOf("J", "Z"),
            listOf("L", "S"),
            listOf("N", "Q", "R", "W")
        )

        val ALL_LINES: List<String> = LINE_GROUPS.flatten()

        fun newInstance(selected: List<String>, callback: Callback): LinePickerBottomSheet {
            val fragment = LinePickerBottomSheet()
            fragment.arguments = Bundle().apply {
                putStringArrayList(ARG_SELECTED, ArrayList(selected))
            }
            fragment.callback = callback
            return fragment
        }

        private fun lineColor(line: String): Int = when (line) {
            "1", "2", "3"      -> Color.parseColor("#D82233")
            "4", "5", "6"      -> Color.parseColor("#009952")
            "7"                -> Color.parseColor("#9A38A1")
            "A", "C", "E"      -> Color.parseColor("#0062CF")
            "B", "D", "F", "M" -> Color.parseColor("#EB6800")
            "G"                -> Color.parseColor("#799534")
            "J", "Z"           -> Color.parseColor("#8E5C33")
            "L", "S"           -> Color.parseColor("#7C858C")
            "N", "Q", "R", "W" -> Color.parseColor("#F6BC26")
            else               -> Color.GRAY
        }

        // Yellow lines (#F6BC26) need black text for contrast; all others use white
        private fun isLightBackground(line: String) = line in setOf("N", "Q", "R", "W")
    }

    var callback: Callback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_line_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val selected = arguments?.getStringArrayList(ARG_SELECTED) ?: arrayListOf()
        val chipContainer = view.findViewById<LinearLayout>(R.id.chipContainer)

        for (group in LINE_GROUPS) {
            val chipGroup = ChipGroup(requireContext()).apply {
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = dp(4).toInt()
                layoutParams = params
                isSingleSelection = false
                chipSpacingHorizontal = dp(6).toInt()
                chipSpacingVertical = dp(4).toInt()
            }
            for (line in group) {
                chipGroup.addView(createLineChip(line, line in selected))
            }
            chipContainer.addView(chipGroup)
        }

        view.findViewById<MaterialButton>(R.id.doneButton).setOnClickListener {
            val result = mutableListOf<String>()
            for (i in 0 until chipContainer.childCount) {
                val group = chipContainer.getChildAt(i) as? ChipGroup ?: continue
                for (j in 0 until group.childCount) {
                    val chip = group.getChildAt(j) as? Chip ?: continue
                    if (chip.isChecked) result.add(chip.text.toString())
                }
            }
            callback?.onLinesSelected(result)
            dismiss()
        }
    }

    private fun createLineChip(line: String, isChecked: Boolean): Chip {
        val bgColor = lineColor(line)
        val isLight = isLightBackground(line)
        val textColor = if (isLight) Color.BLACK else Color.WHITE
        // Use the app's primary CTA purple for the selection stroke on all chips —
        // more visible than white/black in both light and dark mode
        val strokeCheckedColor = Color.parseColor("#6200EE")

        val chipSizePx = dp(44).toInt()

        return Chip(requireContext()).apply {
            text = line
            isCheckable = true
            this.isChecked = isChecked

            // No icon overlays — selection indicated by stroke alone
            isCheckedIconVisible = false
            isChipIconVisible = false
            isCloseIconVisible = false

            chipBackgroundColor = ColorStateList.valueOf(bgColor)
            setTextColor(textColor)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER

            // Stroke: thick primary-color stroke when checked, invisible when unchecked
            chipStrokeWidth = dp(3)
            chipStrokeColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(strokeCheckedColor, Color.TRANSPARENT)
            )

            // Circular shape: zero out all internal padding so ChipDrawable centres text evenly
            setEnsureMinTouchTargetSize(false)
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(chipSizePx / 2f)
                .build()
            chipMinHeight = dp(44)
            chipStartPadding = 0f
            chipEndPadding = 0f
            textStartPadding = 0f
            textEndPadding = 0f

            layoutParams = ChipGroup.LayoutParams(chipSizePx, chipSizePx)
        }
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
