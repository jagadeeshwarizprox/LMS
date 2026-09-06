import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'

/**
 * Video playback.
 *
 * The provider identifier is never held in props, state or the bundle. Pressing
 * play asks the server for a grant, the server re-checks entitlement, and the
 * player is built from the answer. Nothing is fetched until the learner asks for
 * it, so a page load never reveals what it could have played.
 *
 * THE COPY LINK PROBLEM, AND HOW IT IS CLOSED HERE
 *
 * "Copy video URL" and "Copy embed code" are drawn by YouTube inside a cross
 * origin iframe. No script of ours can reach into that document to remove a menu
 * item, and the previous approach of covering two corners left the rest of the
 * surface live: a right click anywhere else still opened the menu.
 *
 * So the iframe is no longer interactive at all. It is rendered with
 * `controls: 0` and sits under a shield that covers the entire player and takes
 * every pointer and keyboard event. Contextmenu, auxclick, middle click, drag,
 * selection and long press are all cancelled on the shield. Because the pointer
 * never reaches the iframe, the menu that carries the link can never be opened.
 *
 * Everything a learner needs is then rebuilt in our own control bar below:
 * play, pause, scrub, skip, speed, volume, fullscreen. Fullscreen is taken on
 * our shell, not the iframe, so the watermark and the shield go fullscreen too.
 *
 * This closes the copy path in the browser. It does not make the id secret,
 * because it still moves over the network to build the player. What answers that
 * is the grant being short lived, every upload being unlisted, and rotation
 * being one click in Access and sharing. The permanent fix is moving the library
 * to signed playback (Cloudflare Stream), which the video service is already
 * shaped for.
 */

let apiReady = null
function loadYouTubeApi() {
  if (apiReady) return apiReady
  apiReady = new Promise((resolve) => {
    if (window.YT?.Player) return resolve(window.YT)
    const tag = document.createElement('script')
    tag.src = 'https://www.youtube.com/iframe_api'
    window.onYouTubeIframeAPIReady = () => resolve(window.YT)
    document.head.appendChild(tag)
  })
  return apiReady
}

const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2]

function clock(sec) {
  if (!sec || !isFinite(sec)) return '0:00'
  const s = Math.floor(sec % 60)
  const m = Math.floor((sec / 60) % 60)
  const h = Math.floor(sec / 3600)
  const mm = h ? String(m).padStart(2, '0') : String(m)
  return h ? `${h}:${mm}:${String(s).padStart(2, '0')}` : `${mm}:${String(s).padStart(2, '0')}`
}

