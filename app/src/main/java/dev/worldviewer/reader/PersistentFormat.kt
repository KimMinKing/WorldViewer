package dev.worldviewer.reader

import java.io.DataInputStream

internal fun DataInputStream.requirePersistentRecordEnd(label: String) {
    if (read() != -1) {
        throw TxtImportException("${label}에 예상하지 못한 데이터가 있습니다.")
    }
}
