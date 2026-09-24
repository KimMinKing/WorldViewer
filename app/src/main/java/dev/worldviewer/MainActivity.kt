package dev.worldviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.worldviewer.reader.BlockRenderer
import dev.worldviewer.reader.BookCatalogStore
import dev.worldviewer.reader.ImportMetadata
import dev.worldviewer.reader.ImportMetadataStore
import dev.worldviewer.reader.ImportCancellation
import dev.worldviewer.reader.ImportProgress
import dev.worldviewer.reader.ImportProgressPhase
import dev.worldviewer.reader.IndexedTextBlockSource
import dev.worldviewer.reader.InMemoryTextBlockSource
import dev.worldviewer.reader.ReaderFixture
import dev.worldviewer.reader.PinyinDisplayMode
import dev.worldviewer.reader.ReadingPosition
import dev.worldviewer.reader.ReadingPositionResolver
import dev.worldviewer.reader.ReadingPositionStore
import dev.worldviewer.reader.ReadingProgress
import dev.worldviewer.reader.StagedTextImport
import dev.worldviewer.reader.StreamingTextIndex
import dev.worldviewer.reader.TextBlock
import dev.worldviewer.reader.TextBlockAdapter
import dev.worldviewer.reader.TextBlockSource
import dev.worldviewer.reader.TextBlocker
import dev.worldviewer.reader.TxtImporter
import dev.worldviewer.reader.TxtImportCancelledException
import dev.worldviewer.pinyin.PinyinBlockCoordinator
import dev.worldviewer.pinyin.UnihanPinyinAdapter
import dev.worldviewer.pinyin.UnihanPinyinTable
import dev.worldviewer.selection.SelectionGestureController
import dev.worldviewer.selection.CachedBlockSelectionTextNavigator
import dev.worldviewer.selection.SelectionEdge
import dev.worldviewer.selection.SelectionRange
import dev.worldviewer.selection.StringSelectionTextNavigator
import java.util.concurrent.Executors
import java.io.File

class MainActivity : Activity() {
    private val blockExecutor = Executors.newSingleThreadExecutor()
    private val pinyinExecutor = Executors.newSingleThreadExecutor()
    private val positionExecutor = Executors.newSingleThreadExecutor()
    private lateinit var reader: RecyclerView
    private lateinit var readerLayoutManager: LinearLayoutManager
    private lateinit var statusView: TextView
    private lateinit var openTxtButton: Button
    private lateinit var cancelImportButton: Button
    private lateinit var readingProgressView: TextView
    private lateinit var readingProgressSeekBar: SeekBar
    private lateinit var previousLocationButton: Button
    private var textBlockAdapter: TextBlockAdapter? = null
    private var pinyinCoordinator: PinyinBlockCoordinator? = null
    private var selectionGestureController: SelectionGestureController? = null
    private var pinyinMode = PinyinDisplayMode.ORIGINAL
    private var anchorRestoreGeneration = 0L
    private var pendingPinyinReadings: Map<Int, List<dev.worldviewer.pinyin.PinyinReading>>? = null
    private var pendingImport: StagedTextImport? = null
    private var activeImportCancellation: ImportCancellation? = null
    private var currentDocumentSha256: String? = null
    private var documentUtf16Length = 0
    private var trackingReadingProgress = false
    private var navigationInFlight = false
    private var navigationGeneration = 0L
    private var pendingNavigationOrigin: ReaderAnchor? = null
    private var previousNavigationAnchor: ReaderAnchor? = null

