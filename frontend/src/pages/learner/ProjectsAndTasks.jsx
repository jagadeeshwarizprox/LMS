import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, StatusTag, fmtDate } from '../../components/Ui'
import Drawer from '../../components/Drawer'
import TaskThread from '../../components/TaskThread'

/**
 * Chapter tasks, and nothing else.
 *
 * This page used to carry a second card called "Projects" where a learner typed up work
 * they had done elsewhere. The guided project system replaced that: projects are now
 * briefed by the org and run through stages with a mentor gate, and they live in their
 * own workspace. Leaving the old card here gave a learner two different things both
 * called Projects, one of which no longer led anywhere, on a page whose sibling in the
 * menu was also called Project.
 */
export default function Tasks() {
  const [assignments, setAssignments] = useState(null)
  const [open, setOpen] = useState(null)

  const load = async () => setAssignments(await api.get('/learner/assignments'))

  useEffect(() => { load() }, [])

  if (!assignments) return <TableSkeleton />

  return (
    <Page title="Tasks"
      lede="What your mentor has set you, what you have handed in, and what they said about it.">
      <Card title="Your tasks"
        note="Open one to read the whole conversation, reply, and send it again after changes.">
        {assignments.length === 0 ? (
          <Empty title="No tasks yet">Tasks appear here once a mentor sets one or you submit from a chapter.</Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Task</th><th>Submitted</th><th>Status</th><th>Score</th><th /></tr></thead>
            <tbody>
              {assignments.map((a) => (
                <tr key={a.id}>
                  <td>{a.title}</td>
                  <td className="mono">{fmtDate(a.submittedAt)}</td>
                  <td><StatusTag status={a.status} /></td>
                  <td className="mono">{a.score ?? '\u2014'}</td>
                  <td className="text-end">
                    <button className="btn btn-quiet btn-sm" onClick={() => setOpen(a)}>
                      {a.status === 'CHANGES' ? 'Changes asked' : 'Open'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Drawer
        open={Boolean(open)}
        title={open?.title || ''}
        subtitle="Your task"
        onClose={() => setOpen(null)}
      >
        {open && <TaskThread assignmentId={open.id} role="LEARNER" onChanged={load} />}
      </Drawer>
    </Page>
  )
}
