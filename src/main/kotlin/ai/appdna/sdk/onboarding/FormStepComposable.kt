package ai.appdna.sdk.onboarding

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.interpolated
import java.text.SimpleDateFormat
import java.util.*

/**
 * Form step composable: renders native input controls for each FormField (SPEC-082).
 */
@Composable
fun FormStep(
    config: StepConfig,
    onNext: (Map<String, Any>?) -> Unit,
    /**
     * SPEC-070-A finalization B4 P1 — previously-entered responses
     * (one map keyed by field_id) used to restore form state on back nav.
     * Mirrors iOS FormStepView.savedValues (FormStepView.swift:9,119-123).
     */
    savedValues: Map<String, Any>? = null,
) {
    val fields = config.fields ?: emptyList()
    val values = remember { mutableStateMapOf<String, Any?>() }
    val errors = remember { mutableStateMapOf<String, String>() }

    // Initialize defaults + savedValues. Order: savedValues (back-nav
    // restoration) wins over fieldDefaults wins over per-field default_value.
    LaunchedEffect(fields) {
        // SPEC-070-A finalization B4 P1 — restore from prior responses first.
        savedValues?.forEach { (fieldId, value) ->
            if (values[fieldId] == null) values[fieldId] = value
        }
        // SPEC-083: Apply fieldDefaults from StepConfigOverride next
        config.field_defaults?.forEach { (fieldId, value) ->
            if (values[fieldId] == null) {
                values[fieldId] = value
            }
        }
        // Then apply per-field default_value (doesn't override hook-injected defaults)
        fields.forEach { field ->
            if (values[field.id] == null) {
                field.config?.default_value?.let { values[field.id] = it }
            }
        }
    }

    val visibleFields = fields.filter { field ->
        val dep = field.depends_on ?: return@filter true
        val depValue = values[dep.field_id]
        when (dep.operator) {
            "not_empty", "is_set" -> depValue != null && depValue.toString().isNotEmpty()
            "empty" -> depValue == null || depValue.toString().isEmpty()
            "equals" -> depValue.toString() == dep.value.toString()
            "not_equals" -> depValue.toString() != dep.value.toString()
            "contains" -> depValue.toString().contains(dep.value.toString())
            "gt" -> (depValue as? Number)?.toDouble()?.let { it > (dep.value as? Number)?.toDouble() ?: 0.0 } ?: false
            "lt" -> (depValue as? Number)?.toDouble()?.let { it < (dep.value as? Number)?.toDouble() ?: 0.0 } ?: false
            else -> true
        }
    }

    val canSubmit = visibleFields.filter { it.required }.all { field ->
        val v = values[field.id]
        v != null && v.toString().isNotEmpty()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // SPEC-070-A finalization B4 P1 — image_url header above the
            // title, capped at 120dp tall like iOS FormStepView.swift:22-32.
            // Uses Coil via NetworkImage so SVG / PNG / GIF all render.
            config.image_url?.takeIf { it.isNotBlank() }?.let { url ->
                ai.appdna.sdk.core.NetworkImage(
                    url = url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    contentDescription = null,
                )
                Spacer(Modifier.height(12.dp))
            }

            // Header
            config.title?.let {
                Text(
                    text = it.interpolated(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            config.subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it.interpolated(),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // Fields
            visibleFields.forEach { field ->
                if (field.type != FormFieldType.TOGGLE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = field.label.interpolated(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (field.required) {
                            Text(
                                text = " *",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                FormFieldControl(field, values, errors)

                errors[field.id]?.let { error ->
                    Text(
                        text = error,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // CTA
        Button(
            onClick = {
                errors.clear()
                // Validate
                visibleFields.forEach { field ->
                    if (field.required) {
                        val v = values[field.id]
                        if (v == null || v.toString().isEmpty()) {
                            errors[field.id] = "${field.label} is required"
                        }
                    }
                    field.validation?.pattern?.let { pattern ->
                        val v = values[field.id]?.toString() ?: ""
                        // SPEC-070-A I.5 — `containsMatchIn` for partial-match
                        // parity with iOS `NSRegularExpression.firstMatch`.
                        // Authors typically write patterns like `\d{6}` to test
                        // *contains* a 6-digit run, not anchored full-string.
                        if (v.isNotEmpty() && !Regex(pattern).containsMatchIn(v)) {
                            errors[field.id] = field.validation.pattern_message ?: "Invalid format"
                        }
                    }
                }
                if (errors.isEmpty()) {
                    val response = values.mapValues { (_, v) -> v ?: "" }
                    @Suppress("UNCHECKED_CAST")
                    onNext(response as Map<String, Any>)
                }
            },
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = (config.cta_text ?: "Continue").interpolated(),
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun FormFieldControl(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>
) {
    when (field.type) {
        FormFieldType.TEXT, FormFieldType.EMAIL, FormFieldType.PHONE -> {
            // SPEC-070-A I.5b — keyboardType selection now respects the
            // optional `field.config.keyboard_type` override ("url",
            // "number", "decimal", "phone", "email", "ascii", "default").
            // Falls back to the FormFieldType-derived default. iOS reads
            // `keyboard_type` from `OnboardingConfig.keyboard_type` to back
            // the same field-level override; Android does the same.
            val keyboardType = resolveKeyboardType(field)
            OutlinedTextField(
                value = values[field.id]?.toString() ?: "",
                onValueChange = { values[field.id] = it; errors.remove(field.id) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
                // SPEC-070-A I.5b — `imeAction = Done` so the soft keyboard
                // shows a "Done" key that dismisses the IME. Mirrors iOS
                // `submitLabel(.done)`.
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                isError = errors.containsKey(field.id),
                singleLine = true
            )
        }

        FormFieldType.TEXTAREA -> {
            OutlinedTextField(
                value = values[field.id]?.toString() ?: "",
                onValueChange = { values[field.id] = it; errors.remove(field.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
                isError = errors.containsKey(field.id),
                maxLines = 5
            )
        }

        FormFieldType.NUMBER -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = values[field.id]?.toString() ?: "",
                    onValueChange = {
                        values[field.id] = it.toDoubleOrNull() ?: it
                        errors.remove(field.id)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
                    // SPEC-070-A I.5b — number keyboard + Done IME action.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    isError = errors.containsKey(field.id),
                    singleLine = true
                )
                field.config?.unit?.let { unit ->
                    Spacer(Modifier.width(8.dp))
                    Text(text = unit, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
        }

        FormFieldType.DATE -> {
            DateField(field, values, errors)
        }

        FormFieldType.TIME -> {
            TimeField(field, values, errors)
        }

        FormFieldType.DATETIME -> {
            DateField(field, values, errors)
            Spacer(Modifier.height(8.dp))
            TimeField(field, values, errors)
        }

        FormFieldType.SELECT -> {
            SelectField(field, values, errors)
        }

        FormFieldType.SLIDER -> {
            val min = field.config?.min_value?.toFloat() ?: 0f
            val max = field.config?.max_value?.toFloat() ?: 100f
            val step = field.config?.step?.toFloat() ?: 1f
            val unit = field.config?.unit ?: ""
            val current = (values[field.id] as? Number)?.toFloat() ?: min

            Text(
                text = "${current.toInt()}${if (unit.isNotEmpty()) " $unit" else ""}",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Slider(
                value = current,
                onValueChange = { values[field.id] = it.toDouble() },
                valueRange = min..max,
                steps = if (step > 0 && max > min) ((max - min) / step).toInt() - 1 else 0,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${min.toInt()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                Text("${max.toInt()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        }

        FormFieldType.TOGGLE -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(field.label.interpolated(), fontSize = 16.sp)
                Switch(
                    checked = values[field.id] as? Boolean ?: false,
                    onCheckedChange = { values[field.id] = it }
                )
            }
        }

        FormFieldType.STEPPER -> {
            val min = field.config?.min_value?.toInt() ?: 0
            val max = field.config?.max_value?.toInt() ?: 100
            val step = field.config?.step?.toInt() ?: 1
            val current = (values[field.id] as? Number)?.toInt() ?: min

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (current - step >= min) values[field.id] = current - step },
                    enabled = current - step >= min
                ) {
                    Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "$current${field.config?.unit?.let { " $it" } ?: ""}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 48.dp)
                )
                Spacer(Modifier.width(16.dp))
                IconButton(
                    onClick = { if (current + step <= max) values[field.id] = current + step },
                    enabled = current + step <= max
                ) {
                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        FormFieldType.LOCATION -> {
            // SPEC-070-A I.18 — full autocomplete location input. Reuses the
            // dedicated `LocationFieldComposable` so the legacy form-step
            // path matches block-based rendering fidelity. Honours
            // `location_type`, `location_bias_country`, `location_language`,
            // `location_min_chars`, `location_placeholder` from
            // `OnboardingConfig.kt:204-208`.
            LocationFieldComposable(
                field = field,
                values = values,
                errors = errors,
            )
        }
        FormFieldType.SEGMENTED -> {
            val options = field.options ?: emptyList()
            val selected = values[field.id]?.toString() ?: options.firstOrNull()?.id ?: ""
            if (options.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    options.forEachIndexed { index, option ->
                        val isSelected = selected == option.id
                        val shape = when {
                            options.size == 1 -> RoundedCornerShape(8.dp)
                            index == 0 -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                            index == options.size - 1 -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                            else -> RoundedCornerShape(0.dp)
                        }
                        OutlinedButton(
                            onClick = { values[field.id] = option.id },
                            modifier = Modifier.weight(1f),
                            shape = shape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(option.label.interpolated(), fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>
) {
    val context = LocalContext.current
    val dateStr = values[field.id]?.toString() ?: ""

    OutlinedButton(
        onClick = {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    // SPEC-070-A I.6 — ISO8601 (`yyyy-MM-dd`) submission
                    // matches iOS `DateFormatter.iso8601` round-trip exactly.
                    // Locale.US prevents Arabic-numeral substitution
                    // (Locale.getDefault() in fa-IR / ar would format
                    // `۱۴۰۲-۰۱-۰۵` and break server geocoding).
                    val formatted = String.format(
                        Locale.US,
                        "%04d-%02d-%02d",
                        year, month + 1, day,
                    )
                    values[field.id] = formatted
                    errors.remove(field.id)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = dateStr.ifEmpty { field.placeholder ?: "Select date" },
            color = if (dateStr.isEmpty())
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TimeField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>
) {
    val context = LocalContext.current
    val timeKey = "${field.id}_time"
    val timeStr = values[timeKey]?.toString() ?: ""

    OutlinedButton(
        onClick = {
            val cal = Calendar.getInstance()
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    // SPEC-070-A I.6/I.10 — `Locale.US` keeps digits ASCII-numeric
                    // so the time round-trips with iOS server geocoding regardless
                    // of device locale (Arabic / Persian / Bengali otherwise emit
                    // their native numerals here).
                    val formatted = String.format(Locale.US, "%02d:%02d", hour, minute)
                    values[timeKey] = formatted
                    // For datetime, merge date + time as ISO8601 (`YYYY-MM-DDTHH:MM`).
                    val dateStr = values[field.id]?.toString() ?: ""
                    if (dateStr.isNotEmpty() && field.type == FormFieldType.DATETIME) {
                        values[field.id] = "${dateStr}T$formatted"
                    } else {
                        values[field.id] = formatted
                    }
                    errors.remove(field.id)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = timeStr.ifEmpty { field.placeholder ?: "Select time" },
            color = if (timeStr.isEmpty())
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * SPEC-070-A I.5b — derive a [KeyboardType] for a [FormField]. Honours the
 * optional `keyboard_type` config override ("url", "number", "decimal",
 * "phone", "email", "ascii", "default") and falls back to the field type.
 */
private fun resolveKeyboardType(field: FormField): KeyboardType {
    val override = field.config?.keyboard_type?.lowercase()
    if (override != null) {
        when (override) {
            "url", "uri" -> return KeyboardType.Uri
            "number", "numeric" -> return KeyboardType.Number
            "decimal" -> return KeyboardType.Decimal
            "phone", "tel" -> return KeyboardType.Phone
            "email" -> return KeyboardType.Email
            "ascii" -> return KeyboardType.Ascii
            "password" -> return KeyboardType.Password
            "number_password" -> return KeyboardType.NumberPassword
            "default", "text" -> return KeyboardType.Text
        }
    }
    return when (field.type) {
        FormFieldType.EMAIL -> KeyboardType.Email
        FormFieldType.PHONE -> KeyboardType.Phone
        FormFieldType.NUMBER -> KeyboardType.Number
        else -> KeyboardType.Text
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>
) {
    val options = field.options ?: emptyList()
    var expanded by remember { mutableStateOf(false) }
    val selected = values[field.id]?.toString() ?: ""
    val selectedLabel = options.find { it.id == selected }?.label ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = errors.containsKey(field.id)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label.interpolated()) },
                    onClick = {
                        values[field.id] = option.id
                        errors.remove(field.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
