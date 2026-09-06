import { useCallback, useEffect, useRef, useState } from 'react'
import { getToken, deviceId } from '../api/client'
import Icon from './Icon'

/**
 * Python, running in the learner's own browser.
 *
 * The obvious build is a sandbox on our server, and it is the wrong one. Arbitrary code
 * from every learner on a machine that also holds the database is a problem you have to
 * keep solving: containers, timeouts, memory caps, network egress, someone eventually
 * getting out. Pyodide runs CPython compiled to WebAssembly inside the browser tab, so
 * the only machine at risk is theirs, and there is nothing to escape into.
 *
 * What that costs: about ten megabytes on first use, from a CDN, and no threads or
 * sockets. For teaching pandas, numpy and matplotlib, none of that matters.
 */

const PYODIDE = 'https://cdn.jsdelivr.net/pyodide/v0.26.2/full/'
const BASE = import.meta.env.VITE_API_BASE || '/api'

let loading = null
function loadPyodide() {
  if (loading) return loading
  loading = new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = `${PYODIDE}pyodide.js`
    s.onload = async () => {
      try {
        const py = await window.loadPyodide({ indexURL: PYODIDE })
        resolve(py)
      } catch (e) { reject(e) }
    }
    s.onerror = () => reject(new Error('Python could not be downloaded. Check your connection.'))
    document.head.appendChild(s)
  })
  return loading
}

const STARTER = `# Anything you write here runs in your browser.
# pandas, numpy and matplotlib are available.

import pandas as pd

df = pd.DataFrame({"region": ["North", "South", "East"], "sales": [120, 340, 210]})
print(df)
print("total:", df["sales"].sum())
`

export default function PythonRunner({ initialCode, datasets = [], onClose }) {
  const [code, setCode] = useState(initialCode || STARTER)
  const [out, setOut] = useState([])
  const [status, setStatus] = useState('cold')   // cold | booting | ready | running
  const [plot, setPlot] = useState(null)
  const py = useRef(null)
  const outRef = useRef(null)

  useEffect(() => { if (initialCode) setCode(initialCode) }, [initialCode])
  useEffect(() => { outRef.current?.scrollTo(0, outRef.current.scrollHeight) }, [out])

  const boot = useCallback(async () => {
    if (py.current) return py.current
    setStatus('booting')
    setOut([{ kind: 'note', text: 'Downloading Python. This happens once, then it is instant.' }])
    try {
      const p = await loadPyodide()
      await p.loadPackage(['pandas', 'numpy', 'matplotlib'])
      p.setStdout({ batched: (t) => setOut((o) => [...o, { kind: 'out', text: t }]) })
      p.setStderr({ batched: (t) => setOut((o) => [...o, { kind: 'err', text: t }]) })
      py.current = p
      setStatus('ready')
      setOut([{ kind: 'note', text: 'Python is ready. pandas, numpy and matplotlib are loaded.' }])
      return p
    } catch (e) {
      setStatus('cold')
      setOut([{ kind: 'err', text: e.message }])
      throw e
    }
  }, [])

  /**
   * A dataset attached to the topic is written into Python's own filesystem, so
   * pd.read_csv("retail-sales.csv") works with the filename the learner can see.
   */
  const mount = async (p, d) => {
    const res = await fetch(`${BASE}/files/${d.fileId}`, {
      headers: { Authorization: `Bearer ${getToken()}`, 'X-Device-Id': deviceId() }
    })
    if (!res.ok) throw new Error(`${d.filename} could not be loaded.`)
    const buf = new Uint8Array(await res.arrayBuffer())
    p.FS.writeFile(d.filename, buf)
    return d.filename
  }

  const run = async () => {
    let p
    try { p = await boot() } catch { return }
    setStatus('running')
    setPlot(null)
    try {
      for (const d of datasets) {
        try { await mount(p, d) } catch (e) {
          setOut((o) => [...o, { kind: 'err', text: e.message }])
        }
      }
      await p.runPythonAsync(code)

      /* a chart is drawn to a buffer rather than a window, so pull it out if there
         is one and show it under the output */
      const png = await p.runPythonAsync(`
import io, base64
try:
    import matplotlib.pyplot as _plt
    if _plt.get_fignums():
        _buf = io.BytesIO()
        _plt.savefig(_buf, format="png", bbox_inches="tight", dpi=110)
        _plt.close("all")
        base64.b64encode(_buf.getvalue()).decode()
    else:
        ""
except Exception:
    ""
`)
      if (png) setPlot(`data:image/png;base64,${png}`)
    } catch (e) {
      setOut((o) => [...o, { kind: 'err', text: String(e.message || e).split('\n').slice(-12).join('\n') }])
    } finally {
      setStatus('ready')
    }
  }

  const reset = () => {
    py.current = null
    loading = null
    setOut([])
    setPlot(null)
    setStatus('cold')
  }

  return (
    <div className="runner">
      <div className="runner-bar">
        <span className={`runner-dot ${status}`} />
        <span className="runner-status">
          {status === 'cold' ? 'Not started'
            : status === 'booting' ? 'Starting'
            : status === 'running' ? 'Running' : 'Ready'}
        </span>
        <span className="runner-actions">
          <button className="btn btn-quiet" onClick={() => { setOut([]); setPlot(null) }}>Clear</button>
          <button className="btn btn-quiet" onClick={reset} title="Forget everything and start again">
            Restart
          </button>
          <button className="btn btn-pib" onClick={run} disabled={status === 'running' || status === 'booting'}>
            <Icon name="projects" size={14} /> Run
          </button>
        </span>
      </div>

      {datasets.length > 0 && (
        <div className="runner-files">
          Ready to read by filename: {datasets.map((d) => (
            <code key={d.fileId}>{d.filename}</code>
          ))}
        </div>
      )}

      <textarea
        className="runner-code"
        value={code}
        spellCheck={false}
        onChange={(e) => setCode(e.target.value)}
        onKeyDown={(e) => {
          /* Ctrl-Enter runs, the way every notebook does it */
          if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); run() }
          if (e.key === 'Tab') {
            e.preventDefault()
            const el = e.target
            const at = el.selectionStart
            setCode(code.slice(0, at) + '    ' + code.slice(el.selectionEnd))
            requestAnimationFrame(() => { el.selectionStart = el.selectionEnd = at + 4 })
          }
        }}
      />

      <div className="runner-out" ref={outRef}>
        {out.length === 0 && !plot ? (
          <span className="muted">Output appears here. Ctrl and Enter runs.</span>
        ) : (
          <>
            {out.map((o, i) => <pre className={`runner-line ${o.kind}`} key={i}>{o.text}</pre>)}
            {plot && <img className="runner-plot" src={plot} alt="chart" />}
          </>
        )}
      </div>

      <div className="runner-note">
        This runs entirely in your browser. Nothing is sent to a server, and nothing you
        write here is saved.
      </div>
    </div>
  )
}
