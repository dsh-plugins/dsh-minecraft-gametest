import { useEffect, useRef, useState } from 'react'
import type { ConfigPanelProps, DshLoaderUi } from '@dsh-plugin/dsh-loader/client'
import { Alert, Button, Card, DshProvider } from '@dsh-plugin/dsh-ui-components'

const CONFIG_ID = 'minecraft-gametest'
export const inject = ['dshLoaderUi']

function ConnectionPanel({ client }: ConfigPanelProps) {
  const [result, setResult] = useState<unknown>()
  const [error, setError] = useState('')
  const [pending, setPending] = useState<'probe' | 'replay-state'>()
  const request = useRef<AbortController | undefined>(undefined)
  useEffect(() => () => request.current?.abort(), [])

  async function invoke(operation: 'probe' | 'replay-state') {
    request.current?.abort()
    const current = new AbortController()
    request.current = current
    setResult(undefined)
    setError('')
    setPending(operation)
    try {
      const response = await client.invoke(CONFIG_ID, operation, { input: {} }, current.signal)
      if (!current.signal.aborted) setResult(response.value)
    } catch (failure) {
      if (!current.signal.aborted) setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      if (request.current === current && !current.signal.aborted) setPending(undefined)
    }
  }

  return (
    <DshProvider theme="host">
      <Card title="Minecraft GameTest">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <Button variant="primary" loading={pending === 'probe'} disabled={!!pending} onClick={() => void invoke('probe')}>Test connection</Button>
          <Button loading={pending === 'replay-state'} disabled={!!pending} onClick={() => void invoke('replay-state')}>Replay status</Button>
        </div>
        {error && <Alert type="error" content={error} style={{ marginTop: 12 }} />}
        {result != null && <pre role="status" style={{ overflow: 'auto', maxHeight: 280 }}>{JSON.stringify(result, null, 2)}</pre>}
      </Card>
    </DshProvider>
  )
}

export function apply(ctx: Parameters<DshLoaderUi['config']['registerPanel']>[0] & { dshLoaderUi: DshLoaderUi }) {
  ctx.dshLoaderUi.config.registerPanel(ctx, { id: 'connection', configId: CONFIG_ID, component: ConnectionPanel })
}
