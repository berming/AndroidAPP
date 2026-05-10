package com.communicationcard.game.ui.multiplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.communicationcard.game.R
import com.communicationcard.game.network.ChatMessage

/**
 * 聊天消息适配器
 */
class ChatAdapter(
    private var messages: List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

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
        private val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)

        fun bind(message: ChatMessage) {
            tvSender.text = "${message.senderName}:"
            tvMessage.text = message.text

            val context = itemView.context

            when {
                message.isSystemMessage -> {
                    tvSender.setTextColor(ContextCompat.getColor(context, R.color.accent))
                    tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                message.isQuickMessage -> {
                    tvSender.setTextColor(ContextCompat.getColor(context, R.color.primary_light))
                    tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                message.isTeamOnly -> {
                    tvSender.setTextColor(ContextCompat.getColor(context, R.color.team_a))
                    tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                else -> {
                    tvSender.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
            }
        }
    }
}
