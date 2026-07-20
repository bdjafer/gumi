import { AbsolutePath, rawOf, type PathLike } from '@astrale-os/kernel-core'

export function childPath(parent: PathLike, slug: string): AbsolutePath {
  const raw = rawOf(parent).replace(/\/$/, '')
  return AbsolutePath.parse(`${raw || ''}/${slug}`)
}

export function devicePath(parent: PathLike, deviceId: string): AbsolutePath {
  return childPath(parent, `device-${deviceId}`)
}

export function capturePath(device: PathLike, captureId: string): AbsolutePath {
  return childPath(device, `capture-${captureId}`)
}

export function recordingPath(capture: PathLike, recordingId: string): AbsolutePath {
  return childPath(capture, `recording-${recordingId}`)
}

export function captureClosurePath(capture: PathLike): AbsolutePath {
  return childPath(capture, 'closure')
}

export function recordingFinalizationPath(recording: PathLike): AbsolutePath {
  return childPath(recording, 'finalization')
}

export function transcriptionPath(recording: PathLike, processingJobId: string): AbsolutePath {
  return childPath(recording, `transcription-${processingJobId}`)
}

/** One processing-job identity owns exactly one semantic Transcript. */
export function transcriptPath(transcription: PathLike): AbsolutePath {
  return childPath(transcription, 'transcript')
}

export function transcriptSegmentPath(transcript: PathLike, index: string): AbsolutePath {
  return childPath(transcript, `segment-${index}`)
}

export function transcriptPagePublicationPath(
  transcript: PathLike,
  startIndex: string,
): AbsolutePath {
  return childPath(transcript, `page-${startIndex}`)
}

export function transcriptPublicationPath(transcription: PathLike): AbsolutePath {
  return childPath(transcription, 'publication')
}

export function conversationPath(parent: PathLike, conversationId: string): AbsolutePath {
  return childPath(parent, `conversation-${conversationId}`)
}

/** One Recording can appear at most once in one Conversation. */
export function conversationMembershipPath(
  conversation: PathLike,
  recordingId: string,
): AbsolutePath {
  return childPath(conversation, `recording-${recordingId}`)
}
