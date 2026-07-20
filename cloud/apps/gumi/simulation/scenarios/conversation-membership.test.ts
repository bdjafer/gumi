import { AbsolutePath, EDIT, READ } from '@astrale-os/kernel-core'
import { createInlineStep } from '@astrale-os/sdk/step'
import { describe, expect, it } from 'vitest'

import {
  addRecordingToConversation,
  authorizeAddRecordingToConversation,
} from '#runtime/conversation/add-recording-to-conversation'
import {
  authorizeCreateConversation,
  createConversation,
} from '#runtime/conversation/create-conversation'
import { D } from '#schema'

import { SimulationKernel } from '../support/simulation-kernel'

const OWNER = '/identities/ada'
const OTHER = '/identities/grace'
const PARENT = AbsolutePath.parse('/spaces/ada')
const CONVERSATION_ID = '0190c6f0-7b21-7a40-8b11-000000000071'
const RECORDING_ID = '0190c6f0-7b21-7a40-8b11-000000000072'
const ARTIFACT_ID = '0190c6f0-7b21-7a40-8b11-000000000073'
const OTHER_ARTIFACT_ID = '0190c6f0-7b21-7a40-8b11-000000000074'
const RECORDING = `${PARENT.raw}/recording-one`
const TRANSCRIPT = `${RECORDING}/transcript-one`
const OTHER_TRANSCRIPT = `${RECORDING}/transcript-two`
const SECOND_RECORDING_ID = '0190c6f0-7b21-7a40-8b11-000000000076'
const SECOND_ARTIFACT_ID = '0190c6f0-7b21-7a40-8b11-000000000077'
const SECOND_RECORDING = `${PARENT.raw}/recording-two`
const SECOND_TRANSCRIPT = `${SECOND_RECORDING}/transcript-one`

function seededWorld(allow = true) {
  const graph = new SimulationKernel(allow)
  graph.seed(OWNER, 'kernel:class.Identity')
  graph.seed(OTHER, 'kernel:class.Identity')
  graph.seed(PARENT.raw)
  const recording = graph.seed(RECORDING, D.Recording.path.class.raw, {
    [D.Recording.recordingId.key]: RECORDING_ID,
    [D.Recording.status.key]: 'ready',
  })
  const transcript = graph.seed(TRANSCRIPT, D.Transcript.path.class.raw, {
    [D.Transcript.artifactId.key]: ARTIFACT_ID,
    [D.Transcript.status.key]: 'ready',
  })
  graph.link(transcript, D.transcribes.path.class.raw, recording)
  return { graph, kernel: graph.init(), step: createInlineStep(), recording, transcript }
}

async function conversationWorld(allow = true) {
  const world = seededWorld(allow)
  const conversation = await createConversation(
    { parent: PARENT, conversationId: CONVERSATION_ID, title: 'Design review' },
    { kernel: world.kernel, principal: OWNER, step: world.step },
  )
  return { ...world, conversation }
}

