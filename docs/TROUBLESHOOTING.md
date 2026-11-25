# Troubleshoot the Android preview

## A recording does not appear

In **Cases**, use **Choose with another file picker**. Confirm the filename under **Selected recording** and tap **Import selected audio**. A file can be selectable even when its codec cannot be decoded on this device. Check the [processing limits](android/OFFLINE.md#processing-limits).

## Transcription asks to send audio

Choose **Vosk** for bundled English recognition. The **Whisper API** route requires a configured companion and approval of the exact audio payload. See [Whisper setup](providers/openai-whisper.md).

## The candidate starts with an empty vault

The 0.6 candidate has its own application ID. Open the earlier app and export a protected case before uninstalling it. Follow the [protected transfer guide](evidence/PROTECTED-TRANSFER.md) to import and verify the snapshot.
