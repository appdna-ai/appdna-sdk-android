package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SPEC-070-A I.18 — Reusable location-autocomplete composable for legacy
 * form-step `FormFieldType.LOCATION` fields. Ports the autocomplete logic
 * previously embedded inline in
 * `ContentBlockRenderer.kt:3840-3974 FormInputLocationPlaceholder` so the
 * legacy onboarding `FormStepComposable` can use the same UI fidelity that
 * block-based rendering already enjoys.
 *
 * Wires three [FormFieldConfig] knobs:
 *   - `location_type` — surfaced as an `x-location-type` query header so
 *     the backend can return only the requested level (city / region /
 *     country / address). Falls back to "address".
 *   - `location_bias_country` — ISO 3166-1 alpha-2 country bias hint
 *     (`country=us`).
 *   - `location_language` — BCP-47 language tag for response strings
 *     (`language=fr-FR`).
 *   - `location_min_chars` — minimum keystrokes before triggering the
 *     /api/v1/sdk/geocode/autocomplete debounce (default 3).
 *
 * The IME action is `Search` so the keyboard's enter key submits the
 * query. Mirrors iOS `Onboarding/OnboardingStepViews/LocationFieldView.swift`.
 */
@Composable
fun LocationFieldComposable(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
    modifier: Modifier = Modifier,
) {
    val config = field.config
    val minChars = (config?.location_min_chars ?: 3).coerceAtLeast(1)
    val placeholder = config?.location_placeholder
        ?: field.placeholder
        ?: "Search for a location..."
    val locationType = config?.location_type ?: "address"
    val biasCountry = config?.location_bias_country
    val language = config?.location_language

    val initialText = (values[field.id] as? Map<*, *>)?.get("address") as? String
        ?: values[field.id]?.toString().orEmpty()
    var text by remember { mutableStateOf(initialText) }
    var suggestions by remember { mutableStateOf<List<LocationSuggestionData>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                values[field.id] = newText
                errors.remove(field.id)
                debounceJob?.cancel()
                if (newText.length >= minChars) {
                    debounceJob = coroutineScope.launch {
                        delay(300)
                        isSearching = true
                        suggestions = fetchLocationSuggestions(
                            query = newText,
                            locationType = locationType,
                            biasCountry = biasCountry,
                            language = language,
                        )
                        showSuggestions = suggestions.isNotEmpty()
                        isSearching = false
                    }
                } else {
                    suggestions = emptyList()
                    showSuggestions = false
                }
            },
            label = { Text(field.label) },
            placeholder = { Text(placeholder) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
            // SPEC-070-A I.5b — Search IME action so Enter triggers a
            // keyboard-driven submit (autocomplete still fires on debounce).
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = errors.containsKey(field.id),
        )

        if (showSuggestions) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF9FAFB))
                    .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp)),
            ) {
                suggestions.forEachIndexed { index, suggestion ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                text = suggestion.address
                                showSuggestions = false
                                // Persist structured shape so hosts get
                                // typed lat/lng — matches iOS LocationData.
                                values[field.id] = mapOf(
                                    "address" to suggestion.address,
                                    "latitude" to suggestion.latitude,
                                    "longitude" to suggestion.longitude,
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(text = suggestion.address, fontSize = 14.sp, color = Color.Black)
                    }
                    if (index < suggestions.size - 1) {
                        @Suppress("DEPRECATION")
                        Divider(color = Color.LightGray.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

internal data class LocationSuggestionData(
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Calls `/api/v1/sdk/geocode/autocomplete?q=…` (host comes from the
 * configured environment) and forwards the SDK API key via `x-api-key`.
 * Matches the existing inline implementation in `ContentBlockRenderer.kt`.
 */
private suspend fun fetchLocationSuggestions(
    query: String,
    locationType: String,
    biasCountry: String?,
    language: String?,
): List<LocationSuggestionData> {
    return try {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val baseUrl = ai.appdna.sdk.AppDNA.getApiBaseUrl()
        val apiKey = ai.appdna.sdk.AppDNA.getApiKey()
        val params = StringBuilder("q=$encodedQuery&type=$locationType")
        if (!biasCountry.isNullOrBlank()) {
            params.append("&country=").append(java.net.URLEncoder.encode(biasCountry, "UTF-8"))
        }
        if (!language.isNullOrBlank()) {
            params.append("&language=").append(java.net.URLEncoder.encode(language, "UTF-8"))
        }
        val url = java.net.URL("$baseUrl/api/v1/sdk/geocode/autocomplete?$params")
        val connection = withContext(Dispatchers.IO) {
            (url.openConnection() as? java.net.HttpURLConnection)?.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                if (apiKey != null) setRequestProperty("x-api-key", apiKey)
            }
        }
        if (connection == null || connection.responseCode != 200) return emptyList()
        val body = withContext(Dispatchers.IO) {
            connection.inputStream.bufferedReader().readText()
        }
        val json = JSONObject(body)
        val results = json.optJSONArray("data") ?: return emptyList()
        (0 until minOf(results.length(), 5)).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            LocationSuggestionData(
                address = item.optString("formatted_address", ""),
                latitude = item.optDouble("latitude", 0.0),
                longitude = item.optDouble("longitude", 0.0),
            )
        }
    } catch (e: Exception) {
        ai.appdna.sdk.Log.debug("LocationFieldComposable autocomplete failed: ${e.message}")
        emptyList()
    }
}
