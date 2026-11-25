# Troubleshoot the Android preview

## A recording does not appear

In **Cases**, use **Choose with another file picker**. Confirm the filename under **Selected recording** and tap **Import selected audio**. A file can be selectable even when its codec cannot be decoded on this device. Check the [processing limits](android/OFFLINE.md#processing-limits).

## Transcription asks to send audio

Choose **Vosk** for bundled English recognition. The **Whisper API** route requires a configured companion and approval of the exact audio payload. See [Whisper setup](providers/openai-whisper.md).

## The candidate starts with an empty vault

The 0.6 candidate has its own application ID. Leave the earlier installation and its vault in place while you transfer each case.

1. In the earlier app, export a protected case if that version supports it. Retain its passphrase, ciphertext hash and any independently trusted checkpoint fingerprint separately.
2. Restore the package into the candidate's empty vault. Open the restored case and check that its recordings and revisions are present.
3. Independently verify the exported snapshot against your retained records before considering uninstall or reset. Keep the original installation if it lacks protected export, the restore fails, verification fails or the old vault contains newer work.

See the [recovery boundary](evidence/PROTECTED-TRANSFER.md#recovery-boundary) for what the export covers.
