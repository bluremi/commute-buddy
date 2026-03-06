package com.commutebuddy.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class LinePickerBottomSheet : BottomSheetDialogFragment() {

    fun interface Callback {
        fun onLinesSelected(lines: List<String>)
    }

    companion object {
        private const val ARG_SELECTED = "selected_lines"

        val ALL_LINES = listOf(
            "1", "2", "3", "4", "5", "6", "7",
            "A", "B", "C", "D", "E", "F", "G",
            "J", "L", "M", "N", "Q", "R", "S", "W", "Z"
        )

        fun newInstance(selected: List<String>, callback: Callback): LinePickerBottomSheet {
            val fragment = LinePickerBottomSheet()
            fragment.arguments = Bundle().apply {
                putStringArrayList(ARG_SELECTED, ArrayList(selected))
            }
            fragment.callback = callback
            return fragment
        }
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
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroup)

        for (line in ALL_LINES) {
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, chipGroup, false) as Chip
            chip.text = line
            chip.isChecked = line in selected
            chipGroup.addView(chip)
        }

        view.findViewById<MaterialButton>(R.id.doneButton).setOnClickListener {
            val result = mutableListOf<String>()
            for (i in 0 until chipGroup.childCount) {
                val chip = chipGroup.getChildAt(i) as Chip
                if (chip.isChecked) result.add(chip.text.toString())
            }
            callback?.onLinesSelected(result)
            dismiss()
        }
    }
}
