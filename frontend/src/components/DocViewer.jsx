import { useEffect, useState } from 'react'
import { api, getToken, deviceId } from '../api/client'
import Icon from './Icon'

/**
 * Reading a file without leaving the LMS.
 *
 * A learner who has to download a notebook, find it, and open Jupyter before they can
 * read what their mentor attached will mostly not bother. Every viewer here works on
 * bytes fetched through the authenticated client, because a plain link carries no
 * bearer token and comes back refused.
 */

const BASE = import.meta.env.VITE_API_BASE || '/api'

async function fetchFile(fileId) {
  const res = await fetch(`${BASE}/files/${fileId}`, {
    headers: { Authorization: `Bearer ${getToken()}`, 'X-Device-Id': deviceId() }
  })
  if (!res.ok) throw new Error('That file could not be opened.')
  return res.blob()
}

export function kindOf(filename = '') {
  const ext = filename.split('.').pop()?.toLowerCase()
  if (ext === 'ipynb') return 'notebook'
  if (ext === 'pdf') return 'pdf'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(ext)) return 'image'
  if (['csv', 'tsv'].includes(ext)) return 'table'
  if (['xlsx', 'xls', 'ods'].includes(ext)) return 'sheet'
  if (['zip', 'tar', 'gz', 'tgz', '7z', 'rar'].includes(ext)) return 'archive'
  /*
   * The upload rule accepts every one of these, so the viewer has to as well.
   * A learner handing in a .java file and getting a download prompt instead of a
   * reader is the same failure as refusing the upload, one step later.
   */
  if (['py', 'sql', 'js', 'ts', 'jsx', 'tsx', 'json', 'r', 'rmd', 'txt', 'md', 'yml',
       'yaml', 'sh', 'java', 'c', 'cpp', 'h', 'cs', 'go', 'rb', 'php', 'css', 'html',
       'xml', 'scala', 'jl', 'm', 'rtf'].includes(ext)) return 'code'
  if (['docx', 'doc', 'odt'].includes(ext)) return 'docx'
  return 'none'
}

export default function DocViewer({ fileId, filename, onRunPython }) {
  const [state, setState] = useState({ loading: true })
  const kind = kindOf(filename)

  useEffect(() => {
    let url
    let cancelled = false
    ;(async () => {
      if (kind === 'none') { setState({ loading: false }); return }
      try {
        const blob = await fetchFile(fileId)
        if (cancelled) return

        if (kind === 'pdf' || kind === 'image') {
          url = URL.createObjectURL(blob)
          setState({ loading: false, url })
        } else if (kind === 'sheet') {
          const XLSX = await import('xlsx')
          const wb = XLSX.read(await blob.arrayBuffer(), { type: 'array' })
          const sheets = wb.SheetNames.map((name) => ({
            name,
            rows: XLSX.utils.sheet_to_json(wb.Sheets[name], { header: 1, blankrows: false })
          }))
          setState({ loading: false, sheets })
        } else if (kind === 'archive') {
          setState({ loading: false, archive: true })
        } else if (kind === 'docx') {
          const mammoth = await import('mammoth')
          const { value } = await mammoth.convertToHtml({ arrayBuffer: await blob.arrayBuffer() })
          setState({ loading: false, html: value })
        } else {
          const text = await blob.text()
          if (kind === 'notebook') setState({ loading: false, nb: JSON.parse(text) })
          else if (kind === 'table') setState({ loading: false, rows: parseDelimited(text) })
          else setState({ loading: false, text })
        }
      } catch (e) {
        if (!cancelled) setState({ loading: false, error: e.message })
      }
    })()
    return () => { cancelled = true; if (url) URL.revokeObjectURL(url) }
  }, [fileId, kind])

  if (kind === 'none') {
    return <div className="viewer-none">This file type opens outside the LMS. Download it to read it.</div>
  }
  if (state.loading) return <div className="viewer-none">Opening {filename}</div>
  if (state.error) return <div className="viewer-none">{state.error}</div>

  if (kind === 'pdf') {
    return <object className="viewer-pdf" data={state.url} type="application/pdf">
      <div className="viewer-none">Your browser will not display this inline. Download it instead.</div>
    </object>
  }
  if (kind === 'image') return <img className="viewer-image" src={state.url} alt={filename} />
  if (kind === 'docx') {
    return <div className="viewer-doc" dangerouslySetInnerHTML={{ __html: state.html }} />
  }
  if (kind === 'table') return <TablePreview rows={state.rows} />
  if (kind === 'notebook') return <Notebook nb={state.nb} onRunPython={onRunPython} />
  if (kind === 'sheet') return <SheetPreview sheets={state.sheets} />
  if (kind === 'archive') {
    return (
      <div className="viewer-none">
        An archive. Download it to open the contents, or ask for the files unzipped so they
        can be read here.
      </div>
    )
  }

  return (
    <div className="viewer-code">
      {onRunPython && filename.endsWith('.py') && (
        <button className="btn btn-quiet viewer-run" onClick={() => onRunPython(state.text)}>
          <Icon name="projects" size={14} /> Open in Python
        </button>
      )}
      <pre><code>{state.text}</code></pre>
    </div>
  )
}

