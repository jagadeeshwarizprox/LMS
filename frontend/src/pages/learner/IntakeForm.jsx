import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import { useToast } from '../../context/ToastContext'
import { FormSkeleton } from '../../components/Skeletons'
import TabBar from '../../components/TabBar'
import { Card, Empty, Page, Tag } from '../../components/Ui'

/**
 * The information and career form, rendered from configuration.
 *
 * Every question used to be written into this file as JSX. The sections around them were
 * editable in the database, which was the wrong half: what a section is called matters far
 * less than what it asks, and what it asks is exactly what differs between one client and
 * the next. Changing a single question meant shipping code.
 *
 * Now the sections and the questions both come from the server, and this file only knows
 * how to draw a question of each type. Answers were already stored as a free-form map
 * keyed by section, so nothing needed migrating and every answer already given still lines
 * up against the question that asked it.
 *
 * Two behaviours worth knowing while reading it:
 *
 *   Each section saves on its own, so a long form survives being abandoned halfway.
 *
 *   The professional section asks completely different things of a fresher, somebody in
 *   work, and somebody returning from a break. That branch is data too: a question carries
 *   the profile types it applies to and is simply not drawn for the others.
 */

const SKILL_SECTION = 'technical'

function Field({ field, value, onChange }) {
  const id = `f_${field.sectionKey}_${field.key}`
  const common = {
    id,
    name: field.key,
    value: value ?? '',
    onChange,
    className: 'form-control',
    disabled: field.readOnly
  }

  const control = () => {
    switch (field.type) {
      case 'LONG_TEXT':
        return <textarea {...common} rows={3} />
      case 'CHOICE':
        return (
          <select {...common} className="form-select">
            <option value="">Choose one</option>
            {(field.options || []).map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        )
      case 'MULTI_CHOICE':
        return (
          <div className="chips-pick">
            {(field.options || []).map((o) => {
              const picked = String(value || '').split('|').filter(Boolean)
              const on = picked.includes(o)
              return (
                <button
                  type="button"
                  key={o}
                  className={`chip-pick ${on ? 'is-on' : ''}`}
                  aria-pressed={on}
                  onClick={() => onChange({
                    target: {
                      name: field.key,
                      value: (on ? picked.filter((x) => x !== o) : [...picked, o]).join('|')
                    }
                  })}
                >
                  {o}
                </button>
              )
            })}
          </div>
        )
      case 'NUMBER': return <input {...common} type="number" />
      case 'DATE': return <input {...common} type="date" />
      case 'MONTH': return <input {...common} type="month" />
      case 'EMAIL': return <input {...common} type="email" />
      case 'PHONE': return <input {...common} type="tel" />
      case 'LINK': return <input {...common} type="url" placeholder="https://" />
      default: return <input {...common} type="text" />
    }
  }

  /* a checkbox is its own shape: the label sits beside the box, not above it */
  if (field.type === 'CHECKBOX') {
    return (
      <div className="col-12 mb-3">
        <label className="d-flex gap-2 align-items-start" style={{ fontSize: '.88rem' }}>
          <input
            type="checkbox"
            checked={value === 'yes'}
            onChange={(e) => onChange({
              target: { name: field.key, value: e.target.checked ? 'yes' : 'no' }
            })}
          />
          <span>{field.label}</span>
        </label>
        {field.help && <div className="small muted ms-4">{field.help}</div>}
      </div>
    )
  }

  return (
    <div className={field.type === 'LONG_TEXT' ? 'col-12 mb-3' : 'col-md-6 mb-3'}>
      <label className="form-label" htmlFor={id}>
        {field.label}
        {field.required && <span className="req"> *</span>}
      </label>
      {control()}
      {field.help && <div className="small muted mt-1">{field.help}</div>}
      {field.readOnly && (
        <div className="small muted mt-1">From your record, so it is not edited here.</div>
      )}
    </div>
  )
}

export default function IntakeForm() {
  const { learner, loading, reload } = useLearner()
  const toast = useToast()
  const [form, setForm] = useState(null)
  const [sections, setSections] = useState(null)
  const [fields, setFields] = useState([])
  const [active, setActive] = useState(null)
  const [draft, setDraft] = useState({})
  /* empty means the organisation has not set any, so the question is dropped rather
     than shown with nothing in it */
  const [skills, setSkills] = useState([])
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get('/learner/settings/skills')
      .then((r) => setSkills(r.skills || []))
      .catch(() => setSkills([]))

    Promise.all([
      api.get('/learner/intake'),
      api.get('/learner/form-sections').catch(() => []),
      api.get('/learner/form-fields').catch(() => [])
    ]).then(([f, secs, flds]) => {
      const live = (secs || []).filter((s) => s.active !== false)
      setForm(f)
      setSections(live)
      setFields(flds || [])
      const first = live[0]?.key || null
      setActive(first)
      setDraft(first ? (f.sections?.[first] || {}) : {})
    }).catch(() => setSections([]))
  }, [])

  if (loading || !learner || !form || !sections) return <FormSkeleton />

  if (sections.length === 0) {
    return (
      <Page title="Information and career form">
        <Empty title="There is nothing to fill in yet" icon="intake">
          The form has not been set up. Nothing is being asked of you until it is.
        </Empty>
      </Page>
    )
  }

  const done = new Set(form.completedSections || [])
  const total = sections.length
  const set = (e) => setDraft((d) => ({ ...d, [e.target.name]: e.target.value }))

  const switchTo = (key) => {
    setActive(key)
    setDraft(form.sections?.[key] || {})
  }

  const profileType = draft.profileType || form.profileType

  /*
   * The questions for the section on screen. A field with showWhen applies only to some
   * profile types, which is how one section asks a fresher and somebody in work
   * completely different things without being two sections.
   */
  const visible = fields
    .filter((f) => f.sectionKey === active && f.active !== false)
    .filter((f) => !f.showWhen?.length || f.showWhen.includes(profileType))

  const missing = visible
    .filter((f) => f.required && !f.readOnly && !String(draft[f.key] || '').trim())
    .map((f) => f.label)

  const save = async () => {
    if (missing.length > 0) {
      toast.push(`Still needed: ${missing.slice(0, 3).join(', ')}`, 'bad')
      return
    }
    setBusy(true)
    try {
      const updated = await api.put(`/learner/intake/${active}`, draft)
      setForm(updated)
      toast.push('Section saved.')
      const next = sections.find((s) => !new Set(updated.completedSections).has(s.key))
      if (next) switchTo(next.key)
      else { toast.push('Form complete. Your modules are one step closer.'); await reload() }
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  const current = sections.find((s) => s.key === active)

  return (
    <Page
      lede={`${done.size} of ${total} sections saved. Your modules open once the required ones are in.`}
      title="Information and career form"
      actions={done.size === total ? <Tag kind="ok">Complete</Tag> : <Tag kind="wait">In progress</Tag>}
    >
      <p className="text-muted mb-3" style={{ fontSize: '.88rem', maxWidth: 640 }}>
        Each section saves on its own, so you can stop and come back. What you write here
        goes to your mentor and shapes the roadmap you get.
      </p>

      <TabBar
        active={active}
        onChange={switchTo}
        items={sections.map((s) => ({ key: s.key, label: s.label, done: done.has(s.key) }))}
      />

      <Card note={current?.note}>
        <div className="row">
          {visible.length === 0 && !(active === SKILL_SECTION && skills.length > 0) && (
            <div className="col-12">
              <p className="muted mb-0">Nothing is asked in this section.</p>
            </div>
          )}

          {/*
            * The skills grid is the one thing still generated rather than configured, and
            * deliberately: it is one question repeated for whatever an organisation
            * teaches, and that list is already a setting. Forty rows in the question table
            * saying the same thing would be worse.
            */}
          {active === SKILL_SECTION && skills.length > 0 && (
            <>
              <div className="col-12 mb-2">
                <div className="text-muted" style={{ fontSize: '.85rem' }}>
                  Rate each one honestly. Where you are already strong, we route you past it.
                </div>
              </div>
              {skills.map((s) => (
                <Field
                  key={s}
                  field={{
                    sectionKey: active,
                    key: `skill_${s}`,
                    label: s,
                    type: 'CHOICE',
                    options: ['Beginner', 'Intermediate', 'Advanced']
                  }}
                  value={draft[`skill_${s}`]}
                  onChange={set}
                />
              ))}
            </>
          )}

          {visible.map((f) => (
            <Field
              key={f.id || f.key}
              field={f}
              value={f.readOnly && f.key === 'email' ? learner.email : draft[f.key]}
              onChange={set}
            />
          ))}
        </div>

        <div className="d-flex gap-2 mt-2 align-items-center">
          <button className="btn btn-pib" onClick={save} disabled={busy}>
            {busy ? 'Saving' : 'Save this section'}
          </button>
          {missing.length > 0 && (
            <span className="small muted">{missing.length} still needed in this section</span>
          )}
        </div>
      </Card>
    </Page>
  )
}
