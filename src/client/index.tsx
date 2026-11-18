import { useEffect, useRef, useState } from 'react'
import type { ConfigPanelProps, DshLoaderUi } from '@dsh-plugin/dsh-loader/client'
import {
  Alert, Button, Card, DshProvider, Field, Input, InputNumber, Progress, Select, Switch, Textarea,
} from '@dsh-plugin/dsh-ui-components'
import versions from '../../protocol/versions.json'

const CONFIG_ID = 'minecraft-gametest'
export const inject = ['dshLoaderUi']

type Settings = {
  enabled: boolean
  minecraftVersion: string
  javaExecutable: string
  minimum: number
  maximum: number
  connections: { endpoint: string, tokenFile: string }[]
}

type Status = {
  installation: {
    state: string
    version: string
    logs: string[]
    completed: number
    total: number
    downloaded: number
    downloadSize: number
  }
  desiredVersion: string
  activeVersion: string
  switching: boolean
  waiting: number
  lastError: string
  configError: string
  games: { slot: number, endpoint: string, source: string, state: string, account: string }[]
  accounts: { id: string, name: string, inUse: boolean }[]
}

const options = Object.keys(versions).map(version => ({ value: version, label: version }))
const row = { display: 'flex', flexWrap: 'wrap', gap: 8 } as const
const stack = { display: 'grid', gap: 12 } as const
const fieldGrid = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(205px, 1fr))', gap: 12 } as const