describe('Conversation membership', () => {
  it('creates one caller-owned Conversation and converges stable replay', async () => {
    const world = await conversationWorld()
    const edgeCount = world.graph.edges.length

    const replay = await createConversation(
      { parent: PARENT, conversationId: CONVERSATION_ID, title: 'Design review' },
      { kernel: world.kernel, principal: OWNER, step: world.step },
    )

    expect(replay.raw).toBe(world.conversation.raw)
    expect(world.graph.edges).toHaveLength(edgeCount)
    expect(world.graph.nodes.get(world.conversation.raw)?.class.raw).toBe(
      D.Conversation.path.class.raw,
    )
  })

  it('rejects Conversation identity reuse with changed facts or owner', async () => {
    const world = await conversationWorld()

    await expect(
      createConversation(
        { parent: PARENT, conversationId: CONVERSATION_ID, title: 'Different title' },
        { kernel: world.kernel, principal: OWNER, step: world.step },
      ),
    ).rejects.toThrow(/different immutable facts/)
    await expect(
      createConversation(
        { parent: PARENT, conversationId: CONVERSATION_ID, title: 'Design review' },
        { kernel: world.kernel, principal: OTHER, step: world.step },
      ),
    ).rejects.toThrow(/different immutable facts/)
  })

  it('atomically binds the exact ready Recording, Transcript, Conversation, and actor', async () => {
    const world = await conversationWorld()

    const first = await addRecordingToConversation(
      world.conversation,
      { recording: AbsolutePath.parse(RECORDING), transcript: AbsolutePath.parse(TRANSCRIPT) },
      { kernel: world.kernel, principal: OWNER, step: world.step },
    )
    const replay = await addRecordingToConversation(
      world.conversation,
      { recording: AbsolutePath.parse(RECORDING), transcript: AbsolutePath.parse(TRANSCRIPT) },
      { kernel: world.kernel, principal: OWNER, step: world.step },
    )

    expect(first.replayed).toBe(false)
    expect(replay).toEqual({ membership: first.membership, replayed: true })
    const receipt = world.graph.nodes.get(first.membership.raw)!
    expect(receipt.props).toMatchObject({
      [D.ConversationMembership.recordingId.key]: RECORDING_ID,
      [D.ConversationMembership.transcriptArtifactId.key]: ARTIFACT_ID,
    })
    expect(world.graph.edges.filter(({ target }) => target === first.membership.raw)).toHaveLength(1)
    expect(world.graph.edges.filter(({ source }) => source === first.membership.raw)).toHaveLength(3)
  })

  it('uses the fixed Recording-derived receipt path as a concurrent arbiter', async () => {
    const world = await conversationWorld()
    const params = {
      recording: AbsolutePath.parse(RECORDING),
      transcript: AbsolutePath.parse(TRANSCRIPT),
    }

    const results = await Promise.all([
      addRecordingToConversation(world.conversation, params, {
        kernel: world.kernel,
        principal: OWNER,
        step: world.step,
      }),
      addRecordingToConversation(world.conversation, params, {
        kernel: world.kernel,
        principal: OWNER,
        step: world.step,
      }),
    ])

    expect(results.map(({ replayed }) => replayed).sort()).toEqual([false, true])
    expect(new Set(results.map(({ membership }) => membership.raw)).size).toBe(1)
  })

  it('groups multiple exact Recording and Transcript pairs without collapsing their identities', async () => {
    const world = await conversationWorld()
    const secondRecording = world.graph.seed(SECOND_RECORDING, D.Recording.path.class.raw, {
      [D.Recording.recordingId.key]: SECOND_RECORDING_ID,
      [D.Recording.status.key]: 'ready',
    })
    const secondTranscript = world.graph.seed(SECOND_TRANSCRIPT, D.Transcript.path.class.raw, {
      [D.Transcript.artifactId.key]: SECOND_ARTIFACT_ID,
      [D.Transcript.status.key]: 'ready',
    })
    world.graph.link(secondTranscript, D.transcribes.path.class.raw, secondRecording)

    const first = await addRecordingToConversation(
      world.conversation,
      { recording: AbsolutePath.parse(RECORDING), transcript: AbsolutePath.parse(TRANSCRIPT) },
      { kernel: world.kernel, principal: OWNER, step: world.step },
    )
    const second = await addRecordingToConversation(
      world.conversation,
      {
        recording: AbsolutePath.parse(SECOND_RECORDING),
        transcript: AbsolutePath.parse(SECOND_TRANSCRIPT),
      },
      { kernel: world.kernel, principal: OWNER, step: world.step },
    )

    expect(first.membership.raw).not.toBe(second.membership.raw)
    expect(
      world.graph.edges.filter(
        ({ source, edge }) =>
          source === world.conversation.raw && edge === D.conversation_has_membership.path.class.raw,
      ),
    ).toHaveLength(2)
  })

  it('rejects a Transcript that is foreign, unready, or substituted after membership', async () => {
    const foreign = await conversationWorld()
    const unrelatedRecording = foreign.graph.seed('/spaces/ada/recording-two', D.Recording.path.class.raw, {
      [D.Recording.recordingId.key]: '0190c6f0-7b21-7a40-8b11-000000000075',
      [D.Recording.status.key]: 'ready',
    })
    const otherTranscript = foreign.graph.seed(OTHER_TRANSCRIPT, D.Transcript.path.class.raw, {
      [D.Transcript.artifactId.key]: OTHER_ARTIFACT_ID,
      [D.Transcript.status.key]: 'ready',
    })
    foreign.graph.link(otherTranscript, D.transcribes.path.class.raw, unrelatedRecording)

    await expect(
      addRecordingToConversation(
        foreign.conversation,
        { recording: AbsolutePath.parse(RECORDING), transcript: AbsolutePath.parse(OTHER_TRANSCRIPT) },
        { kernel: foreign.kernel, principal: OWNER, step: foreign.step },
      ),
    ).rejects.toThrow(/does not transcribe the exact Recording/)

    otherTranscript.props[D.Transcript.status.key] = 'publishing'
    await expect(
      addRecordingToConversation(
        foreign.conversation,
        { recording: AbsolutePath.parse(RECORDING), transcript: AbsolutePath.parse(OTHER_TRANSCRIPT) },
        { kernel: foreign.kernel, principal: OWNER, step: foreign.step },
      ),
    ).rejects.toThrow(/ready Gumi Transcript/)

    await addRecordingToConversation(
      foreign.conversation,
      { recording: AbsolutePath.parse(RECORDING), transcript: AbsolutePath.parse(TRANSCRIPT) },
      { kernel: foreign.kernel, principal: OWNER, step: foreign.step },
    )
    otherTranscript.props[D.Transcript.status.key] = 'ready'
    foreign.graph.edges.splice(
      foreign.graph.edges.findIndex(({ source, edge }) =>
        source === OTHER_TRANSCRIPT && edge === D.transcribes.path.class.raw),
      1,
    )
    foreign.graph.link(otherTranscript, D.transcribes.path.class.raw, foreign.recording)
    await expect(
      addRecordingToConversation(
        foreign.conversation,
        { recording: AbsolutePath.parse(RECORDING), transcript: AbsolutePath.parse(OTHER_TRANSCRIPT) },
        { kernel: foreign.kernel, principal: OWNER, step: foreign.step },
      ),
    ).rejects.toThrow(/different immutable facts/)
  })

  it('rejects actor substitution and tampered membership evidence', async () => {
    const world = await conversationWorld()
    const params = {
      recording: AbsolutePath.parse(RECORDING),
      transcript: AbsolutePath.parse(TRANSCRIPT),
    }
    const first = await addRecordingToConversation(world.conversation, params, {
      kernel: world.kernel,
      principal: OWNER,
      step: world.step,
    })

    await expect(
      addRecordingToConversation(world.conversation, params, {
        kernel: world.kernel,
        principal: OTHER,
        step: world.step,
      }),
    ).rejects.toThrow(/different immutable facts/)

    const receipt = world.graph.nodes.get(first.membership.raw)!
    receipt.props[D.ConversationMembership.transcriptArtifactId.key] = OTHER_ARTIFACT_ID
    await expect(
      addRecordingToConversation(world.conversation, params, {
        kernel: world.kernel,
        principal: OWNER,
        step: world.step,
      }),
    ).rejects.toThrow(/different immutable facts/)
  })

  it('gates the actual caller on the parent, Conversation, Recording, and Transcript', async () => {
    const denied = seededWorld(false)
    await expect(
      authorizeCreateConversation({ kernel: denied.kernel, principal: OWNER, parent: PARENT }),
    ).rejects.toThrow('PERMISSION_DENIED')
    await expect(
      authorizeAddRecordingToConversation({
        kernel: denied.kernel,
        principal: OWNER,
        conversation: '/conversations/one',
        recording: RECORDING,
        transcript: TRANSCRIPT,
      }),
    ).rejects.toThrow('PERMISSION_DENIED')

    expect(denied.graph.authorizationChecks).toEqual([
      { who: OWNER, on: PARENT, perms: EDIT, context: 'Conversation.create' },
      {
        who: OWNER,
        on: '/conversations/one',
        perms: EDIT,
        context: 'Conversation.addRecording conversation',
      },
      {
        who: OWNER,
        on: RECORDING,
        perms: READ,
        context: 'Conversation.addRecording recording',
      },
      {
        who: OWNER,
        on: TRANSCRIPT,
        perms: READ,
        context: 'Conversation.addRecording transcript',
      },
    ])
  })
})
