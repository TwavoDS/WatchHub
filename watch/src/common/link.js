// Shared connection with the WatchHub phone app, used by every page.
// Also keeps the list of notifications and handles vibration, so notifications
// still buzz while you are on the camera or navigation page.

import interconnect from '@system.interconnect'
import file from '@system.file'
import vibrator from '@system.vibrator'

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
let B64_LOOKUP = null

export function b64ToBytes(s) {
  if (!B64_LOOKUP) {
    B64_LOOKUP = new Uint8Array(256)
    for (let i = 0; i < B64.length; i++) B64_LOOKUP[B64.charCodeAt(i)] = i
  }
  const len = s.length
  let out = (len * 3) / 4
  if (s[len - 1] === '=') out--
  if (s[len - 2] === '=') out--
  const v = new Uint8Array(out)
  let p = 0
  for (let i = 0; i < len; i += 4) {
    const a = B64_LOOKUP[s.charCodeAt(i)]
    const b = B64_LOOKUP[s.charCodeAt(i + 1)]
    const c = B64_LOOKUP[s.charCodeAt(i + 2)]
    const d = B64_LOOKUP[s.charCodeAt(i + 3)]
    v[p++] = (a << 2) | (b >> 4)
    if (p < out) v[p++] = ((b & 15) << 4) | (c >> 2)
    if (p < out) v[p++] = ((c & 3) << 6) | d
  }
  return v
}

/** Vibrate a pattern like "SSL" (S = short, L = long). */
export function vibrate(pattern) {
  const p = String(pattern || '')
  let delay = 0
  for (let i = 0; i < p.length && i < 6; i++) {
    const mode = p[i] === 'L' ? 'long' : 'short'
    setTimeout(() => { try { vibrator.vibrate({ mode: mode }) } catch (e) {} }, delay)
    delay += mode === 'long' ? 700 : 350
  }
}

function newState() {
  return {
    conn: null,
    open: false,
    listeners: [],          // functions(msg) of the visible page
    notifs: [],             // newest first: {id, pkg, app, title, text, color, vib, time}
    icons: {},              // pkg -> uri of a saved icon
    iconAsked: {},
    page: ''
  }
}

// Every page gets its own copy of this file, so the shared state must live on
// the app object; bind() points this copy at it.
let state = newState()

export function bind(app) {
  const def = app && app.$def
  if (!def) return
  if (!def.__hub) def.__hub = state
  state = def.__hub
}

function emit(msg) {
  state.listeners.slice().forEach((fn) => { try { fn(msg) } catch (e) {} })
}

function onMessage(raw) {
  let msg = raw
  if (typeof raw === 'string') {
    try { msg = JSON.parse(raw) } catch (e) { return }
  }
  if (!msg || typeof msg !== 'object') return
  if (msg.data && !msg.t) {
    msg = msg.data
    if (typeof msg === 'string') { try { msg = JSON.parse(msg) } catch (e) { return } }
  }
  switch (msg.t) {
    case 'notif':
      state.notifs = [msg].concat(state.notifs.filter((n) => n.id !== msg.id)).slice(0, 20)
      if (!msg.quiet && msg.vib) vibrate(msg.vib)
      requestIcon(msg.pkg)
      break
    case 'remind':
      if (state.notifs.some((n) => n.id === msg.id)) vibrate(msg.vib || 'S')
      break
    case 'gone':
      state.notifs = state.notifs.filter((n) => n.id !== msg.id)
      break
    case 'list':
      state.notifs = (msg.items || []).slice().reverse()
      state.notifs.forEach((n) => requestIcon(n.pkg))
      break
    case 'icon':
      saveIcon(msg.pkg, msg.d)
      return
  }
  emit(msg)
}

function requestIcon(pkg) {
  if (!pkg || state.icons[pkg] || state.iconAsked[pkg]) return
  state.iconAsked[pkg] = true
  send({ cmd: 'icon', pkg: pkg })
}

function saveIcon(pkg, b64) {
  if (!pkg || !b64) return
  const uri = 'internal://files/icon_' + pkg.replace(/[^A-Za-z0-9_]/g, '_') + '.png'
  let bytes
  try { bytes = b64ToBytes(b64) } catch (e) { return }
  file.writeArrayBuffer({
    uri: uri,
    buffer: bytes,
    success: () => {
      state.icons[pkg] = uri
      emit({ t: 'iconReady', pkg: pkg })
    }
  })
}

export function connect() {
  if (state.conn) return state.conn
  const conn = interconnect.instance()
  conn.onmessage = (data) => onMessage(data && data.data)
  conn.onopen = () => {
    state.open = true
    send({ cmd: 'hello' })
    if (state.page) send({ cmd: 'page', p: state.page })
    emit({ t: 'linkOpen' })
  }
  conn.onclose = (d) => { state.open = false; emit({ t: 'linkClosed', code: d && d.code }) }
  conn.onerror = (d) => emit({ t: 'linkError', code: d && d.code })
  state.conn = conn
  return conn
}

/** Sends a message to the phone; onFail(text) is called when it could not be sent. */
export function send(obj, onFail) {
  const conn = connect()
  conn.send({
    data: obj,
    fail: (d, code) => {
      if (!onFail) return
      let info = code
      if (info === undefined) { try { info = JSON.stringify(d) } catch (e) { info = String(d) } }
      onFail(String(info))
    }
  })
}

/** A page registers its message handler while it is visible. */
export function listen(fn) {
  connect()
  state.listeners.push(fn)
  return () => { state.listeners = state.listeners.filter((f) => f !== fn) }
}

/** Tells the phone which page is open, so notifications don't pull you out of the camera. */
export function setPage(p) {
  state.page = p
  send({ cmd: 'page', p: p })
}

export function notifs() { return state.notifs }
export function iconFor(pkg) { return state.icons[pkg] || '' }

export function dismiss(id) {
  state.notifs = state.notifs.filter((n) => n.id !== id)
  send({ cmd: 'dismiss', id: id })
  emit({ t: 'gone', id: id })
}

export function sync() { send({ cmd: 'sync' }) }
