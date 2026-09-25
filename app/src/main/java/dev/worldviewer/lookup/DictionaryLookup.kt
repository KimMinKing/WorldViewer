package dev.worldviewer.lookup

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

enum class LookupResultType {
    USER_DEFINED,
    REVERSE_DICTIONARY,
    TRANSLATION,
}

enum class LookupRetention {
    DO_NOT_STORE,
    CACHE_UNTIL_EXPIRY,
    USER_DATA,
}

data class LookupStoragePolicy(
    val retention: LookupRetention,
    val expiresAtEpochMillis: Long? = null,
) {
    init {
        require((retention == LookupRetention.CACHE_UNTIL_EXPIRY) == (expiresAtEpochMillis != null)) {
            "캐시 만료 시각은 만료형 캐시에만 필요합니다."
        }
    }
}

data class LookupAttribution(
    val displayName: String,
    val sourceUrl: String? = null,
    val licenseName: String? = null,
    val licenseUrl: String? = null,
) {
    init {
        require(displayName.isNotBlank()) { "공급원 표시 이름이 필요합니다." }
    }
}

data class DictionaryLookupRequest(
    val sourceText: String,
    val documentId: String?,
    val documentVersion: String?,
    val rangeStartUtf16: Int?,
    val rangeEndUtf16: Int?,
    val contextBefore: String? = null,
    val contextAfter: String? = null,
    val selectionVersion: Long,
) {
    init {
        require(sourceText.isNotBlank()) { "조회 원문이 필요합니다." }
        require((documentId == null) == (documentVersion == null)) {
            "문서 ID와 버전은 함께 제공해야 합니다."
        }
        require((rangeStartUtf16 == null) == (rangeEndUtf16 == null)) {
            "조회 범위 시작과 끝은 함께 제공해야 합니다."
        }
        if (rangeStartUtf16 != null && rangeEndUtf16 != null) {
            require(rangeStartUtf16 >= 0 && rangeEndUtf16 > rangeStartUtf16) {
                "조회 범위가 올바르지 않습니다."
            }
        }
        require(selectionVersion >= 0) { "선택 버전은 음수일 수 없습니다." }
    }
}

data class DictionaryLookupEntry(
    val headword: String,
    val meanings: List<String>,
    val partOfSpeech: String? = null,
    val reading: String? = null,
) {
    init {
        require(headword.isNotBlank()) { "표제어가 필요합니다." }
        require(meanings.isNotEmpty() && meanings.all(String::isNotBlank)) {
            "뜻풀이에는 비어 있지 않은 뜻이 하나 이상 필요합니다."
        }
    }
}

data class DictionaryLookupResponse(
    val providerId: String,
    val providerVersion: String,
    val queryText: String,
    val entries: List<DictionaryLookupEntry>,
    val resultType: LookupResultType,
    val attribution: LookupAttribution,
    val storagePolicy: LookupStoragePolicy,
) {
    init {
        require(providerId.isNotBlank()) { "공급원 ID가 필요합니다." }
        require(providerVersion.isNotBlank()) { "공급원 버전이 필요합니다." }
        require(queryText.isNotBlank()) { "조회 원문이 필요합니다." }
    }
}

fun interface DictionaryLookupProvider {
    fun lookup(
        request: DictionaryLookupRequest,
        cancellation: LookupCancellation,
    ): DictionaryLookupResponse
}

class LookupCancellation internal constructor() {
    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    internal fun cancel() {
        cancelled = true
    }

    fun throwIfCancelled() {
        if (cancelled) throw LookupCancelledException()
    }
}

class LookupCancelledException : RuntimeException("사전 조회가 취소됐습니다.")

/** Runs only explicit lookup requests and prevents an older response from replacing a newer one. */
class DictionaryLookupCoordinator(
    private val worker: Executor,
    private val deliver: ((() -> Unit) -> Unit),
    private val onResult: (DictionaryLookupRequest, Result<DictionaryLookupResponse>) -> Unit,
) : AutoCloseable {
    private val generation = AtomicLong()

    @Volatile
    private var activeCancellation: LookupCancellation? = null

    fun request(request: DictionaryLookupRequest, provider: DictionaryLookupProvider) {
        val requestGeneration = generation.incrementAndGet()
        activeCancellation?.cancel()
        val cancellation = LookupCancellation()
        activeCancellation = cancellation
        worker.execute {
            val result = runCatching {
                cancellation.throwIfCancelled()
                provider.lookup(request, cancellation).also {
                    cancellation.throwIfCancelled()
                    require(it.queryText == request.sourceText) {
                        "공급원 응답의 조회 원문이 요청과 다릅니다."
                    }
                }
            }
            deliver {
                if (
                    generation.get() == requestGeneration &&
                    activeCancellation === cancellation &&
                    !cancellation.isCancelled
                ) {
                    activeCancellation = null
                    onResult(request, result)
                }
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        activeCancellation?.cancel()
        activeCancellation = null
    }

    override fun close() = cancel()
}
