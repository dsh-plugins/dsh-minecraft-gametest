import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign, type KeyObject } from 'node:crypto'
import { mkdir, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { GameAccount } from './launcher.js'

// Modrinth App uses the Xbox Win32 SISU flow, followed by the Minecraft launcher exchange.
const clientId = '00000000402b5328'
const redirectUri = 'https://login.live.com/oauth20_desktop.srf'
const scope = 'service::user.auth.xboxlive.com::MBI_SSL'
const idPattern = /^[0-9a-f]{32}$/
const flowTimeout = 10 * 60_000

interface StoredAccount {
  id: string
  name: string
  refreshToken: string
  accessToken: string
  expiresAt: number
}

interface XboxToken {
  Token: string
  DisplayClaims?: { xui?: { uhs: string }[] }
}

interface Device {
  privateKey: KeyObject
  proof: { kty: 'EC', crv: 'P-256', alg: 'ES256', use: 'sig', x: string, y: string }
  token: string
}

interface Flow {
  device: Device
  state: string
  verifier: string
  session: string
  expiresAt: number
}

export class Accounts {
  private readonly inUse = new Set<string>()
  private readonly retryAfter = new Map<string, number>()
  private readonly flows = new Map<string, Flow>()

  constructor(private readonly directory: string) {}

  async list() {
    const accounts = await this.readAccounts()
    return accounts.map(({ id, name }) => ({ id, name, inUse: this.inUse.has(id) }))
  }

  async begin(signal: AbortSignal) {
    for (const [id, flow] of this.flows) if (flow.expiresAt <= Date.now()) this.flows.delete(id)
    if (this.flows.size >= 16) throw new Error('Too many Microsoft sign-ins are in progress')
    const device = await xboxDevice(signal)
    const verifier = randomBytes(48).toString('base64url')
    const state = randomBytes(24).toString('base64url')
    const { data, headers } = await xboxPost<{ MsaOauthRedirect: string }>(
      'https://sisu.xboxlive.com/authenticate',
      {
        AppId: clientId, DeviceToken: device.token, Offers: [scope], RedirectUri: redirectUri,
        Sandbox: 'RETAIL', TokenType: 'code', TitleId: '1794566092',
        Query: { state, prompt: 'select_account', code_challenge_method: 'S256',
          code_challenge: createHash('sha256').update(verifier).digest('base64url') },
      }, device.privateKey, signal,
    )
    const session = headers.get('x-sessionid')
    const url = new URL(data.MsaOauthRedirect)
    if (!session || url.origin !== 'https://login.live.com' || url.username || url.password) {
      throw new Error('Xbox returned an unexpected Microsoft sign-in URL')
    }
    const id = randomUUID()
    this.flows.set(id, { device, state, verifier, session, expiresAt: Date.now() + flowTimeout })
    return { id, url: url.href }
  }

  async finish(id: string, callback: string, signal: AbortSignal) {
    const flow = this.flows.get(id)
    if (!flow || flow.expiresAt < Date.now()) {
      this.flows.delete(id)
      throw new Error('Microsoft sign-in expired. Start a new sign-in.')
    }
    let url: URL
    try { url = new URL(callback) } catch { throw new Error('Paste the full final Microsoft redirect URL') }
    if (url.origin + url.pathname !== redirectUri || url.searchParams.get('state') !== flow.state || url.username || url.password) {
      throw new Error('The URL does not match this Microsoft sign-in')
    }
    const code = url.searchParams.get('code')
    if (!code) throw new Error('Microsoft did not provide an authorization code')
    this.flows.delete(id)
    const oauth = await oauthToken({ grant_type: 'authorization_code', code, code_verifier: flow.verifier, redirect_uri: redirectUri }, signal)
    const account = await exchange(flow.device, flow.session, oauth, signal)
    await this.store(account)
    this.retryAfter.delete(account.id)
    return { id: account.id, name: account.name }
  }

  async remove(id: string) {
    if (!idPattern.test(id)) throw new Error('Invalid Minecraft account')
    if (this.inUse.has(id)) throw new Error('That account is assigned to a game')
    // Reserve across the asynchronous removal so a launch cannot claim the file concurrently.
    this.inUse.add(id)
    try {
      await rm(join(this.directory, `${id}.json`), { force: true })
    } finally {
      this.inUse.delete(id)
      this.retryAfter.delete(id)
    }
  }

  async checkout(signal: AbortSignal): Promise<{ account: GameAccount, release: () => void } | undefined> {
    for (let account of await this.readAccounts()) {
      const id = account.id
      if (this.inUse.has(id) || (this.retryAfter.get(id) ?? 0) > Date.now()) continue
      this.inUse.add(id)
      try {
        if (account.expiresAt - Date.now() < 120_000) {
          const device = await xboxDevice(signal)
          const oauth = await oauthToken({ grant_type: 'refresh_token', refresh_token: account.refreshToken }, signal)
          // Microsoft rotates its refresh token. Save it even if the game services are temporarily unavailable.
          account.refreshToken = oauth.refresh_token
          await this.store(account)
          const refreshed = await exchange(device, undefined, oauth, signal)
          if (refreshed.id !== id) throw new Error('Microsoft returned a different Minecraft account')
          await this.store(refreshed)
          account = refreshed
        }
        return {
          account: { id, name: account.name, accessToken: account.accessToken },
          release: () => { this.inUse.delete(id) },
        }
      } catch {
        this.inUse.delete(id)
        signal.throwIfAborted()
        this.retryAfter.set(id, Date.now() + 60_000)
      }
    }
    return undefined
  }

  private async readAccounts() {
    const files = await readdir(this.directory).catch(error => {
      if (error.code === 'ENOENT') return []
      throw error
    })
    const accounts: StoredAccount[] = []
    for (const file of files.sort()) {
      if (!file.endsWith('.json') || !idPattern.test(file.slice(0, -5))) continue
      const value = await readFile(join(this.directory, file), 'utf8').then(data => JSON.parse(data) as StoredAccount).catch(() => undefined)
      if (value?.id === file.slice(0, -5) && typeof value.name === 'string' && typeof value.accessToken === 'string'
        && typeof value.refreshToken === 'string' && typeof value.expiresAt === 'number') accounts.push(value)
    }
    return accounts
  }

  private async store(account: StoredAccount) {
    await mkdir(this.directory, { recursive: true, mode: 0o700 })
    const target = join(this.directory, `${account.id}.json`)
    const temp = `${target}.${randomUUID()}.tmp`
    try {
      await writeFile(temp, JSON.stringify(account), { mode: 0o600, flag: 'wx' })
      await rename(temp, target)
    } finally {
      await rm(temp, { force: true }).catch(() => {})
    }
  }
}

async function xboxDevice(signal: AbortSignal): Promise<Device> {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  const { x, y } = publicKey.export({ format: 'jwk' })
  const proof = { kty: 'EC', crv: 'P-256', alg: 'ES256', use: 'sig', x: x!, y: y! } as const
  const { data } = await xboxPost<XboxToken>('https://device.auth.xboxlive.com/device/authenticate', {
    Properties: {
      AuthMethod: 'ProofOfPossession', Id: `{${randomUUID().toUpperCase()}}`,
      DeviceType: 'Win32', Version: '10.16.0', ProofKey: proof,
    },
    RelyingParty: 'http://auth.xboxlive.com', TokenType: 'JWT',
  }, privateKey, signal)
  return { proof, privateKey, token: data.Token }
}

interface OAuthToken { access_token: string, refresh_token: string }

async function oauthToken(fields: Record<string, string>, signal: AbortSignal) {
  const { data } = await request<OAuthToken>('https://login.live.com/oauth20_token.srf', {
    method: 'POST', body: new URLSearchParams({ client_id: clientId, scope, ...fields }),
  }, signal)
  if (!data.access_token || !data.refresh_token) throw new Error('Microsoft did not return sign-in credentials')
  return data
}

async function exchange(device: Device, session: string | undefined, oauth: OAuthToken, signal: AbortSignal): Promise<StoredAccount> {
  const { data: sisu } = await xboxPost<{ UserToken: XboxToken, TitleToken: XboxToken }>('https://sisu.xboxlive.com/authorize', {
    AccessToken: `t=${oauth.access_token}`, AppId: clientId, DeviceToken: device.token,
    ProofKey: device.proof, Sandbox: 'RETAIL', SessionId: session ?? null,
    SiteName: 'user.auth.xboxlive.com', RelyingParty: 'http://xboxlive.com', UseModernGamertag: true,
  }, device.privateKey, signal)
  const { data: xsts } = await xboxPost<XboxToken>('https://xsts.auth.xboxlive.com/xsts/authorize', {
    RelyingParty: 'rp://api.minecraftservices.com/', TokenType: 'JWT',
    Properties: { SandboxId: 'RETAIL', UserTokens: [sisu.UserToken.Token], DeviceToken: device.token, TitleToken: sisu.TitleToken.Token },
  }, device.privateKey, signal)
  const hash = xsts.DisplayClaims?.xui?.[0]?.uhs
  if (!hash) throw new Error('Xbox did not return a Minecraft user identity')
  const { data: game } = await request<{ access_token: string, expires_in: number }>('https://api.minecraftservices.com/launcher/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ platform: 'PC_LAUNCHER', xtoken: `XBL3.0 x=${hash};${xsts.Token}` }),
  }, signal)
  const expiresAt = Date.now() + game.expires_in * 1000
  if (!game.access_token || !Number.isFinite(expiresAt)) throw new Error('Minecraft did not return launch credentials')
  const authorization = { headers: { Authorization: `Bearer ${game.access_token}` } }
  await request(`https://api.minecraftservices.com/entitlements/license?requestId=${randomUUID()}`, authorization, signal)
  const { data: profile } = await request<{ id: string, name: string }>('https://api.minecraftservices.com/minecraft/profile', authorization, signal)
  if (!idPattern.test(profile.id) || !/^[A-Za-z0-9_]{1,16}$/.test(profile.name)) throw new Error('The account has no valid Minecraft Java profile')
  return { id: profile.id, name: profile.name, accessToken: game.access_token, refreshToken: oauth.refresh_token, expiresAt }
}

