package com.dre.carbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import com.dre.carbook.DueState
import com.dre.carbook.dateText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
    error: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier,
        singleLine = singleLine, isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        suffix = suffix?.let { { Text(it) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: LocalDate, onChange: (LocalDate) -> Unit, label: String = "Дата", modifier: Modifier = Modifier.fillMaxWidth()) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = dateText(date), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.DateRange, null) }, modifier = Modifier.fillMaxWidth(),
        )
        // перехватываем нажатие поверх поля
        Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null) { open = true })
    }
    if (open) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        ) { DatePicker(st) }
    }
}

fun stateColor(s: DueState): Color = when (s) {
    DueState.OVERDUE -> Color(0xFFD32F2F)
    DueState.SOON -> Color(0xFFF57C00)
    DueState.OK -> Color(0xFF388E3C)
    DueState.UNKNOWN -> Color(0xFF9E9E9E)
}

/** Палитра для категорий в статистике. */
val chartColors = listOf(
    Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFFE53935),
    Color(0xFF00ACC1), Color(0xFF6D4C41), Color(0xFFFDD835), Color(0xFF3949AB), Color(0xFFD81B60),
    Color(0xFF7CB342), Color(0xFF757575),
)

/**
 * Текстовое поле с выпадающими подсказками. Вводить можно что угодно — подсказка лишь подставляет текст.
 * [suggest] получает введённый текст и возвращает варианты.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    suggest: (String) -> List<String>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // уже выбранный вариант не предлагаем повторно
    val options = suggest(value).filterNot { it == value }
    androidx.compose.material3.ExposedDropdownMenuBox(
        expanded = expanded && options.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); expanded = true },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded && options.isNotEmpty()) },
            modifier = Modifier.fillMaxWidth()
                .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && options.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(o) },
                    onClick = { onChange(o); expanded = false },
                )
            }
        }
    }
}
