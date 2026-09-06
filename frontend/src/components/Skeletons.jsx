/**
 * Skeletons carry the shape of the page they replace, so content swaps into the
 * same footprint and nothing jumps when it arrives.
 */
function Head() {
  return (
    <div className="d-flex align-items-start gap-3 mb-4">
      <div>
        <div className="sk mb-2" style={{ width: 120, height: 11 }} />
        <div className="sk" style={{ width: 210, height: 26 }} />
      </div>
    </div>
  )
}

export function RoadmapSkeleton() {
  return (
    <div className="page">
      <Head />
      <div className="row g-3 mb-4">
        {[0, 1, 2, 3].map((i) => (
          <div className="col-6 col-lg-3" key={i}>
            <div className="sk" style={{ height: 74, borderRadius: 16 }} />
          </div>
        ))}
      </div>
      {[0, 1, 2, 3].map((i) => <div className="sk sk-row" key={i} />)}
    </div>
  )
}

export function TableSkeleton({ rows = 6 }) {
  return (
    <div className="page">
      <Head />
      <div className="card-pib">
        <div className="sk mb-3" style={{ width: 160, height: 14 }} />
        {Array.from({ length: rows }).map((_, i) => (
          <div className="sk mb-2" key={i} style={{ height: 34 }} />
        ))}
      </div>
    </div>
  )
}

export function FormSkeleton() {
  return (
    <div className="page">
      <Head />
      <div className="sk mb-3" style={{ height: 38, borderRadius: 10 }} />
      <div className="card-pib">
        <div className="row g-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div className="col-md-6" key={i}><div className="sk" style={{ height: 58 }} /></div>
          ))}
        </div>
      </div>
    </div>
  )
}
