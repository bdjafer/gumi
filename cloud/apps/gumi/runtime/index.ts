import { remoteClassMethods, type SchemaMethodsImpl } from '@astrale-os/sdk'

import type { Deps } from '#deps'
import { schema } from '#schema'

import { closeCaptureMethod } from './capture/close-capture'
import { finalizeRecordingMethod } from './capture/finalize-recording'
import { openCaptureMethod } from './capture/open-capture'
import { registerDeviceMethod } from './fleet/register-device'
import { publishTranscriptMethod } from './transcription/publish-transcript'
import { addRecordingToConversationMethod } from './conversation/add-recording-to-conversation'
import { createConversationMethod } from './conversation/create-conversation'

const classMethods = remoteClassMethods<Deps>()

export const methods: SchemaMethodsImpl<typeof schema, Deps> = {
  class: {
    Device: classMethods(schema, 'Device', {
      register: registerDeviceMethod,
      openCapture: openCaptureMethod,
    }),
    CaptureSession: classMethods(schema, 'CaptureSession', {
      close: closeCaptureMethod,
      finalizeRecording: finalizeRecordingMethod,
    }),
    Recording: classMethods(schema, 'Recording', {
      publishTranscript: publishTranscriptMethod,
    }),
    Conversation: classMethods(schema, 'Conversation', {
      create: createConversationMethod,
      addRecording: addRecordingToConversationMethod,
    }),
  },
}
