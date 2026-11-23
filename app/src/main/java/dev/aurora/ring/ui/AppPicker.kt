package dev.aurora.ring.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

private suspend fun loadLaunchableApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    resolved.mapNotNull { info ->
        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
        AppEntry(pkg, info.loadLabel(pm)?.toString() ?: pkg)
    }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
}

private fun avatarColor(key: String): Color {
    val palette = listOf(
        Color(0xFF8B7CFF), Color(0xFF00E5C0), Color(0xFFFF4D8D),
        Color(0xFFFFC46B), Color(0xFF4D9BFF), Color(0xFFB07CFF)
    )
    return palette[(key.hashCode() and 0x7FFFFFFF) % palette.size]
}

@Composable
fun AppPickerDialog(
    title: String,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(selected) }

    LaunchedEffect(Unit) {
        apps = loadLaunchableApps(context)
        loading = false
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Aurora.BgElevated,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Aurora.Text
                )
                Text(
                    text = "${picked.size} selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                if (loading) {
                    Text(
                        text = "Loading…",
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = Aurora.TextDim
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .padding(top = 8.dp)
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
                            val checked = app.pkg in picked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        picked = if (checked) picked - app.pkg else picked + app.pkg
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(avatarColor(app.pkg).copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = app.label.take(1).uppercase(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = avatarColor(app.pkg)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Aurora.Text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.pkg,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Aurora.TextDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        picked = if (value) picked + app.pkg else picked - app.pkg
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Aurora.Mint,
                                        uncheckedColor = Aurora.TextDim,
                                        checkmarkColor = Color(0xFF07120F)
                                    )
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Aurora.TextDim)
                    }
                    TextButton(onClick = { onConfirm(picked) }) {
                        Text("Done", color = Aurora.Mint)
                    }
                }
            }
        }
    }
}
