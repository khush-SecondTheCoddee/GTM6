package com.nextgen.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { EditorScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(viewModel: EditorViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("NextGen Editor") }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
        bottomBar = { FormattingRibbon(state, viewModel) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            val value = remember(state.text, state.selectionStart, state.selectionEnd) { TextFieldValue(state.text, androidx.compose.ui.text.TextRange(state.selectionStart, state.selectionEnd)) }
            val horizontal = rememberScrollState()
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f))
                    .then(if (state.reflowMode) Modifier else Modifier.horizontalScroll(horizontal)),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    modifier = Modifier.padding(if (state.reflowMode) 12.dp else 24.dp).then(if (state.reflowMode) Modifier.fillMaxWidth() else Modifier.width(794.dp)),
                    shadowElevation = if (state.reflowMode) 0.dp else 3.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { viewModel.updateText(it.text, it.selection.start, it.selection.end) },
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun FormattingRibbon(state: EditorUiState, viewModel: EditorViewModel) {
    Surface(shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            RibbonButton("Bold", viewModel::toggleBold) { Icon(Icons.Default.FormatBold, "Bold") }
            RibbonButton("Italic", viewModel::toggleItalic) { Icon(Icons.Default.FormatItalic, "Italic") }
            listOf(1, 2, 3).forEach { level -> Button(onClick = { viewModel.setHeading(level) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("H$level") } }
            RibbonButton("Bullet list", viewModel::toggleBullet) { Icon(Icons.Default.FormatListBulleted, "Bullet list") }
            HorizontalDivider(Modifier.height(28.dp).width(1.dp))
            RibbonButton("Align start", { viewModel.setAlignment(Alignment.START) }) { Icon(Icons.Default.FormatAlignLeft, "Align start") }
            RibbonButton("Align center", { viewModel.setAlignment(Alignment.CENTER) }) { Icon(Icons.Default.FormatAlignCenter, "Align center") }
            RibbonButton("Justify", { viewModel.setAlignment(Alignment.JUSTIFY) }) { Icon(Icons.Default.FormatAlignJustify, "Justify") }
            Spacer(Modifier.width(8.dp))
            RibbonButton(if (state.reflowMode) "Print layout" else "Reflow mode", viewModel::toggleReflow) { Icon(Icons.Default.PhoneAndroid, "Layout mode", tint = if (state.reflowMode) MaterialTheme.colorScheme.primary else Color.Unspecified) }
        }
    }
}

@Composable
private fun RibbonButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    IconButton(onClick = onClick) { icon() }
}
