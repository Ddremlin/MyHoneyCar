package com.dre.carbook.elm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.dre.carbook.elm.ConnState
import com.dre.carbook.elm.ObdSession
import com.dre.carbook.elm.UiState
import com.dre.carbook.elm.obd.CommandGuard

private val quickCommands = listOf("ATI", "ATRV", "ATDP", "0100", "010C", "0105", "03", "0902")

@Composable
fun TerminalScreen(state: UiState, vm: ObdSession, modifier: Modifier = Modifier) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val connected = state.conn == ConnState.CONNECTED

    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.lastIndex)
    }

    /** Опасная команда, ждущая подтверждения: (команда, чем опасна). */
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun send() {
        val cmd = CommandGuard.normalize(input) ?: return
        input = ""
        val risk = CommandGuard.risk(cmd)
        if (risk != null) pending = cmd to risk else vm.sendRaw(cmd)
    }

    pending?.let { (cmd, risk) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Отправить $cmd?") },
            text = {
                Text(
                    "$risk.\n\nКоманда может изменить работу автомобиля или адаптера. " +
                        "Отправляйте, только если точно знаете, что она делает."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    vm.sendRaw(cmd, confirmed = true)
                }) { Text("Отправить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } },
        )
    }

    Column(modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(state.log) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.startsWith(">")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickCommands.forEach { c ->
                AssistChip(onClick = { vm.sendRaw(c) }, label = { Text(c) }, enabled = connected)
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("AT-команда или PID, напр. 010D") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
                enabled = connected,
            )
            IconButton(onClick = { send() }, enabled = connected) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
            }
            IconButton(onClick = { vm.clearLog() }) {
                Icon(Icons.Filled.Delete, contentDescription = "Очистить")
            }
        }
    }
}