/* a preview, not a spreadsheet: the first rows are what tells you the shape */
function parseDelimited(text) {
  const sep = text.indexOf('\t') > -1 && text.indexOf(',') === -1 ? '\t' : ','
  const lines = text.split(/\r?\n/).filter((l) => l.trim())
  return {
    total: lines.length - 1,
    header: splitRow(lines[0], sep),
    rows: lines.slice(1, 51).map((l) => splitRow(l, sep))
  }
}

/** Quoted fields containing the separator are common in exported data. */
function splitRow(line, sep) {
  const out = []
  let cur = '', inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (c === '"') {
      if (inQuotes && line[i + 1] === '"') { cur += '"'; i++ }
      else inQuotes = !inQuotes
    } else if (c === sep && !inQuotes) { out.push(cur); cur = '' }
    else cur += c
  }
  out.push(cur)
  return out
}

function TablePreview({ rows }) {
  return (
    <div className="viewer-table">
      <div className="small muted mb-2">
        {rows.total.toLocaleString('en-IN')} rows, {rows.header.length} columns.
        Showing the first {Math.min(50, rows.rows.length)}.
      </div>
      <div className="viewer-table-scroll">
        <table className="table table-pib mb-0">
          <thead><tr>{rows.header.map((h, i) => <th key={i}>{h}</th>)}</tr></thead>
          <tbody>
            {rows.rows.map((r, i) => (
              <tr key={i}>{r.map((c, j) => <td key={j} className="mono">{c}</td>)}</tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/**
 * A notebook rendered the way it looks in Jupyter: code cells with their outputs,
 * markdown as text. Images in outputs are base64 in the file already, so plots show.
 */
function Notebook({ nb, onRunPython }) {
  const cells = nb?.cells || []
  return (
    <div className="viewer-nb">
      {cells.map((cell, i) => {
        const src = Array.isArray(cell.source) ? cell.source.join('') : cell.source || ''
        if (cell.cell_type === 'markdown') {
          return <div className="nb-md" key={i}>{src}</div>
        }
        return (
          <div className="nb-cell" key={i}>
            <div className="nb-code">
              <span className="nb-label mono">In [{cell.execution_count ?? ' '}]</span>
              <pre><code>{src}</code></pre>
              {onRunPython && (
                <button className="nb-run" onClick={() => onRunPython(src)} title="Run this cell">
                  <Icon name="projects" size={13} /> Run
                </button>
              )}
            </div>
            {(cell.outputs || []).map((out, j) => <Output out={out} key={j} />)}
          </div>
        )
      })}
    </div>
  )
}

function Output({ out }) {
  const text = (v) => (Array.isArray(v) ? v.join('') : v || '')
  if (out.output_type === 'stream') {
    return <pre className="nb-out"><code>{text(out.text)}</code></pre>
  }
  if (out.output_type === 'error') {
    return (
      <pre className="nb-out nb-err">
        <code>{(out.traceback || []).join('\n').replace(/\u001b\[[0-9;]*m/g, '')}</code>
      </pre>
    )
  }
  const data = out.data || {}
  if (data['image/png']) {
    return <img className="nb-img" src={`data:image/png;base64,${text(data['image/png'])}`} alt="output" />
  }
  if (data['text/html']) {
    return <div className="nb-out nb-html" dangerouslySetInnerHTML={{ __html: text(data['text/html']) }} />
  }
  if (data['text/plain']) {
    return <pre className="nb-out"><code>{text(data['text/plain'])}</code></pre>
  }
  return null
}

/**
 * A workbook, one tab per sheet.
 *
 * Nothing is editable here on purpose: this is for reading what somebody handed in, and
 * a viewer that looks editable but is not is worse than one that clearly is not.
 */
function SheetPreview({ sheets }) {
  const [tab, setTab] = useState(0)
  if (!sheets?.length) return <div className="viewer-none">This workbook has no sheets.</div>
  const rows = sheets[tab].rows || []
  const header = rows[0] || []
  const body = rows.slice(1, 101)

  return (
    <div className="viewer-sheet">
      {sheets.length > 1 && (
        <div className="sheet-tabs">
          {sheets.map((s, i) => (
            <button key={s.name} className={i === tab ? 'on' : ''} onClick={() => setTab(i)}>
              {s.name}
            </button>
          ))}
        </div>
      )}
      <div className="table-responsive">
        <table className="table table-pib table-sm mb-0">
          <thead>
            <tr>{header.map((h, i) => <th key={i}>{String(h ?? '')}</th>)}</tr>
          </thead>
          <tbody>
            {body.map((r, i) => (
              <tr key={i}>
                {header.map((_, c) => <td key={c} className="mono small">{String(r[c] ?? '')}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length > 101 && (
        <p className="small muted mt-2">First 100 rows of {rows.length - 1}.</p>
      )}
    </div>
  )
}
