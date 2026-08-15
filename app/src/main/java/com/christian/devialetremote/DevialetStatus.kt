package com.christian.devialetremote

data class DevialetSource(
    val name: String,
    val index: Int,
    val isEnabled: Boolean,
    val isSelected: Boolean
)

data class DevialetStatus(
    val deviceName: String,
    val powerOn: Boolean,
    val muted: Boolean,
    val volumeInt: Int,
    val sourceIndex: Int,
    val sources: List<DevialetSource>
) {
    val volumeDb: Double get() = (volumeInt - 195) / 2.0
    val currentSourceName: String get() = sources.getOrNull(sourceIndex)?.name ?: "?"
    val enabledSources: List<DevialetSource> get() = sources.filter { it.isEnabled }
}
