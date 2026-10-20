package app.harmonic.music.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The last searches, newest first. Stored only on the phone. */
class SearchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<String>> = _items.asStateFlow()

    private fun load(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun save(list: List<String>) {
        _items.value = list
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun add(query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        save((listOf(q) + _items.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX))
    }

    fun remove(query: String) = save(_items.value - query)

    fun clear() = save(emptyList())

    /** Replaces the whole history (used by restore from backup). */
    fun replaceAll(list: List<String>) = save(list.take(MAX))

    private companion object {
        const val KEY = "items"
        const val MAX = 20
    }
}
