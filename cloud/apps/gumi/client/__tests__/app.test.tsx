import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import { App } from '@/app'

afterEach(cleanup)

describe('domain client', () => {
  it('renders a standalone preview outside the Astrale shell', () => {
    render(<App />)

    expect(screen.getByText('Gumi conversation')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Architecture walk-through' })).toBeTruthy()
    expect(screen.getByText(/control plane cannot assume Android forever/)).toBeTruthy()
    expect(screen.getByText('Verified')).toBeTruthy()
  })
})
