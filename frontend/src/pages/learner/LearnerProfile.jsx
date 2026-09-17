import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import { useToast } from '../../context/ToastContext'
import FaqBlock from '../../components/FaqBlock'
import FileUpload from '../../components/FileUpload'
import { FormSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Tag, TrackTag, fmtDate } from '../../components/Ui'

export default function LearnerProfile() {
  /*
   * `gates` is defaulted rather than read straight off the context.
   *
   * The render below walks gates.form, gates.prereq and gates.call. If the dashboard
   * payload is ever missing that key the whole page throws and React unmounts the tree,
   * which the learner sees as a profile that never loads rather than as an error. A
   * default costs nothing and turns a blank screen into three unticked boxes.
   */
  const { learner, gates = {}, loading } = useLearner()
  const toast = useToast()
  const [resumes, setResumes] = useState([])
  const [url, setUrl] = useState('')
  const [filename, setFilename] = useState('')
  /* an uploaded file, as the alternative to the link box */
  const [file, setFile] = useState(null)

  const load = () => api.get('/learner/resumes').then(setResumes).catch(() => setResumes([]))
  useEffect(() => { load() }, [])

  if (loading || !learner) return <FormSkeleton />

  const addResume = async () => {
    try {
      await api.post('/learner/resumes', {
        url: url.trim() || null,
        fileId: file?.id || null,
        filename: filename.trim() || file?.filename || null
      })
      toast.push('Resume version saved.')
      setUrl(''); setFilename(''); setFile(null)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  return (
    <Page title="My profile" lede="Your details and how you sign in." actions={<TrackTag type={learner.trackType} />}>
      <div className="row g-3">
        <div className="col-lg-6">
          <Card title="Record">
            <dl className="row mb-0" style={{ fontSize: '.9rem' }}>
              <dt className="col-5 text-muted fw-normal">Name</dt><dd className="col-7">{learner.name}</dd>
              <dt className="col-5 text-muted fw-normal">Login ID</dt><dd className="col-7 mono">{learner.email}</dd>
              <dt className="col-5 text-muted fw-normal">Course</dt><dd className="col-7">{learner.bundle || '\u2014'}</dd>
              <dt className="col-5 text-muted fw-normal">Mentor</dt><dd className="col-7">{learner.mentor || 'Being assigned'}</dd>
              <dt className="col-5 text-muted fw-normal">Mentor mail</dt><dd className="col-7 mono">{learner.mentorEmail || '\u2014'}</dd>
              <dt className="col-5 text-muted fw-normal">Joined</dt><dd className="col-7">{fmtDate(learner.joinedOn)}</dd>
            </dl>
            {learner.whatsappGroupLink && (
              <a className="btn btn-navy mt-3" href={learner.whatsappGroupLink} target="_blank" rel="noreferrer">
                Open my WhatsApp group
              </a>
            )}
          </Card>

          <Card title="Onboarding">
            <div className="d-flex flex-wrap gap-2">
              <Tag kind={gates.form ? 'ok' : 'wait'}>Information form</Tag>
              <Tag kind={gates.prereq ? 'ok' : 'wait'}>Prerequisite video</Tag>
              <Tag kind={gates.call ? 'ok' : 'wait'}>
                {learner.trackType === 'BATCH' ? 'Induction' : 'Onboarding call'}
              </Tag>
            </div>
          </Card>

        </div>

        <div className="col-lg-6">
          <Card title="Resume versions" note="Older versions are kept, never overwritten.">
            {resumes.length === 0 ? <Empty title="No resume added yet" /> : (
              <table className="table table-pib mb-3">
                <thead><tr><th>Version</th><th>File</th><th>Added</th><th>Reviewed</th></tr></thead>
                <tbody>
                  {resumes.map((r) => (
                    <tr key={r.id}>
                      <td className="mono">v{r.version}</td>
                      <td>
                        <a
                          href={r.fileId ? `/api/files/${r.fileId}` : r.url}
                          target="_blank" rel="noreferrer"
                        >{r.filename || 'Open'}</a>
                      </td>
                      <td className="mono">{fmtDate(r.uploadedAt)}</td>
                      <td>{r.reviewedByMentor ? <Tag kind="ok">Reviewed</Tag> : <Tag kind="wait">Waiting</Tag>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {/*
              * Upload or link.
              *
              * This box took a URL and nothing else, so handing in a resume meant putting
              * it on Drive and getting the sharing right, and the link stopped working a
              * month later without anybody noticing until review. A pdf or a doc goes
              * straight in now; the link is still there for anybody who prefers it.
              */}
            <div className="row">
              <div className="col-md-6 mb-2">
                <label className="form-label">Upload a pdf or doc</label>
                <FileUpload
                  purpose="RESUME"
                  multiple={false}
                  label="Choose your resume"
                  accept=".pdf,.doc,.docx,.rtf,.odt"
                  onUploaded={(f) => { setFile(f); if (!filename) setFilename(f.filename) }}
                />
              </div>
              <div className="col-md-6 mb-2">
                <label className="form-label">Or a link to it</label>
                <input className="form-control" placeholder="https://..."
                  value={url} onChange={(e) => setUrl(e.target.value)}
                  disabled={!!file} />
                <div className="small text-muted mt-1">
                  {file ? 'Remove the file to use a link instead.' : 'Drive, Dropbox, anywhere it opens without a login.'}
                </div>
              </div>
              <div className="col-md-6 mb-2">
                <label className="form-label">Call it</label>
                <input className="form-control" placeholder="Resume, September"
                  value={filename} onChange={(e) => setFilename(e.target.value)} />
              </div>
            </div>
            <button className="btn btn-pib" onClick={addResume} disabled={!url && !file}>
              Add version
            </button>
            {file && (
              <button className="btn btn-quiet ms-2" onClick={() => setFile(null)}>
                Clear the file
              </button>
            )}
          </Card>
        </div>
      </div>
      <FaqBlock placement="PROFILE" track={learner?.trackType} />
    </Page>
  )
}
