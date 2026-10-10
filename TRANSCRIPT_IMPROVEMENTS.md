# Transcript reading improvements

New recordings split at each ten-minute boundary in the decoded PCM stream, while retaining the existing silence-based splits between those boundaries. Transcript assembly inserts `[HH:MM:SS]` once per ten-minute section. Silent sections remain visible, and resumed jobs assemble the same saved checkpoints without accumulating duplicate markers.

The Burmese transcription instructions request audio-grounded standard Pali spelling, full speech retention, natural punctuation, topic paragraphs, and separate lines for poems and gathas. Uncertain words are marked rather than completed from memory. This improves the transcription instructions; it does not establish measured accuracy. Evaluate a real sermon against a trusted Burmese/Pali reference before judging accuracy.

The reader displays spacious paragraphs and highlighted time markers, with time buttons for jumping through long transcripts. Text export, external-reader files, copy, and sharing include the recording title and duration. Incomplete jobs are labelled as drafts. Verse line breaks are retained in UTF-8 text.

Gemini and Notion keys are masked initially, each with its own accessible visibility button. Closing and reopening settings resets visibility. Masking is a display feature; it does not change the existing credential storage.

Completed older transcripts are preserved. Older jobs still in progress retain their original audio chunk layout when decoding must be repeated, so existing checkpoint text stays paired with the correct audio. Their timestamps use real saved chunk start positions and may be slightly after a ten-minute boundary. Jobs without valid span metadata retain their text without fabricated timestamps. Newly transcribed audio uses the improved spelling and paragraph instructions; already saved checkpoint text is preserved.

Validation: run `./gradlew.bat testDebugUnitTest assembleDebug lintDebug`. Added tests cover silence-triggered splits at an exact ten-minute boundary, sample continuity, deterministic resume, silent sections, retained verse line breaks, legacy timing, hour formatting, and draft export headers. Existing 65-minute PCM integrity and network reliability tests remain applicable.
