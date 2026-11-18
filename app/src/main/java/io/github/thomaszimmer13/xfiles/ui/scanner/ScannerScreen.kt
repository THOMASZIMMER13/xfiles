package io.github.thomaszimmer13.xfiles.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.ocr.OcrModelState
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import java.util.concurrent.Executors

/**
 * Scanner de documents : aperçu caméra (ignoré par TalkBack), conseil de cadrage annoncé,
 * photo automatique ou manuelle, puis PDF cherchable enregistré dans le dossier d'où l'on vient.
 */
@Composable
fun ScannerScreen(directory: String, onOpen: (String) -> Unit, onBack: () -> Unit, viewModel: ScannerViewModel = viewModel(key = "scanner:$directory")) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by viewModel.modelState.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    var askLeave by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }

    LaunchedEffect(state.saved) {
        state.saved?.let {
            viewModel.consumeSaved()
            onOpen(Routes.document(it.path))
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(android.util.Size(3000, 4000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .build()
    }
    val camera = remember { mutableStateOf<Camera?>(null) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    fun capture() {
        if (state.processing) return
        imageCapture.takePicture(
            mainExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // Photo ramenée à 2400 pixels de côté : assez pour lire, sans saturer la mémoire.
                    val bitmap = image.toUprightBitmap(maxSide = 2400)
                    image.close()
                    viewModel.onCaptured(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    viewModel.captureFailed()
                }
            },
        )
    }
    DisposableEffect(viewModel) {
        viewModel.onAutoCapture = { mainExecutor.execute { capture() } }
        onDispose { viewModel.onAutoCapture = null }
    }
    LaunchedEffect(state.torch, camera.value) { camera.value?.cameraControl?.enableTorch(state.torch) }

    val count = state.pages.size
    val title = stringResource(R.string.scan_title)
    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = title,
            spokenTitle = if (count == 0) title else pluralStringResource(R.plurals.scan_title_pages, count, count),
            onBack = { if (count > 0) askLeave = true else onBack() },
            titleFocus = titleFocus,
        )
        StatusMessage(announcement)

        when {
            !granted -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scan_camera_needed), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.scan_allow_camera)) }
            }
            models !is OcrModelState.Ready -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val d = models as? OcrModelState.Downloading
                Text(
                    if (d != null) stringResource(R.string.ocr_downloading, (d.fraction * 100).toInt())
                    else stringResource(R.string.ocr_models_message, android.text.format.Formatter.formatShortFileSize(context, viewModel.modelSize)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (d == null) Button(onClick = viewModel::downloadModels) { Text(stringResource(R.string.ocr_models_download)) }
            }
            else -> {
                val guidance = state.guidance
                Text(
                    when {
                        state.saving -> stringResource(R.string.scan_saving)
                        state.processing -> stringResource(R.string.scan_processing)
                        guidance != null -> stringResource(ScannerViewModel.guidanceText(guidance))
                        else -> stringResource(R.string.scan_aim)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::capture, enabled = !state.processing && !state.saving, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.scan_take_photo))
                    }
                    Button(onClick = { viewModel.save(directory) }, enabled = count > 0 && !state.processing && !state.saving, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(
                            if (count == 0) stringResource(R.string.scan_finish) else pluralStringResource(R.plurals.scan_finish_pages, count, count),
                        )
                    }
                }
                ToggleRow(stringResource(R.string.scan_auto), state.autoCapture, viewModel::setAutoCapture)
                ToggleRow(stringResource(R.string.scan_torch), state.torch, viewModel::setTorch)
                if (count > 0) {
                    OutlinedButton(onClick = viewModel::removeLastPage, enabled = !state.processing, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.scan_remove_last))
                    }
                }
                CameraPreview(
                    imageCapture = imageCapture,
                    onCamera = { camera.value = it },
                    onFrame = viewModel::onFrame,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                )
            }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.scan_leave_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(pluralStringResource(R.plurals.scan_leave_message, count, count)) },
            confirmButton = { TextButton(onClick = { askLeave = false; viewModel.save(directory) }) { Text(stringResource(R.string.scan_save)) } },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { askLeave = false; viewModel.discard(); onBack() }) { Text(stringResource(R.string.scan_discard)) }
                    TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
    androidx.activity.compose.BackHandler(enabled = count > 0) { askLeave = true }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Aperçu caméra : décoratif pour TalkBack, l'information passe par le conseil annoncé. */
@Composable
private fun CameraPreview(imageCapture: ImageCapture, onCamera: (Camera) -> Unit, onFrame: (Bitmap) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var lastFrame by remember { mutableStateOf(0L) }
    val provider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    // En quittant l'écran, la caméra est libérée (elle est liée au cycle de vie de l'activité).
    DisposableEffect(Unit) {
        onDispose {
            provider.value?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier.clearAndSetSemantics { },
        factory = { ctx ->
            val view = PreviewView(ctx).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val cameraProvider = future.get()
                provider.value = cameraProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    val now = System.currentTimeMillis()
                    if (now - lastFrame < 500) {
                        image.close()
                        return@setAnalyzer
                    }
                    lastFrame = now
                    val bitmap = image.toUprightBitmap()
                    image.close()
                    onFrame(bitmap)
                }
                runCatching {
                    cameraProvider.unbindAll()
                    onCamera(cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture))
                }
            }, ContextCompat.getMainExecutor(context))
            view
        },
    )
}

/** Image de la caméra tournée dans le sens de l'écran (et réduite si [maxSide] est dépassé). */
private fun ImageProxy.toUprightBitmap(maxSide: Int = Int.MAX_VALUE): Bitmap {
    val raw = toBitmap()
    val degrees = imageInfo.rotationDegrees
    val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
    if (degrees == 0 && scale == 1f) return raw
    val m = Matrix().apply {
        postScale(scale, scale)
        postRotate(degrees.toFloat())
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
}