export default function SecurePlayer({ videoRef, onProgress, onEnded, poster, track = true }) {
  const { user } = useAuth()
  const shell = useRef(null)
  const host = useRef(null)
  const player = useRef(null)
  const reported = useRef(new Set())
  const lastSaved = useRef(0)
  const hideTimer = useRef(null)

  const [state, setState] = useState('idle')   // idle | loading | playing | error
  const [error, setError] = useState('')
  const [mark, setMark] = useState(0)
  const [resume, setResume] = useState(null)

  const [playing, setPlaying] = useState(false)
  const [at, setAt] = useState(0)
  const [dur, setDur] = useState(0)
  const [buffered, setBuffered] = useState(0)
  const [speed, setSpeed] = useState(1)
  const [volume, setVolume] = useState(100)
  const [muted, setMuted] = useState(false)
  const [full, setFull] = useState(false)
  const [chromeOn, setChromeOn] = useState(true)
  const [speedOpen, setSpeedOpen] = useState(false)
  const [wmSlot, setWmSlot] = useState(0)

  useEffect(() => () => { try { player.current?.destroy() } catch { /* gone already */ } }, [])

  /* asked before playback, so the cover can offer a resume rather than making
     somebody drag a scrubber to find their place */
  useEffect(() => {
    if (!videoRef || !track) return
    api.get(`/learner/watch/${videoRef}`)
      .then((r) => setResume(r.resume ? r : null))
      .catch(() => setResume(null))
  }, [videoRef, track])

  const savePosition = useCallback((force) => {
    const p = player.current
    if (!track || !videoRef || !p?.getDuration) return
    const d = Math.round(p.getDuration())
    const now = Math.round(p.getCurrentTime())
    if (!d || now < 5) return
    if (!force && Math.abs(now - lastSaved.current) < 15) return
    lastSaved.current = now
    api.post(`/learner/watch/${videoRef}`, { seconds: now, durationSec: d }).catch(() => {})
  }, [track, videoRef])

  /* a position is only useful if it survives the tab closing, which is the most
     common way a long video ends */
  useEffect(() => {
    const flush = () => savePosition(true)
    window.addEventListener('pagehide', flush)
    document.addEventListener('visibilitychange', flush)
    return () => {
      flush()
      window.removeEventListener('pagehide', flush)
      document.removeEventListener('visibilitychange', flush)
    }
  }, [savePosition])

  /* the watermark moves, so a crop or a corner cover cannot lose it */
  useEffect(() => {
    if (state !== 'playing') return
    const id = setInterval(() => setWmSlot((n) => (n + 1) % 4), 9000)
    return () => clearInterval(id)
  }, [state])

  useEffect(() => {
    const onFs = () => setFull(Boolean(document.fullscreenElement))
    document.addEventListener('fullscreenchange', onFs)
    return () => document.removeEventListener('fullscreenchange', onFs)
  }, [])

  const report = (pct) => onProgress?.(pct)

  const start = async (fromStart) => {
    if (!videoRef) { setError('No video is attached to this chapter yet.'); setState('error'); return }
    setState('loading')
    try {
      const grant = await api.post(`/video/${videoRef}/grant`)
      const YT = await loadYouTubeApi()
      const startAt = fromStart ? 0 : (resume?.seconds || 0)
      player.current = new YT.Player(host.current, {
        videoId: grant.playbackId,
        host: 'https://www.youtube-nocookie.com',
        playerVars: {
          /*
           * controls 0 removes the whole YouTube control bar, which is where the
           * logo link and the share affordance live. disablekb 0 would hand
           * keyboard control to the iframe, so it is off and we bind our own.
           * fs 0 keeps their fullscreen button away; ours takes the shell.
           */
          controls: 0, rel: 0, disablekb: 1, fs: 0, modestbranding: 1,
          iv_load_policy: 3, playsinline: 1, origin: window.location.origin
        },
        events: {
          onReady: (e) => {
            if (startAt > 0) e.target.seekTo(startAt, true)
            e.target.playVideo()
            setDur(e.target.getDuration() || 0)
            setState('playing')
          },
          onStateChange: (e) => {
            if (e.data === YT.PlayerState.PLAYING) setPlaying(true)
            if (e.data === YT.PlayerState.PAUSED) setPlaying(false)
            if (e.data === YT.PlayerState.ENDED) {
              setPlaying(false)
              report(100)
              onEnded?.()
            }
          }
        }
      })
      poll()
    } catch (e) {
      setError(e.message)
      setState('error')
    }
  }

  /* watch tracking replaces the honour-system "mark as watched" button */
  const poll = () => {
    const id = setInterval(() => {
      const p = player.current
      if (!p?.getDuration) return
      const d = p.getDuration()
      if (!d) return
      const now = p.getCurrentTime()
      setDur(d)
      setAt(now)
      setBuffered((p.getVideoLoadedFraction?.() || 0) * 100)
      const pct = Math.round((now / d) * 100)
      setMark(pct)
      ;[25, 50, 75, 95].forEach((step) => {
        if (pct >= step && !reported.current.has(step)) {
          reported.current.add(step)
          report(step)
        }
      })
      savePosition(false)
    }, 1000)
    return () => clearInterval(id)
  }

  /* ------------------------------------------------------------ controls */

  const toggle = () => {
    const p = player.current
    if (!p) return
    if (playing) { p.pauseVideo(); setPlaying(false) } else { p.playVideo(); setPlaying(true) }
  }

  const seekTo = (sec) => {
    const p = player.current
    if (!p) return
    const target = Math.max(0, Math.min(dur || 0, sec))
    p.seekTo(target, true)
    setAt(target)
  }

  const nudge = (by) => seekTo((player.current?.getCurrentTime() || 0) + by)

  const setRate = (r) => {
    player.current?.setPlaybackRate(r)
    setSpeed(r)
    setSpeedOpen(false)
  }

  const setVol = (v) => {
    player.current?.setVolume(v)
    setVolume(v)
    if (v > 0 && muted) { player.current?.unMute(); setMuted(false) }
  }

  const toggleMute = () => {
    const p = player.current
    if (!p) return
    if (muted) { p.unMute(); setMuted(false) } else { p.mute(); setMuted(true) }
  }

  const toggleFull = () => {
    if (document.fullscreenElement) document.exitFullscreen()
    else shell.current?.requestFullscreen?.()
  }

  /* keyboard, bound on our shell because the iframe never gets focus */
  const onKey = (e) => {
    if (state !== 'playing') return
    const k = e.key
    if (k === ' ' || k === 'k') { e.preventDefault(); toggle() }
    else if (k === 'ArrowRight') { e.preventDefault(); nudge(5) }
    else if (k === 'ArrowLeft') { e.preventDefault(); nudge(-5) }
    else if (k === 'j') nudge(-10)
    else if (k === 'l') nudge(10)
    else if (k === 'm') toggleMute()
    else if (k === 'f') toggleFull()
    else if (k === 'ArrowUp') { e.preventDefault(); setVol(Math.min(100, volume + 5)) }
    else if (k === 'ArrowDown') { e.preventDefault(); setVol(Math.max(0, volume - 5)) }
  }

  /* the control bar fades while the video plays and comes back on movement */
  const wake = () => {
    setChromeOn(true)
    clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => { if (playing) setChromeOn(false) }, 2600)
  }

  /* every event that could open a native or provider menu, cancelled */
  const swallow = (e) => { e.preventDefault(); e.stopPropagation() }

  const watermark = `${user?.name || ''} · ${user?.email || ''}`
  const pct = dur ? (at / dur) * 100 : 0

  return (
    <div
      className={`player-shell ${full ? 'is-full' : ''} ${chromeOn ? '' : 'chrome-off'}`}
      ref={shell}
      tabIndex={0}
      onKeyDown={onKey}
      onMouseMove={wake}
      onContextMenu={swallow}
      onAuxClick={swallow}
      onDragStart={swallow}
    >
      {state === 'idle' && (
        <div className="player-cover as-div">
          <button className="player-cover-hit" onClick={() => start(false)}>
            <span className="play-dot" aria-hidden="true" />
            <span className="player-cover-label">{poster || 'Play chapter'}</span>
            <span className="player-cover-note">
              {resume ? `Continue from ${resume.label}` : 'Plays inside the LMS only'}
            </span>
          </button>
          {resume && (
            <div className="player-resume">
              <div className="player-resume-bar"><i style={{ width: `${resume.percent}%` }} /></div>
              <button className="player-restart" onClick={() => start(true)}>Start again</button>
            </div>
          )}
        </div>
      )}

      {state === 'loading' && (
        <div className="player-cover as-div"><span className="player-cover-note">Opening a secure session</span></div>
      )}

      {state === 'error' && (
        <div className="player-cover as-div">
          <span className="player-cover-label">This video will not play</span>
          <span className="player-cover-note">{error}</span>
        </div>
      )}

      <div className={`player-frame ${state === 'playing' ? 'live' : ''}`}>
        <div ref={host} />

        {state === 'playing' && (
          <>
            {/*
              * The shield. It covers the whole iframe, so no pointer event ever
              * reaches YouTube's document and the menu carrying the link cannot
              * be opened from anywhere on the surface. A click on it plays or
              * pauses, which is what a click on a video is expected to do.
              */}
            <div
              className="player-shield"
              onClick={toggle}
              onDoubleClick={toggleFull}
              onContextMenu={swallow}
              onAuxClick={swallow}
              onMouseDown={(e) => { if (e.button !== 0) swallow(e) }}
              onDragStart={swallow}
            />

            {/* carries the viewer's identity into any screen recording, and moves */}
            <div className={`wm wm-slot-${wmSlot}`}>{watermark}</div>

            {!playing && (
              <div className="player-paused" aria-hidden="true">
                <span className="play-dot sm" />
              </div>
            )}
          </>
        )}
      </div>

      {state === 'playing' && (
        <div className="player-bar" onContextMenu={swallow}>
          <div
            className="pb-track"
            onClick={(e) => {
              const r = e.currentTarget.getBoundingClientRect()
              seekTo(((e.clientX - r.left) / r.width) * dur)
            }}
          >
            <i className="pb-buffer" style={{ width: `${buffered}%` }} />
            <i className="pb-play" style={{ width: `${pct}%` }} />
            <i className="pb-knob" style={{ left: `${pct}%` }} />
          </div>

          <div className="pb-row">
            <button className="pb-btn" onClick={toggle} title={playing ? 'Pause (k)' : 'Play (k)'}>
              {playing ? '❚❚' : '▶'}
            </button>
            <button className="pb-btn" onClick={() => nudge(-10)} title="Back 10s (j)">↺10</button>
            <button className="pb-btn" onClick={() => nudge(10)} title="Forward 10s (l)">10↻</button>

            <span className="pb-time mono">{clock(at)} <em>/</em> {clock(dur)}</span>

            <div className="pb-vol">
              <button className="pb-btn" onClick={toggleMute} title="Mute (m)">
                {muted || volume === 0 ? '🔇' : '🔊'}
              </button>
              <input
                type="range" min="0" max="100" value={muted ? 0 : volume}
                onChange={(e) => setVol(Number(e.target.value))}
                aria-label="Volume"
              />
            </div>

            <div className="pb-speed">
              <button className="pb-btn wide" onClick={() => setSpeedOpen((v) => !v)}>{speed}×</button>
              {speedOpen && (
                <div className="pb-speed-menu">
                  {SPEEDS.map((r) => (
                    <button key={r} className={r === speed ? 'on' : ''} onClick={() => setRate(r)}>{r}×</button>
                  ))}
                </div>
              )}
            </div>

            <span className="pb-mark mono">{mark}% watched</span>

            <button className="pb-btn" onClick={toggleFull} title="Fullscreen (f)">
              {full ? '⤢' : '⛶'}
            </button>
          </div>
        </div>
      )}

      {state === 'playing' && (
        <div className="player-foot">
          <span className="muted">Do not share. This session is tied to your account.</span>
        </div>
      )}
    </div>
  )
}
