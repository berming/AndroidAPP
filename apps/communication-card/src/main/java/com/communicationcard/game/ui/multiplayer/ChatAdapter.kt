package com.communicationcard.game.ui.multiplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.communicationcard.game.R
import com.communicationcard.game.network.ChatMessage
import java.text.SimpleDateFormat
import java.util.*

/**
 * 聊天消息适配器
 */
class ChatAdapter(
    private var messages: List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    fun updateMessages(newMessages: List<ChatMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSenderName: TextView = itemView.findViewById(R.id.tvSenderName)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)

        fun bind(message: ChatMessage) {
            tvSenderName.text = message.senderName
            tvMessage.text = message.text
            tvTime.text = timeFormat.format(Date(message.timestamp))

            val context = itemView.context

            when {
                message.isSystemMessage -> {
                    tvSenderName.setTextColor(ContextCompat.getColor(context, R.color.accent))
                    tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                message.isQuickMessage -> {
                    tvSenderName.setTextColor(ContextCompat.getColor(context, R.color.primary_light))
                    tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                message.isTeamOnly -> {
                    tvSenderName.setTextColor(ContextCompat.getColor(context, R.color.team_a))
                    tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                else -> {
                    tvSenderName.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
            }
        }
    }
}
