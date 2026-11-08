package com.example.nuclearithackathon

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                AudioScreen()
            }
        }
    }

    @Composable
    private fun AudioScreen() {

        var selectedUri by remember {
            mutableStateOf<Uri?>(null)
        }

        var fileName by remember {
            mutableStateOf("Файл не выбран")
        }

        var status by remember {
            mutableStateOf("Ожидание аудиофайла")
        }

        var transcript by remember {
            mutableStateOf("")
        }

        var isProcessing by remember {
            mutableStateOf(false)
        }

        val scope = rememberCoroutineScope()

        // Выбор аудиофайла
        val filePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                selectedUri = uri
                fileName = getFileName(uri)
                status = "Аудиофайл выбран"
                transcript = ""
            }
        }

        // Сохранение TXT
        val saveTxtLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            if (uri != null) {
                try {
                    saveTextToUri(uri, transcript)
                    status = "TXT успешно сохранён"
                } catch (e: Exception) {
                    status = "Ошибка TXT: ${e.message}"
                }
            }
        }

        // Сохранение JSON
        val saveJsonLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "application/json"
            )
        ) { uri ->
            if (uri != null) {
                try {
                    val json = transcriptToJson(transcript)
                    saveTextToUri(uri, json)
                    status = "JSON успешно сохранён"
                } catch (e: Exception) {
                    status = "Ошибка JSON: ${e.message}"
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text(
                text = "OfflineAudio",
                style = MaterialTheme.typography.headlineLarge
            )

            Text(
                text = "Локальная расшифровка аудиозаписей"
            )

            HorizontalDivider()

            Button(
                enabled = !isProcessing,
                onClick = {
                    filePicker.launch(
                        arrayOf(
                            "audio/wav",
                            "audio/x-wav",
                            "audio/*"
                        )
                    )
                }
            ) {
                Text("Выбрать аудиофайл")
            }

            Text("Файл: $fileName")

            Text("Статус: $status")

            Button(
                enabled = selectedUri != null && !isProcessing,
                onClick = {
                    val uri = selectedUri ?: return@Button

                    scope.launch {
                        isProcessing = true
                        status = "Загрузка модели и распознавание..."
                        transcript = ""

                        try {
                            transcript = withContext(Dispatchers.IO) {
                                transcribeWav(uri)
                            }

                            status = "Готово"

                        } catch (e: Exception) {
                            status = "Ошибка: ${e.message}"

                        } finally {
                            isProcessing = false
                        }
                    }
                }
            ) {
                Text(
                    if (isProcessing) {
                        "Обработка..."
                    } else {
                        "Обработать аудио"
                    }
                )
            }

            if (isProcessing) {
                CircularProgressIndicator()
            }

            HorizontalDivider()

            Text(
                text = "Расшифровка",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = transcript.ifBlank {
                    "Расшифровка пока отсутствует"
                }
            )

            // Кнопки экспорта появляются после распознавания
            if (transcript.isNotBlank()) {

                HorizontalDivider()

                Text(
                    text = "Экспорт стенограммы",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    Button(
                        onClick = {
                            saveTxtLauncher.launch("transcript.txt")
                        }
                    ) {
                        Text("Сохранить TXT")
                    }

                    Button(
                        onClick = {
                            saveJsonLauncher.launch("transcript.json")
                        }
                    ) {
                        Text("Сохранить JSON")
                    }
                }
            }
        }
    }

    // Распознавание через локальный Whisper
    private suspend fun transcribeWav(uri: Uri): String {

        val wavFile = File(cacheDir, "selected.wav")

        var context: WhisperContext? = null

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                wavFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Не удалось открыть аудиофайл")

            val samples = readPcm16Mono16k(wavFile)

            context = WhisperContext.createContextFromAsset(
                assets,
                "models/ggml-tiny.bin"
            )

            return context.transcribeData(samples)

        } finally {
            context?.release()
            wavFile.delete()
        }
    }

    // Чтение WAV: PCM 16-bit, mono, 16000 Hz
    private fun readPcm16Mono16k(file: File): FloatArray {

        val bytes = file.readBytes()

        require(bytes.size >= 44) {
            "Файл слишком короткий"
        }

        fun uint16(pos: Int): Int =
            (bytes[pos].toInt() and 255) or
                    ((bytes[pos + 1].toInt() and 255) shl 8)

        fun uint32(pos: Int): Int =
            (bytes[pos].toInt() and 255) or
                    ((bytes[pos + 1].toInt() and 255) shl 8) or
                    ((bytes[pos + 2].toInt() and 255) shl 16) or
                    ((bytes[pos + 3].toInt() and 255) shl 24)

        require(
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
        ) {
            "Нужен WAV-файл"
        }

        var position = 12

        var format = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1

        var dataOffset = -1
        var dataSize = 0

        while (position + 8 <= bytes.size) {

            val chunkName = String(
                bytes,
                position,
                4,
                Charsets.US_ASCII
            )

            val size = uint32(position + 4)
            val start = position + 8

            if (
                size < 0 ||
                size.toLong() > bytes.size.toLong() - start
            ) {
                break
            }

            when (chunkName) {

                "fmt " -> {
                    require(size >= 16) {
                        "Повреждённый WAV"
                    }

                    format = uint16(start)
                    channels = uint16(start + 2)
                    sampleRate = uint32(start + 4)
                    bitsPerSample = uint16(start + 14)
                }

                "data" -> {
                    dataOffset = start
                    dataSize = size
                }
            }

            position = start + size + (size and 1)
        }

        require(
            format == 1 &&
                    channels == 1 &&
                    sampleRate == 16000 &&
                    bitsPerSample == 16
        ) {
            "Поддерживается PCM WAV, 16 кГц, моно, 16 бит"
        }

        require(dataOffset >= 0 && dataSize > 0) {
            "В WAV отсутствуют аудиоданные"
        }

        return FloatArray(dataSize / 2) { index ->

            val offset = dataOffset + index * 2

            val low = bytes[offset].toInt() and 255
            val high = bytes[offset + 1].toInt()

            val sample = ((high shl 8) or low).toShort()

            sample / 32768.0f
        }
    }

    // Преобразование стенограммы Whisper в JSON
    private fun transcriptToJson(transcript: String): String {

        val segments = JSONArray()

        val regex = Regex(
            """\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})\.(\d{3})\]\s*:?\s*"""
        )

        val matches = regex.findAll(transcript).toList()

        require(matches.isNotEmpty()) {
            "Не удалось найти временные метки Whisper"
        }

        fun toMilliseconds(
            hours: String,
            minutes: String,
            seconds: String,
            millis: String
        ): Long {
            return hours.toLong() * 3600000L +
                    minutes.toLong() * 60000L +
                    seconds.toLong() * 1000L +
                    millis.toLong()
        }

        matches.forEachIndexed { index, match ->

            val start = match.range.last + 1

            val end = matches.getOrNull(index + 1)
                ?.range
                ?.first ?: transcript.length

            val text = transcript.substring(start, end).trim()

            val values = match.groupValues

            val startMs = toMilliseconds(
                values[1],
                values[2],
                values[3],
                values[4]
            )

            val endMs = toMilliseconds(
                values[5],
                values[6],
                values[7],
                values[8]
            )

            val segment = JSONObject().apply {

                // Диаризация пока не реализована.
                put("speaker_id", JSONObject.NULL)

                put("start_ms", startMs)
                put("end_ms", endMs)
                put("text", text)
            }

            segments.put(segment)
        }

        return JSONObject()
            .put("transcript", segments)
            .toString(2)
    }

    // Запись текста в файл через Android
    private fun saveTextToUri(uri: Uri, text: String) {

        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(
                text.toByteArray(Charsets.UTF_8)
            )
        } ?: error("Не удалось создать файл")
    }

    // Получение имени выбранного файла
    private fun getFileName(uri: Uri): String {

        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {
                return cursor.getString(0) ?: "audio.wav"
            }
        }

        return "audio.wav"
    }
}
