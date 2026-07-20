import { defineSchema, KernelSchema } from '@astrale-os/kernel-core'
import { compileDomain, type Domain } from '@astrale-os/kernel-core/domain'

import { CaptureClosure } from './capture/capture-closure.js'
import { CaptureSession } from './capture/capture-session.js'
import { capture_has_recording } from './capture/capture-has-recording.js'
import { capture_requested_by } from './capture/capture-requested-by.js'
import { Recording } from './capture/recording.js'
import { RecordingFinalization } from './capture/recording-finalization.js'
import { Conversation } from './conversation/conversation.js'
import { conversation_has_membership } from './conversation/conversation-has-membership.js'
import { ConversationMembership } from './conversation/conversation-membership.js'
import { conversation_owned_by } from './conversation/conversation-owned-by.js'
import { membership_added_by } from './conversation/membership-added-by.js'
import { membership_has_recording } from './conversation/membership-has-recording.js'
import { membership_has_transcript } from './conversation/membership-has-transcript.js'
import { Device } from './fleet/device.js'
import { device_has_capture } from './fleet/device-has-capture.js'
import { device_owned_by } from './fleet/device-owned-by.js'
import { recording_has_transcription } from './transcription/recording-has-transcription.js'
import { Transcript } from './transcription/transcript.js'
import { transcript_has_segment } from './transcription/transcript-has-segment.js'
import { TranscriptPagePublication } from './transcription/transcript-page-publication.js'
import { TranscriptPublication } from './transcription/transcript-publication.js'
import { TranscriptSegment } from './transcription/transcript-segment.js'
import { transcribes } from './transcription/transcribes.js'
import { Transcription } from './transcription/transcription.js'
import { transcription_has_transcript } from './transcription/transcription-has-transcript.js'

export * from './capture/capture-closure.js'
export * from './capture/capture-session.js'
export * from './capture/capture-has-recording.js'
export * from './capture/capture-requested-by.js'
export * from './capture/media-manifest.js'
export * from './capture/recording.js'
export * from './capture/recording-finalization.js'
export * from './conversation/conversation.js'
export * from './conversation/conversation-has-membership.js'
export * from './conversation/conversation-membership.js'
export * from './conversation/conversation-owned-by.js'
export * from './conversation/membership-added-by.js'
export * from './conversation/membership-has-recording.js'
export * from './conversation/membership-has-transcript.js'
export * from './fleet/device.js'
export * from './fleet/device-has-capture.js'
export * from './fleet/device-owned-by.js'
export * from './transcription/primitives.js'
export * from './transcription/recording-has-transcription.js'
export * from './transcription/transcript.js'
export * from './transcription/transcript-has-segment.js'
export * from './transcription/transcript-page-publication.js'
export * from './transcription/transcript-publication.js'
export * from './transcription/transcript-segment.js'
export * from './transcription/transcribes.js'
export * from './transcription/transcription.js'
export * from './transcription/transcription-has-transcript.js'
export const GUMI_DOMAIN = 'gumi.astrale.ai'

export const schema = defineSchema(GUMI_DOMAIN, {
  interfaces: {},
  classes: {
    Device,
    CaptureSession,
    CaptureClosure,
    Recording,
    RecordingFinalization,
    Conversation,
    ConversationMembership,
    conversation_owned_by,
    conversation_has_membership,
    membership_has_recording,
    membership_has_transcript,
    membership_added_by,
    device_owned_by,
    device_has_capture,
    capture_requested_by,
    capture_has_recording,
    Transcription,
    Transcript,
    TranscriptSegment,
    TranscriptPagePublication,
    TranscriptPublication,
    recording_has_transcription,
    transcription_has_transcript,
    transcribes,
    transcript_has_segment,
  },
  imports: [KernelSchema],
})

export const D: Domain<typeof schema> = compileDomain(schema)
