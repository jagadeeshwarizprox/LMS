import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Icon from '../../components/Icon'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Grid, Note, Page, Stat, Switch, Tag } from '../../components/Ui'

const TYPE_LABEL = {
  TEXT: 'Short text', LONG_TEXT: 'Long text', CHOICE: 'Choose one',
  MULTI_CHOICE: 'Choose several', NUMBER: 'Number', DATE: 'Date', MONTH: 'Month',
  EMAIL: 'Email', PHONE: 'Phone', LINK: 'Link', CHECKBOX: 'Tick box'
}

/**
 * What you ask the people you teach.
 *
 * These were eight names in a Java constant, which meant the product decided. They are
 * a starting point now, not a rule: rename them, reorder them, make one optional, or
 * switch it off. Only the required ones gate onboarding, so adding an optional section
 * later never un-finishes somebody who already got through.
 */
export default function FormSections() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [fields, setFields] = useState([])
  const [open, setOpen] = useState(null)          // which section's questions are shown
  const [skills, setSkills] = useState('')

  const load = async () => {
    setRows(await api.get('/super/catalogue/form-sections'))
    setFields(await api.get('/super/catalogue/form-fields').catch(() => []))
    const s = await api.get('/super/catalogue/settings').catch(() => null)
    setSkills(s?.settings?.find((x) => x.key === 'onboarding.skills')?.value || '')
  }
  useEffect(() => { load() }, [])
  if (!rows) return <TableSkeleton />

  const run = async (fn, msg) => {
    try {
      const r = await fn()
      toast.push(r?.reason || msg)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const save = (row) => run(() => api.post('/super/catalogue/form-sections', row), 'Saved.')

  /*
   * The questions.
   *
   * Sections were editable and their questions were not, which is the wrong half: what a
   * section is called matters far less than what it asks. A question carries a stable
   * key that files the answer, so the label can be reworded freely and every answer
   * already given stays attached to it.
   */
  const saveField = (row) => run(() => api.post('/super/catalogue/form-fields', row), 'Saved.')

  const moveField = (sectionKey, id, dir) => {
    const ids = fields.filter((f) => f.sectionKey === sectionKey).map((f) => f.id)
    const i = ids.indexOf(id)
    const j = i + dir
    if (j < 0 || j >= ids.length) return
    ;[ids[i], ids[j]] = [ids[j], ids[i]]
    /* the whole list is sent in its new order, with the untouched sections left where
       they were, because positions are global rather than per section */
    const others = fields.filter((f) => f.sectionKey !== sectionKey).map((f) => f.id)
    run(() => api.post('/super/catalogue/form-fields/reorder', { ids: [...ids, ...others] }),
      'Order saved.')
  }

  const editField = async (sectionKey, row) => {
    const r = await ask({
      title: row ? `Edit "${row.label}"` : 'New question',
      body: row
        ? 'The wording can change freely. What it is filed under cannot, so answers already given stay attached.'
        : 'It is added to the end of this section.',
      fields: [
        { name: 'label', label: 'The question', required: true, value: row?.label || '' },
        {
          name: 'type',
          label: 'Answer type',
          value: row?.type || 'TEXT',
          options: [
            { value: 'TEXT', label: 'Short text' },
            { value: 'LONG_TEXT', label: 'Long text' },
            { value: 'CHOICE', label: 'Choose one' },
            { value: 'MULTI_CHOICE', label: 'Choose several' },
            { value: 'NUMBER', label: 'Number' },
            { value: 'DATE', label: 'Date' },
            { value: 'MONTH', label: 'Month' },
            { value: 'EMAIL', label: 'Email' },
            { value: 'PHONE', label: 'Phone' },
            { value: 'LINK', label: 'Link' },
            { value: 'CHECKBOX', label: 'Tick box' }
          ]
        },
        {
          name: 'options',
          label: 'Choices, one per line',
          multiline: true,
          value: (row?.options || []).join('\n'),
          hint: 'Only used by the two choose types.'
        },
        { name: 'help', label: 'Hint under the question', value: row?.help || '' },
        {
          name: 'showWhen',
          label: 'Only ask this of',
          value: (row?.showWhen || []).join(','),
          hint: 'Blank asks everybody. Or FRESHER, WORKING, CAREER_GAP, comma separated.'
        }
      ],
      confirmLabel: row ? 'Save' : 'Add it'
    })
    if (!r) return
    saveField({
      ...(row || {}),
      sectionKey,
      label: r.label,
      type: r.type,
      help: r.help || null,
      options: String(r.options || '').split('\n').map((x) => x.trim()).filter(Boolean),
      showWhen: String(r.showWhen || '').split(',').map((x) => x.trim().toUpperCase()).filter(Boolean)
    })
  }

  const questionsIn = (sectionKey) => fields.filter((f) => f.sectionKey === sectionKey)

  const move = (id, dir) => {
    const ids = rows.map((r) => r.id)
    const i = ids.indexOf(id)
    const j = i + dir
    if (j < 0 || j >= ids.length) return
    ;[ids[i], ids[j]] = [ids[j], ids[i]]
    run(() => api.post('/super/catalogue/form-sections/reorder', { ids }), 'Order saved.')
  }

  const edit = async (row) => {
    const r = await ask({
      title: `Edit ${row.label}`,
      fields: [
        { name: 'label', label: 'What the learner sees', required: true, value: row.label },
        { name: 'note', label: 'One line under it', value: row.note || '' }
      ],
      confirmLabel: 'Save'
    })
    if (r) save({ ...row, ...r })
  }

  const add = async () => {
    const r = await ask({
      title: 'Add a section',
      body: 'It appears at the end of the form. Only required sections gate onboarding, so '
          + 'adding an optional one never un-finishes anybody.',
      fields: [
        { name: 'label', label: 'What the learner sees', required: true, placeholder: 'Availability' },
        { name: 'note', label: 'One line under it', placeholder: 'Hours a week, and which days' },
        { name: 'required', label: 'Counts towards onboarding', required: true,
          options: [{ value: 'yes', label: 'Required' }, { value: 'no', label: 'Optional' }] }
      ],
      confirmLabel: 'Add'
    })
    if (r) save({ label: r.label, note: r.note, required: r.required === 'yes', active: true })
  }

  const saveSkills = () => run(
    () => api.post('/super/catalogue/settings', { 'onboarding.skills': skills }),
    skills.trim() ? 'Saved. Learners will rate themselves on these.'
                  : 'Cleared. The question is dropped from the form.')

  const list = skills.split(',').map((s) => s.trim()).filter(Boolean)

  const required = rows.filter((f) => f.required).length
  const shown = rows.filter((f) => f.active).length

  return (
    <Page
      title="Information form"
      lede={'The questions every learner answers before their modules open. Add, edit, '
        + 'reorder or remove any of them here.'}
      actions={<button className="btn btn-pib" onClick={add}>Add a section</button>}
    >
      <Note>
        Changing a question never wipes answers already given. It only applies to what is
        asked next, and onboarding counts only the sections still marked required.
      </Note>

      <Grid cols={3} style={{ marginBottom: 18 }}>
        <Stat value={rows.length} label="Sections" icon="intake" />
        <Stat value={required} label="Required" icon="check" hint="these gate onboarding" />
        <Stat value={shown} label="Shown on the form" icon="features"
          hint={`${rows.length - shown} switched off`} />
        <Stat value={list.length} label="Skills rated" icon="projects"
          hint={list.length ? 'learners score themselves' : 'question dropped'} />
      </Grid>

      {rows.length === 0 ? (
        <Empty title="No sections" icon="intake">
          The form would be empty, so onboarding would clear immediately.
        </Empty>
      ) : (
        <Card title="Sections, in the order they are asked">
          {rows.map((f, i) => (
            <div className="q" key={f.id} style={f.active ? undefined : { opacity: .6 }}>
              <span className="drag">{String(i + 1).padStart(2, '0')}</span>
              <div className="qb">
                <b>{f.label}</b>
                <div className="qmeta">
                  {f.note && <span>{f.note}</span>}
                  <span className="mono">{f.key}</span>
                </div>
                <div className="d-flex gap-3 mt-2 flex-wrap">
                  <Switch
                    checked={f.required}
                    label={f.required ? 'Required' : 'Optional'}
                    onChange={() => save({ ...f, required: !f.required })}
                  />
                  <Switch
                    checked={f.active}
                    label={f.active ? 'Shown' : 'Hidden'}
                    onChange={() => save({ ...f, active: !f.active })}
                  />
                </div>
              </div>
              <div style={{ display: 'flex', gap: 4, alignItems: 'flex-start' }}>
                <button className="icon-btn" aria-label="Move up" onClick={() => move(f.id, -1)}>
                  <Icon name="chevron" size={14} className="flip" />
                </button>
                <button className="icon-btn" aria-label="Move down" onClick={() => move(f.id, 1)}>
                  <Icon name="chevron" size={14} />
                </button>
                <button className="btn btn-s" onClick={() => edit(f)}>Edit</button>
                <button
                  className="btn btn-s btn-x"
                  onClick={() => run(() => api.del(`/super/catalogue/form-sections/${f.id}`), 'Done.')}
                >
                  Remove
                </button>
              </div>

              {/*
                * The questions live under the section they belong to rather than in a
                * list of their own, because "what does Education ask" is the question
                * somebody actually has, and a flat list of seventy questions is not an
                * answer to it.
                */}
              <div className="q-questions">
                <button
                  className="q-expand"
                  aria-expanded={open === f.key}
                  onClick={() => setOpen(open === f.key ? null : f.key)}
                >
                  <Icon name="chevron" size={13} className={open === f.key ? 'rot' : ''} />
                  {questionsIn(f.key).length} question{questionsIn(f.key).length === 1 ? '' : 's'}
                </button>

                {open === f.key && (
                  <div className="q-list">
                    {questionsIn(f.key).map((q, qi) => (
                      <div className={`q-row${q.active === false ? ' is-off' : ''}`} key={q.id}>
                        <span className="mono tiny">{qi + 1}</span>
                        <div className="q-row-body">
                          <span>{q.label}</span>
                          <div className="qmeta">
                            <span>{TYPE_LABEL[q.type] || q.type}</span>
                            <span className="mono">{q.key}</span>
                            {q.required && <Tag kind="wait">Required</Tag>}
                            {q.showWhen?.length > 0 && <Tag kind="batch">{q.showWhen.join(', ')}</Tag>}
                            {q.active === false && <Tag>Hidden</Tag>}
                          </div>
                        </div>
                        <div className="q-row-acts">
                          <button className="icon-btn" aria-label="Move up"
                            onClick={() => moveField(f.key, q.id, -1)}>
                            <Icon name="chevron" size={13} className="flip" />
                          </button>
                          <button className="icon-btn" aria-label="Move down"
                            onClick={() => moveField(f.key, q.id, 1)}>
                            <Icon name="chevron" size={13} />
                          </button>
                          <button className="btn btn-s"
                            onClick={() => saveField({ ...q, required: !q.required })}>
                            {q.required ? 'Make optional' : 'Make required'}
                          </button>
                          <button className="btn btn-s"
                            onClick={() => saveField({ ...q, active: q.active === false })}>
                            {q.active === false ? 'Show' : 'Hide'}
                          </button>
                          <button className="btn btn-s" onClick={() => editField(f.key, q)}>Edit</button>
                          <button
                            className="btn btn-s btn-x"
                            onClick={() => run(
                              () => api.del(`/super/catalogue/form-fields/${q.id}`), 'Done.')}
                          >
                            Remove
                          </button>
                        </div>
                      </div>
                    ))}
                    <button className="addq" onClick={() => editField(f.key, null)}>
                      Add a question to {f.label}
                    </button>
                  </div>
                )}
              </div>
            </div>
          ))}
          <button className="addq" onClick={add}>Add a section</button>
        </Card>
      )}

      <Card
        title="Skills learners rate themselves on"
        note="Comma separated. Leave it blank and the question is dropped from the form entirely."
      >
        <div className="row g-2 align-items-end">
          <div className="col-md-9">
            <input
              className="form-control"
              value={skills}
              onChange={(e) => setSkills(e.target.value)}
              placeholder="Leave blank to drop the question"
            />
          </div>
          <div className="col-md-3">
            <button className="btn btn-pib w-100" onClick={saveSkills}>Save</button>
          </div>
        </div>
        {list.length > 0 ? (
          <div className="chips mt-3">
            {list.map((s) => <span className="chip" key={s}>{s}</span>)}
          </div>
        ) : (
          <div className="small muted mt-3">
            Nothing set, so learners are not asked to rate themselves on anything.
          </div>
        )}
      </Card>
    </Page>
  )
}
