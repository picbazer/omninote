package com.example.data.ai

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false
) {
    enum class Role {
        USER,
        MODEL
    }
}