    private val pinyinScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            pendingPinyinReadings = null
            requestVisiblePinyin()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState != RecyclerView.SCROLL_STATE_IDLE) return
            updateReadingProgress()
            requestVisiblePinyin()
            pendingPinyinReadings?.let { readings ->
                pendingPinyinReadings = null
                applyPinyinReadings(readings)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val horizontalPadding = 20.dp
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 248, 243))
        }
        statusView = TextView(this).apply {
            setTextColor(Color.rgb(47, 93, 80))
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPadding, 12.dp, horizontalPadding, 12.dp)
            text = getString(R.string.reader_loading, BuildConfig.VERSION_NAME)
        }
        val selectionControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        fun selectionButton(
            label: String,
            accessibilityLabel: String,
            action: () -> Unit,
        ) = Button(this).apply {
            text = label
            textSize = 12f
            contentDescription = accessibilityLabel
            setOnClickListener { action() }
            selectionControls.addView(
                this,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        selectionButton("시작←", "선택 시작을 한 글자 앞쪽으로 확장") {
            selectionGestureController?.adjust(SelectionEdge.START, -1)
        }
        selectionButton("시작→", "선택 시작을 한 글자 뒤쪽으로 축소") {
            selectionGestureController?.adjust(SelectionEdge.START, 1)
        }
        selectionButton("끝←", "선택 끝을 한 글자 앞쪽으로 축소") {
            selectionGestureController?.adjust(SelectionEdge.END, -1)
        }
        selectionButton("끝→", "선택 끝을 한 글자 뒤쪽으로 확장") {
            selectionGestureController?.adjust(SelectionEdge.END, 1)
        }
        selectionButton("발음", "선택한 첫 글자의 임시 병음 수정") {
            editSelectedPinyin()
        }
        selectionButton("취소", "선택 취소") {
            selectionGestureController?.cancelSelection()
        }
        val restoredPosition = savedInstanceState?.getInt(STATE_FIRST_VISIBLE_POSITION, 0) ?: 0
        val restoredOffset = savedInstanceState?.getInt(STATE_FIRST_VISIBLE_OFFSET, 0) ?: 0
        val restoredWidth = savedInstanceState?.getInt(STATE_READER_WIDTH, 0) ?: 0
        val restoredAnchor = savedInstanceState?.takeIf {
            it.containsKey(STATE_READER_ANCHOR_UTF16)
        }?.let {
            ReaderAnchor(
                position = restoredPosition,
                globalUtf16 = it.getInt(STATE_READER_ANCHOR_UTF16),
                screenY = it.getInt(STATE_READER_ANCHOR_SCREEN_Y, 1),
            )
        }
        previousNavigationAnchor = savedInstanceState?.takeIf {
            it.containsKey(STATE_PREVIOUS_NAVIGATION_UTF16)
        }?.let {
            ReaderAnchor(
                position = it.getInt(STATE_PREVIOUS_NAVIGATION_POSITION),
                globalUtf16 = it.getInt(STATE_PREVIOUS_NAVIGATION_UTF16),
                screenY = it.getInt(STATE_PREVIOUS_NAVIGATION_SCREEN_Y, 1),
            )
        }
        pinyinMode = savedInstanceState?.getString(STATE_PINYIN_MODE)?.let { saved ->
            runCatching { PinyinDisplayMode.valueOf(saved) }.getOrNull()
        } ?: PinyinDisplayMode.ORIGINAL
        val renderer = BlockRenderer.entries.firstOrNull {
            it.launchValue == intent.getStringExtra(EXTRA_RENDERER)
        } ?: BlockRenderer.TEXT_VIEW

        readerLayoutManager = LinearLayoutManager(this)
        reader = RecyclerView(this).apply {
            layoutManager = readerLayoutManager
            setHasFixedSize(false)
            itemAnimator = null
        }

        root.addView(statusView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val fileControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        openTxtButton = Button(this).apply {
                text = getString(R.string.open_txt)
                contentDescription = getString(R.string.open_txt_description)
                setOnClickListener { openTxtPicker() }
        }
        cancelImportButton = Button(this).apply {
            text = getString(R.string.cancel_import)
            contentDescription = getString(R.string.cancel_import_description)
            visibility = View.GONE
            setOnClickListener {
                isEnabled = false
                activeImportCancellation?.cancel()
                statusView.text = getString(R.string.reader_import_cancelling)
            }
        }
        fileControls.addView(
            openTxtButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        fileControls.addView(
            cancelImportButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(fileControls, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val pinyinControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun pinyinButton(label: String, accessibilityLabel: String, mode: PinyinDisplayMode) {
            pinyinControls.addView(
                Button(this).apply {
                    text = label
                    textSize = 12f
                    contentDescription = accessibilityLabel
                    setOnClickListener { changePinyinMode(mode) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        pinyinButton("원문", "병음 숨기기", PinyinDisplayMode.ORIGINAL)
        pinyinButton("전체 병음", "화면의 모든 한자 병음 보기", PinyinDisplayMode.ALL)
        pinyinButton("선택 병음", "선택한 한자의 병음만 보기", PinyinDisplayMode.SELECTION)
        root.addView(pinyinControls, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val navigationControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        readingProgressView = TextView(this).apply {
            setTextColor(Color.rgb(47, 93, 80))
            textSize = 13f
            gravity = Gravity.CENTER
            text = getString(R.string.reading_progress, 0.0)
            setPadding(12.dp, 0, 4.dp, 0)
        }
        readingProgressSeekBar = SeekBar(this).apply {
            max = ReadingProgress.MAX
            isEnabled = false
            contentDescription = getString(R.string.reading_progress_control)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateReadingProgressLabel(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    if (documentUtf16Length <= 1 || navigationInFlight) return
                    trackingReadingProgress = true
                    pendingNavigationOrigin = captureReaderAnchor()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    trackingReadingProgress = false
                    val origin = pendingNavigationOrigin
                    pendingNavigationOrigin = null
                    if (documentUtf16Length <= 1 || origin == null) {
                        updateReadingProgress()
                        return
                    }
                    navigateToUtf16(
                        targetUtf16 = ReadingProgress.toOffset(seekBar.progress, documentUtf16Length),
                        returnAnchor = origin,
                        screenY = reader.paddingTop + 1,
                    )
                }
            })
        }
        previousLocationButton = Button(this).apply {
            text = getString(R.string.previous_location)
            textSize = 12f
            isEnabled = false
            contentDescription = getString(R.string.previous_location_description)
            setOnClickListener {
                val target = previousNavigationAnchor ?: return@setOnClickListener
                val origin = captureReaderAnchor() ?: return@setOnClickListener
                navigateToUtf16(target.globalUtf16, origin, target.screenY)
            }
        }
        navigationControls.addView(
            readingProgressView,
            LinearLayout.LayoutParams(92.dp, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        navigationControls.addView(
            readingProgressSeekBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        navigationControls.addView(
            previousLocationButton,
            LinearLayout.LayoutParams(132.dp, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        root.addView(navigationControls, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(reader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            selectionControls,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsets()
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        blockExecutor.execute {
            val preparationStarted = SystemClock.elapsedRealtime()
            val removedPartials = TxtImporter.cleanStalePartials(importDirectory()) +
                metadataStore().cleanStalePartials() +
                readingPositionStore().cleanStalePartials() +
                bookCatalogStore().cleanStalePartials()
            if (removedPartials > 0) {
                Log.i(IMPORT_TAG, "removedStalePartials=$removedPartials")
            }
            if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_PINYIN_PROBE, false)) {
                reportPinyinProbe()
            }
            val recentResult = runCatching {
                val metadata = metadataStore().load() ?: return@runCatching null
                val savedPosition = runCatching {
                    readingPositionStore().load(metadata.sha256)
                }.onFailure { error ->
                    Log.e(POSITION_TAG, "saved position ignored", error)
                }.getOrNull()
                val inspected = TxtImporter.inspectStored(
                    resolveStoredImport(metadata.storedName),
                    expectedEncoding = metadata.encoding,
                )
                if (
                    inspected.sha256 != metadata.sha256 ||
                    inspected.byteCount != metadata.byteCount ||
                    inspected.encoding != metadata.encoding
                ) {
                    throw IllegalStateException("최근 문서의 원본 검증값이 일치하지 않습니다.")
                }
                runCatching {
                    bookCatalogStore().reconcileCurrent(metadata, System.currentTimeMillis())
                }.onFailure { error ->
                    Log.e(CATALOG_TAG, "catalog reconciliation deferred", error)
                }
                val summary = StreamingTextIndex.build(
                    original = inspected.storedFile,
                    destinationDirectory = File(cacheDir, TEXT_INDEX_DIRECTORY),
                    contentId = inspected.sha256,
                    encoding = inspected.encoding,
                    maxBlockUtf16 = MAX_BLOCK_UTF16,
                )
                val indexReader = StreamingTextIndex.Reader(summary.indexFile, summary.dataFile)
                try {
                    val savedBlockPosition = savedPosition
                        ?.takeIf { summary.blockCount > 0 && it.utf16Offset in 0..summary.utf16Length }
                        ?.let { position ->
                            indexReader.positionForUtf16(
                                position.utf16Offset.coerceAtMost(summary.utf16Length - 1),
                            )
                        }
                    val initialPosition = when {
                        restoredAnchor != null -> restoredAnchor.position
                        savedBlockPosition != null -> savedBlockPosition
                        else -> restoredPosition
                    }.coerceIn(0, maxOf(0, summary.blockCount - 1))
                    val initialBlocks = buildSet {
                        if (summary.blockCount > 0) add(0)
                        for (position in initialPosition - 2..initialPosition + 2) {
                            if (position in 0 until summary.blockCount) add(position)
                        }
                    }.associateWith(indexReader::blockAt)
                    val resolvedSavedPosition = if (savedPosition != null && savedBlockPosition != null) {
                        ReadingPositionResolver.resolve(
                            currentDocumentSha256 = metadata.sha256,
                            documentUtf16Length = summary.utf16Length,
                            containingBlockPosition = savedBlockPosition,
                            availableBlocks = initialBlocks,
                            saved = savedPosition,
                        )
                    } else {
                        null
                    }
                    PreparedRecentDocument(
                        metadata = metadata,
                        indexReader = indexReader,
                        initialBlocks = initialBlocks,
                        utf16Length = summary.utf16Length,
                        savedPosition = resolvedSavedPosition,
                    )
                } catch (error: Exception) {
                    indexReader.close()
                    throw error
                }
            }
            val recent = recentResult.getOrNull()
            val metadata = recent?.metadata
            val sourceText = ReaderFixture.text
            val documentName = metadata?.displayName ?: getString(R.string.fixture_name)
            val blockSource: TextBlockSource = if (recent != null) {
                IndexedTextBlockSource(
                    reader = recent.indexReader,
                    deliver = { action -> reader.post { action() } },
                    initialBlocks = recent.initialBlocks,
                )
            } else {
                InMemoryTextBlockSource(TextBlocker.split(sourceText, MAX_BLOCK_UTF16))
            }
            runOnUiThread {
                if (isDestroyed) {
                    blockSource.close()
                    return@runOnUiThread
                }
                val adapter = TextBlockAdapter(blockSource, renderer)
                currentDocumentSha256 = metadata?.sha256
                textBlockAdapter = adapter
                reader.adapter = adapter
                adapter.setPinyinMode(pinyinMode)
                adapter.restorePinyinOverrides(restoredPinyinOverrides(savedInstanceState))
                val documentLength = recent?.utf16Length ?: sourceText.length
                documentUtf16Length = documentLength
                previousNavigationAnchor = previousNavigationAnchor?.takeIf { anchor ->
                    anchor.position in 0 until blockSource.blockCount &&
                        anchor.globalUtf16 in 0 until documentLength
                }
                readingProgressSeekBar.isEnabled = documentLength > 1
                previousLocationButton.isEnabled = previousNavigationAnchor != null
                if (renderer == BlockRenderer.TEXT_VIEW && documentLength > 0) {
                    val delay = intent.getIntExtra(EXTRA_SELECTION_DELAY, DEFAULT_SELECTION_DELAY)
                        .takeIf { it in SELECTION_DELAYS } ?: DEFAULT_SELECTION_DELAY
                    selectionGestureController = SelectionGestureController(
                        reader = reader,
                        adapter = adapter,
                        text = recent?.let {
                            CachedBlockSelectionTextNavigator(documentLength, adapter::cachedBlockAt)
                        } ?: StringSelectionTextNavigator(sourceText),
                        confirmationDelayMillis = delay.toLong(),
                        onSelectionChanged = { range ->
                            selectionControls.visibility = if (range == null) View.GONE else View.VISIBLE
                        },
                        onSelectionCompleted = { range ->
                            preserveReaderAnchor { adapter.setCompletedPinyinSelection(range) }
                        },
                    )
                    restoredSelection(savedInstanceState, documentLength)?.let { restored ->
                        selectionGestureController?.restoreSelection(restored)
                    }
                    val pinyinAdapter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                        val table = assets.open(UnihanPinyinTable.ASSET_PATH).use(UnihanPinyinTable::load)
                        UnihanPinyinAdapter(table)
                    }
                    pinyinCoordinator = PinyinBlockCoordinator(
                        blockCount = blockSource.blockCount,
                        blockAt = adapter::blockAtOrNull,
                        worker = pinyinExecutor,
                        deliver = { action -> reader.post { action() } },
                        convert = { block -> pinyinAdapter.convert(block.text) },
                        onUpdate = { readings ->
                            if (pinyinMode == PinyinDisplayMode.ORIGINAL) {
                                adapter.setPinyinReadings(readings)
                            } else if (reader.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                                pendingPinyinReadings = readings
                            } else {
                                applyPinyinReadings(readings)
                            }
                        },
                    )
                    reader.addOnScrollListener(pinyinScrollListener)
                }
                val savedReadingAnchor = recent?.savedPosition
                savedReadingAnchor?.let { position ->
                    Log.i(
                        POSITION_TAG,
                        "restoring document=${position.documentSha256} block=${position.blockIndex} " +
                            "utf16=${position.utf16Offset}",
                    )
                }
                if (restoredAnchor != null || savedReadingAnchor != null || savedInstanceState != null) {
                    reader.post {
                        when {
                            restoredAnchor != null -> restoreReaderAnchor(restoredAnchor)
                            savedReadingAnchor != null -> restoreReaderAnchor(
                                ReaderAnchor(
                                    position = savedReadingAnchor.blockIndex,
                                    globalUtf16 = savedReadingAnchor.utf16Offset,
                                    screenY = reader.paddingTop + 1,
                                ),
                            )
                            else -> {
                                val offset = if (reader.width == restoredWidth) restoredOffset else 0
                                readerLayoutManager.scrollToPositionWithOffset(restoredPosition, offset)
                            }
                        }
                    }
                }
                reader.post { updateReadingProgress() }
                reader.postDelayed({ updateReadingProgress() }, NAVIGATION_SETTLE_DELAY_MS)
                statusView.text = recentResult.exceptionOrNull()?.let { error ->
                    getString(R.string.reader_import_failed, error.message ?: getString(R.string.unknown_error))
                } ?: getString(
                    R.string.reader_ready,
                    documentName,
                    metadata?.encoding ?: "fixture",
                    blockSource.blockCount,
                    documentLength,
                )
                Log.i(
                    BLOCK_SOURCE_TAG,
                    "source=${if (recent == null) "memory" else "indexed"} " +
                        "blocks=${blockSource.blockCount} utf16=$documentLength " +
                        "prepareMs=${SystemClock.elapsedRealtime() - preparationStarted}",
                )
                if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_PROBE, false)) {
                    reader.post { reportRendererProbe(adapter, renderer) }
                }
                if (pinyinMode != PinyinDisplayMode.ORIGINAL) {
                    reader.post { requestVisiblePinyin() }
                }
            }
        }
    }

    private fun openTxtPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(picker, REQUEST_OPEN_TXT)
    }

    @Deprecated("Uses the platform file picker without adding an Activity Result dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_TXT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val displayName = displayName(uri)
        val cancellation = ImportCancellation()
        beginImportOperation(cancellation, displayName)
        val stageStarted = SystemClock.elapsedRealtime()
        blockExecutor.execute {
            val result = runCatching {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("선택한 파일을 읽을 수 없습니다.")
                TxtImporter.stage(
                    input = input,
                    destinationDirectory = importDirectory(),
                    totalBytes = contentSize(uri),
                    cancellation = cancellation,
                    onProgress = { progress -> reportImportProgress(cancellation, displayName, progress) },
                )
            }
            val stageMillis = SystemClock.elapsedRealtime() - stageStarted
            runOnUiThread {
                if (isDestroyed) {
                    result.getOrNull()?.close()
                    return@runOnUiThread
                }
                finishImportOperation(cancellation)
                result.onSuccess { staged ->
                    val automatic = staged.candidates.singleOrNull()?.takeIf { it.automatic }
                    if (automatic != null) {
                        commitStagedImport(staged, displayName, automatic.encoding, stageMillis)
                    } else {
                        showEncodingPreview(staged, displayName, stageMillis)
                    }
                }.onFailure(::showImportFailure)
            }
        }
    }

    private fun showEncodingPreview(
        staged: StagedTextImport,
        displayName: String,
        stageMillis: Long,
    ) {
        pendingImport?.close()
        pendingImport = staged
        val labels = staged.candidates.map { candidate ->
            "${candidate.encoding} · ${candidate.preview}"
        }.toTypedArray()
        var selected = -1
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.encoding_preview_title, displayName))
            .setSingleChoiceItems(labels, -1) { _, which ->
                selected = which
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
            .setPositiveButton(R.string.encoding_open) { _, _ ->
                val candidate = staged.candidates.getOrNull(selected) ?: return@setPositiveButton
                pendingImport = null
                commitStagedImport(staged, displayName, candidate.encoding, stageMillis)
            }
            .setNegativeButton(R.string.encoding_cancel) { _, _ ->
                cancelStagedImport(staged)
            }
            .setOnCancelListener { cancelStagedImport(staged) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        }
        dialog.show()
    }

    private fun commitStagedImport(
        staged: StagedTextImport,
        displayName: String,
        encoding: String,
        stageMillis: Long,
    ) {
        val cancellation = ImportCancellation()
        beginImportOperation(cancellation, displayName)
        val commitStarted = SystemClock.elapsedRealtime()
        blockExecutor.execute {
            val result = runCatching {
                TxtImporter.commit(
                    staged = staged,
                    encoding = encoding,
                    cancellation = cancellation,
                    onProgress = { progress -> reportImportProgress(cancellation, displayName, progress) },
                ).also { imported ->
                    val metadata = ImportMetadata(
                        storedName = imported.storedFile.name,
                        displayName = displayName,
                        sha256 = imported.sha256,
                        byteCount = imported.byteCount,
                        encoding = imported.encoding,
                    )
                    metadataStore().save(metadata)
                    runCatching {
                        bookCatalogStore().reconcileCurrent(metadata, System.currentTimeMillis())
                    }.onFailure { error ->
                        Log.e(CATALOG_TAG, "catalog reconciliation deferred", error)
                    }
                }
            }
            val importMillis = stageMillis + (SystemClock.elapsedRealtime() - commitStarted)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                finishImportOperation(cancellation)
                result.onSuccess { imported ->
                    Log.i(
                        IMPORT_TAG,
                        "stored=${imported.storedFile.name} bytes=${imported.byteCount} " +
                            "encoding=${imported.encoding} sha256=${imported.sha256} " +
                            "importMs=$importMillis",
                    )
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }.onFailure(::showImportFailure)
            }
        }
    }

    private fun cancelStagedImport(staged: StagedTextImport) {
        if (pendingImport === staged) pendingImport = null
        staged.close()
        statusView.text = getString(R.string.reader_import_cancelled)
        Log.i(IMPORT_TAG, "encoding preview cancelled")
    }

    private fun showImportFailure(error: Throwable) {
        if (error is TxtImportCancelledException) {
            statusView.text = getString(R.string.reader_import_cancelled)
            Log.i(IMPORT_TAG, "TXT import cancelled")
        } else {
            statusView.text = getString(
                R.string.reader_import_failed,
                error.message ?: getString(R.string.unknown_error),
            )
            Log.e(IMPORT_TAG, "TXT import failed", error)
        }
    }

    private fun beginImportOperation(cancellation: ImportCancellation, displayName: String) {
        activeImportCancellation = cancellation
        openTxtButton.isEnabled = false
        cancelImportButton.isEnabled = true
        cancelImportButton.visibility = View.VISIBLE
        statusView.text = getString(R.string.reader_importing, displayName)
    }

    private fun finishImportOperation(cancellation: ImportCancellation) {
        if (activeImportCancellation !== cancellation) return
        activeImportCancellation = null
        openTxtButton.isEnabled = true
        cancelImportButton.visibility = View.GONE
    }

    private fun reportImportProgress(
        cancellation: ImportCancellation,
        displayName: String,
        progress: ImportProgress,
    ) {
        statusView.post {
            if (isDestroyed || activeImportCancellation !== cancellation) return@post
            val phase = when (progress.phase) {
                ImportProgressPhase.COPYING -> getString(R.string.import_phase_copying)
                ImportProgressPhase.VALIDATING -> getString(R.string.import_phase_validating)
            }
            val amount = progress.totalBytes
                ?.takeIf { it > 0L }
                ?.let { total ->
                    getString(
                        R.string.import_progress_percent,
                        (progress.processedBytes * 100L / total).coerceIn(0L, 100L),
                    )
                }
                ?: getString(R.string.import_progress_bytes, progress.processedBytes)
            statusView.text = getString(R.string.reader_import_progress, displayName, phase, amount)
        }
    }

    private fun displayName(uri: Uri): String {
        val queried = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        return (queried ?: uri.lastPathSegment ?: getString(R.string.imported_txt)).safeDisplayName()
    }

    private fun contentSize(uri: Uri): Long? = contentResolver
        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0L } else null
        }

    private fun String.safeDisplayName(): String =
        replace('\r', ' ').replace('\n', ' ').trim().take(80).ifEmpty { getString(R.string.imported_txt) }

    private fun importDirectory(): File = File(filesDir, "imports")

    private fun metadataStore(): ImportMetadataStore = ImportMetadataStore(importDirectory())

    private fun bookCatalogStore(): BookCatalogStore = BookCatalogStore(File(filesDir, "library"))

    private fun readingPositionStore(): ReadingPositionStore =
        ReadingPositionStore(File(filesDir, "reading-positions"))

    private fun resolveStoredImport(storedName: String): File {
        if (!STORED_NAME.matches(storedName)) {
            throw IllegalArgumentException("잘못된 보존 파일 이름입니다.")
        }
        val directory = importDirectory().canonicalFile
        val stored = File(directory, storedName).canonicalFile
        if (stored.parentFile != directory || !stored.isFile) {
            throw IllegalArgumentException("보존된 TXT를 찾을 수 없습니다.")
        }
        return stored
    }

    private fun changePinyinMode(mode: PinyinDisplayMode) {
        if (pinyinMode == mode) return
        reader.stopScroll()
        val anchor = captureReaderAnchor()
        pinyinMode = mode
        textBlockAdapter?.setPinyinMode(mode)
        if (mode == PinyinDisplayMode.ORIGINAL) {
            pinyinCoordinator?.clear()
        } else {
            requestVisiblePinyin()
        }
        restoreReaderAnchor(anchor)
        Log.i(
            PINYIN_UI_TAG,
            "mode=$mode position=${anchor?.position} utf16=${anchor?.globalUtf16}",
        )
    }

    private fun requestVisiblePinyin() {
        if (pinyinMode == PinyinDisplayMode.ORIGINAL || !::readerLayoutManager.isInitialized) return
        val first = readerLayoutManager.findFirstVisibleItemPosition()
        val last = readerLayoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last >= first) {
            pinyinCoordinator?.requestVisible(first, last)
        }
    }

    private fun applyPinyinReadings(readings: Map<Int, List<dev.worldviewer.pinyin.PinyinReading>>) {
        val adapter = textBlockAdapter ?: return
        preserveReaderAnchor { adapter.setPinyinReadings(readings) }
    }

    private fun editSelectedPinyin() {
        val selection = selectionGestureController?.completedSelection() ?: return
        val start = selection.startUtf16
        val source = textBlockAdapter?.characterAt(start) ?: return
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(textBlockAdapter?.pronunciationAt(start).orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("'$source' 임시 병음 수정")
            .setView(input)
            .setPositiveButton("적용") { _, _ ->
                preserveReaderAnchor {
                    textBlockAdapter?.setPinyinOverride(start, input.text.toString())
                }
                Log.i(PINYIN_UI_TAG, "override applied offset=$start")
            }
            .setNeutralButton("기본값") { _, _ ->
                preserveReaderAnchor { textBlockAdapter?.setPinyinOverride(start, null) }
                Log.i(PINYIN_UI_TAG, "override cleared offset=$start")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun preserveReaderAnchor(change: () -> Unit) {
        val anchor = captureReaderAnchor()
        change()
        restoreReaderAnchor(anchor)
    }

    private fun updateReadingProgress() {
        if (trackingReadingProgress || navigationInFlight || documentUtf16Length <= 0) return
        val anchor = captureReaderAnchor() ?: return
        val progress = ReadingProgress.fromOffset(anchor.globalUtf16, documentUtf16Length)
        readingProgressSeekBar.progress = progress
        updateReadingProgressLabel(progress)
    }

    private fun updateReadingProgressLabel(progress: Int) {
        val percent = progress.coerceIn(0, ReadingProgress.MAX) / 10.0
        readingProgressView.text = getString(R.string.reading_progress, percent)
        readingProgressSeekBar.contentDescription = getString(R.string.reading_progress_description, percent)
    }

    private fun navigateToUtf16(targetUtf16: Int, returnAnchor: ReaderAnchor, screenY: Int) {
        val adapter = textBlockAdapter ?: return
        if (documentUtf16Length <= 0) return
        val target = targetUtf16.coerceIn(0, documentUtf16Length - 1)
        val generation = ++navigationGeneration
        navigationInFlight = true
        readingProgressSeekBar.isEnabled = false
        previousLocationButton.isEnabled = false
        selectionGestureController?.cancelSelection()
        adapter.requestBlockContaining(target) { position ->
            if (isDestroyed || generation != navigationGeneration || !navigationInFlight) {
                return@requestBlockContaining
            }
            navigationInFlight = false
            previousNavigationAnchor = returnAnchor
            readingProgressSeekBar.isEnabled = documentUtf16Length > 1
            previousLocationButton.isEnabled = true
            readingProgressSeekBar.progress = ReadingProgress.fromOffset(target, documentUtf16Length)
            updateReadingProgressLabel(readingProgressSeekBar.progress)
            restoreReaderAnchor(ReaderAnchor(position, target, screenY))
            Log.i(
                NAVIGATION_TAG,
                "targetUtf16=$target block=$position returnUtf16=${returnAnchor.globalUtf16}",
            )
            reader.postDelayed({ updateReadingProgress() }, NAVIGATION_SETTLE_DELAY_MS)
        }
        reader.postDelayed(
            {
                if (generation == navigationGeneration && navigationInFlight) {
                    navigationInFlight = false
                    readingProgressSeekBar.isEnabled = documentUtf16Length > 1
                    previousLocationButton.isEnabled = previousNavigationAnchor != null
                    updateReadingProgress()
                    Log.e(NAVIGATION_TAG, "navigation timed out targetUtf16=$target")
                }
            },
            NAVIGATION_TIMEOUT_MS,
        )
    }

    private fun captureReadingPosition(): ReadingPosition? {
        val documentSha256 = currentDocumentSha256 ?: return null
        val anchor = captureReaderAnchor() ?: return null
        val block = textBlockAdapter?.blockAtOrNull(anchor.position) ?: return null
        var local = (anchor.globalUtf16 - block.startUtf16).coerceIn(0, block.text.length)
        if (
            local in 1 until block.text.length &&
            Character.isLowSurrogate(block.text[local]) &&
            Character.isHighSurrogate(block.text[local - 1])
        ) {
            local--
        }
        var beforeStart = (local - ReadingPositionStore.MAX_QUOTE_UTF16).coerceAtLeast(0)
        if (beforeStart in 1 until block.text.length && Character.isLowSurrogate(block.text[beforeStart])) {
            beforeStart++
        }
        var afterEnd = (local + ReadingPositionStore.MAX_QUOTE_UTF16).coerceAtMost(block.text.length)
        if (
            afterEnd in 1 until block.text.length &&
            Character.isHighSurrogate(block.text[afterEnd - 1]) &&
            Character.isLowSurrogate(block.text[afterEnd])
        ) {
            afterEnd--
        }
        return ReadingPosition(
            documentSha256 = documentSha256,
            blockIndex = anchor.position,
            utf16Offset = block.startUtf16 + local,
            quoteBefore = block.text.substring(beforeStart, local),
            quoteAfter = block.text.substring(local, afterEnd),
        )
    }

    private fun persistReadingPosition() {
        val position = captureReadingPosition() ?: return
        positionExecutor.execute {
            runCatching { readingPositionStore().save(position) }
                .onSuccess {
                    Log.i(
                        POSITION_TAG,
                        "saved document=${position.documentSha256} block=${position.blockIndex} " +
                            "utf16=${position.utf16Offset}",
                    )
                }
                .onFailure { error -> Log.e(POSITION_TAG, "position save failed", error) }
        }
    }

    private fun captureReaderAnchor(): ReaderAnchor? {
        if (!::readerLayoutManager.isInitialized) return null
        val position = readerLayoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val view = readerLayoutManager.findViewByPosition(position) as? TextView ?: return null
        // A fully visible block can have reserved space above its first text line. Anchor the
        // actual text line so mode changes do not scroll that reserve (and ruby) out of view.
        val contentTop = view.top + view.totalPaddingTop
        val screenY = maxOf(reader.paddingTop, contentTop) + 1
        val localY = (screenY - view.top).toFloat()
        val adapter = textBlockAdapter ?: return null
        val global = adapter.globalUtf16OffsetFor(
            position,
            view,
            (view.totalPaddingLeft + 1).toFloat(),
            localY,
        ) ?: return null
        return ReaderAnchor(position, global, screenY)
    }

    private fun restoreReaderAnchor(anchor: ReaderAnchor?) {
        if (anchor == null) return
        val generation = ++anchorRestoreGeneration
        restoreReaderAnchor(anchor, generation, remainingPasses = 2, remainingAttempts = 6)
        reader.postDelayed(
            {
                if (!isDestroyed && generation == anchorRestoreGeneration) {
                    restoreReaderAnchor(anchor, generation, remainingPasses = 0, remainingAttempts = 2)
                }
            },
            FINAL_ANCHOR_RESTORE_DELAY_MS,
        )
    }

    private fun restoreReaderAnchor(
        anchor: ReaderAnchor,
        generation: Long,
        remainingPasses: Int,
        remainingAttempts: Int,
    ) {
        reader.postOnAnimation {
            if (isDestroyed || generation != anchorRestoreGeneration) return@postOnAnimation
            val view = readerLayoutManager.findViewByPosition(anchor.position) as? TextView
            val layout = view?.layout
            if (view == null || layout == null) {
                readerLayoutManager.scrollToPositionWithOffset(anchor.position, 0)
                if (remainingAttempts > 0) {
                    restoreReaderAnchor(
                        anchor,
                        generation,
                        remainingPasses,
                        remainingAttempts - 1,
                    )
                }
                return@postOnAnimation
            }
            val block = textBlockAdapter?.blockAt(anchor.position) ?: return@postOnAnimation
            val local = (anchor.globalUtf16 - block.startUtf16).coerceIn(0, block.text.length)
            val line = layout.getLineForOffset(local)
            val sourceLineTop = view.totalPaddingTop + layout.getLineTop(line)
            readerLayoutManager.scrollToPositionWithOffset(
                anchor.position,
                anchor.screenY - sourceLineTop,
            )
            if (remainingPasses > 0) {
                restoreReaderAnchor(
                    anchor,
                    generation,
                    remainingPasses - 1,
                    remainingAttempts,
                )
            } else if (BuildConfig.DEBUG) {
                reader.postOnAnimation {
                    val actual = captureReaderAnchor()
                    val actualView = actual?.let {
                        readerLayoutManager.findViewByPosition(it.position) as? TextView
                    }
                    val actualBlock = actual?.let { textBlockAdapter?.blockAt(it.position) }
                    val actualLayout = actualView?.layout
                    val actualLocal = if (actual != null && actualBlock != null) {
                        (actual.globalUtf16 - actualBlock.startUtf16).coerceIn(0, actualBlock.text.length)
                    } else {
                        null
                    }
                    val actualLine = if (actualLocal != null) actualLayout?.getLineForOffset(actualLocal) else null
                    val lineStart = if (actualLine != null && actualBlock != null && actualLayout != null) {
                        actualBlock.startUtf16 + actualLayout.getLineStart(actualLine)
                    } else {
                        null
                    }
                    val lineEnd = if (actualLine != null && actualBlock != null && actualLayout != null) {
                        actualBlock.startUtf16 + actualLayout.getLineEnd(actualLine)
                    } else {
                        null
                    }
                    val visibleLineStarts = if (actualBlock != null && actualLayout != null) {
                        (0 until minOf(actualLayout.lineCount, 12)).joinToString(",") { lineIndex ->
                            (actualBlock.startUtf16 + actualLayout.getLineStart(lineIndex)).toString()
                        }
                    } else {
                        ""
                    }
                    Log.i(
                        PINYIN_UI_TAG,
                        "anchor requested=${anchor.globalUtf16} topLine=$lineStart..$lineEnd " +
                            "contains=${lineStart != null && lineEnd != null && anchor.globalUtf16 in lineStart..lineEnd} " +
                            "position=${anchor.position} lineStarts=$visibleLineStarts",
                    )
                }
            }
        }
    }

    private fun reportPinyinProbe() {
        runCatching {
            val loadStarted = SystemClock.elapsedRealtimeNanos()
            val table = assets.open(UnihanPinyinTable.ASSET_PATH).use(UnihanPinyinTable::load)
            val loadNanos = SystemClock.elapsedRealtimeNanos() - loadStarted
            val convertStarted = SystemClock.elapsedRealtimeNanos()
            val readings = UnihanPinyinAdapter(table).convert("银行行长 A中🙂𠀀𠮷")
            val convertNanos = SystemClock.elapsedRealtimeNanos() - convertStarted
            Log.i(
                PINYIN_PROBE_TAG,
                "entries=${table.entryCount} loadMs=${loadNanos / 1_000_000.0} " +
                    "convertUs=${convertNanos / 1_000.0} tokens=${readings.size} " +
                    "mapped=${readings.count { it.pronunciation != null }}",
            )
        }.onFailure { error ->
            Log.e(PINYIN_PROBE_TAG, "pinyin probe failed", error)
        }
    }

    private fun reportRendererProbe(adapter: TextBlockAdapter, renderer: BlockRenderer) {
        val position = readerLayoutManager.findFirstVisibleItemPosition()
        val child = readerLayoutManager.findViewByPosition(position) ?: return
        val block = adapter.blockAt(position)
        adapter.hitSamples(child).forEach { sample ->
            Log.i(
                PROBE_TAG,
                "hit renderer=${renderer.launchValue} block=$position line=${sample.line} " +
                    "x=${sample.horizontalFraction} local=${sample.utf16Offset} " +
                    "global=${block.startUtf16 + sample.utf16Offset}",
            )
        }
        val node = child.createAccessibilityNodeInfo()
        val actions = node.actionList.joinToString(separator = ",") { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            PROBE_TAG,
            "accessibility renderer=${renderer.launchValue} class=${node.className} " +
                "text=${node.text != null} description=${node.contentDescription != null} " +
                "granularities=${node.movementGranularities} actions=$actions",
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::reader.isInitialized && ::readerLayoutManager.isInitialized) {
            val position = readerLayoutManager.findFirstVisibleItemPosition()
            if (position != RecyclerView.NO_POSITION) {
                val firstVisible = readerLayoutManager.findViewByPosition(position)
                outState.putInt(STATE_FIRST_VISIBLE_POSITION, position)
                outState.putInt(STATE_READER_WIDTH, reader.width)
                outState.putInt(
                    STATE_FIRST_VISIBLE_OFFSET,
                    (firstVisible?.top ?: reader.paddingTop) - reader.paddingTop,
                )
            }
        }
        selectionGestureController?.completedSelection()?.let { selection ->
            outState.putInt(STATE_SELECTION_START, selection.startUtf16)
            outState.putInt(STATE_SELECTION_END, selection.endUtf16)
        }
        captureReaderAnchor()?.let { anchor ->
            outState.putInt(STATE_READER_ANCHOR_UTF16, anchor.globalUtf16)
            outState.putInt(STATE_READER_ANCHOR_SCREEN_Y, anchor.screenY)
        }
        previousNavigationAnchor?.let { anchor ->
            outState.putInt(STATE_PREVIOUS_NAVIGATION_POSITION, anchor.position)
            outState.putInt(STATE_PREVIOUS_NAVIGATION_UTF16, anchor.globalUtf16)
            outState.putInt(STATE_PREVIOUS_NAVIGATION_SCREEN_Y, anchor.screenY)
        }
        outState.putString(STATE_PINYIN_MODE, pinyinMode.name)
        textBlockAdapter?.pinyinOverrides()?.toSortedMap()?.let { overrides ->
            outState.putIntArray(STATE_PINYIN_OVERRIDE_OFFSETS, overrides.keys.toIntArray())
            outState.putStringArray(STATE_PINYIN_OVERRIDE_VALUES, overrides.values.toTypedArray())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        persistReadingPosition()
        super.onStop()
    }

    override fun onDestroy() {
        activeImportCancellation?.cancel()
        activeImportCancellation = null
        pendingImport?.close()
        pendingImport = null
        selectionGestureController?.dispose()
        if (::reader.isInitialized) reader.removeOnScrollListener(pinyinScrollListener)
        pinyinCoordinator?.close()
        textBlockAdapter?.close()
        blockExecutor.shutdownNow()
        pinyinExecutor.shutdownNow()
        positionExecutor.shutdown()
        super.onDestroy()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun WindowInsets.systemBarInsets(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = getInsets(WindowInsets.Type.systemBars())
            Rect(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            Rect(
                systemWindowInsetLeft,
                systemWindowInsetTop,
                systemWindowInsetRight,
                systemWindowInsetBottom,
            )
        }

    private fun restoredSelection(savedInstanceState: Bundle?, textLength: Int): SelectionRange? {
        if (savedInstanceState == null || !savedInstanceState.containsKey(STATE_SELECTION_START)) return null
        val start = savedInstanceState.getInt(STATE_SELECTION_START)
        val end = savedInstanceState.getInt(STATE_SELECTION_END)
        return if (start >= 0 && end > start && end <= textLength) {
            SelectionRange(start, end)
        } else {
            null
        }
    }

    private fun restoredPinyinOverrides(savedInstanceState: Bundle?): Map<Int, String> {
        val offsets = savedInstanceState?.getIntArray(STATE_PINYIN_OVERRIDE_OFFSETS) ?: return emptyMap()
        val values = savedInstanceState.getStringArray(STATE_PINYIN_OVERRIDE_VALUES) ?: return emptyMap()
        if (offsets.size != values.size) return emptyMap()
        return offsets.indices.associate { index -> offsets[index] to values[index] }
    }

    private companion object {
        const val MAX_BLOCK_UTF16 = 768
        const val REQUEST_OPEN_TXT = 1001
        const val STATE_FIRST_VISIBLE_POSITION = "reader.firstVisiblePosition"
        const val STATE_FIRST_VISIBLE_OFFSET = "reader.firstVisibleOffset"
        const val STATE_READER_WIDTH = "reader.width"
        const val STATE_READER_ANCHOR_UTF16 = "reader.anchorUtf16"
        const val STATE_READER_ANCHOR_SCREEN_Y = "reader.anchorScreenY"
        const val STATE_PREVIOUS_NAVIGATION_POSITION = "reader.previousNavigationPosition"
        const val STATE_PREVIOUS_NAVIGATION_UTF16 = "reader.previousNavigationUtf16"
        const val STATE_PREVIOUS_NAVIGATION_SCREEN_Y = "reader.previousNavigationScreenY"
        const val STATE_SELECTION_START = "selection.start"
        const val STATE_SELECTION_END = "selection.end"
        const val STATE_PINYIN_MODE = "pinyin.mode"
        const val STATE_PINYIN_OVERRIDE_OFFSETS = "pinyin.overrideOffsets"
        const val STATE_PINYIN_OVERRIDE_VALUES = "pinyin.overrideValues"
        const val EXTRA_RENDERER = "renderer"
        const val EXTRA_PROBE = "probe_renderer"
        const val PROBE_TAG = "WorldViewerProbe"
        const val EXTRA_PINYIN_PROBE = "probe_pinyin"
        const val PINYIN_PROBE_TAG = "WorldViewerPinyin"
        const val PINYIN_UI_TAG = "WorldViewerPinyinUI"
        const val EXTRA_SELECTION_DELAY = "selection_delay"
        const val IMPORT_TAG = "WorldViewerImport"
        const val CATALOG_TAG = "WorldViewerCatalog"
        const val POSITION_TAG = "WorldViewerPosition"
        const val NAVIGATION_TAG = "WorldViewerNavigation"
        const val BLOCK_SOURCE_TAG = "WorldViewerBlocks"
        const val TEXT_INDEX_DIRECTORY = "text-index"
        const val DEFAULT_SELECTION_DELAY = 350
        const val FINAL_ANCHOR_RESTORE_DELAY_MS = 350L
        const val NAVIGATION_SETTLE_DELAY_MS = 450L
        const val NAVIGATION_TIMEOUT_MS = 3_000L
        val SELECTION_DELAYS = setOf(250, 350, 450)
        val STORED_NAME = Regex("[0-9a-f]{64}\\.original")
    }

    private data class ReaderAnchor(
        val position: Int,
        val globalUtf16: Int,
        val screenY: Int,
    )

    private data class PreparedRecentDocument(
        val metadata: ImportMetadata,
        val indexReader: StreamingTextIndex.Reader,
        val initialBlocks: Map<Int, TextBlock>,
        val utf16Length: Int,
        val savedPosition: ReadingPosition?,
    )
}
