package com.dre.carbook.elm.ui

import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dre.carbook.elm.ObdSession
import com.dre.carbook.elm.TripFile
import com.dre.carbook.elm.UiState
import java.io.File
import java.util.Date

@Composable
fun TripsScreen(state: UiState, vm: ObdSession, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var toDelete by remember { mutableStateOf<TripFile?>(null) }
    LaunchedEffect(Unit) { vm.refreshTrips() }

    if (state.trips.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Записей нет. Начните запись кнопкой «● Запись» на вкладке «Панель».")
        }
        return
    }

    fun share(t: TripFile) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(vm.tripsDir, t.name))
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, t.name))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "CSV: разделитель «;», десятичная запятая — открывается в Excel и LibreOffice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.trips, key = { it.name }) { t ->
            val recording = state.recording?.fileName == t.name
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = {
                        Text(DateFormat.format("dd.MM.yyyy HH:mm", Date(t.modified)).toString())
                    },
                    supportingContent = {
                        Text(
                            if (recording) "идёт запись…" else "${t.name} · ${Formatter.formatShortFileSize(ctx, t.size)}"
                        )
                    },
                    trailingContent = {
                        if (!recording) {
                            Row {
                                if (Build.VERSION.SDK_INT >= 29) {
                                    TextButton(onClick = { vm.exportToDownloads(t.name) }) { Text("В загрузки") }
                                }
                                IconButton(onClick = { share(t) }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Поделиться")
                                }
                                IconButton(onClick = { toDelete = t }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить запись?") },
            text = { Text(t.name) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTrip(t.name)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
