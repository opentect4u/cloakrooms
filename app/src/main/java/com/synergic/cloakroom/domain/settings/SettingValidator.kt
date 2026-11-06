package com.synergic.cloakroom.domain.settings

import java.math.BigDecimal

/** Checks new setting values against the row's data_type, options, min/max and depends_on. */
object SettingValidator {

    /** Returns an error message, or null when [value] is valid for [def]. PASSWORD values are plain text here. */
    fun validateValue(def: SettingDefinition, value: String): String? {
        val v = value.trim()
        return when (def.dataType) {
            SettingDataType.BOOL -> if (v == "Y" || v == "N") null else "Must be Y or N"
            SettingDataType.INT -> {
                val number = v.toBigIntegerOrNull() ?: return "Enter a whole number"
                checkRange(def, number.toBigDecimal())
            }
            SettingDataType.DECIMAL -> {
                val number = v.toBigDecimalOrNull() ?: return "Enter a number"
                checkRange(def, number)
            }
            SettingDataType.ENUM -> if (def.options.any { it.first == v }) null
                else "Choose one of: ${def.options.joinToString { it.second }}"
            SettingDataType.TEXT, SettingDataType.PASSWORD -> null
        }
    }

    /**
     * Validates a batch of [changes] (name → new value) against the current [definitions].
     * A setting whose depends_on switch is off (after the batch) cannot be changed, and a
     * PASSWORD setting that is switched on must have a password.
     * Returns setting name → error; empty when everything is valid.
     */
    fun validateChanges(definitions: List<SettingDefinition>, changes: Map<String, String>): Map<String, String> {
        val byName = definitions.associateBy { it.name }
        val errors = LinkedHashMap<String, String>()
        fun effective(name: String): String? = changes[name] ?: byName[name]?.value

        changes.forEach { (name, value) ->
            val def = byName[name]
            if (def == null) {
                errors[name] = "Unknown setting"
                return@forEach
            }
            val parent = def.dependsOn
            if (parent != null && effective(parent) != "Y") {
                errors[name] = "Turn on ${byName[parent]?.label ?: parent} first"
                return@forEach
            }
            validateValue(def, value)?.let { errors[name] = it }
        }

        definitions.filter { it.dataType == SettingDataType.PASSWORD }.forEach { def ->
            val switchedOn = def.dependsOn == null || effective(def.dependsOn) == "Y"
            if (switchedOn && effective(def.name).isNullOrEmpty() && def.name !in errors) {
                errors[def.name] = "Set a password"
            }
        }
        return errors
    }

    private fun checkRange(def: SettingDefinition, number: BigDecimal): String? {
        def.minValue?.let { if (number < BigDecimal.valueOf(it)) return "Must be at least ${plain(it)}" }
        def.maxValue?.let { if (number > BigDecimal.valueOf(it)) return "Must be at most ${plain(it)}" }
        return null
    }

    private fun plain(d: Double): String = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
}
