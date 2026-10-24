package com.inkreader.tts

enum class ComputerVoiceModel(val wireName: String, val title: String) {
    AUTO("auto", "自动"),
    XIAOXIAO("xiaoxiao", "晓晓"),
    NANAMI("nanami", "七海");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.wireName == value } ?: AUTO
    }
}
