package com.synergic.cloakroom.domain.settings

/**
 * Lookup rule for md_setting: for each setting name, the row of the logged-in user wins over the
 * customer-wide row (`user_id IS NULL`). Rows of other users are ignored.
 */
object SettingsResolver {

    fun <T> resolve(
        rows: List<T>,
        userId: Long?,
        nameOf: (T) -> String,
        userIdOf: (T) -> Long?,
    ): Map<String, T> {
        val result = LinkedHashMap<String, T>()
        rows.filter { userIdOf(it) == null }.forEach { result[nameOf(it)] = it }
        if (userId != null) {
            rows.filter { userIdOf(it) == userId }.forEach { result[nameOf(it)] = it }
        }
        return result
    }
}
