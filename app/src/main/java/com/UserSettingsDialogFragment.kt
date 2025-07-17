package com.BWCTrans

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.BWCTrans.databinding.DialogUserSettingsBinding // IMPORTANT: Use the new binding class
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class UserSettingsDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogUserSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogUserSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeBtn.setOnClickListener {
            dismiss()
        }

        binding.autoPlaybackSwitch.setOnCheckedChangeListener { _, isChecked ->
            // You can save this setting to SharedPreferences here
            val message = "Auto-playback " + if (isChecked) "ON" else "OFF"
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        binding.sendFeedbackBtn.setOnClickListener {
            Toast.makeText(requireContext(), "Feedback action triggered", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
