package dev.worldviewer

import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.InputType
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.worldviewer.backup.BackupFormatException
import dev.worldviewer.backup.RestoreConflictPolicy
import dev.worldviewer.backup.RestorePreview
import dev.worldviewer.backup.WorldViewerBackup
import dev.worldviewer.backup.WorldViewerRestore
import dev.worldviewer.lookup.DictionaryLookupCoordinator
import dev.worldviewer.lookup.DictionaryLookupRequest
import dev.worldviewer.lookup.DictionaryLookupResponse
import dev.worldviewer.lookup.DebugAzureCredentialImport
import dev.worldviewer.lookup.LegacyDictionaryCleanup
import dev.worldviewer.lookup.AzureTranslationProvider
import dev.worldviewer.lookup.AzureTranslatorCredentialStore
import dev.worldviewer.lookup.AzureTranslatorCredentials
import dev.worldviewer.lookup.MyMemoryTranslationProvider
import dev.worldviewer.lookup.savableMeanings
import dev.worldviewer.reader.AutoBookCover
import dev.worldviewer.reader.AnnotationType
import dev.worldviewer.reader.AutomaticChapter
import dev.worldviewer.reader.AutomaticChapterDetector
import dev.worldviewer.reader.BlockRenderer
import dev.worldviewer.reader.BookCatalog
import dev.worldviewer.reader.BookCatalogEntry
import dev.worldviewer.reader.BookCatalogStore
import dev.worldviewer.reader.FolderTxtCandidate
import dev.worldviewer.reader.FolderTxtScan
import dev.worldviewer.reader.FolderTxtScanner
import dev.worldviewer.reader.ImportMetadata
import dev.worldviewer.reader.ImportMetadataStore
import dev.worldviewer.reader.ImportCancellation
import dev.worldviewer.reader.ImportProgress
import dev.worldviewer.reader.ImportProgressPhase
import dev.worldviewer.reader.IndexedTextBlockSource
import dev.worldviewer.reader.InMemoryTextBlockSource
import dev.worldviewer.reader.ManualChapter
import dev.worldviewer.reader.ManualChapterStore
import dev.worldviewer.reader.MeaningQuery
import dev.worldviewer.reader.MeaningScope
import dev.worldviewer.reader.ReaderFixture
import dev.worldviewer.reader.PinyinDisplayMode
import dev.worldviewer.reader.ReadingPosition
import dev.worldviewer.reader.ReadingPositionResolver
import dev.worldviewer.reader.ReadingPositionStore
import dev.worldviewer.reader.ReadingTimeKey
import dev.worldviewer.reader.ReadingTimeLedger
import dev.worldviewer.reader.ReadingTimeStore
import dev.worldviewer.reader.ReadingProgress
import dev.worldviewer.reader.ReaderStyle
import dev.worldviewer.reader.ReaderStyleColors
import dev.worldviewer.reader.ReaderStylePreferences
import dev.worldviewer.reader.ReaderStyleStore
import dev.worldviewer.reader.ReaderTheme
import dev.worldviewer.reader.hasReadableContrast
import dev.worldviewer.reader.resolvedBackgroundColor
import dev.worldviewer.reader.resolvedTextColor
import dev.worldviewer.reader.StagedTextImport
import dev.worldviewer.reader.StreamingTextIndex
import dev.worldviewer.reader.StreamingTextSearch
import dev.worldviewer.reader.TextBlock
import dev.worldviewer.reader.TextBlockAdapter
import dev.worldviewer.reader.TextBlockSource
import dev.worldviewer.reader.TextBlocker
import dev.worldviewer.reader.TextSearchCancellation
import dev.worldviewer.reader.TextSearchResult
import dev.worldviewer.reader.TxtImporter
import dev.worldviewer.reader.TxtImportCancelledException
import dev.worldviewer.reader.TxtImportException
import dev.worldviewer.reader.UserMeaningStore
import dev.worldviewer.reader.UserMeaning
import dev.worldviewer.reader.UserMeaningIndex
import dev.worldviewer.reader.UserAnnotationStore
import dev.worldviewer.reader.UserAnnotation
import dev.worldviewer.pinyin.PinyinBlockCoordinator
import dev.worldviewer.pinyin.UnihanPinyinAdapter
import dev.worldviewer.pinyin.UnihanPinyinTable
import dev.worldviewer.selection.SelectionGestureController
import dev.worldviewer.selection.CachedBlockSelectionTextNavigator
import dev.worldviewer.selection.SelectionEdge
import dev.worldviewer.selection.SelectionRange
import dev.worldviewer.selection.SelectionRequestGate
import dev.worldviewer.selection.StringSelectionTextNavigator
import java.util.concurrent.Executors
import java.util.UUID
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : Activity() {
    private val blockExecutor = Executors.newSingleThreadExecutor()
    private val pinyinExecutor = Executors.newSingleThreadExecutor()
    private val positionExecutor = Executors.newSingleThreadExecutor()
    private val backupExecutor = Executors.newSingleThreadExecutor()
    private val dictionaryExecutor = Executors.newSingleThreadExecutor()
    private val readingTimeHandler = Handler(Looper.getMainLooper())
    private var readingTimeLedger: ReadingTimeLedger? = null
    private var readingTimeLoadError: Throwable? = null
    @Volatile private var readingTimeSaveError: Throwable? = null
    private var readingTimeResumed = false
    private var readingTimeWindowFocused = false
    private var readingTimeScreenOn = true
    private var readingTimeActiveBook: String? = null
    private var screenReceiverRegistered = false
    private val readingTimeTick = object : Runnable {
        override fun run() {
            if (readingTimeActiveBook == null) return
            syncReadingTimer()
            if (readingTimeActiveBook != null) {
                checkpointReadingTime()
                readingTimeHandler.postDelayed(this, READING_TIME_TICK_MS)
            }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.action == Intent.ACTION_SCREEN_ON) {
                readingTimeScreenOn = intent.action == Intent.ACTION_SCREEN_ON
                syncReadingTimer()
            }
        }
    }
    private lateinit var rootLayout: FrameLayout
    private lateinit var bookshelfHome: LinearLayout
    private lateinit var bookshelfItems: ListView
    private lateinit var bookshelfEmptyView: TextView
    private lateinit var bookshelfStatusView: TextView
    private var bookshelfSnapshot: BookshelfSnapshot? = null
    private var readerBackCallback: OnBackInvokedCallback? = null
    private lateinit var readerChrome: LinearLayout
    private lateinit var selectionControls: LinearLayout
    private lateinit var reader: RecyclerView
    private lateinit var readerLayoutManager: LinearLayoutManager
    private lateinit var statusView: TextView
    private lateinit var bookshelfButton: Button
    private lateinit var styleButton: Button
    private lateinit var tableOfContentsButton: Button
    private lateinit var searchButton: Button
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
    private var folderCancellation: ImportCancellation? = null
    private var folderOperationDialog: AlertDialog? = null
    private var currentDocumentSha256: String? = null
    private var currentReaderStyle = ReaderStyle.DEFAULT
    private var documentUtf16Length = 0
    private var trackingReadingProgress = false
    private var navigationInFlight = false
    private var navigationGeneration = 0L
    private var pendingNavigationOrigin: ReaderAnchor? = null
    private var previousNavigationAnchor: ReaderAnchor? = null
    private var activeSearchCancellation: TextSearchCancellation? = null
    private var activeSearchDialog: AlertDialog? = null
    private var searchGeneration = 0L
    private var pendingBackupIncludesOriginals: Boolean? = null
    private var pendingRestoreFile: File? = null
    private var backupOperationDialog: AlertDialog? = null
    private var backupOperationInProgress = false
    private var restoreInProgress = false
    private var readerChromeVisible = false
    private var dictionaryLookupCoordinator: DictionaryLookupCoordinator? = null
    private var dictionaryProgressDialog: AlertDialog? = null
    private val selectionRequestGate = SelectionRequestGate()

    private val pinyinScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            pendingPinyinReadings = null
            requestVisiblePinyin()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                setReaderChromeVisible(false)
            }
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
        if (DebugAzureCredentialImport.importIfPresent(applicationContext)) {
            Log.i(MEANING_TAG, "Imported local Azure Translator credentials into Android Keystore")
        }
        val removedLegacyDirectories = LegacyDictionaryCleanup.clean(applicationContext)
        if (removedLegacyDirectories > 0) {
            Log.i(MEANING_TAG, "Removed $removedLegacyDirectories discontinued dictionary directories")
        }
        if (WorldViewerRestore(filesDir, ::availableStorageBytes).recoverInterruptedRestore()) {
            Log.w(RESTORE_TAG, "Recovered an interrupted backup restore before loading reader data")
        }

        val horizontalPadding = 20.dp
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(250, 248, 243))
        }
        val root = rootLayout
        readerChromeVisible = savedInstanceState?.getBoolean(STATE_READER_CHROME_VISIBLE, false) ?: false
        readerChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (readerChromeVisible) View.VISIBLE else View.GONE
        }
        statusView = TextView(this).apply {
            setTextColor(Color.rgb(47, 93, 80))
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPadding, 12.dp, horizontalPadding, 12.dp)
            text = getString(R.string.reader_loading, BuildConfig.VERSION_NAME)
        }
        selectionControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val selectionControlRows = List(2) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                selectionControls.addView(
                    this,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
        var selectionButtonCount = 0
        fun selectionButton(
            label: String,
            accessibilityLabel: String,
            action: () -> Unit,
        ) = Button(this).apply {
            text = label
            textSize = 12f
            contentDescription = accessibilityLabel
            setOnClickListener { action() }
            selectionControlRows[selectionButtonCount / SELECTION_BUTTONS_PER_ROW].addView(
                this,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            selectionButtonCount += 1
        }
        selectionButton("앞으로 늘리기", "선택 시작을 한 글자 앞쪽으로 확장") {
            selectionGestureController?.adjust(SelectionEdge.START, -1)
        }
        selectionButton("앞에서 줄이기", "선택 시작을 한 글자 뒤쪽으로 축소") {
            selectionGestureController?.adjust(SelectionEdge.START, 1)
        }
        selectionButton("뒤에서 줄이기", "선택 끝을 한 글자 앞쪽으로 축소") {
            selectionGestureController?.adjust(SelectionEdge.END, -1)
        }
        selectionButton("뒤로 늘리기", "선택 끝을 한 글자 뒤쪽으로 확장") {
            selectionGestureController?.adjust(SelectionEdge.END, 1)
        }
        selectionButton("뜻", "선택한 원문의 저장된 뜻 또는 Azure 번역 보기") {
            showSelectedMeanings()
        }
        selectionButton("주석", "선택한 원문에 강조 또는 메모 추가") {
            showSelectionAnnotationMenu()
        }
        selectionButton("병음 수정", "선택한 첫 글자의 임시 병음 수정") {
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
            isClickable = true
            setOnClickListener { handleReaderChromeClick() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isClickable = true
                    info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_CLICK,
                            getString(
                                if (readerChromeVisible) {
                                    R.string.reader_controls_hide
                                } else {
                                    R.string.reader_controls_show
                                },
                            ),
                        ),
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                        return host.performClick()
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        }
        val readerTapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: android.view.MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
                    val centerStart = reader.width / 3f
                    val centerEnd = reader.width * 2f / 3f
                    if (event.x !in centerStart..centerEnd) return false
                    return reader.performClick()
                }
            },
        )
        reader.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: android.view.MotionEvent): Boolean {
                readerTapDetector.onTouchEvent(event)
                return false
            }
        })

        readerChrome.addView(
            statusView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val fileControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bookshelfButton = Button(this).apply {
            text = getString(R.string.bookshelf)
            contentDescription = getString(R.string.bookshelf_description)
            isEnabled = false
            setOnClickListener { showBookshelf() }
        }
        styleButton = Button(this).apply {
            text = getString(R.string.reader_style)
            contentDescription = getString(R.string.reader_style_description)
            isEnabled = false
            setOnClickListener { showReaderStyle() }
        }
        tableOfContentsButton = Button(this).apply {
            text = getString(R.string.table_of_contents)
            contentDescription = getString(R.string.table_of_contents_description)
            isEnabled = false
            setOnClickListener { showTableOfContentsMenu() }
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
            bookshelfButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        fileControls.addView(
            styleButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        fileControls.addView(
            tableOfContentsButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        fileControls.addView(
            openTxtButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        fileControls.addView(
            cancelImportButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        readerChrome.addView(fileControls, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
        searchButton = Button(this).apply {
            text = getString(R.string.search)
            textSize = 12f
            contentDescription = getString(R.string.search_description)
            isEnabled = false
            setOnClickListener {
                if (activeSearchCancellation != null) cancelActiveSearch() else showSearchInput()
            }
        }
        pinyinControls.addView(
            searchButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        pinyinControls.addView(
            Button(this).apply {
                text = getString(R.string.reader_tools)
                textSize = 12f
                contentDescription = getString(R.string.reader_tools_description)
                setOnClickListener { showReaderTools() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        readerChrome.addView(pinyinControls, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
        readerChrome.addView(
            navigationControls,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        root.addView(
            reader,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            readerChrome,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        root.addView(
            selectionControls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        val startOnBookshelf = savedInstanceState?.getBoolean(STATE_BOOKSHELF_VISIBLE)
            ?: !intent.getBooleanExtra(EXTRA_OPEN_READER, false)
        bookshelfHome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(horizontalPadding, 16.dp, horizontalPadding, 8.dp)
            setBackgroundColor(Color.rgb(250, 248, 243))
            visibility = if (startOnBookshelf) View.VISIBLE else View.GONE
        }
        bookshelfHome.addView(TextView(this).apply {
            text = getString(R.string.bookshelf)
            textSize = 26f
            setTextColor(Color.rgb(35, 33, 29))
            setPadding(0, 0, 0, 12.dp)
        })
        val bookshelfActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun bookshelfAction(label: Int, action: () -> Unit) {
            bookshelfActions.addView(Button(this).apply {
                text = getString(label)
                textSize = 12f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        bookshelfAction(R.string.open_txt, ::openTxtPicker)
        bookshelfAction(R.string.bookshelf_settings, ::showReaderStyle)
        bookshelfAction(R.string.bookshelf_manage) {
            bookshelfSnapshot?.let(::showBookshelfDialog)
        }
        bookshelfHome.addView(bookshelfActions)
        bookshelfHome.addView(Button(this).apply {
            text = getString(R.string.reading_time)
            setOnClickListener { showReadingTime() }
        })
        bookshelfHome.addView(Button(this).apply {
            text = getString(R.string.bookshelf_add_folder)
            setOnClickListener { openFolderPicker() }
        })
        bookshelfStatusView = TextView(this).apply {
            text = getString(R.string.bookshelf_loading)
            setPadding(0, 12.dp, 0, 12.dp)
        }
        bookshelfHome.addView(bookshelfStatusView)
        bookshelfEmptyView = TextView(this).apply {
            text = getString(R.string.bookshelf_empty)
            textSize = 18f
            visibility = View.GONE
            setPadding(0, 24.dp, 0, 0)
        }
        bookshelfHome.addView(bookshelfEmptyView)
        bookshelfItems = ListView(this).apply {
            setOnItemClickListener { _, _, position, _ ->
                val book = bookshelfSnapshot?.catalog?.books?.getOrNull(position) ?: return@setOnItemClickListener
                if (book.bookId == currentDocumentSha256) openReaderFromBookshelf()
                else openCatalogBook(book)
            }
        }
        bookshelfHome.addView(bookshelfItems, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        root.addView(bookshelfHome, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        if (startOnBookshelf) reader.visibility = View.INVISIBLE
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsets()
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        updateReaderBackHandling()
        readingTimeScreenOn = getSystemService(PowerManager::class.java).isInteractive
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, screenFilter)
        }
        screenReceiverRegistered = true
        backupExecutor.execute {
            val result = runCatching {
                readingTimeStore().cleanStalePartials()
                readingTimeStore().load().orEmpty()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { readingTimeLedger = ReadingTimeLedger(it); syncReadingTimer() }
                    .onFailure { error ->
                        readingTimeLoadError = error
                        Log.e(READING_TIME_TAG, "saved reading time unavailable; existing file preserved", error)
                    }
            }
        }

        blockExecutor.execute {
            val preparationStarted = SystemClock.elapsedRealtime()
            val removedPartials = TxtImporter.cleanStalePartials(importDirectory()) +
                metadataStore().cleanStalePartials() +
                readingPositionStore().cleanStalePartials() +
                bookCatalogStore().cleanStalePartials() +
                readerStyleStore().cleanStalePartials() +
                manualChapterStore().cleanStalePartials() +
                userMeaningStore().cleanStalePartials() +
                userAnnotationStore().cleanStalePartials()
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
                    bookCatalogStore().reconcileCurrent(metadata, System.currentTimeMillis(), updateLastOpened = false)
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
            val resolvedStyle = runCatching {
                (readerStyleStore().load() ?: ReaderStylePreferences()).resolved(metadata?.sha256)
            }.onFailure { error ->
                Log.e(STYLE_TAG, "saved reader style ignored", error)
            }.getOrDefault(ReaderStyle.DEFAULT)
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
                currentReaderStyle = resolvedStyle
                applyReaderTheme(resolvedStyle)
                val adapter = TextBlockAdapter(blockSource, renderer, resolvedStyle)
                currentDocumentSha256 = metadata?.sha256
                syncReadingTimer()
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
                            selectionRequestGate.selectionChanged()
                            cancelDictionaryLookup()
                            selectionControls.visibility = if (range == null) View.GONE else View.VISIBLE
                            if (range != null) setReaderChromeVisible(false)
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
                bookshelfButton.isEnabled = true
                styleButton.isEnabled = true
                tableOfContentsButton.isEnabled = documentLength > 0
                searchButton.isEnabled = documentLength > 0
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
                refreshHighlights()
            }
        }
        if (startOnBookshelf) showBookshelf()
    }

    private fun handleReaderChromeClick() {
        if (::selectionControls.isInitialized && selectionControls.visibility == View.VISIBLE) {
            val controller = selectionGestureController
            if (controller != null) {
                controller.cancelSelection()
            } else {
                textBlockAdapter?.setSelection(null)
                selectionControls.visibility = View.GONE
            }
            return
        }
        setReaderChromeVisible(!readerChromeVisible)
    }

    private fun setReaderChromeVisible(visible: Boolean) {
        if (!::readerChrome.isInitialized || readerChromeVisible == visible) return
        readerChromeVisible = visible
        readerChrome.visibility = if (visible) View.VISIBLE else View.GONE
        if (::reader.isInitialized) {
            reader.sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            )
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

    private fun openFolderPicker() {
        if (folderCancellation != null) return
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_OPEN_FOLDER)
    }

    private fun showBackupRestoreMenu() {
        if (backupOperationInProgress) {
            Toast.makeText(this, R.string.backup_restore_busy, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_restore)
            .setItems(
                arrayOf(
                    getString(R.string.backup_create),
                    getString(R.string.restore_open),
                ),
            ) { _, which ->
                if (which == 0) showBackupContentsChoice() else openRestorePicker()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBackupContentsChoice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_contents_title)
            .setMessage(R.string.backup_contents_message)
            .setPositiveButton(R.string.backup_include_originals) { _, _ -> openBackupDestination(true) }
            .setNeutralButton(R.string.backup_without_originals) { _, _ -> openBackupDestination(false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openBackupDestination(includeOriginals: Boolean) {
        pendingBackupIncludesOriginals = includeOriginals
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val picker = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.worldviewer.backup"
            putExtra(Intent.EXTRA_TITLE, "worldviewer-$timestamp.wvb")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(picker, REQUEST_CREATE_BACKUP)
    }

    private fun openRestorePicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(picker, REQUEST_OPEN_BACKUP)
    }

    private fun createBackup(uri: Uri, includeOriginals: Boolean) {
        val position = captureReadingPosition()
        beginBackupOperation(getString(R.string.backup_creating), isRestore = false)
        backupExecutor.execute {
            var temporary: File? = null
            val result = runCatching {
                temporary = File.createTempFile("worldviewer-backup-", ".wvb", cacheDir)
                position?.let { current ->
                    positionExecutor.submit { readingPositionStore().save(current) }.get()
                }
                val preview = WorldViewerBackup(filesDir, ::availableStorageBytes).create(
                    destination = requireNotNull(temporary),
                    includeOriginalTexts = includeOriginals,
                )
                try {
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: throw BackupFormatException("선택한 위치에 백업을 쓸 수 없습니다.")
                    output.buffered().use { destination ->
                        requireNotNull(temporary).inputStream().buffered().use { source ->
                            source.copyTo(destination)
                        }
                    }
                } catch (error: Exception) {
                    if (error is BackupFormatException) throw error
                    throw BackupFormatException(
                        "선택한 위치에 백업을 저장하지 못했습니다. 저장 공간과 쓰기 권한을 확인하세요.",
                        error,
                    )
                }
                preview
            }
            temporary?.delete()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                endBackupOperation()
                result.onSuccess { preview ->
                    statusView.text = getString(
                        R.string.backup_created,
                        preview.entries.size,
                        Formatter.formatShortFileSize(this, preview.totalBytes),
                    )
                    Toast.makeText(this, R.string.backup_created_short, Toast.LENGTH_SHORT).show()
                    Log.i(
                        RESTORE_TAG,
                        "backup created entries=${preview.entries.size} bytes=${preview.totalBytes} " +
                            "originals=${preview.originalTextCount}",
                    )
                }.onFailure { error -> showBackupRestoreFailure(R.string.backup_failed, error) }
            }
        }
    }

    private fun inspectRestore(uri: Uri, displayName: String) {
        beginBackupOperation(getString(R.string.restore_inspecting, displayName), isRestore = false)
        backupExecutor.execute {
            var temporary: File? = null
            val result = runCatching {
                temporary = File.createTempFile("worldviewer-restore-", ".wvb", cacheDir)
                copyRestoreSource(uri, requireNotNull(temporary))
                WorldViewerRestore(filesDir, ::availableStorageBytes).preview(requireNotNull(temporary))
            }
            runOnUiThread {
                if (isDestroyed) {
                    temporary?.delete()
                    return@runOnUiThread
                }
                endBackupOperation()
                result.onSuccess { preview -> showRestorePreview(requireNotNull(temporary), displayName, preview) }
                    .onFailure { error ->
                        temporary?.delete()
                        showBackupRestoreFailure(R.string.restore_inspect_failed, error)
                    }
            }
        }
    }

    private fun copyRestoreSource(uri: Uri, destination: File) {
        val declaredSize = contentSize(uri)
        if (declaredSize != null && declaredSize > MAX_BACKUP_ARCHIVE_BYTES) {
            throw BackupFormatException("백업 파일이 허용 크기를 넘습니다.")
        }
        if (
            declaredSize != null &&
            availableStorageBytes(cacheDir) < declaredSize + TEMPORARY_SPACE_MARGIN_BYTES
        ) {
            throw BackupFormatException("백업을 검사할 임시 공간이 부족합니다. 기기 저장 공간을 확보하세요.")
        }
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw BackupFormatException("선택한 백업 파일을 읽을 수 없습니다.")
            input.buffered().use { source ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BACKUP_COPY_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_BACKUP_ARCHIVE_BYTES) {
                            throw BackupFormatException("백업 파일이 허용 크기를 넘습니다.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is BackupFormatException) throw error
            throw BackupFormatException(
                "백업 파일을 임시 공간에 복사하지 못했습니다. 저장 공간과 읽기 권한을 확인하세요.",
                error,
            )
        }
    }

    private fun showRestorePreview(file: File, displayName: String, preview: RestorePreview) {
        pendingRestoreFile?.delete()
        pendingRestoreFile = file
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val message = getString(
            R.string.restore_preview_message,
            displayName,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(preview.backup.createdAtEpochMillis)),
            preview.backup.entries.size,
            Formatter.formatShortFileSize(this, preview.backup.totalBytes),
            preview.backup.originalTextCount,
            preview.newPaths.size,
            preview.unchangedPaths.size,
            preview.conflicts.size,
        )
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.restore_preview_title)
            .setMessage(message)
        if (preview.conflicts.isNotEmpty()) {
            builder
                .setPositiveButton(R.string.restore_keep_existing) { _, _ ->
                    if (pendingRestoreFile === file) pendingRestoreFile = null
                    applyRestore(file, preview, RestoreConflictPolicy.KEEP_EXISTING)
                }
                .setNeutralButton(R.string.restore_replace_existing) { _, _ ->
                    if (pendingRestoreFile === file) pendingRestoreFile = null
                    applyRestore(file, preview, RestoreConflictPolicy.REPLACE_EXISTING)
                }
        } else {
            builder.setPositiveButton(R.string.restore_apply) { _, _ ->
                if (pendingRestoreFile === file) pendingRestoreFile = null
                applyRestore(file, preview, RestoreConflictPolicy.KEEP_EXISTING)
            }
        }
        val dialog = builder
            .setNegativeButton(android.R.string.cancel) { _, _ -> clearPendingRestore(file) }
            .create()
        dialog.setOnCancelListener { clearPendingRestore(file) }
        dialog.show()
    }

    private fun clearPendingRestore(file: File) {
        if (pendingRestoreFile === file) {
            pendingRestoreFile = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        file.delete()
    }

    private fun applyRestore(file: File, preview: RestorePreview, policy: RestoreConflictPolicy) {
        beginBackupOperation(getString(R.string.restore_applying), isRestore = true)
        backupExecutor.execute {
            val result = runCatching {
                positionExecutor.submit { }.get()
                WorldViewerRestore(filesDir, ::availableStorageBytes).restore(file, policy, preview)
            }
            file.delete()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { restored ->
                    endBackupOperation(keepRestoreGuard = true)
                    Log.i(
                        RESTORE_TAG,
                        "restore applied=${restored.appliedPaths.size} skipped=${restored.skippedPaths.size} " +
                            "policy=$policy",
                    )
                    Toast.makeText(this, R.string.restore_complete, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }.onFailure { error ->
                    endBackupOperation()
                    showBackupRestoreFailure(R.string.restore_failed, error)
                }
            }
        }
    }

    private fun beginBackupOperation(message: String, isRestore: Boolean) {
        backupOperationInProgress = true
        restoreInProgress = isRestore
        syncReadingTimer()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        statusView.text = message
        backupOperationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.backup_restore)
            .setMessage(message)
            .setCancelable(false)
            .show()
    }

    private fun endBackupOperation(keepRestoreGuard: Boolean = false) {
        backupOperationDialog?.dismiss()
        backupOperationDialog = null
        backupOperationInProgress = false
        if (!keepRestoreGuard) {
            restoreInProgress = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        syncReadingTimer()
    }

    private fun showBackupRestoreFailure(title: Int, error: Throwable) {
        Log.e(RESTORE_TAG, "backup or restore operation failed", error)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(error.message ?: getString(R.string.unknown_error))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @Suppress("UsableSpace")
    private fun availableStorageBytes(path: File): Long = runCatching {
        val storage = getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(path))
    }.getOrElse { path.usableSpace }

    private fun showBookshelf() {
        if (bookshelfHome.visibility != View.VISIBLE) {
            persistReadingPosition()
            selectionGestureController?.cancelSelection()
        }
        setReaderChromeVisible(false)
        reader.visibility = View.INVISIBLE
        bookshelfHome.visibility = View.VISIBLE
        syncReadingTimer()
        rootLayout.setBackgroundColor(Color.rgb(250, 248, 243))
        applySystemBarAppearance(false)
        updateReaderBackHandling()
        bookshelfStatusView.text = getString(R.string.bookshelf_loading)
        bookshelfStatusView.visibility = View.VISIBLE
        bookshelfButton.isEnabled = false
        styleButton.isEnabled = false
        tableOfContentsButton.isEnabled = false
        blockExecutor.execute {
            val result = runCatching {
                positionExecutor.submit { }.get()
                val catalog = bookCatalogStore().load() ?: BookCatalog(currentBookId = null, books = emptyList())
                val progress = catalog.books.mapNotNull { book ->
                    runCatching {
                        val position = readingPositionStore().load(book.documentSha256) ?: return@runCatching null
                        val indexDirectory = File(cacheDir, TEXT_INDEX_DIRECTORY)
                        StreamingTextIndex.Reader(
                            File(indexDirectory, "${book.documentSha256}.index"),
                            File(indexDirectory, "${book.documentSha256}.blocks"),
                        ).use { index ->
                            book.bookId to ReadingProgress.fromOffset(position.utf16Offset, index.utf16Length)
                        }
                    }.onFailure { error ->
                        Log.w(CATALOG_TAG, "recent progress unavailable book=${book.bookId}", error)
                    }.getOrNull()
                }.toMap()
                BookshelfSnapshot(catalog, progress)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                bookshelfButton.isEnabled = activeImportCancellation == null
                styleButton.isEnabled = activeImportCancellation == null
                tableOfContentsButton.isEnabled = activeImportCancellation == null && documentUtf16Length > 0
                result.onSuccess(::renderBookshelfHome)
                    .onFailure { error -> showLibraryFailure(getString(R.string.bookshelf_load_failed), error) }
            }
        }
    }

    private fun renderBookshelfHome(snapshot: BookshelfSnapshot) {
        bookshelfSnapshot = snapshot
        bookshelfStatusView.visibility = View.GONE
        bookshelfEmptyView.visibility = if (snapshot.catalog.books.isEmpty()) View.VISIBLE else View.GONE
        bookshelfItems.adapter = object : ArrayAdapter<CharSequence>(
            this,
            android.R.layout.simple_list_item_1,
            bookshelfLabels(snapshot),
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    isSingleLine = false
                    maxLines = 4
                    textSize = 16f
                    setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                }
        }
    }

    private fun openReaderFromBookshelf() {
        currentDocumentSha256?.let { bookId ->
            blockExecutor.execute {
                runCatching { bookCatalogStore().markOpened(bookId, System.currentTimeMillis()) }
                    .onFailure { error -> Log.e(CATALOG_TAG, "recent book timestamp update failed", error) }
            }
        }
        bookshelfHome.visibility = View.GONE
        reader.visibility = View.VISIBLE
        syncReadingTimer()
        rootLayout.setBackgroundColor(currentReaderStyle.resolvedBackgroundColor())
        applySystemBarAppearance(hasDarkReaderBackground(currentReaderStyle))
        updateReaderBackHandling()
        reader.requestFocus()
        if (pinyinMode != PinyinDisplayMode.ORIGINAL) reader.post { requestVisiblePinyin() }
    }

    private fun bookshelfLabels(snapshot: BookshelfSnapshot): Array<CharSequence> =
        snapshot.catalog.books.map { book ->
            val title = if (book.bookId == snapshot.catalog.currentBookId) {
                getString(R.string.bookshelf_current, book.displayName)
            } else {
                book.displayName
            }
            val cover = AutoBookCover.from(book.bookId, book.displayName)
            val prefix = "■ ${cover.initial}  "
            SpannableString(
                prefix + getString(
                    R.string.bookshelf_item,
                    title,
                    book.encoding,
                    book.byteCount,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(
                        Date(book.lastOpenedAtEpochMillis),
                    ),
                    snapshot.progressTenths[book.bookId]?.let { progress ->
                        getString(R.string.bookshelf_progress_suffix, progress / 10.0)
                    }.orEmpty(),
                ),
            ).apply {
                setSpan(
                    ForegroundColorSpan(cover.colorArgb),
                    0,
                    prefix.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }.toTypedArray()

    private fun showBookshelfDialog(snapshot: BookshelfSnapshot) {
        val catalog = snapshot.catalog
        if (catalog.books.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.bookshelf)
                .setMessage(R.string.bookshelf_empty)
                .setPositiveButton(R.string.open_txt) { _, _ -> openTxtPicker() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val labels = bookshelfLabels(snapshot)
        var selected = catalog.books.indexOfFirst { it.bookId == catalog.currentBookId }.coerceAtLeast(0)
        var updateActions: () -> Unit = {}
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf)
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
                updateActions()
            }
            .setPositiveButton(R.string.bookshelf_open, null)
            .setNeutralButton(R.string.bookshelf_remove, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val openButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val removeButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            updateActions = {
                val selectedBook = catalog.books.getOrNull(selected)
                val isCurrent = selectedBook?.bookId == catalog.currentBookId
                openButton.isEnabled = selectedBook != null && !isCurrent
                removeButton.isEnabled = selectedBook != null && !isCurrent
            }
            openButton.setOnClickListener {
                val selectedBook = catalog.books.getOrNull(selected) ?: return@setOnClickListener
                dialog.dismiss()
                openCatalogBook(selectedBook)
            }
            removeButton.setOnClickListener {
                val selectedBook = catalog.books.getOrNull(selected) ?: return@setOnClickListener
                dialog.dismiss()
                confirmCatalogRemoval(selectedBook)
            }
            updateActions()
        }
        dialog.show()
    }

    private fun openCatalogBook(book: BookCatalogEntry) {
        beginLibraryOperation(getString(R.string.bookshelf_opening, book.displayName))
        persistReadingPosition()
        blockExecutor.execute {
            val result = runCatching {
                val inspected = TxtImporter.inspectStored(
                    resolveStoredImport(book.storedName),
                    expectedEncoding = book.encoding,
                )
                if (
                    inspected.sha256 != book.documentSha256 ||
                    inspected.byteCount != book.byteCount ||
                    inspected.encoding != book.encoding
                ) {
                    throw TxtImportException("보존된 책의 검증값이 목록과 일치하지 않습니다.")
                }
                val metadata = ImportMetadata(
                    storedName = book.storedName,
                    displayName = book.displayName,
                    sha256 = book.documentSha256,
                    byteCount = book.byteCount,
                    encoding = book.encoding,
                )
                metadataStore().save(metadata)
                bookCatalogStore().reconcileCurrent(metadata, System.currentTimeMillis())
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess {
                    Log.i(CATALOG_TAG, "opened book=${book.bookId}")
                    startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_READER, true))
                    finish()
                }.onFailure { error ->
                    finishLibraryOperation()
                    showLibraryFailure(getString(R.string.bookshelf_open_failed), error)
                }
            }
        }
    }

    private fun confirmCatalogRemoval(book: BookCatalogEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bookshelf_remove_title, book.displayName))
            .setMessage(R.string.bookshelf_remove_message)
            .setPositiveButton(R.string.bookshelf_remove) { _, _ -> removeCatalogBook(book) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeCatalogBook(book: BookCatalogEntry) {
        beginLibraryOperation(getString(R.string.bookshelf_removing, book.displayName))
        blockExecutor.execute {
            val result = runCatching { bookCatalogStore().removeNonCurrent(book.bookId) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                finishLibraryOperation()
                result.onSuccess {
                    statusView.text = getString(R.string.bookshelf_removed, book.displayName)
                    Log.i(CATALOG_TAG, "removed book=${book.bookId}; original preserved")
                    showBookshelf()
                }.onFailure { error ->
                    showLibraryFailure(getString(R.string.bookshelf_remove_failed), error)
                }
            }
        }
    }

    private fun beginLibraryOperation(message: String) {
        cancelActiveSearch()
        bookshelfButton.isEnabled = false
        styleButton.isEnabled = false
        tableOfContentsButton.isEnabled = false
        searchButton.isEnabled = false
        openTxtButton.isEnabled = false
        statusView.text = message
        bookshelfStatusView.text = message
        bookshelfStatusView.visibility = View.VISIBLE
    }

    private fun finishLibraryOperation() {
        if (activeImportCancellation != null) return
        bookshelfButton.isEnabled = true
        styleButton.isEnabled = true
        tableOfContentsButton.isEnabled = documentUtf16Length > 0
        searchButton.isEnabled = documentUtf16Length > 0
        openTxtButton.isEnabled = true
    }

    private fun showLibraryFailure(title: String, error: Throwable) {
        statusView.text = getString(
            R.string.bookshelf_operation_failed,
            error.message ?: getString(R.string.unknown_error),
        )
        Log.e(CATALOG_TAG, title, error)
        bookshelfStatusView.text = getString(R.string.bookshelf_operation_failed, error.message ?: getString(R.string.unknown_error))
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(error.message ?: getString(R.string.unknown_error))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showReaderStyle() {
        styleButton.isEnabled = false
        bookshelfButton.isEnabled = false
        tableOfContentsButton.isEnabled = false
        blockExecutor.execute {
            val result = runCatching { readerStyleStore().load() ?: ReaderStylePreferences() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                styleButton.isEnabled = activeImportCancellation == null
                bookshelfButton.isEnabled = activeImportCancellation == null
                tableOfContentsButton.isEnabled = activeImportCancellation == null && documentUtf16Length > 0
                result.onSuccess(::showReaderStyleDialog)
                    .onFailure { error -> showStyleFailure(error) }
            }
        }
    }

    private fun showAutomaticTableOfContents() {
        tableOfContentsButton.isEnabled = false
        val documentId = currentDocumentSha256
        blockExecutor.execute {
            val result = runCatching {
                if (documentId == null) {
                    AutomaticChapterDetector.detect(TextBlocker.split(ReaderFixture.text, MAX_BLOCK_UTF16).asSequence())
                } else {
                    val directory = File(cacheDir, TEXT_INDEX_DIRECTORY)
                    StreamingTextIndex.Reader(
                        File(directory, "$documentId.index"),
                        File(directory, "$documentId.blocks"),
                    ).use { index ->
                        AutomaticChapterDetector.detect(
                            (0 until index.blockCount).asSequence().map(index::blockAt),
                        )
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tableOfContentsButton.isEnabled = documentUtf16Length > 0
                result.onSuccess(::showAutomaticTableOfContentsDialog)
                    .onFailure { error ->
                        Log.e(CHAPTER_TAG, "automatic table of contents failed", error)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.table_of_contents_failed)
                            .setMessage(error.message ?: getString(R.string.unknown_error))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
            }
        }
    }

    private fun showTableOfContentsMenu() {
        val actions = if (currentDocumentSha256 == null) {
            listOf(getString(R.string.table_of_contents_automatic))
        } else {
            listOf(
                getString(R.string.table_of_contents_automatic),
                getString(R.string.table_of_contents_manual),
                getString(R.string.table_of_contents_add_here),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.table_of_contents)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showAutomaticTableOfContents()
                    1 -> showManualTableOfContents()
                    2 -> addManualChapterHere()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSearchInput() {
        if (documentUtf16Length <= 0) return
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.search_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.search_title)
            .setView(input)
            .setPositiveButton(R.string.search_start) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isBlank() || query.length > StreamingTextSearch.MAX_QUERY_UTF16) {
                    Toast.makeText(this, R.string.search_invalid_query, Toast.LENGTH_SHORT).show()
                } else {
                    startStreamingSearch(query)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startStreamingSearch(query: String) {
        cancelActiveSearch()
        val cancellation = TextSearchCancellation()
        val generation = ++searchGeneration
        val documentId = currentDocumentSha256
        val results = ArrayList<TextSearchResult>()
        val labels = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val status = TextView(this).apply {
            setPadding(24.dp, 12.dp, 24.dp, 12.dp)
            text = getString(R.string.search_progress, 0, 0)
        }
        val list = ListView(this).apply { adapter = labels }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 360.dp))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_results_title, query))
            .setView(content)
            .setNegativeButton(R.string.search_cancel, null)
            .create()
        activeSearchCancellation = cancellation
        activeSearchDialog = dialog
        searchButton.text = getString(R.string.search_cancel)
        searchButton.contentDescription = getString(R.string.search_cancel_description)
        list.setOnItemClickListener { _, _, position, _ ->
            val result = results.getOrNull(position) ?: return@setOnItemClickListener
            val origin = captureReaderAnchor() ?: return@setOnItemClickListener
            cancelActiveSearch()
            navigateToUtf16(result.utf16Offset, origin, reader.paddingTop + 1)
            Log.i(SEARCH_TAG, "navigate queryLength=${query.length} utf16=${result.utf16Offset}")
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { cancelActiveSearch() }
        }
        dialog.setOnCancelListener { cancelActiveSearch() }
        dialog.setOnDismissListener {
            if (activeSearchDialog === dialog) cancelActiveSearch()
        }
        dialog.show()

        blockExecutor.execute {
            val searchResult = runCatching {
                fun search(blocks: Sequence<TextBlock>) = StreamingTextSearch.search(
                    blocks = blocks,
                    query = query,
                    cancellation = cancellation,
                    onProgress = { progress ->
                        runOnUiThread {
                            if (
                                isDestroyed || generation != searchGeneration ||
                                activeSearchDialog !== dialog || currentDocumentSha256 != documentId
                            ) return@runOnUiThread
                            progress.matches.forEach { match ->
                                results += match
                                val percent = ReadingProgress.fromOffset(
                                    match.utf16Offset,
                                    documentUtf16Length,
                                ) / 10.0
                                labels.add(getString(R.string.search_result_item, percent, match.preview))
                            }
                            status.text = getString(R.string.search_progress, progress.scannedBlocks, results.size)
                        }
                    },
                )
                if (documentId == null) {
                    search(TextBlocker.split(ReaderFixture.text, MAX_BLOCK_UTF16).asSequence())
                } else {
                    val directory = File(cacheDir, TEXT_INDEX_DIRECTORY)
                    StreamingTextIndex.Reader(
                        File(directory, "$documentId.index"),
                        File(directory, "$documentId.blocks"),
                    ).use { index ->
                        search((0 until index.blockCount).asSequence().map(index::blockAt))
                    }
                }
            }
            runOnUiThread {
                if (
                    isDestroyed || generation != searchGeneration ||
                    activeSearchDialog !== dialog || currentDocumentSha256 != documentId
                ) return@runOnUiThread
                searchResult.onSuccess { summary ->
                    if (activeSearchCancellation === cancellation) activeSearchCancellation = null
                    searchButton.text = getString(R.string.search)
                    searchButton.contentDescription = getString(R.string.search_description)
                    status.text = when {
                        summary.cancelled -> getString(R.string.search_cancelled, results.size)
                        summary.truncated -> getString(R.string.search_truncated, results.size)
                        results.isEmpty() -> getString(R.string.search_empty)
                        else -> getString(R.string.search_complete, results.size)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = getString(android.R.string.ok)
                    Log.i(
                        SEARCH_TAG,
                        "complete queryLength=${query.length} results=${summary.resultCount} " +
                            "blocks=${summary.scannedBlocks} cancelled=${summary.cancelled} " +
                            "truncated=${summary.truncated}",
                    )
                }.onFailure { error ->
                    if (activeSearchCancellation === cancellation) activeSearchCancellation = null
                    searchButton.text = getString(R.string.search)
                    searchButton.contentDescription = getString(R.string.search_description)
                    status.text = getString(
                        R.string.search_failed,
                        error.message ?: getString(R.string.unknown_error),
                    )
                    Log.e(SEARCH_TAG, "search failed", error)
                }
            }
        }
    }

    private fun cancelActiveSearch() {
        val cancellation = activeSearchCancellation
        val dialog = activeSearchDialog
        if (cancellation == null && dialog == null) return
        searchGeneration++
        activeSearchCancellation = null
        activeSearchDialog = null
        cancellation?.cancel()
        if (dialog?.isShowing == true) dialog.dismiss()
        if (::searchButton.isInitialized) {
            searchButton.text = getString(R.string.search)
            searchButton.contentDescription = getString(R.string.search_description)
            searchButton.isEnabled = documentUtf16Length > 0 && activeImportCancellation == null
        }
        Log.i(SEARCH_TAG, "cancelled")
    }

    private fun addManualChapterHere() {
        val position = captureReadingPosition()
        if (position == null) {
            Toast.makeText(this, R.string.table_of_contents_position_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.table_of_contents_title_hint)
        }
        blockExecutor.execute {
            val result = runCatching { manualChapterStore().load(position.documentSha256).size + 1 }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { number ->
                    input.setText(getString(R.string.table_of_contents_default_title, number))
                    input.selectAll()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.table_of_contents_add_here)
                        .setView(input)
                        .setPositiveButton(R.string.table_of_contents_save) { _, _ ->
                            val title = input.text.toString().trim()
                            val chapter = ManualChapter(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                utf16Offset = position.utf16Offset,
                                quoteBefore = position.quoteBefore,
                                quoteAfter = position.quoteAfter,
                            )
                            updateManualChapters(position.documentSha256) { chapters -> chapters + chapter }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }.onFailure(::showManualChapterFailure)
            }
        }
    }

    private fun showManualTableOfContents() {
        val documentId = currentDocumentSha256 ?: return
        tableOfContentsButton.isEnabled = false
        blockExecutor.execute {
            val result = runCatching { manualChapterStore().load(documentId) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tableOfContentsButton.isEnabled = documentUtf16Length > 0
                result.onSuccess(::showManualTableOfContentsDialog)
                    .onFailure(::showManualChapterFailure)
            }
        }
    }

    private fun showManualTableOfContentsDialog(chapters: List<ManualChapter>) {
        if (chapters.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.table_of_contents_manual)
                .setMessage(R.string.table_of_contents_manual_empty)
                .setPositiveButton(R.string.table_of_contents_add_here) { _, _ -> addManualChapterHere() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val labels = chapters.map { chapter ->
            val progress = ReadingProgress.fromOffset(chapter.utf16Offset, documentUtf16Length) / 10.0
            getString(R.string.table_of_contents_item, chapter.title, progress)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.table_of_contents_manual)
            .setItems(labels) { _, which ->
                chapters.getOrNull(which)?.let(::showManualChapterActions)
            }
            .setPositiveButton(R.string.table_of_contents_add_here) { _, _ -> addManualChapterHere() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManualChapterActions(chapter: ManualChapter) {
        AlertDialog.Builder(this)
            .setTitle(chapter.title)
            .setMessage(
                getString(
                    R.string.table_of_contents_manual_position,
                    ReadingProgress.fromOffset(chapter.utf16Offset, documentUtf16Length) / 10.0,
                ),
            )
            .setPositiveButton(R.string.table_of_contents_go) { _, _ ->
                val origin = captureReaderAnchor() ?: return@setPositiveButton
                navigateToUtf16(chapter.utf16Offset, origin, reader.paddingTop + 1)
                Log.i(CHAPTER_TAG, "manual navigate id=${chapter.id} utf16=${chapter.utf16Offset}")
            }
            .setNeutralButton(R.string.table_of_contents_edit) { _, _ -> editManualChapter(chapter) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editManualChapter(chapter: ManualChapter) {
        val documentId = currentDocumentSha256 ?: return
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(chapter.title)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.table_of_contents_edit)
            .setView(input)
            .setPositiveButton(R.string.table_of_contents_save) { _, _ ->
                val title = input.text.toString().trim()
                updateManualChapters(documentId) { chapters ->
                    chapters.map { saved -> if (saved.id == chapter.id) saved.copy(title = title) else saved }
                }
            }
            .setNeutralButton(R.string.table_of_contents_delete) { _, _ ->
                confirmDeleteManualChapter(documentId, chapter)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteManualChapter(documentId: String, chapter: ManualChapter) {
        AlertDialog.Builder(this)
            .setTitle(R.string.table_of_contents_delete)
            .setMessage(getString(R.string.table_of_contents_delete_message, chapter.title))
            .setPositiveButton(R.string.table_of_contents_delete) { _, _ ->
                updateManualChapters(documentId) { chapters ->
                    chapters.filterNot { saved -> saved.id == chapter.id }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateManualChapters(
        documentId: String,
        transform: (List<ManualChapter>) -> List<ManualChapter>,
    ) {
        tableOfContentsButton.isEnabled = false
        blockExecutor.execute {
            val result = runCatching {
                val store = manualChapterStore()
                val updated = transform(store.load(documentId))
                store.save(documentId, updated)
                updated
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tableOfContentsButton.isEnabled = documentUtf16Length > 0
                result.onSuccess {
                    Toast.makeText(this, R.string.table_of_contents_saved, Toast.LENGTH_SHORT).show()
                }.onFailure(::showManualChapterFailure)
            }
        }
    }

    private fun showManualChapterFailure(error: Throwable) {
        Log.e(CHAPTER_TAG, "manual table of contents failed", error)
        AlertDialog.Builder(this)
            .setTitle(R.string.table_of_contents_manual_failed)
            .setMessage(error.message ?: getString(R.string.unknown_error))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAutomaticTableOfContentsDialog(chapters: List<AutomaticChapter>) {
        if (chapters.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.table_of_contents)
                .setMessage(R.string.table_of_contents_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = chapters.map { chapter ->
            val progress = ReadingProgress.fromOffset(chapter.utf16Offset, documentUtf16Length) / 10.0
            getString(R.string.table_of_contents_item, chapter.title, progress)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.table_of_contents_automatic)
            .setItems(labels) { _, which ->
                val chapter = chapters.getOrNull(which) ?: return@setItems
                val origin = captureReaderAnchor() ?: return@setItems
                navigateToUtf16(chapter.utf16Offset, origin, reader.paddingTop + 1)
                Log.i(CHAPTER_TAG, "navigate title=${chapter.title} utf16=${chapter.utf16Offset}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showReaderStyleDialog(preferences: ReaderStylePreferences) {
        val bookId = currentDocumentSha256
        val padding = 24.dp
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 8.dp, padding, 0)
        }
        val scopeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val bookScope = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.reader_style_scope_book)
            isEnabled = bookId != null
        }
        val defaultScope = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.reader_style_scope_default)
        }
        scopeGroup.addView(bookScope, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        scopeGroup.addView(defaultScope, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(scopeGroup)

        val fontLabel = TextView(this)
        val fontSize = SeekBar(this).apply { max = STYLE_FONT_MAX - STYLE_FONT_MIN }
        val spacingLabel = TextView(this)
        val lineSpacing = SeekBar(this).apply { max = STYLE_LINE_MAX - STYLE_LINE_MIN }
        content.addView(fontLabel)
        content.addView(fontSize)
        content.addView(spacingLabel)
        content.addView(lineSpacing)

        val themeLabel = TextView(this).apply { text = getString(R.string.reader_style_theme) }
        val themeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val lightTheme = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.reader_style_light)
        }
        val darkTheme = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.reader_style_dark)
        }
        themeGroup.addView(lightTheme, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        themeGroup.addView(darkTheme, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(themeLabel)
        content.addView(themeGroup)

        val textColors = listOf<Int?>(null) + ReaderStyleColors.TEXT
        val backgroundColors = listOf<Int?>(null) + ReaderStyleColors.BACKGROUND
        val textColorSpinner = Spinner(this)
        val backgroundColorSpinner = Spinner(this)
        val colorNames = resources.getStringArray(R.array.reader_style_color_names).toList()
        val backgroundNames = resources.getStringArray(R.array.reader_style_background_names).toList()
        textColorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colorNames)
        backgroundColorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backgroundNames)
        content.addView(TextView(this).apply { text = getString(R.string.reader_style_text_color) })
        content.addView(textColorSpinner)
        content.addView(TextView(this).apply { text = getString(R.string.reader_style_background_color) })
        content.addView(backgroundColorSpinner)
        val preview = TextView(this).apply {
            text = getString(R.string.reader_style_preview_sample)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
        content.addView(preview)
        val contrastMessage = TextView(this).apply { text = getString(R.string.reader_style_contrast_warning) }
        content.addView(contrastMessage)
        var saveButton: Button? = null
        var clearButton: Button? = null

        fun selectedStyle(): ReaderStyle = ReaderStyle(
            fontSizeSp = STYLE_FONT_MIN + fontSize.progress,
            lineSpacingPercent = STYLE_LINE_MIN + lineSpacing.progress,
            theme = if (themeGroup.checkedRadioButtonId == darkTheme.id) ReaderTheme.DARK else ReaderTheme.LIGHT,
            textColorArgb = textColors[textColorSpinner.selectedItemPosition.coerceAtLeast(0)],
            backgroundColorArgb = backgroundColors[backgroundColorSpinner.selectedItemPosition.coerceAtLeast(0)],
        )
        fun updatePreview() {
            val style = selectedStyle()
            preview.textSize = style.fontSizeSp.toFloat()
            preview.setLineSpacing(0f, style.lineSpacingPercent / 100f)
            preview.setTextColor(style.resolvedTextColor())
            preview.setBackgroundColor(style.resolvedBackgroundColor())
            contrastMessage.visibility = if (style.hasReadableContrast()) View.GONE else View.VISIBLE
            saveButton?.isEnabled = style.hasReadableContrast()
        }

        fun updateLabels() {
            fontLabel.text = getString(R.string.reader_style_font_size, STYLE_FONT_MIN + fontSize.progress)
            spacingLabel.text = getString(R.string.reader_style_line_spacing, STYLE_LINE_MIN + lineSpacing.progress)
            updatePreview()
        }
        fun putStyle(style: ReaderStyle) {
            fontSize.progress = style.fontSizeSp.coerceIn(STYLE_FONT_MIN, STYLE_FONT_MAX) - STYLE_FONT_MIN
            lineSpacing.progress = style.lineSpacingPercent.coerceIn(STYLE_LINE_MIN, STYLE_LINE_MAX) - STYLE_LINE_MIN
            themeGroup.check(if (style.theme == ReaderTheme.DARK) darkTheme.id else lightTheme.id)
            textColorSpinner.setSelection(textColors.indexOf(style.textColorArgb).coerceAtLeast(0))
            backgroundColorSpinner.setSelection(backgroundColors.indexOf(style.backgroundColorArgb).coerceAtLeast(0))
            updateLabels()
        }
        val colorListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updatePreview()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        textColorSpinner.onItemSelectedListener = colorListener
        backgroundColorSpinner.onItemSelectedListener = colorListener
        themeGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        }
        fontSize.setOnSeekBarChangeListener(sliderListener)
        lineSpacing.setOnSeekBarChangeListener(sliderListener)
        scopeGroup.setOnCheckedChangeListener { _, checkedId ->
            val style = if (checkedId == defaultScope.id) {
                preferences.defaultStyle
            } else {
                bookId?.let(preferences.bookStyles::get) ?: preferences.defaultStyle
            }
            putStyle(style)
            clearButton?.isEnabled = checkedId == defaultScope.id ||
                (bookId != null && preferences.bookStyles.containsKey(bookId))
        }
        if (bookId != null) scopeGroup.check(bookScope.id) else scopeGroup.check(defaultScope.id)

        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_style)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.reader_style_save, null)
            .setNeutralButton(R.string.reader_style_use_default, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            clearButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            clearButton?.isEnabled = scopeGroup.checkedRadioButtonId == defaultScope.id ||
                (bookId != null && preferences.bookStyles.containsKey(bookId))
            updatePreview()
            saveButton?.setOnClickListener {
                val style = selectedStyle()
                val saveForBook = scopeGroup.checkedRadioButtonId == bookScope.id && bookId != null
                saveReaderStyle(dialog) { latest ->
                    if (saveForBook) {
                        latest.copy(bookStyles = latest.bookStyles + (requireNotNull(bookId) to style))
                    } else {
                        latest.copy(defaultStyle = style)
                    }
                }
            }
            clearButton?.setOnClickListener {
                val defaultSelected = scopeGroup.checkedRadioButtonId == defaultScope.id
                saveReaderStyle(dialog) { latest ->
                    if (defaultSelected) latest.copy(defaultStyle = ReaderStyle.DEFAULT)
                    else latest.copy(bookStyles = latest.bookStyles - requireNotNull(bookId))
                }
            }
        }
        dialog.show()
    }

    private fun saveReaderStyle(
        dialog: AlertDialog,
        update: (ReaderStylePreferences) -> ReaderStylePreferences,
    ) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
        blockExecutor.execute {
            val result = runCatching {
                val latest = readerStyleStore().load() ?: ReaderStylePreferences()
                val updated = update(latest)
                readerStyleStore().save(updated)
                updated.resolved(currentDocumentSha256)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { style ->
                    dialog.dismiss()
                    applyReaderStyle(style)
                    statusView.text = getString(R.string.reader_style_saved)
                }.onFailure { error ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = currentDocumentSha256 != null
                    showStyleFailure(error)
                }
            }
        }
    }

    private fun applyReaderStyle(style: ReaderStyle) {
        val anchor = captureReaderAnchor()
        currentReaderStyle = style
        applyReaderTheme(style)
        textBlockAdapter?.setReaderStyle(style)
        restoreReaderAnchor(anchor)
        if (pinyinMode != PinyinDisplayMode.ORIGINAL) reader.post { requestVisiblePinyin() }
        Log.i(
            STYLE_TAG,
            "applied book=${currentDocumentSha256 ?: "fixture"} font=${style.fontSizeSp} " +
                "line=${style.lineSpacingPercent} theme=${style.theme}",
        )
    }

    private fun applyReaderTheme(style: ReaderStyle) {
        val dark = hasDarkReaderBackground(style)
        val background = style.resolvedBackgroundColor()
        val text = if (dark) Color.rgb(232, 230, 224) else Color.rgb(35, 34, 31)
        val accent = if (dark) Color.rgb(153, 205, 188) else Color.rgb(47, 93, 80)
        val button = if (dark) Color.rgb(52, 56, 53) else Color.rgb(230, 226, 217)
        rootLayout.setBackgroundColor(if (bookshelfHome.visibility == View.VISIBLE) Color.rgb(250, 248, 243) else background)
        reader.setBackgroundColor(background)
        readerChrome.setBackgroundColor(background)
        selectionControls.setBackgroundColor(background)
        statusView.setTextColor(accent)
        readingProgressView.setTextColor(accent)
        applyControlTheme(readerChrome, text, button)
        applyControlTheme(selectionControls, text, button)
        applySystemBarAppearance(dark && bookshelfHome.visibility != View.VISIBLE)
    }

    private fun hasDarkReaderBackground(style: ReaderStyle): Boolean =
        ReaderStyleColors.contrast(style.resolvedBackgroundColor(), Color.WHITE) >
            ReaderStyleColors.contrast(style.resolvedBackgroundColor(), Color.BLACK)

    private fun applySystemBarAppearance(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    private fun applyControlTheme(group: ViewGroup, textColor: Int, buttonColor: Int) {
        for (index in 0 until group.childCount) {
            when (val child = group.getChildAt(index)) {
                is Button -> {
                    child.setTextColor(textColor)
                    child.backgroundTintList = ColorStateList.valueOf(buttonColor)
                }
                is ViewGroup -> applyControlTheme(child, textColor, buttonColor)
            }
        }
    }

    private fun showStyleFailure(error: Throwable) {
        Log.e(STYLE_TAG, "reader style operation failed", error)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_style_failed)
            .setMessage(error.message ?: getString(R.string.unknown_error))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @Deprecated("Uses the platform file picker without adding an Activity Result dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_FOLDER) {
            if (resultCode == RESULT_OK) data?.data?.let(::scanFolder)
            return
        }
        if (requestCode == REQUEST_CREATE_BACKUP) {
            val includeOriginals = pendingBackupIncludesOriginals
            pendingBackupIncludesOriginals = null
            if (resultCode == RESULT_OK && includeOriginals != null) {
                data?.data?.let { uri -> createBackup(uri, includeOriginals) }
            }
            return
        }
        if (requestCode == REQUEST_OPEN_BACKUP) {
            if (resultCode == RESULT_OK) {
                data?.data?.let { uri -> inspectRestore(uri, displayName(uri)) }
            }
            return
        }
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

    private fun scanFolder(treeUri: Uri) {
        val cancellation = ImportCancellation()
        folderCancellation = cancellation
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_folder_scan_title)
            .setMessage(R.string.bookshelf_folder_scanning)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancellation.cancel() }
            .setOnCancelListener { cancellation.cancel() }
            .create()
        folderOperationDialog = dialog
        dialog.show()
        blockExecutor.execute {
            val result = runCatching {
                val existing = bookCatalogStore().load()?.books?.map(BookCatalogEntry::bookId).orEmpty().toSet()
                FolderTxtScanner(contentResolver).scan(treeUri, existing, cancellation) { count ->
                    runOnUiThread {
                        if (!isDestroyed && folderCancellation === cancellation) {
                            dialog.setMessage(getString(R.string.bookshelf_folder_scanned, count))
                        }
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed || folderCancellation !== cancellation) return@runOnUiThread
                dialog.dismiss()
                folderOperationDialog = null
                folderCancellation = null
                result.onSuccess(::showFolderPreview).onFailure { error ->
                    if (error !is TxtImportCancelledException) showLibraryFailure(
                        getString(R.string.bookshelf_folder_scan_title), error,
                    )
                }
            }
        }
    }

    private fun showFolderPreview(scan: FolderTxtScan) {
        val examples = scan.newFiles.take(8).joinToString("\n") { "• ${it.displayName}" }
        val failureExamples = scan.failures.take(4).joinToString("\n") { "• $it" }
        val message = getString(
            R.string.bookshelf_folder_preview,
            scan.newFiles.size,
            scan.duplicateCount,
            scan.failures.size,
            scan.newFiles.count { it.encodings.none { candidate -> candidate.automatic } },
        ) + listOf(examples, failureExamples).filter(String::isNotEmpty).joinToString("\n\n", prefix = "\n\n")
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_folder_scan_title)
            .setMessage(message)
            .setPositiveButton(R.string.bookshelf_folder_import) { _, _ -> startFolderBatch(scan) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = scan.newFiles.isNotEmpty()
        }
        dialog.show()
    }

    private data class FolderBatch(
        val files: List<FolderTxtCandidate>,
        val cancellation: ImportCancellation,
        val duplicateCount: Int,
        val failures: MutableList<String>,
        var index: Int = 0,
        var added: Int = 0,
    )

    private fun startFolderBatch(scan: FolderTxtScan) {
        val cancellation = ImportCancellation()
        folderCancellation = cancellation
        val batch = FolderBatch(scan.newFiles, cancellation, scan.duplicateCount, scan.failures.toMutableList())
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_folder_import)
            .setMessage(getString(R.string.bookshelf_folder_progress, 0, batch.files.size, ""))
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancellation.cancel() }
            .setOnCancelListener { cancellation.cancel() }
            .create()
        folderOperationDialog = dialog
        dialog.show()
        continueFolderBatch(batch)
    }

    private fun continueFolderBatch(batch: FolderBatch) {
        if (isDestroyed || batch.cancellation !== folderCancellation) return
        if (batch.index >= batch.files.size || batch.cancellation.isCancelled()) {
            finishFolderBatch(batch)
            return
        }
        val file = batch.files[batch.index]
        val automatic = file.encodings.singleOrNull()?.takeIf { it.automatic }
        if (automatic != null) {
            importFolderFile(batch, file, automatic.encoding)
            return
        }
        var selected = -1
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.encoding_preview_title, file.displayName))
            .setSingleChoiceItems(file.encodings.map { "${it.encoding} · ${it.preview}" }.toTypedArray(), -1) { _, which ->
                selected = which
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
            .setPositiveButton(R.string.encoding_open) { _, _ ->
                importFolderFile(batch, file, file.encodings[selected].encoding)
            }
            .setNeutralButton(R.string.bookshelf_folder_skip) { _, _ ->
                batch.failures += "${file.displayName}: 인코딩을 선택하지 않아 건너뜀"
                batch.index++
                continueFolderBatch(batch)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                batch.cancellation.cancel()
                finishFolderBatch(batch)
            }
            .setOnCancelListener {
                batch.cancellation.cancel()
                finishFolderBatch(batch)
            }
            .create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false }
        dialog.show()
    }

    private fun importFolderFile(batch: FolderBatch, file: FolderTxtCandidate, encoding: String) {
        folderOperationDialog?.setMessage(getString(
            R.string.bookshelf_folder_progress, batch.index + 1, batch.files.size, file.displayName,
        ))
        blockExecutor.execute {
            val result = runCatching {
                val input = contentResolver.openInputStream(file.uri)
                    ?: throw TxtImportException("파일을 다시 열 수 없습니다.")
                TxtImporter.stage(
                    input, importDirectory(), totalBytes = file.byteCount, cancellation = batch.cancellation,
                ).use { staged ->
                    if (staged.sha256 != file.sha256) throw TxtImportException("미리보기 뒤 원본 내용이 바뀌었습니다.")
                    val imported = TxtImporter.commit(staged, encoding, batch.cancellation)
                    bookCatalogStore().addImported(imported, file.displayName, System.currentTimeMillis())
                }
            }
            runOnUiThread {
                if (isDestroyed || folderCancellation !== batch.cancellation) return@runOnUiThread
                result.onSuccess { batch.added++ }.onFailure { error ->
                    if (error is TxtImportCancelledException) batch.cancellation.cancel()
                    else batch.failures += "${file.displayName}: ${error.message ?: "가져오기 실패"}"
                }
                batch.index++
                continueFolderBatch(batch)
            }
        }
    }

    private fun finishFolderBatch(batch: FolderBatch) {
        folderOperationDialog?.dismiss()
        folderOperationDialog = null
        folderCancellation = null
        showBookshelf()
        val summary = (if (batch.cancellation.isCancelled()) {
            getString(R.string.bookshelf_folder_cancelled) + "\n\n"
        } else "") + getString(
            R.string.bookshelf_folder_done,
            batch.added, batch.duplicateCount, batch.failures.size,
        ) + batch.failures.take(5).joinToString("\n", prefix = if (batch.failures.isEmpty()) "" else "\n\n")
        AlertDialog.Builder(this).setTitle(R.string.bookshelf_folder_import)
            .setMessage(summary).setPositiveButton(android.R.string.ok, null).show()
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
                    startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_READER, true))
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
        cancelActiveSearch()
        activeImportCancellation = cancellation
        bookshelfButton.isEnabled = false
        styleButton.isEnabled = false
        tableOfContentsButton.isEnabled = false
        searchButton.isEnabled = false
        openTxtButton.isEnabled = false
        cancelImportButton.isEnabled = true
        cancelImportButton.visibility = View.VISIBLE
        statusView.text = getString(R.string.reader_importing, displayName)
    }

    private fun finishImportOperation(cancellation: ImportCancellation) {
        if (activeImportCancellation !== cancellation) return
        activeImportCancellation = null
        bookshelfButton.isEnabled = true
        styleButton.isEnabled = true
        tableOfContentsButton.isEnabled = documentUtf16Length > 0
        searchButton.isEnabled = documentUtf16Length > 0
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

    private fun readerStyleStore(): ReaderStyleStore = ReaderStyleStore(File(filesDir, "reader-styles"))

    private fun manualChapterStore(): ManualChapterStore = ManualChapterStore(File(filesDir, "manual-chapters"))

    private fun userMeaningStore(): UserMeaningStore = UserMeaningStore(File(filesDir, "user-meanings"))

    private fun userAnnotationStore(): UserAnnotationStore = UserAnnotationStore(File(filesDir, "user-annotations"))

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

    private fun showReaderTools() {
        val actions = if (currentDocumentSha256 == null) {
            listOf(
                getString(R.string.vocabulary),
                getString(R.string.backup_restore),
                getString(R.string.reading_time),
            )
        } else {
            listOf(
                getString(R.string.vocabulary),
                getString(R.string.backup_restore),
                getString(R.string.reading_time),
                getString(R.string.bookmark_here),
                getString(R.string.annotations),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_tools)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showVocabulary()
                    1 -> showBackupRestoreMenu()
                    2 -> showReadingTime()
                    3 -> addBookmarkHere()
                    4 -> showAnnotations()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addBookmarkHere() {
        val position = captureReadingPosition() ?: return
        val now = System.currentTimeMillis()
        val value = UserAnnotation(
            id = UUID.randomUUID().toString(),
            type = AnnotationType.BOOKMARK,
            documentSha256 = position.documentSha256,
            startUtf16 = position.utf16Offset,
            endUtf16 = position.utf16Offset,
            quoteBefore = position.quoteBefore,
            quoteSelected = "",
            quoteAfter = position.quoteAfter,
            note = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        updateAnnotations({ it + value }) { showAnnotations() }
    }

    private fun showSelectionAnnotationMenu() {
        val documentId = currentDocumentSha256
        val range = selectionGestureController?.completedSelection()
        val selected = range?.let(::selectedSourceText)
        if (documentId == null || range == null || selected.isNullOrBlank()) {
            Toast.makeText(this, R.string.annotation_selection_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val target = AnnotationTarget(
            documentId,
            range,
            selected.safeUtf16Prefix(UserAnnotationStore.MAX_SELECTED_UTF16),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.annotation_add)
            .setItems(arrayOf(getString(R.string.highlight_add), getString(R.string.note_add))) { _, which ->
                if (which == 0) addSelectionAnnotation(target, AnnotationType.HIGHLIGHT, null)
                else showAddNote(target)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddNote(target: AnnotationTarget) {
        val input = EditText(this).apply {
            hint = getString(R.string.note_hint)
            minLines = 3
            maxLines = 7
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.note_add)
            .setView(input)
            .setPositiveButton(R.string.annotation_save) { _, _ ->
                addSelectionAnnotation(target, AnnotationType.NOTE, input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSelectionAnnotation(target: AnnotationTarget, type: AnnotationType, note: String?) {
        val now = System.currentTimeMillis()
        val value = UserAnnotation(
            id = UUID.randomUUID().toString(),
            type = type,
            documentSha256 = target.documentSha256,
            startUtf16 = target.range.startUtf16,
            endUtf16 = target.range.endUtf16,
            quoteBefore = "",
            quoteSelected = target.selectedText,
            quoteAfter = "",
            note = note,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        updateAnnotations({ it + value }) {
            selectionGestureController?.cancelSelection()
            refreshHighlights()
        }
    }

    private fun showAnnotations() {
        val documentId = currentDocumentSha256 ?: return
        blockExecutor.execute {
            val result = runCatching {
                userAnnotationStore().load()
                    .filter { it.documentSha256 == documentId }
                    .sortedWith(compareBy<UserAnnotation> { it.startUtf16 }.thenBy { it.createdAtEpochMillis })
            }
            runOnUiThread {
                if (isDestroyed || currentDocumentSha256 != documentId) return@runOnUiThread
                result.onSuccess(::showAnnotationsDialog).onFailure(::showAnnotationFailure)
            }
        }
    }

    private fun showAnnotationsDialog(values: List<UserAnnotation>) {
        if (values.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.annotations)
                .setMessage(R.string.annotations_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.annotations)
            .setItems(values.map { value ->
                getString(
                    R.string.annotation_item,
                    annotationTypeLabel(value.type),
                    ReadingProgress.fromOffset(value.startUtf16, documentUtf16Length) / 10.0,
                    annotationPreview(value),
                )
            }.toTypedArray()) { _, which -> values.getOrNull(which)?.let(::showAnnotationActions) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAnnotationActions(value: UserAnnotation) {
        val neutralLabel = if (value.type == AnnotationType.NOTE) R.string.note_edit else R.string.annotation_delete
        AlertDialog.Builder(this)
            .setTitle(annotationTypeLabel(value.type))
            .setMessage(annotationPreview(value))
            .setPositiveButton(R.string.table_of_contents_go) { _, _ ->
                val origin = captureReaderAnchor() ?: return@setPositiveButton
                navigateToUtf16(value.startUtf16, origin, reader.paddingTop + 1)
            }
            .setNeutralButton(neutralLabel) { _, _ ->
                if (value.type == AnnotationType.NOTE) editNote(value) else confirmDeleteAnnotation(value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editNote(value: UserAnnotation) {
        val input = EditText(this).apply {
            setText(value.note.orEmpty())
            minLines = 3
            maxLines = 7
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.note_edit)
            .setView(input)
            .setPositiveButton(R.string.annotation_save) { _, _ ->
                updateAnnotations({ values ->
                    values.map { saved ->
                        if (saved.id == value.id) saved.copy(
                            note = input.text.toString().trim(),
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        ) else saved
                    }
                }) { showAnnotations() }
            }
            .setNeutralButton(R.string.annotation_delete) { _, _ -> confirmDeleteAnnotation(value) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAnnotation(value: UserAnnotation) {
        AlertDialog.Builder(this)
            .setTitle(R.string.annotation_delete)
            .setMessage(R.string.annotation_delete_message)
            .setPositiveButton(R.string.annotation_delete) { _, _ ->
                updateAnnotations({ values -> values.filterNot { it.id == value.id } }) {
                    refreshHighlights()
                    showAnnotations()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAnnotations(
        transform: (List<UserAnnotation>) -> List<UserAnnotation>,
        onSuccess: () -> Unit,
    ) {
        blockExecutor.execute {
            val result = runCatching {
                val store = userAnnotationStore()
                store.save(transform(store.load()))
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess {
                    Toast.makeText(this, R.string.annotation_saved, Toast.LENGTH_SHORT).show()
                    onSuccess()
                }.onFailure(::showAnnotationFailure)
            }
        }
    }

    private fun refreshHighlights() {
        val documentId = currentDocumentSha256
        if (documentId == null) {
            textBlockAdapter?.setHighlights(emptyList())
            return
        }
        blockExecutor.execute {
            val result = runCatching {
                userAnnotationStore().load().filter {
                    it.documentSha256 == documentId && it.type == AnnotationType.HIGHLIGHT
                }.map { SelectionRange(it.startUtf16, it.endUtf16) }
            }
            runOnUiThread {
                if (!isDestroyed && currentDocumentSha256 == documentId) {
                    result.onSuccess { ranges -> textBlockAdapter?.setHighlights(ranges) }
                        .onFailure(::showAnnotationFailure)
                }
            }
        }
    }

    private fun annotationTypeLabel(type: AnnotationType): String = getString(
        when (type) {
            AnnotationType.BOOKMARK -> R.string.bookmark
            AnnotationType.HIGHLIGHT -> R.string.highlight
            AnnotationType.NOTE -> R.string.note
        },
    )

    private fun annotationPreview(value: UserAnnotation): String =
        (value.note ?: value.quoteSelected.ifBlank { value.quoteAfter })
            .replace(Regex("\\s+"), " ")
            .trim()
            .safeUtf16Prefix(80)

    private fun String.safeUtf16Prefix(maximum: Int): String {
        if (length <= maximum) return this
        val end = if (maximum > 0 && Character.isHighSurrogate(this[maximum - 1])) maximum - 1 else maximum
        return substring(0, end)
    }

    private fun showAnnotationFailure(error: Throwable) {
        Log.e(ANNOTATION_TAG, "annotation operation failed", error)
        AlertDialog.Builder(this)
            .setTitle(R.string.annotation_failed)
            .setMessage(error.message ?: getString(R.string.unknown_error))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showSelectedMeanings() {
        val range = selectionGestureController?.completedSelection() ?: return
        if (range.endUtf16 - range.startUtf16 > MAX_TRANSLATION_SELECTION_UTF16) {
            Toast.makeText(this, R.string.meaning_selection_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = textBlockAdapter ?: return
        val selectionVersion = selectionRequestGate.snapshot()
        val documentSha256 = currentDocumentSha256
        blockExecutor.execute {
            adapter.readRange(range.startUtf16, range.endUtf16) { textResult ->
                runOnUiThread {
                    if (isDestroyed || !selectionRequestGate.isCurrent(selectionVersion)) {
                        Log.i(MEANING_TAG, "stale selection result discarded")
                        return@runOnUiThread
                    }
                    textResult.onSuccess { source ->
                        if (source.isBlank() ||
                            source.codePointCount(0, source.length) > AzureTranslationProvider.MAX_REQUEST_CHARACTERS
                        ) {
                            Toast.makeText(this, R.string.meaning_selection_unavailable, Toast.LENGTH_SHORT).show()
                            return@onSuccess
                        }
                        val target = MeaningTarget(
                            sourceText = source,
                            documentSha256 = documentSha256,
                            startUtf16 = range.startUtf16,
                            endUtf16 = range.endUtf16,
                            selectionVersion = selectionVersion,
                        )
                        if (source.length > UserMeaningStore.MAX_SOURCE_UTF16) {
                            startOnlineTranslation(target)
                        } else {
                            loadSavedMeanings(target)
                        }
                    }.onFailure { error ->
                        Log.e(MEANING_TAG, "selected text unavailable", error)
                        Toast.makeText(this, R.string.meaning_selection_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun loadSavedMeanings(target: MeaningTarget) {
        blockExecutor.execute {
            val result = runCatching {
                UserMeaningIndex(userMeaningStore().load()).resolve(target.query())
            }
            runOnUiThread {
                if (isDestroyed || !selectionRequestGate.isCurrent(target.selectionVersion)) return@runOnUiThread
                result.onSuccess { meanings ->
                    if (meanings.isEmpty()) startOnlineTranslation(target)
                    else showMeaningDialog(target, meanings)
                }.onFailure(::showMeaningFailure)
            }
        }
    }

    private fun selectedSourceText(range: SelectionRange): String? {
        val adapter = textBlockAdapter ?: return null
        val result = StringBuilder(range.endUtf16 - range.startUtf16)
        var cursor = range.startUtf16
        while (cursor < range.endUtf16) {
            val block = adapter.cachedBlockAt(cursor) ?: return null
            val localStart = cursor - block.startUtf16
            val globalEnd = minOf(range.endUtf16, block.endUtf16)
            result.append(block.text, localStart, globalEnd - block.startUtf16)
            cursor = globalEnd
        }
        return result.toString()
    }

    private fun showMeaningDialog(target: MeaningTarget, meanings: List<UserMeaning>) {
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.meaning_title, target.sourceText))
            .setPositiveButton(R.string.azure_translation) { _, _ -> startOnlineTranslation(target) }
            .setNeutralButton(R.string.meaning_add) { _, _ -> showAddMeaning(target) }
            .setNegativeButton(android.R.string.cancel, null)
        builder.setItems(meanings.map { value ->
            getString(R.string.meaning_item, meaningScopeLabel(value.scope), value.meaning)
        }.toTypedArray()) { _, which ->
            meanings.getOrNull(which)?.let { editUserMeaning(it, target) }
        }
        builder.show()
    }

    private fun showDictionaryResult(target: MeaningTarget, response: DictionaryLookupResponse) {
        val isAzure = response.providerId == "azure-translator-f0"
        val title = if (isAzure) R.string.azure_translation else R.string.mymemory_translation
        if (response.entries.isEmpty()) {
            val builder = AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(
                    if (target.canSaveMeaning()) R.string.translation_no_result
                    else R.string.translation_no_result_long,
                )
                .setNegativeButton(android.R.string.cancel, null)
            if (target.canSaveMeaning()) {
                builder.setPositiveButton(R.string.meaning_add) { _, _ -> showAddMeaning(target) }
            }
            builder.show()
            return
        }
        val padding = 24.dp
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 8.dp, padding, 8.dp)
        }
        val resultTextColor = if (currentReaderStyle.theme == ReaderTheme.DARK) {
            Color.rgb(236, 232, 222)
        } else {
            Color.rgb(35, 33, 29)
        }
        if (!target.canSaveMeaning()) {
            content.addView(TextView(this).apply {
                textSize = 12f
                text = getString(R.string.translation_long_save_unavailable)
            })
        }
        content.addView(TextView(this).apply {
            textSize = 16f
            setTextColor(resultTextColor)
            text = getString(R.string.translation_source, target.sourceText)
        })
        response.entries.forEachIndexed { entryIndex, entry ->
            content.addView(TextView(this).apply {
                textSize = 16f
                setTextColor(resultTextColor)
                text = buildString {
                    if (entryIndex > 0) append("\n")
                    append(getString(R.string.translation_result))
                    if (entry.headword != target.sourceText) append("\n").append(entry.headword)
                    entry.reading?.let { append("  ").append(it) }
                    entry.meanings.forEachIndexed { meaningIndex, meaning ->
                        append("\n")
                        if (entry.meanings.size > 1) append(meaningIndex + 1).append(". ")
                        append(meaning)
                        entry.sourceMeanings.getOrNull(meaningIndex)?.let { source ->
                            append("\n   ").append(source)
                        }
                    }
                }
            })
        }
        content.addView(TextView(this).apply {
            setPadding(0, 16.dp, 0, 8.dp)
            text = getString(
                if (isAzure) R.string.azure_translation_attribution
                else R.string.online_translation_attribution,
            )
            contentDescription = getString(
                if (isAzure) R.string.azure_translation_attribution_description
                else R.string.online_translation_attribution_description,
            )
            setOnClickListener { openWebPage(if (isAzure) AZURE_TRANSLATOR_URL else MYMEMORY_URL) }
        })
        content.addView(TextView(this).apply {
            textSize = 12f
            text = getString(
                if (isAzure) R.string.azure_translation_notice
                else R.string.online_translation_notice,
            )
        })
        val scroll = ScrollView(this).apply { addView(content) }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
        if (target.canSaveMeaning()) {
            builder.setPositiveButton(R.string.meaning_add) { _, _ -> saveLookupMeanings(target, response) }
        }
        builder.show()
    }

    private fun saveLookupMeanings(target: MeaningTarget, response: DictionaryLookupResponse) {
        if (!target.canSaveMeaning()) return
        val suggestions = response.savableMeanings(UserMeaningStore.MAX_MEANING_UTF16)
        if (suggestions.isEmpty()) {
            showAddMeaning(target)
            return
        }
        val scope = if (target.documentSha256 == null) MeaningScope.GLOBAL else MeaningScope.RANGE
        val now = System.currentTimeMillis()
        updateUserMeanings({ saved ->
            val existing = saved.asSequence()
                .filter {
                    it.sourceText == target.sourceText &&
                        it.scope == scope &&
                        it.documentSha256 == (if (scope == MeaningScope.GLOBAL) null else target.documentSha256) &&
                        it.rangeStartUtf16 == (if (scope == MeaningScope.RANGE) target.startUtf16 else null) &&
                        it.rangeEndUtf16 == (if (scope == MeaningScope.RANGE) target.endUtf16 else null)
                }
                .map(UserMeaning::meaning)
                .toSet()
            saved + suggestions.filterNot(existing::contains).map { meaning ->
                UserMeaning(
                    id = UUID.randomUUID().toString(),
                    sourceText = target.sourceText,
                    meaning = meaning,
                    scope = scope,
                    documentSha256 = if (scope == MeaningScope.GLOBAL) null else target.documentSha256,
                    rangeStartUtf16 = if (scope == MeaningScope.RANGE) target.startUtf16 else null,
                    rangeEndUtf16 = if (scope == MeaningScope.RANGE) target.endUtf16 else null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
            }
        }) {
            if (selectionRequestGate.isCurrent(target.selectionVersion)) {
                selectionGestureController?.cancelSelection()
            }
        }
    }

    private fun startOnlineTranslation(target: MeaningTarget) {
        val credentials = AzureTranslatorCredentialStore(applicationContext).load()
        if (credentials != null) {
            requestAzureTranslation(target, credentials)
            return
        }
        showAzureCredentialsDialog(target)
    }

    private fun showAzureCredentialsDialog(target: MeaningTarget) {
        val padding = 24.dp
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val keyField = EditText(this).apply {
            hint = getString(R.string.azure_translation_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val regionField = EditText(this).apply {
            hint = getString(R.string.azure_translation_region_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        fields.addView(keyField)
        fields.addView(regionField)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.azure_translation_setup_title)
            .setMessage(R.string.azure_translation_setup_message)
            .setView(fields)
            .setPositiveButton(R.string.azure_translation_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        if (target.sourceText.toByteArray(Charsets.UTF_8).size <= 500) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.azure_translation_use_mymemory)) { _, _ ->
                startMyMemoryTranslation(target)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = runCatching {
                    AzureTranslatorCredentials(
                        key = keyField.text.toString().trim(),
                        region = regionField.text.toString().trim(),
                    )
                }
                result.onSuccess { credentials ->
                    AzureTranslatorCredentialStore(applicationContext).save(credentials)
                    dialog.dismiss()
                    requestAzureTranslation(target, credentials)
                }.onFailure { keyField.error = it.message }
            }
        }
        dialog.show()
    }

    private fun startMyMemoryTranslation(target: MeaningTarget) {
        val preferences = getSharedPreferences(DICTIONARY_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(MYMEMORY_CONSENT, false)) {
            requestMyMemoryTranslation(target)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.online_translation_consent_title)
            .setMessage(R.string.online_translation_consent_message)
            .setPositiveButton(R.string.online_translation_continue) { _, _ ->
                preferences.edit().putBoolean(MYMEMORY_CONSENT, true).apply()
                requestMyMemoryTranslation(target)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestMyMemoryTranslation(target: MeaningTarget) {
        requestOnlineTranslation(target, MyMemoryTranslationProvider(), isAzure = false)
    }

    private fun requestAzureTranslation(
        target: MeaningTarget,
        credentials: AzureTranslatorCredentials,
    ) {
        requestOnlineTranslation(target, AzureTranslationProvider(credentials), isAzure = true)
    }

    private fun requestOnlineTranslation(
        target: MeaningTarget,
        provider: dev.worldviewer.lookup.DictionaryLookupProvider,
        isAzure: Boolean,
    ) {
        cancelDictionaryLookup()
        val coordinator = DictionaryLookupCoordinator(
            worker = dictionaryExecutor,
            deliver = { action -> runOnUiThread(action) },
        ) { request, result ->
            dictionaryProgressDialog?.dismiss()
            dictionaryProgressDialog = null
            if (isDestroyed || !selectionRequestGate.isCurrent(request.selectionVersion)) {
                Log.i(MEANING_TAG, "stale online translation discarded")
                return@DictionaryLookupCoordinator
            }
            result.onSuccess { response -> showDictionaryResult(target, response) }
                .onFailure { error ->
                    Log.e(MEANING_TAG, "online translation failed", error)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.online_translation_failed)
                        .setMessage(
                            getString(
                                if (isAzure) R.string.azure_translation_failed_message
                                else R.string.online_translation_failed_message,
                                error.message ?: getString(R.string.unknown_error),
                            ),
                        )
                        .setPositiveButton(
                            if (isAzure) R.string.azure_translation else R.string.mymemory_translation,
                        ) { _, _ ->
                            if (isAzure) {
                                AzureTranslatorCredentialStore(applicationContext).load()?.let { saved ->
                                    requestAzureTranslation(target, saved)
                                } ?: showAzureCredentialsDialog(target)
                            } else requestMyMemoryTranslation(target)
                        }
                        .setNeutralButton(R.string.azure_translation_change_settings) { _, _ ->
                            if (isAzure) {
                                AzureTranslatorCredentialStore(applicationContext).clear()
                                showAzureCredentialsDialog(target)
                            } else {
                                startOnlineTranslation(target)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
        }
        dictionaryLookupCoordinator = coordinator
        dictionaryProgressDialog = AlertDialog.Builder(this)
            .setTitle(if (isAzure) R.string.azure_translation else R.string.mymemory_translation)
            .setMessage(
                if (isAzure) R.string.azure_translation_progress else R.string.online_translation_progress,
            )
            .setNegativeButton(R.string.translation_cancel) { _, _ -> cancelDictionaryLookup() }
            .setOnCancelListener { cancelDictionaryLookup() }
            .show()
        coordinator.request(
            DictionaryLookupRequest(
                sourceText = target.sourceText,
                documentId = target.documentSha256,
                documentVersion = target.documentSha256,
                rangeStartUtf16 = target.startUtf16,
                rangeEndUtf16 = target.endUtf16,
                selectionVersion = target.selectionVersion,
            ),
            provider,
        )
    }

    private fun cancelDictionaryLookup() {
        dictionaryLookupCoordinator?.cancel()
        dictionaryLookupCoordinator = null
        dictionaryProgressDialog?.dismiss()
        dictionaryProgressDialog = null
    }

    private fun openWebPage(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, url, Toast.LENGTH_LONG).show() }
    }

    private fun showAddMeaning(target: MeaningTarget) {
        if (!target.canSaveMeaning()) return
        val padding = 24.dp
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 8.dp, padding, 0)
        }
        content.addView(TextView(this).apply { text = target.sourceText })
        val input = EditText(this).apply {
            hint = getString(R.string.meaning_hint)
            minLines = 2
            maxLines = 5
        }
        content.addView(input)
        val scopes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rangeScope = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.meaning_scope_range)
            isEnabled = target.documentSha256 != null
        }
        val bookScope = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.meaning_scope_book)
            isEnabled = target.documentSha256 != null
        }
        val globalScope = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.meaning_scope_global)
        }
        scopes.addView(rangeScope)
        scopes.addView(bookScope)
        scopes.addView(globalScope)
        scopes.check(if (target.documentSha256 != null) rangeScope.id else globalScope.id)
        content.addView(scopes)
        AlertDialog.Builder(this)
            .setTitle(R.string.meaning_add)
            .setView(content)
            .setPositiveButton(R.string.meaning_save) { _, _ ->
                val scope = when (scopes.checkedRadioButtonId) {
                    rangeScope.id -> MeaningScope.RANGE
                    bookScope.id -> MeaningScope.BOOK
                    else -> MeaningScope.GLOBAL
                }
                val now = System.currentTimeMillis()
                val value = UserMeaning(
                    id = UUID.randomUUID().toString(),
                    sourceText = target.sourceText,
                    meaning = input.text.toString().trim(),
                    scope = scope,
                    documentSha256 = if (scope == MeaningScope.GLOBAL) null else target.documentSha256,
                    rangeStartUtf16 = if (scope == MeaningScope.RANGE) target.startUtf16 else null,
                    rangeEndUtf16 = if (scope == MeaningScope.RANGE) target.endUtf16 else null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
                updateUserMeanings({ it + value }) {
                    if (selectionRequestGate.isCurrent(target.selectionVersion)) {
                        selectionGestureController?.cancelSelection()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVocabulary() {
        blockExecutor.execute {
            val result = runCatching { userMeaningStore().load() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess(::showVocabularyDialog).onFailure(::showMeaningFailure)
            }
        }
    }

    private fun showVocabularyDialog(values: List<UserMeaning>) {
        if (values.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.vocabulary)
                .setMessage(R.string.vocabulary_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val ordered = values.sortedWith(
            compareBy<UserMeaning> { it.sourceText }.thenBy { it.scope.ordinal }.thenBy { it.createdAtEpochMillis },
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.vocabulary)
            .setItems(ordered.map { value ->
                getString(
                    R.string.vocabulary_item,
                    value.sourceText,
                    meaningScopeLabel(value.scope),
                    value.meaning,
                )
            }.toTypedArray()) { _, which ->
                ordered.getOrNull(which)?.let { editUserMeaning(it, target = null) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editUserMeaning(value: UserMeaning, target: MeaningTarget?) {
        val input = EditText(this).apply {
            setText(value.meaning)
            minLines = 2
            maxLines = 5
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.meaning_edit_title, value.sourceText, meaningScopeLabel(value.scope)))
            .setView(input)
            .setPositiveButton(R.string.meaning_save) { _, _ ->
                updateUserMeanings({ meanings ->
                    meanings.map { saved ->
                        if (saved.id == value.id) {
                            saved.copy(
                                meaning = input.text.toString().trim(),
                                updatedAtEpochMillis = System.currentTimeMillis(),
                            )
                        } else {
                            saved
                        }
                    }
                }) {
                    if (target != null && selectionRequestGate.isCurrent(target.selectionVersion)) {
                        selectionGestureController?.cancelSelection()
                    } else {
                        showVocabulary()
                    }
                }
            }
            .setNeutralButton(R.string.meaning_delete) { _, _ -> confirmDeleteMeaning(value, target) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteMeaning(value: UserMeaning, target: MeaningTarget?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.meaning_delete)
            .setMessage(getString(R.string.meaning_delete_message, value.sourceText, value.meaning))
            .setPositiveButton(R.string.meaning_delete) { _, _ ->
                updateUserMeanings({ meanings -> meanings.filterNot { it.id == value.id } }) {
                    if (target != null && selectionRequestGate.isCurrent(target.selectionVersion)) {
                        selectionGestureController?.cancelSelection()
                    } else {
                        showVocabulary()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateUserMeanings(
        transform: (List<UserMeaning>) -> List<UserMeaning>,
        onSuccess: () -> Unit,
    ) {
        blockExecutor.execute {
            val result = runCatching {
                val store = userMeaningStore()
                val updated = transform(store.load())
                store.save(updated)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess {
                    Toast.makeText(this, R.string.meaning_saved, Toast.LENGTH_SHORT).show()
                    onSuccess()
                }.onFailure(::showMeaningFailure)
            }
        }
    }

    private fun meaningScopeLabel(scope: MeaningScope): String = getString(
        when (scope) {
            MeaningScope.RANGE -> R.string.meaning_scope_range
            MeaningScope.BOOK -> R.string.meaning_scope_book
            MeaningScope.GLOBAL -> R.string.meaning_scope_global
        },
    )

    private fun showMeaningFailure(error: Throwable) {
        Log.e(MEANING_TAG, "user meaning operation failed", error)
        AlertDialog.Builder(this)
            .setTitle(R.string.meaning_failed)
            .setMessage(error.message ?: getString(R.string.unknown_error))
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        outState.putBoolean(STATE_READER_CHROME_VISIBLE, readerChromeVisible)
        outState.putBoolean(STATE_BOOKSHELF_VISIBLE, bookshelfHome.visibility == View.VISIBLE)
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

    private fun updateReaderBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        if (bookshelfHome.visibility == View.VISIBLE) {
            readerBackCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            readerBackCallback = null
        } else if (readerBackCallback == null) {
            val callback = OnBackInvokedCallback {
                returnToBookshelfOrCancelSelection()
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            readerBackCallback = callback
        }
    }

    private fun returnToBookshelfOrCancelSelection() {
        if (selectionControls.visibility == View.VISIBLE) selectionGestureController?.cancelSelection()
        else showBookshelf()
    }

    private fun readingTimeStore(): ReadingTimeStore = ReadingTimeStore(File(filesDir, "reading-time"))

    private fun persistReadingTimeSnapshot() {
        val snapshot = readingTimeLedger?.snapshot() ?: return
        backupExecutor.execute {
            runCatching { readingTimeStore().save(snapshot) }
                .onSuccess { readingTimeSaveError = null }
                .onFailure { error ->
                    readingTimeSaveError = error
                    Log.e(READING_TIME_TAG, "reading time save failed", error)
                }
        }
    }

    private fun checkpointReadingTime() {
        val changed = readingTimeLedger?.checkpoint(
            SystemClock.elapsedRealtime(), System.currentTimeMillis(), ZoneId.systemDefault(),
        ) ?: false
        if (changed) persistReadingTimeSnapshot()
    }

    private fun syncReadingTimer() {
        val requestedBook = currentDocumentSha256?.takeIf {
            readingTimeLedger != null && readingTimeResumed && readingTimeWindowFocused &&
                readingTimeScreenOn && getSystemService(PowerManager::class.java).isInteractive &&
                ::reader.isInitialized && reader.visibility == View.VISIBLE &&
                ::bookshelfHome.isInitialized && bookshelfHome.visibility != View.VISIBLE &&
                !backupOperationInProgress && !restoreInProgress
        }
        if (requestedBook == readingTimeActiveBook) return
        readingTimeHandler.removeCallbacks(readingTimeTick)
        if (readingTimeActiveBook != null) {
            if (readingTimeLedger?.stop(
                    SystemClock.elapsedRealtime(), System.currentTimeMillis(), ZoneId.systemDefault(),
                ) == true
            ) persistReadingTimeSnapshot()
        }
        readingTimeActiveBook = requestedBook
        if (requestedBook != null) {
            readingTimeLedger?.start(
                requestedBook, SystemClock.elapsedRealtime(), System.currentTimeMillis(), ZoneId.systemDefault(),
            )
            readingTimeHandler.postDelayed(readingTimeTick, READING_TIME_TICK_MS)
        }
    }

    private fun showReadingTime() {
        if (readingTimeLoadError != null) {
            AlertDialog.Builder(this).setTitle(R.string.reading_time)
                .setMessage(R.string.reading_time_load_failed)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val ledger = readingTimeLedger
        if (ledger == null) {
            Toast.makeText(this, R.string.reading_time_loading, Toast.LENGTH_SHORT).show()
            return
        }
        checkpointReadingTime()
        val totals = ledger.snapshot()
        val today = LocalDate.now().toEpochDay()
        backupExecutor.execute {
            val names = runCatching { bookCatalogStore().load()?.books.orEmpty() }
                .getOrDefault(emptyList()).associate { it.bookId to it.displayName }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val byBook = totals.entries.groupBy { it.key.bookId }
                val message = buildString {
                    append(getString(R.string.reading_time_summary,
                        formatReadingDuration(totals.filterKeys { it.epochDay == today }.values.sum()),
                        formatReadingDuration(totals.values.sum())))
                    byBook.entries.sortedByDescending { (_, entries) -> entries.sumOf { it.value } }
                        .forEach { (bookId, entries) ->
                            append("\n\n")
                            append(names[bookId] ?: bookId.take(12))
                            append("\n")
                            append(getString(R.string.reading_time_book,
                                formatReadingDuration(entries.filter { it.key.epochDay == today }.sumOf { it.value }),
                                formatReadingDuration(entries.sumOf { it.value })))
                        }
                    if (byBook.isEmpty()) append("\n\n${getString(R.string.reading_time_empty)}")
                    if (readingTimeSaveError != null) append("\n\n${getString(R.string.reading_time_save_failed)}")
                }
                AlertDialog.Builder(this).setTitle(R.string.reading_time).setMessage(message)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun formatReadingDuration(millis: Long): String {
        val seconds = millis / 1_000
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remainder = seconds % 60
        return when {
            hours > 0 -> getString(R.string.reading_time_hours_minutes, hours, minutes)
            minutes > 0 -> getString(R.string.reading_time_minutes_seconds, minutes, remainder)
            else -> getString(R.string.reading_time_seconds, remainder)
        }
    }

    override fun onResume() {
        super.onResume()
        readingTimeResumed = true
        syncReadingTimer()
    }

    override fun onPause() {
        readingTimeResumed = false
        syncReadingTimer()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        readingTimeWindowFocused = hasFocus
        syncReadingTimer()
    }

    override fun onStop() {
        if (!restoreInProgress) persistReadingPosition()
        super.onStop()
    }

    @SuppressLint("GestureBackNavigation") // API 33+ uses OnBackInvokedCallback above.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (bookshelfHome.visibility == View.VISIBLE) {
            super.onBackPressed()
        } else {
            returnToBookshelfOrCancelSelection()
        }
    }

    override fun onDestroy() {
        readingTimeHandler.removeCallbacks(readingTimeTick)
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        if (Build.VERSION.SDK_INT >= 33) {
            readerBackCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            readerBackCallback = null
        }
        cancelDictionaryLookup()
        cancelActiveSearch()
        activeImportCancellation?.cancel()
        activeImportCancellation = null
        folderCancellation?.cancel()
        folderCancellation = null
        folderOperationDialog?.dismiss()
        folderOperationDialog = null
        pendingImport?.close()
        pendingImport = null
        pendingRestoreFile?.delete()
        pendingRestoreFile = null
        backupOperationDialog?.dismiss()
        backupOperationDialog = null
        selectionGestureController?.dispose()
        if (::reader.isInitialized) reader.removeOnScrollListener(pinyinScrollListener)
        pinyinCoordinator?.close()
        textBlockAdapter?.close()
        blockExecutor.shutdownNow()
        pinyinExecutor.shutdownNow()
        positionExecutor.shutdown()
        backupExecutor.shutdown()
        dictionaryExecutor.shutdownNow()
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
        const val READING_TIME_TICK_MS = 15_000L
        const val MAX_BLOCK_UTF16 = 768
        const val MAX_TRANSLATION_SELECTION_UTF16 = AzureTranslationProvider.MAX_REQUEST_CHARACTERS * 2
        const val REQUEST_OPEN_TXT = 1001
        const val REQUEST_CREATE_BACKUP = 1002
        const val REQUEST_OPEN_BACKUP = 1003
        const val REQUEST_OPEN_FOLDER = 1004
        const val STATE_FIRST_VISIBLE_POSITION = "reader.firstVisiblePosition"
        const val STATE_READER_CHROME_VISIBLE = "reader.chromeVisible"
        const val STATE_BOOKSHELF_VISIBLE = "bookshelf.visible"
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
        const val EXTRA_OPEN_READER = "open_reader"
        const val EXTRA_PROBE = "probe_renderer"
        const val PROBE_TAG = "WorldViewerProbe"
        const val EXTRA_PINYIN_PROBE = "probe_pinyin"
        const val PINYIN_PROBE_TAG = "WorldViewerPinyin"
        const val PINYIN_UI_TAG = "WorldViewerPinyinUI"
        const val EXTRA_SELECTION_DELAY = "selection_delay"
        const val IMPORT_TAG = "WorldViewerImport"
        const val RESTORE_TAG = "WorldViewerRestore"
        const val CATALOG_TAG = "WorldViewerCatalog"
        const val STYLE_TAG = "WorldViewerStyle"
        const val READING_TIME_TAG = "WorldViewerReadingTime"
        const val CHAPTER_TAG = "WorldViewerChapter"
        const val SEARCH_TAG = "WorldViewerSearch"
        const val MEANING_TAG = "WorldViewerMeaning"
        const val ANNOTATION_TAG = "WorldViewerAnnotation"
        const val POSITION_TAG = "WorldViewerPosition"
        const val NAVIGATION_TAG = "WorldViewerNavigation"
        const val BLOCK_SOURCE_TAG = "WorldViewerBlocks"
        const val TEXT_INDEX_DIRECTORY = "text-index"
        const val BACKUP_COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_BACKUP_ARCHIVE_BYTES = 21L * 1024 * 1024 * 1024
        const val TEMPORARY_SPACE_MARGIN_BYTES = 8L * 1024 * 1024
        const val SELECTION_BUTTONS_PER_ROW = 4
        const val DEFAULT_SELECTION_DELAY = 350
        const val FINAL_ANCHOR_RESTORE_DELAY_MS = 350L
        const val NAVIGATION_SETTLE_DELAY_MS = 450L
        const val NAVIGATION_TIMEOUT_MS = 3_000L
        const val STYLE_FONT_MIN = 15
        const val STYLE_FONT_MAX = 30
        const val STYLE_LINE_MIN = 100
        const val STYLE_LINE_MAX = 180
        const val DICTIONARY_PREFERENCES = "dictionary"
        const val MYMEMORY_CONSENT = "mymemory_consent"
        const val MYMEMORY_URL = "https://mymemory.translated.net/"
        const val AZURE_TRANSLATOR_URL = "https://azure.microsoft.com/products/ai-services/ai-translator/"
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

    private data class BookshelfSnapshot(
        val catalog: BookCatalog,
        val progressTenths: Map<String, Int>,
    )

    private data class MeaningTarget(
        val sourceText: String,
        val documentSha256: String?,
        val startUtf16: Int,
        val endUtf16: Int,
        val selectionVersion: Long,
    ) {
        fun canSaveMeaning(): Boolean = sourceText.length <= UserMeaningStore.MAX_SOURCE_UTF16

        fun query() = MeaningQuery(sourceText, documentSha256, startUtf16, endUtf16)
    }

    private data class AnnotationTarget(
        val documentSha256: String,
        val range: SelectionRange,
        val selectedText: String,
    )
}
