import { useStudyClock, hhmm } from '../../components/CheckIn'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Stat, fmtDate } from '../../components/Ui'

/** Effort, shown back to the person who put it in. */
export default function MyTime() {
  const { state, checkIn, checkOut } = useStudyClock(true)
  if (!state) return <TableSkeleton />

  const peak = Math.max(60, ...state.last14.map((d) => d.minutes))

  return (
    <Page
      title="My study time"
      lede="How much you have actually studied, week by week."
      actions={
        state.checkedIn
          ? <button className="btn btn-quiet" onClick={checkOut}>Check out</button>
          : <button className="btn btn-pib" onClick={() => checkIn(null)}>Check in</button>
      }
    >
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3"><Stat value={hhmm(state.todayMinutes)} label="Today" /></div>
        <div className="col-6 col-lg-3"><Stat value={hhmm(state.weekMinutes)} label="This week" /></div>
        <div className="col-6 col-lg-3"><Stat value={hhmm(state.totalMinutes)} label="All time" /></div>
        <div className="col-6 col-lg-3">
          <Stat
            value={state.streakDays}
            label="Day streak"
            tone={state.streakDays >= 3 ? 'var(--teal-600)' : undefined}
          />
        </div>
      </div>

      <Card title="Last two weeks" note="Counted from real activity, so a tab left open never counts.">
        <div className="spark">
          {state.last14.map((d) => (
            <div className="spark-col" key={d.day} title={`${fmtDate(d.day)}: ${hhmm(d.minutes)}`}>
              <div className="spark-bar" style={{ height: `${Math.max(3, (d.minutes / peak) * 100)}%` }} />
              <span className="spark-label">{d.day.slice(8)}</span>
            </div>
          ))}
        </div>
      </Card>

      <Card title="Recent days">
        {state.recent.length === 0 ? (
          <Empty title="Nothing logged yet">
            Check in when you sit down to study and this fills itself in.
          </Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Day</th><th>Time</th><th>Sittings</th><th>Chapters touched</th></tr></thead>
            <tbody>
              {state.recent.map((d) => (
                <tr key={d.day}>
                  <td className="mono">{fmtDate(d.day)}</td>
                  <td className="mono">{hhmm(d.minutes)}</td>
                  <td className="mono">{d.sessions}</td>
                  <td className="mono">{d.chapters}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </Page>
  )
}
