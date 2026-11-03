package com.copy.account.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedTheme(val id: String, val name: String, val json: String)
