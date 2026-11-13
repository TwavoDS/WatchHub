// Shared connection with the WatchHub phone app, used by every page.
// Also keeps the list of notifications and handles vibration, so notifications
// still buzz while you are on the camera or navigation page.

import interconnect from '@system.interconnect'
import file from '@system.file'
import vibrator from '@system.vibrator'
import router from '@system.router'

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
    page: '',
    imgs: {},               // key -> {uri, h, w}: text drawn by the phone (emoji)
    imgParts: {},
    imgAsked: {},
    openId: null,       // notification to show as soon as the notification page opens
    autoClose: false    // the app was opened for a notification: exit after its display time
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
      send({ cmd: 'got', id: msg.id })   // tells the phone the app is really open
      state.notifs = [msg].concat(state.notifs.filter((n) => n.id !== msg.id)).slice(0, 20)
      if (!msg.quiet && msg.vib) vibrate(msg.vib)
      requestIcon(msg.pkg)
      break
    case 'remind':
      if (state.notifs.some((n) => n.id === msg.id)) vibrate(msg.vib || 'S')
      break
    case 'gone':
      state.notifs = state.notifs.filter((n) => n.id !== msg.id)
      dropImage('b' + msg.id)
      break
    case 'list':
      state.notifs = (msg.items || []).slice().reverse()
      state.notifs.forEach((n) => requestIcon(n.pkg))
      break
    case 'icon':
      saveIcon(msg.pkg, msg.d)
      return
    case 'img':
      imgPart(msg)
      return
    case 'goto': {
      // The phone opened the app for a notification or navigation. The notification
      // itself travels inside this message, so the screen never shows an older one first.
      const n = msg.n
      if (n) {
        send({ cmd: 'got', id: n.id })
        state.notifs = [n].concat(state.notifs.filter((x) => x.id !== n.id)).slice(0, 20)
        if (!n.quiet && n.vib) vibrate(n.vib)
        requestIcon(n.pkg)
        state.openId = n.id
      }
      if (msg.p && state.page !== msg.p) {
        // Also when the app was still open on another page (e.g. an old notification):
        // the phone asked for this page, so go there
        const fromStart = state.page === '' || state.page === 'home'
        state.autoClose = fromStart   // opened by the phone: close the app again after the display time
        const uri = '/pages/' + msg.p
        log('I', 'ga naar ' + msg.p + ' (was ' + (state.page || 'dicht') + ')')
        state.page = msg.p
        try {
          if (fromStart) router.push({ uri: uri })
          else router.replace({ uri: uri })
        } catch (e) { log('E', 'pagina openen mislukt: ' + e.message) }
      } else if (n) {
        emit(n)
      }
      return
    }
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
    },
    fail: (d, code) => log('E', 'icoon opslaan mislukt (' + code + ')')
  })
}

function imgPart(m) {
  if (!m.k) return
  let p = state.imgParts[m.k]
  if (!p || p.n !== m.n) { p = { n: m.n, got: 0, parts: [] }; state.imgParts[m.k] = p }
  if (p.parts[m.c] === undefined) { p.parts[m.c] = m.d; p.got++ }
  if (p.got < p.n) return
  delete state.imgParts[m.k]
  let bytes
  try { bytes = b64ToBytes(p.parts.join('')) } catch (e) { log('E', 'afbeelding ' + m.k + ' kapot: ' + e.message); return }
  const uri = 'internal://cache/t_' + m.k + '_' + Date.now() + '.' + (m.f || 'png')
  file.writeArrayBuffer({
    uri: uri,
    buffer: bytes,
    success: () => {
      const old = state.imgs[m.k]
      if (old) { try { file.delete({ uri: old.uri }) } catch (e) {} }
      state.imgs[m.k] = { uri: uri, h: m.h, w: m.w }
      emit({ t: 'imgReady', k: m.k })
    },
    fail: (d, code) => log('E', 'afbeelding opslaan mislukt (' + code + ')')
  })
}

function dropImage(k) {
  const old = state.imgs[k]
  if (!old) return
  delete state.imgs[k]
  try { file.delete({ uri: old.uri }) } catch (e) {}
}

/** Picture of text drawn by the phone, or null; asks the phone for it the first time. */
export function imageFor(k) {
  if (!k) return null
  const have = state.imgs[k]
  if (have) return have
  const now = Date.now()
  if (!state.imgAsked[k] || now - state.imgAsked[k] > 8000) {
    state.imgAsked[k] = now
    send({ cmd: 'img', k: k }, () => { state.imgAsked[k] = 0 })
  }
  return null
}

export function connect() {
  if (state.conn) return state.conn
  const conn = interconnect.instance()
  conn.onmessage = (data) => onMessage(data && data.data)
  conn.onopen = () => {
    state.open = true
    if (failedSends.length) {
      log('W', 'niet verstuurd zonder verbinding: ' + failedSends.slice(-10).join(', '))
      failedSends = []
    }
    send({ cmd: 'hello' })
    if (state.page) send({ cmd: 'page', p: state.page })
    // Ask again for pictures that didn't arrive while the link was down
    state.imgAsked = {}
    emit({ t: 'imgRetry' })
    emit({ t: 'linkOpen' })
  }
  conn.onclose = (d) => { state.open = false; failedSends.push('closed:' + (d && d.code)); emit({ t: 'linkClosed', code: d && d.code }) }
  conn.onerror = (d) => { failedSends.push('error:' + (d && d.code)); emit({ t: 'linkError', code: d && d.code }) }
  state.conn = conn
  return conn
}

/** Sends a message to the phone; onFail(text) is called when it could not be sent. */
// Sends that failed while the link was down; reported to the phone's log once it is back
let failedSends = []

/** Writes a line to the phone's log (Logboek in the WatchHub phone app). */
export function log(level, msg) {
  try {
    const conn = connect()
    conn.send({ data: { cmd: 'log', l: level, m: String(msg).slice(0, 300) } })
  } catch (e) {}
}

// Keep equal to versionCode in manifest.json: the phone uses it to offer updates
export const VERSION = 17

export function send(obj, onFail) {
  if (obj && (obj.cmd === 'hello' || obj.cmd === 'page')) obj.v = VERSION
  const conn = connect()
  conn.send({
    data: obj,
    fail: (d, code) => {
      if (obj && obj.cmd !== 'log' && obj.cmd !== 'ack') failedSends.push(obj.cmd + ':' + code)
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
  dropImage('b' + id)
  send({ cmd: 'dismiss', id: id })
  emit({ t: 'gone', id: id })
}

export function sync() { send({ cmd: 'sync' }) }

/** Takes (and clears) the notification the notification page should open. */
export function takeOpenId() { const id = state.openId; state.openId = null; return id }
export function takeAutoClose() { const v = state.autoClose; state.autoClose = false; return v }

export function action(id, i) { send({ cmd: 'action', id: id, i: i }) }
export function reply(id, text) { send({ cmd: 'reply', id: id, text: text }) }
export function findPhone(on) { send({ cmd: 'findphone', on: on }) }
