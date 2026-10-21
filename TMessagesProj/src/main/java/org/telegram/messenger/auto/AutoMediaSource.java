package org.telegram.messenger.auto;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.net.URLEncoder;

/**
 * Where to play a voice, round or video message from, mirroring MediaController.playMessage:
 * a local file when present, a tg:// stream for streamable rounds and videos, otherwise the file
 * has to be downloaded first (voice messages are never streamed there either).
 */
final class AutoMediaSource {

    static final class Source {
        @Nullable final Uri uri;
        @Nullable final String downloadName;

        private Source(@Nullable Uri uri, @Nullable String downloadName) {
            this.uri = uri;
            this.downloadName = downloadName;
        }

        boolean needsDownload() {
            return uri == null && downloadName != null;
        }
    }

    private AutoMediaSource() {
    }

    /** Null when the message has no playable document. */
    @Nullable
    static Source resolve(@NonNull MessageObject messageObject) {
        TLRPC.Document document = messageObject.getDocument();
        if (document == null || messageObject.messageOwner == null) {
            return null;
        }
        File local = localFile(messageObject);
        if (local != null) {
            return new Source(Uri.fromFile(local), null);
        }
        boolean canStream = SharedConfig.streamMedia
                && (messageObject.isRoundVideo() || messageObject.isVideo() && messageObject.canStreamVideo())
                && !messageObject.shouldEncryptPhotoOrVideo()
                && !DialogObject.isEncryptedDialog(messageObject.getDialogId());
        if (canStream) {
            Uri stream = streamUri(messageObject, document);
            if (stream != null) {
                return new Source(stream, null);
            }
        }
        return new Source(null, FileLoader.getAttachFileName(document));
    }

    /** Starts the download for {@link Source#needsDownload()}; completion arrives as fileLoaded. */
    static void download(@NonNull MessageObject messageObject) {
        FileLoader.getInstance(messageObject.currentAccount).loadFile(messageObject.getDocument(), messageObject,
                FileLoader.PRIORITY_HIGH, messageObject.shouldEncryptPhotoOrVideo() ? 2 : 0);
    }

    @Nullable
    static File localFile(@NonNull MessageObject messageObject) {
        String attachPath = messageObject.messageOwner.attachPath;
        if (attachPath != null && attachPath.length() > 0) {
            File file = new File(attachPath);
            if (file.exists()) {
                return file;
            }
        }
        File cacheFile = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
        return cacheFile != null && cacheFile.exists() ? cacheFile : null;
    }

    @Nullable
    private static Uri streamUri(@NonNull MessageObject messageObject, @NonNull TLRPC.Document document) {
        try {
            int reference = FileLoader.getInstance(messageObject.currentAccount).getFileReference(messageObject);
            String params = "?account=" + messageObject.currentAccount +
                    "&id=" + document.id +
                    "&hash=" + document.access_hash +
                    "&dc=" + document.dc_id +
                    "&size=" + document.size +
                    "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
                    "&rid=" + reference +
                    "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                    "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
            return Uri.parse("tg://" + messageObject.getFileName() + params);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
