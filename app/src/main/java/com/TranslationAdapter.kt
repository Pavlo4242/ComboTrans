package com.ComboTrans

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ComboTrans.databinding.ItemTranslationBinding

class TranslationAdapter : RecyclerView.Adapter<TranslationAdapter.TranslationViewHolder>() {

    private val translations = mutableListOf<TranslationItem>()
    private var lastSpeakerIsUser: Boolean? = null

    data class TranslationItem(val text: String, val isUser: Boolean, var isPending: Boolean)

    class TranslationViewHolder(val binding: ItemTranslationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TranslationViewHolder {
        val binding = ItemTranslationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TranslationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TranslationViewHolder, position: Int) {
        val item = translations[position]
        holder.binding.translationText.text = item.text
        holder.binding.speakerLabel.text = if (item.isUser) "You said:" else "Translation:"

        val textColor = if (item.isUser) R.color.user_text_color else R.color.model_text_color
        val context = holder.binding.root.context
        holder.binding.translationText.setTextColor(ContextCompat.getColor(context, textColor))
        
        // Differentiate pending messages
        holder.binding.root.alpha = if (item.isPending) 0.6f else 1.0f
    }

    override fun getItemCount() = translations.size

    fun addOrUpdateTranslation(text: String, isUser: Boolean, isPending: Boolean) {
        if (text.isBlank()) return

        // If the last speaker is the same and we're just updating their message (e.g., streaming transcription)
        if (lastSpeakerIsUser == isUser && translations.isNotEmpty()) {
            translations[0].text = text
            translations[0].isPending = isPending
            notifyItemChanged(0)
        } else {
            // Add a new message to the top of the list (which is the bottom of the screen)
            translations.add(0, TranslationItem(text, isUser, isPending))
            notifyItemInserted(0)
        }
        lastSpeakerIsUser = isUser
    }
}
