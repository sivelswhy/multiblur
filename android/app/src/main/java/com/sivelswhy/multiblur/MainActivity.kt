package com.sivelswhy.multiblur

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.os.LocaleListCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MultiBlurTheme {
                // Processing stops if the phone goes to sleep: keep the screen on meanwhile.
                val busy = model.busy
                DisposableEffect(busy) {
                    if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { }
                }
                MainScreen(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Files shared to MultiBlur from the gallery or another app. */
    private fun handleShare(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) model.add(uris)
    }
}

@Composable
private fun MultiBlurTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8AB4FF), background = Color(0xFF1C1C1F), surface = Color(0xFF1C1C1F))
        else -> lightColorScheme(primary = Color(0xFF0A62D0))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(model: MainViewModel) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(PickMultipleVisualMedia(MAX_FILES)) { uris -> if (uris.isNotEmpty()) model.add(uris) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(painterResource(R.drawable.ic_tune), contentDescription = stringResource(R.string.settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item { ModelsCard(model) }
            item { AddCard { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (model.queue.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                        val done = model.queue.count { it.status == Status.DONE }
                        Text(
                            stringResource(R.string.queue_progress, done, model.queue.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = model::clearFinished) { Text(stringResource(R.string.clear_finished)) }
                    }
                }
            }
            items(model.queue, key = { System.identityHashCode(it) }) { item -> ItemCard(item, model) }
        }
    }

    if (showSettings) SettingsSheet(model) { showSettings = false }
}

@Composable
private fun ModelsCard(model: MainViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.models), style = MaterialTheme.typography.titleSmall)
            ModelRow(stringResource(R.string.model_plates), model.platesPhase)
            ModelRow(stringResource(R.string.model_faces), model.facesPhase)
            model.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ModelRow(label: String, phase: ModelPhase) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val (text, color) = when (phase) {
                ModelPhase.WAITING -> R.string.model_waiting to MaterialTheme.colorScheme.onSurfaceVariant
                ModelPhase.LOADING -> R.string.model_loading to MaterialTheme.colorScheme.onSurfaceVariant
                ModelPhase.READY -> R.string.model_ready to MaterialTheme.colorScheme.primary
                ModelPhase.FAILED -> R.string.model_failed to MaterialTheme.colorScheme.error
            }
            Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = color)
        }
        when (phase) {
            ModelPhase.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> LinearProgressIndicator(progress = { if (phase == ModelPhase.READY) 1f else 0f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AddCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_add), null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.add_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.add_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun ItemCard(item: Item, model: MainViewModel) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = item.status == Status.DONE) { open(context, item) },
                ) {
                    item.thumbnail?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        detail(item, model),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == Status.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.result?.warning?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (item.status == Status.PROCESSING) {
                        Spacer(Modifier.height(8.dp))
                        val progress = item.progress
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when (item.status) {
                    Status.PROCESSING -> OutlinedButton(onClick = { model.stop(item) }) { Text(stringResource(R.string.stop)) }
                    Status.DONE -> {
                        TextButton(onClick = { model.remove(item) }) { Text(stringResource(R.string.remove)) }
                        OutlinedButton(onClick = { share(context, item) }) { Text(stringResource(R.string.share)) }
                        FilledTonalButton(onClick = { open(context, item) }) { Text(stringResource(R.string.open)) }
                    }
                    else -> TextButton(onClick = { model.remove(item) }) { Text(stringResource(R.string.remove)) }
                }
            }
        }
    }
}

@Composable
private fun detail(item: Item, model: MainViewModel): String = when (item.status) {
    Status.QUEUED -> stringResource(if (model.platesPhase == ModelPhase.READY && model.facesPhase == ModelPhase.READY) R.string.item_queued else R.string.item_waiting_models)
    Status.PROCESSING -> {
        val progress = item.progress
        val left = item.secondsLeft
        when {
            progress == null -> stringResource(R.string.item_starting)
            left != null -> stringResource(R.string.item_progress_left, (progress * 100).roundToInt(), duration(left))
            else -> stringResource(R.string.item_progress, (progress * 100).roundToInt())
        }
    }
    Status.DONE -> {
        val result = item.result!!
        val parts = buildList {
            if (result.plates >= 0) add(pluralStringResource(R.plurals.item_plates, result.plates, result.plates))
            if (result.faces >= 0) add(pluralStringResource(R.plurals.item_faces, result.faces, result.faces))
        }
        "${parts.joinToString(", ").ifEmpty { stringResource(R.string.item_nothing) }} · ${Formatter.formatShortFileSize(LocalContext.current, result.bytes)}"
    }
    Status.ERROR -> item.errorRes?.let { stringResource(it) } ?: item.errorText.orEmpty()
    Status.CANCELED -> stringResource(R.string.item_stopped)
}

