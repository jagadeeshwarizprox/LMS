import { useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { Card, Page, Stat, Tag } from '../../components/Ui'

/**
 * The sheet is the source today and the CRM feed is the source later. The importer
 * is the only thing that knows the difference.
 */
/**
 * What happened to each tab in the workbook.
 *
 * The importer used to read only sheets named premium, group or batch, and skip every
 * other tab without a word. A workbook whose tabs were called Sheet1 imported nothing and
 * reported four zeroes, which reads as an empty file rather than as a file that was never
 * opened. Every sheet now says what it was read as, so a zero always has a reason next to it.
 */
function SheetNotes({ sheets }) {
  if (!sheets || sheets.length === 0) return null
  return (
    <ul className="tiny muted" style={{ margin: '0 0 12px', paddingLeft: 18 }}>
      {sheets.map((line, i) => <li key={i}>{line}</li>)}
    </ul>
  )
}

export default function ImportSheet() {
  const toast = useToast()
  const [file, setFile] = useState(null)
  const [dispatchMail, setDispatchMail] = useState(true)
  const [summary, setSummary] = useState(null)
  const [busy, setBusy] = useState(false)
  const [preview, setPreview] = useState(null)

  /*
   * Uploading the sheet used to be a one way door: press the button and whatever the
   * file happened to hold became accounts. The preview answers the only question anyone
   * has first, which is how many of these rows are new, and it writes nothing.
   */
  const look = async () => {
    if (!file) return
    setBusy(true)
    setSummary(null)
    try {
      const fd = new FormData()
      fd.append('file', file)
      setPreview(await api.upload('/admin/import/preview', fd))
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  const run = async () => {
    if (!file) return
    setBusy(true)
    try {
      const fd = new FormData()
      fd.append('file', file)
      fd.append('dispatchMail', String(dispatchMail))
      setSummary(await api.upload('/admin/import', fd))
      setPreview(null)
      toast.push('Import finished.')
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Page title="Import from the record sheet"
      lede="The record sheet is the source. A row added means the payment is confirmed.">
      <Card
        title="Upload ProITbridge_Student_Records.xlsx"
        note="Sheet membership sets the track. A row in the premium sheet creates a premium learner, a row in the group sheet creates a batch learner."
      >
        <div className="row g-2 align-items-end">
          <div className="col-md-6">
            <label className="form-label">Workbook</label>
            <input className="form-control" type="file" accept=".xlsx,.xls"
              onChange={(e) => setFile(e.target.files[0])} />
          </div>
          <div className="col-md-4">
            <label className="d-flex gap-2 align-items-center" style={{ fontSize: '.88rem' }}>
              <input type="checkbox" checked={dispatchMail} onChange={(e) => setDispatchMail(e.target.checked)} />
              Mail credentials to new learners
            </label>
          </div>
          <div className="col-md-2 d-flex gap-2">
            <button className="btn btn-quiet w-100" onClick={look} disabled={!file || busy}>
              {busy ? 'Reading' : 'Preview'}
            </button>
            <button className="btn btn-pib w-100" onClick={run} disabled={!file || busy}>
              Import
            </button>
          </div>
        </div>
        {preview && (
          <div className="preview-panel mt-3">
            <div className="eyebrow mb-2">Nothing has been written yet</div>
            <div className="row g-3 mb-3">
              <div className="col-6 col-lg-3">
                <Stat value={preview.created} label="New, would be created" />
              </div>
              <div className="col-6 col-lg-3">
                <Stat value={preview.upgraded} label="Would move to premium" />
              </div>
              <div className="col-6 col-lg-3">
                <Stat value={preview.existing} label="Already here" />
              </div>
              <div className="col-6 col-lg-3">
                <Stat value={preview.skipped} label="Skipped"
                  tone={preview.skipped ? 'var(--stop)' : undefined} />
              </div>
            </div>
            <SheetNotes sheets={preview.sheets} />
            {preview.created === 0 && preview.upgraded === 0 ? (
              <div className="locked-note">
                Nothing in this file is new. Importing it would change nothing.
              </div>
            ) : (
              <button className="btn btn-pib" onClick={run} disabled={busy}>
                Import {preview.created + preview.upgraded} of {preview.rows.length} rows
              </button>
            )}
          </div>
        )}

        {/*
          * This used to sit here permanently, under every result.
          *
          * An import that read nothing showed four zeroes with a note about payment
          * underneath, and the note was the only sentence on the screen, so a failed
          * import looked like a payment problem. It is a fact about how the sheet is
          * used, so it belongs with the upload box and not with the outcome.
          */}
        <div className="locked-note mt-3">
          Rows reach this sheet only after the sale closes, so a new row is treated as
          payment confirmed. There is no separate payment step.
        </div>
      </Card>

      {summary && (
        <>
          <div className="row g-3 my-1">
            <div className="col-6 col-lg-3"><Stat value={summary.created} label="Accounts created" /></div>
            <div className="col-6 col-lg-3"><Stat value={summary.upgraded} label="Upgraded to premium" /></div>
            <div className="col-6 col-lg-3"><Stat value={summary.existing} label="Already there" /></div>
            <div className="col-6 col-lg-3"><Stat value={summary.skipped} label="Skipped" /></div>
          </div>
          <Card title="Sheet by sheet">
            <SheetNotes sheets={summary.sheets} />
          </Card>

          <Card title="Row by row">
            <div className="table-responsive" style={{ maxHeight: 420 }}>
              <table className="table table-pib mb-0">
                <thead><tr><th>Email</th><th>Result</th><th>Detail</th></tr></thead>
                <tbody>
                  {summary.rows.map((r, i) => (
                    <tr key={i}>
                      <td className="mono">{r.email}</td>
                      <td>
                        <Tag kind={r.status === 'CREATED' ? 'ok' : r.status === 'UPGRADED' ? 'batch' : r.status === 'EXISTS' ? 'wait' : 'stop'}>
                          {r.status}
                        </Tag>
                      </td>
                      <td>{r.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}
    </Page>
  )
}