async function xboxPost<T>(url: string, json: object, key: KeyObject, signal: AbortSignal) {
  const body = JSON.stringify(json)
  const header = Buffer.alloc(12)
  header.writeUInt32BE(1, 0)
  header.writeBigUInt64BE((BigInt(Math.floor(Date.now() / 1000)) + 11644473600n) * 10000000n, 4)
  const delimiter = Buffer.from([0])
  const payload = Buffer.concat([
    header.subarray(0, 4), delimiter, header.subarray(4), delimiter, Buffer.from('POST'), delimiter,
    Buffer.from(new URL(url).pathname), delimiter, delimiter, Buffer.from(body), delimiter,
  ])
  const signature = Buffer.concat([header, sign('sha256', payload, { key, dsaEncoding: 'ieee-p1363' })]).toString('base64')
  const headers: Record<string, string> = { 'Content-Type': 'application/json; charset=utf-8', Signature: signature }
  if (url !== 'https://sisu.xboxlive.com/authorize') headers['x-xbl-contract-version'] = '1'
  return request<T>(url, { method: 'POST', headers, body }, signal)
}

async function request<T>(url: string, init: RequestInit, signal: AbortSignal) {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  const response = await fetch(url, { ...init, headers, redirect: 'error', signal: AbortSignal.any([signal, AbortSignal.timeout(20_000)]) })
  if (!response.ok) {
    await response.body?.cancel()
    const service = new URL(url)
    throw new Error(`Microsoft sign-in request failed at ${service.hostname}${service.pathname} (HTTP ${response.status})`)
  }
  return { data: await response.json() as T, headers: response.headers }
}