function ConnectionPanel({ client, value, setValue }: ConfigPanelProps) {
  const draft = value as Settings
  const [status, setStatus] = useState<Status>()
  const [pollError, setPollError] = useState('')
  const [error, setError] = useState('')
  const [pending, setPending] = useState('')
  const [login, setLogin] = useState<{ id: string, url: string }>()
  const [redirect, setRedirect] = useState('')
  const action = useRef<AbortController | undefined>(undefined)

  useEffect(() => () => action.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try {
        const result = await client.invoke(CONFIG_ID, 'status', { input: {} }, controller.signal)
        if (controller.signal.aborted) return
        setStatus(result.value as Status)
        setPollError('')
      } catch (failure) {
        if (!controller.signal.aborted) setPollError(String(failure))
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(() => void poll(), 1500)
      }
    }
    void poll()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [client])

  function change<K extends keyof Settings>(key: K, value: Settings[K]) {
    setValue?.({ ...draft, [key]: value })
  }

  async function run<T>(label: string, operation: (signal: AbortSignal) => Promise<T>, complete?: (result: T) => void) {
    if (action.current) return
    const controller = new AbortController()
    action.current = controller
    setPending(label)
    setError('')
    try {
      const result = await operation(controller.signal)
      if (!controller.signal.aborted) complete?.(result)
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      action.current = undefined
      if (!controller.signal.aborted) setPending('')
    }
  }

  const invoke = (operation: string, input: Record<string, string> = {}, complete?: (result: unknown) => void) =>
    run(operation, signal => client.invoke(CONFIG_ID, operation, { input }, signal), result => complete?.(result.value))
  const installation = status?.installation
  const progress = installation?.total ? Math.round(100 * installation.completed / installation.total) : 0
  const invalidLimits = !Number.isInteger(draft.minimum) || !Number.isInteger(draft.maximum)
    || draft.minimum < 0 || draft.maximum > 32 || draft.maximum < 1 || draft.minimum > draft.maximum
  const busy = !!pending

  return (
    <DshProvider theme="host">
      <div style={stack}>
        <Card>
          <div style={stack}>
            <div style={fieldGrid}>
              <Field label="Minecraft version" htmlFor="mcgt-version">
                <Select id="mcgt-version" options={options} value={draft.minecraftVersion || undefined}
                  onChange={value => change('minecraftVersion', String(value ?? ''))}
                  placeholder="Choose a version" allowClear disabled={busy} />
              </Field>
              <Field label="Java executable" htmlFor="mcgt-java" description="Optional absolute path">
                <Input id="mcgt-java" value={draft.javaExecutable} onChange={value => change('javaExecutable', value)}
                  placeholder="Use JAVA_HOME or PATH" disabled={busy} mono />
              </Field>
              <Field label="Minimum active games" htmlFor="mcgt-min">
                <InputNumber id="mcgt-min" min={0} max={32} precision={0} value={draft.minimum}
                  onChange={value => change('minimum', value ?? 0)} disabled={busy} aria-invalid={invalidLimits} />
              </Field>
              <Field label="Maximum active games" htmlFor="mcgt-max">
                <InputNumber id="mcgt-max" min={1} max={32} precision={0} value={draft.maximum}
                  onChange={value => change('maximum', value ?? 1)} disabled={busy} aria-invalid={invalidLimits} />
              </Field>
            </div>
            {invalidLimits && <Alert type="error" content="The minimum must be less than or equal to the maximum." />}
            <Field label="Enable Minecraft tools" htmlFor="mcgt-enabled" layout="inline">
              <Switch id="mcgt-enabled" checked={draft.enabled} onChange={value => change('enabled', value)} disabled={busy} />
            </Field>

            <Field label="External Minecraft MCP connections" description="Optional loopback endpoints for games already running the mod.">
              <div style={stack}>
                {draft.connections.map((connection, index) => (
                  <div style={row} key={index}>
                    <Input aria-label={`MCP endpoint ${index + 1}`} style={{ flex: '1 1 200px' }} mono
                      value={connection.endpoint} placeholder="http://127.0.0.1:4723/mcp" disabled={busy}
                      onChange={endpoint => change('connections', draft.connections.map((item, i) => i === index ? { ...item, endpoint } : item))} />
                    <Input aria-label={`MCP token file ${index + 1}`} style={{ flex: '1 1 200px' }} mono
                      value={connection.tokenFile} placeholder="Absolute path to mcp-token" disabled={busy}
                      onChange={tokenFile => change('connections', draft.connections.map((item, i) => i === index ? { ...item, tokenFile } : item))} />
                    <Button disabled={busy} onClick={() => change('connections', draft.connections.filter((_item, i) => i !== index))}>Remove</Button>
                  </div>
                ))}
                <div>
                  <Button disabled={busy} onClick={() => change('connections', [...draft.connections, { endpoint: '', tokenFile: '' }])}>Add MCP connection</Button>
                </div>
              </div>
            </Field>

            {(installation?.state === 'failed' || !!status?.lastError) && <div>
              <Button loading={pending === 'retry-install'} disabled={busy} onClick={() => void invoke('retry-install')}>Retry</Button>
            </div>}
            {(error || pollError || status?.configError) && <Alert type="error" content={error || pollError || status?.configError} />}
            {status?.switching && <Alert type="info" content={`Waiting for ${status.activeVersion} sessions to release before switching.`} />}
            {status?.lastError && <Alert type="warning" content={status.lastError} />}
            <div role="status" aria-live="polite">
              {status?.desiredVersion
                ? <strong>{installation?.version || status.desiredVersion} · {installation?.state || 'pending'}</strong>
                : 'Choose and save a version to begin installation.'}
            </div>
            {!!status?.desiredVersion && installation?.state === 'installing' && (
              <div>
                <Progress percent={progress} />
                <span>{installation.completed} / {installation.total} files</span>
                {!!installation.downloadSize && <span> · {(installation.downloaded / 1048576).toFixed(1)} / {(installation.downloadSize / 1048576).toFixed(1)} MiB</span>}
              </div>
            )}
            {installation && installation.logs.length > 0 && (
              <Field label="Installation log" htmlFor="mcgt-log">
                <Textarea id="mcgt-log" value={installation.logs.join('\n')} autoSize={{ minRows: 3, maxRows: 9 }} readOnly mono />
              </Field>
            )}
            {status && (
              <div>
                <strong>{status.games.length} active games · {status.waiting} waiting sessions</strong>
                {status.games.length > 0 && <ul>
                  {status.games.map(game => <li key={game.endpoint || game.slot}>
                    {game.source === 'external' ? game.endpoint : `Game ${game.slot}`} · {game.state}{game.account && ` · ${game.account}`}
                  </li>)}
                </ul>}
              </div>
            )}
          </div>
        </Card>

        <Card title="Microsoft account pool">
          <div style={stack}>
            {status?.accounts.length ? (
              <div style={stack}>
                {status.accounts.map(account => (
                  <div style={{ ...row, alignItems: 'center' }} key={account.id}>
                    <span>{account.name} · {account.inUse ? 'In use' : 'Available'}</span>
                    <Button size="sm" disabled={busy || account.inUse}
                      onClick={() => void invoke('account-remove', { id: account.id })}>Remove</Button>
                  </div>
                ))}
              </div>
            ) : 'Games start offline when no Microsoft account is available.'}
            {!login ? (
              <div><Button loading={pending === 'account-begin'} disabled={busy} onClick={() => void invoke('account-begin', {}, result => {
                setLogin(result as typeof login)
                setRedirect('')
              })}>Add Microsoft account</Button></div>
            ) : (
              <div style={stack}>
                <div style={row}>
                  <Button variant="primary" onClick={() => window.open(login.url, '_blank', 'noopener,noreferrer')}>Open Microsoft sign-in</Button>
                  <Button disabled={busy} onClick={() => { setLogin(undefined); setRedirect('') }}>Cancel</Button>
                </div>
                <Field label="Final Microsoft redirect URL" htmlFor="mcgt-redirect"
                  description="After signing in, copy the full address from the Microsoft tab and paste it here.">
                  <Input id="mcgt-redirect" type="password" value={redirect} onChange={setRedirect} autoComplete="off" disabled={busy} />
                </Field>
                <div><Button variant="primary" loading={pending === 'account-finish'} disabled={busy || !redirect.trim()}
                  onClick={() => void invoke('account-finish', { id: login.id, callback: redirect.trim() }, () => {
                    setLogin(undefined)
                    setRedirect('')
                  })}>Bind account</Button></div>
              </div>
            )}
          </div>
        </Card>
      </div>
    </DshProvider>
  )
}

export function apply(ctx: Parameters<DshLoaderUi['config']['registerPanel']>[0] & { dshLoaderUi: DshLoaderUi }) {
  ctx.dshLoaderUi.config.registerPanel(ctx, { id: 'connection', configId: CONFIG_ID, component: ConnectionPanel })
}
