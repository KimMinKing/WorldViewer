package dev.worldviewer.testdocuments;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Emulator-only Storage Access Framework fixture. It is packaged separately from WorldViewer.
 * The limited root retains the first 64 KiB, then reports a reliable-pipe write error.
 */
public final class FailureDocumentsProvider extends DocumentsProvider {
    private static final String ROOT_READ_ONLY = "read-only";
    private static final String ROOT_LIMITED = "limited";
    private static final String DOCUMENT_READ_ONLY_ROOT = "read-only-root";
    private static final String DOCUMENT_LIMITED_ROOT = "limited-root";
    private static final String LIMITED_PREFIX = "limited-file:";
    private static final long WRITE_LIMIT_BYTES = 64L * 1024L;

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
    };

    private File limitedDirectory;

    @Override
    public boolean onCreate() {
        limitedDirectory = new File(requireContextFiles(), "limited");
        return limitedDirectory.mkdirs() || limitedDirectory.isDirectory();
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(resolveProjection(projection, DEFAULT_ROOT_PROJECTION));
        cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_READ_ONLY)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_TITLE, "WorldViewer read-only test")
                .add(Root.COLUMN_SUMMARY, "Cannot create backup files")
                .add(Root.COLUMN_DOCUMENT_ID, DOCUMENT_READ_ONLY_ROOT)
                .add(Root.COLUMN_AVAILABLE_BYTES, 0L);
        cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_LIMITED)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE)
                .add(Root.COLUMN_TITLE, "WorldViewer 64 KiB test")
                .add(Root.COLUMN_SUMMARY, "Write fails after 64 KiB")
                .add(Root.COLUMN_DOCUMENT_ID, DOCUMENT_LIMITED_ROOT)
                .add(Root.COLUMN_AVAILABLE_BYTES, WRITE_LIMIT_BYTES);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION));
        includeDocument(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION));
        if (DOCUMENT_READ_ONLY_ROOT.equals(parentDocumentId)) {
            return cursor;
        }
        if (!DOCUMENT_LIMITED_ROOT.equals(parentDocumentId)) {
            throw new FileNotFoundException("Unknown parent " + parentDocumentId);
        }
        File[] children = limitedDirectory.listFiles();
        if (children != null) {
            for (File child : children) {
                includeDocument(cursor, documentIdForFile(child));
            }
        }
        return cursor;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (!DOCUMENT_LIMITED_ROOT.equals(parentDocumentId)) {
            throw new FileNotFoundException("Read-only test root does not create documents");
        }
        String safeName = uniqueFileName(displayName);
        File file = new File(limitedDirectory, safeName);
        try {
            if (!file.createNewFile()) {
                throw new IOException("Document already exists");
            }
        } catch (IOException error) {
            throw fileError("Cannot create test document", error);
        }
        return documentIdForFile(file);
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId,
            String mode,
            CancellationSignal signal
    ) throws FileNotFoundException {
        File file = fileForDocumentId(documentId);
        if (!mode.contains("w")) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (IOException error) {
            throw fileError("Cannot create test pipe", error);
        }
        Thread writer = new Thread(
                () -> retainPartialThenFail(pipe[0], file),
                "WorldViewerProviderFailure"
        );
        writer.start();
        return pipe[1];
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = fileForDocumentId(documentId);
        if (!file.delete() && file.exists()) {
            throw new FileNotFoundException("Cannot delete " + documentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        if (DOCUMENT_READ_ONLY_ROOT.equals(documentId) || DOCUMENT_LIMITED_ROOT.equals(documentId)) {
            return Document.MIME_TYPE_DIR;
        }
        File file = fileForDocumentId(documentId);
        String extension = MimeTypeMap.getFileExtensionFromUrl(file.getName());
        String detected = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return detected != null ? detected : "application/octet-stream";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return DOCUMENT_LIMITED_ROOT.equals(parentDocumentId) && documentId.startsWith(LIMITED_PREFIX);
    }

    @Override
    public android.content.res.AssetFileDescriptor openDocumentThumbnail(
            String documentId,
            Point sizeHint,
            CancellationSignal signal
    ) throws FileNotFoundException {
        throw new FileNotFoundException("No thumbnails in test provider");
    }

    private void includeDocument(MatrixCursor cursor, String documentId) throws FileNotFoundException {
        if (DOCUMENT_READ_ONLY_ROOT.equals(documentId)) {
            includeDirectory(cursor, documentId, "Read-only destination", 0);
            return;
        }
        if (DOCUMENT_LIMITED_ROOT.equals(documentId)) {
            includeDirectory(
                    cursor,
                    documentId,
                    "64 KiB destination",
                    Document.FLAG_DIR_SUPPORTS_CREATE
            );
            return;
        }
        File file = fileForDocumentId(documentId);
        cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, documentId)
                .add(Document.COLUMN_DISPLAY_NAME, file.getName())
                .add(Document.COLUMN_MIME_TYPE, getDocumentType(documentId))
                .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE)
                .add(Document.COLUMN_SIZE, file.length())
                .add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private static void includeDirectory(
            MatrixCursor cursor,
            String documentId,
            String displayName,
            int flags
    ) {
        cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, documentId)
                .add(Document.COLUMN_DISPLAY_NAME, displayName)
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, flags)
                .add(Document.COLUMN_SIZE, null)
                .add(Document.COLUMN_LAST_MODIFIED, null);
    }

    private void retainPartialThenFail(ParcelFileDescriptor readSide, File file) {
        long written = 0L;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(readSide);
             FileOutputStream output = new FileOutputStream(file, false)) {
            byte[] buffer = new byte[8 * 1024];
            while (written < WRITE_LIMIT_BYTES) {
                int maxRead = (int) Math.min(buffer.length, WRITE_LIMIT_BYTES - written);
                int count = input.read(buffer, 0, maxRead);
                if (count < 0) {
                    return;
                }
                output.write(buffer, 0, count);
                written += count;
            }
            output.getFD().sync();
            readSide.closeWithError("WorldViewer test provider stopped after 64 KiB");
        } catch (IOException ignored) {
            // The partial file is the fixture result. The reliable pipe carries the failure to the caller.
        }
    }

    private File fileForDocumentId(String documentId) throws FileNotFoundException {
        if (!documentId.startsWith(LIMITED_PREFIX)) {
            throw new FileNotFoundException("Unknown document " + documentId);
        }
        File file = new File(limitedDirectory, documentId.substring(LIMITED_PREFIX.length()));
        try {
            if (!file.getCanonicalFile().getParentFile().equals(limitedDirectory.getCanonicalFile()) || !file.isFile()) {
                throw new FileNotFoundException("Unknown document " + documentId);
            }
        } catch (IOException error) {
            throw fileError("Cannot resolve test document", error);
        }
        return file;
    }

    private static String documentIdForFile(File file) {
        return LIMITED_PREFIX + file.getName();
    }

    private String uniqueFileName(String displayName) throws FileNotFoundException {
        String normalized = displayName == null ? "backup.wvb" : displayName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.isBlank()) normalized = "backup.wvb";
        String digest = shortDigest(displayName == null ? normalized : displayName);
        return digest + "-" + normalized;
    }

    private static String shortDigest(String value) throws FileNotFoundException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                result.append(String.format("%02x", hash[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw fileError("SHA-256 unavailable", impossible);
        }
    }

    private File requireContextFiles() {
        if (getContext() == null) throw new IllegalStateException("Provider context unavailable");
        return getContext().getFilesDir();
    }

    private static String[] resolveProjection(String[] requested, String[] defaults) {
        return requested == null ? defaults : requested;
    }

    private static FileNotFoundException fileError(String message, Exception cause) {
        FileNotFoundException error = new FileNotFoundException(message + ": " + cause.getMessage());
        error.initCause(cause);
        return error;
    }
}