private fun duration(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
    seconds < 3600 -> "${(seconds / 60.0).roundToInt()} min"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

private fun open(context: android.content.Context, item: Item) {
    val result = item.result ?: return
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(result.uri, result.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

private fun share(context: android.content.Context, item: Item) {
    val result = item.result ?: return
    val send = Intent(Intent.ACTION_SEND).setType(result.mime).putExtra(Intent.EXTRA_STREAM, result.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

// ------------------------------------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(model: MainViewModel, onDismiss: () -> Unit) {
    val options = model.options
    val replacementPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let(model::setReplacement) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
        ) {
            Section(R.string.mask) {
                SwitchRow(R.string.plates, options.maskPlates) { v -> model.update { it.copy(maskPlates = v) } }
                SwitchRow(R.string.faces, options.maskFaces) { v -> model.update { it.copy(maskFaces = v) } }
            }

            Section(R.string.style) {
                Segmented(
                    listOf(MaskMode.BLUR to R.string.mode_blur, MaskMode.MOSAIC to R.string.mode_mosaic, MaskMode.SOLID to R.string.mode_solid, MaskMode.IMAGE to R.string.mode_image),
                    options.mode,
                ) { v -> model.update { it.copy(mode = v) } }
                if (options.mode == MaskMode.MOSAIC) {
                    SliderRow(R.string.block_size, options.mosaicSize.toFloat(), 4f..60f, "${options.mosaicSize} px") { v ->
                        model.update { it.copy(mosaicSize = v.roundToInt()) }
                    }
                }
                if (options.mode == MaskMode.IMAGE) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.replacement)) },
                        supportingContent = { Text(model.replacementName ?: stringResource(R.string.replacement_note)) },
                        trailingContent = {
                            FilledTonalButton(onClick = { replacementPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) {
                                Text(stringResource(R.string.choose))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                Label(R.string.face_shape)
                Segmented(listOf(FaceShape.ELLIPSE to R.string.shape_ellipse, FaceShape.BOX to R.string.shape_box), options.faceShape) { v ->
                    model.update { it.copy(faceShape = v) }
                }
            }

            Section(R.string.detection) {
                SliderRow(R.string.plate_confidence, options.plateConfidence, 0.05f..0.9f, "%.2f".format(options.plateConfidence), R.string.plate_confidence_note) { v ->
                    model.update { it.copy(plateConfidence = step(v)) }
                }
                SliderRow(R.string.face_confidence, options.faceThreshold, 0.05f..0.9f, "%.2f".format(options.faceThreshold)) { v ->
                    model.update { it.copy(faceThreshold = step(v)) }
                }
                SliderRow(R.string.plate_margin, options.plateMaskScale, 1f..1.6f, "×%.2f".format(options.plateMaskScale)) { v ->
                    model.update { it.copy(plateMaskScale = step(v)) }
                }
                SliderRow(R.string.face_margin, options.faceMaskScale, 1f..1.8f, "×%.2f".format(options.faceMaskScale)) { v ->
                    model.update { it.copy(faceMaskScale = step(v)) }
                }
                Label(R.string.face_resolution)
                Segmented(listOf(0 to R.string.resolution_full, 1920 to null, 1280 to null, 640 to null), options.faceResolution) { v ->
                    model.update { it.copy(faceResolution = v) }
                }
                Label(R.string.small_objects)
                Segmented(
                    listOf(SmallObjects.OFF to R.string.small_off, SmallObjects.PHOTOS to R.string.small_photos, SmallObjects.ALL to R.string.small_all),
                    options.smallObjects,
                ) { v -> model.update { it.copy(smallObjects = v) } }
                SwitchRow(R.string.scores, options.drawScores) { v -> model.update { it.copy(drawScores = v) } }
            }

            Section(R.string.output) {
                SwitchRow(R.string.keep_audio, options.keepAudio) { v -> model.update { it.copy(keepAudio = v) } }
                Text(
                    stringResource(R.string.output_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Section(R.string.language) {
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
                Segmented(listOf("" to R.string.language_system, "en" to null, "fr" to null), current) { tag ->
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
            }

            TextButton(onClick = model::reset, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.reset)) }
        }
    }
}

/** Sliders move in steps of 0.05. */
private fun step(value: Float) = (value * 20).roundToInt() / 20f

private val languageNames = mapOf("en" to "English", "fr" to "Français")

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Label(text: Int) {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
}

@Composable
private fun SwitchRow(text: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(text)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun SliderRow(text: Int, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, note: Int? = null, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
        note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A segmented button row; options without a label resource show their value (or language name). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(choices: List<kotlin.Pair<T, Int?>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        choices.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                label = {
                    Text(
                        label?.let { stringResource(it) } ?: languageNames[value.toString()] ?: value.toString(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

private const val MAX_FILES = 50
