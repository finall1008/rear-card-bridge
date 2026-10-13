package dev.finall.rearcardbridge.packageio

data class StaticResourceChanges(
    val added: List<String>,
    val modified: List<String>,
    val removed: List<String>,
) {
    val changed: Boolean
        get() = added.isNotEmpty() || modified.isNotEmpty() || removed.isNotEmpty()
}
