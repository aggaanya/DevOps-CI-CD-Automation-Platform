import { useCallback, useEffect, useMemo, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'

type Project = { id: string; name: string; slug: string; description?: string; status: string }
type Repo = { id: string; projectId: string; provider: string; repositoryUrl: string; repositoryName: string; defaultBranch: string; status: string }
type Pipeline = { id: string; projectId: string; name: string; description?: string; status: string }
type Run = { id: string; pipelineId: string; pipelineVersionId: string; commitSha: string; branch: string; triggerType: string; triggeredBy: string; status: string; startedAt?: string; finishedAt?: string; createdAt?: string }
type WorkerResult = { id?: string; jobId: string; pipelineId: string; status: string; workerId: string; repositoryUrl: string; commitSha: string; branch: string; startedAt?: string; completedAt?: string; durationMs?: number; message?: string; receivedAt?: string }
type Stage = { id: string; name: string; status: string; startedAt?: string; finishedAt?: string }
type Job = { id: string; stageId: string; name: string; status: string; workerId?: string; exitCode?: number; startedAt?: string; finishedAt?: string }
type Artifact = { id: string; pipelineRunId: string; jobId?: string; artifactType: string; name: string; locationUrl?: string; imageDigest?: string; createdAt?: string }
type Health = { status: string; service: string; components: Record<string, string>; timestamp?: string }
type Org = { id: string; name: string; slug: string; description?: string; status: string }

const navItems = ['Overview', 'Pipelines', 'Runs', 'Workers', 'Repositories', 'Artifacts', 'Infrastructure', 'Settings']
const iconFor = (item: string) => ({ Overview: '▦', Pipelines: '⌘', Runs: '▷', Workers: '♙', Repositories: '▱', Artifacts: '◇', Infrastructure: '☁', Settings: '⚙' }[item] ?? '▦')
const routeFor = (name: string) => name === 'Overview' ? '#/' : `#/${name.toLowerCase()}`

const api = async <T,>(url: string, options?: RequestInit): Promise<T> => {
  const res = await fetch(url, { headers: { 'Content-Type': 'application/json', ...options?.headers }, ...options })
  if (!res.ok) throw new Error((await res.json().catch(() => null))?.message ?? `Request failed (${res.status})`)
  return res.json() as Promise<T>
}

const statusClass = (status = '') => /success|complete|passed/i.test(status) ? 'success' : /fail|cancel|error/i.test(status) ? 'failed' : 'running'
const toneFor = (value = '') => /up/i.test(value) ? 'success' : /down|degraded/i.test(value) ? 'failed' : 'running'
const isDone = (status = '') => /success|fail|complete|cancel|error/i.test(status)
const shortSha = (sha?: string) => (sha ?? '').slice(0, 7) || '—'
const duration = (start?: string, finish?: string, ms?: number) => ms != null ? `${Math.max(1, Math.round(ms / 60000))}m` : start && finish ? `${Math.max(1, Math.round((Date.parse(finish) - Date.parse(start)) / 60000))}m` : '—'
const relativeTime = (date?: string) => {
  if (!date) return '—'
  const mins = Math.max(0, Math.round((Date.now() - Date.parse(date)) / 60000))
  return mins < 60 ? `${mins || 1} min ago` : mins < 1440 ? `${Math.round(mins / 60)}h ago` : `${Math.round(mins / 1440)}d ago`
}
const pipelineNameOf = (pipelines: Pipeline[], pipelineId: string) => pipelines.find(p => p.id === pipelineId)?.name ?? 'Pipeline'

function App() {
  const initialProject = new URLSearchParams(location.search).get('projectId') ?? ''
  const [route, setRoute] = useState(location.hash || '#/')
  const [projectId, setProjectId] = useState(initialProject)
  const [projectInput, setProjectInput] = useState(initialProject)
  const [orgId, setOrgId] = useState('')
  const [projects, setProjects] = useState<Project[]>([])
  const [orgs, setOrgs] = useState<Org[]>([])
  const [repos, setRepos] = useState<Repo[]>([])
  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [runs, setRuns] = useState<Run[]>([])
  const [artifacts, setArtifacts] = useState<Artifact[]>([])
  const [workers, setWorkers] = useState<WorkerResult[]>([])
  const [health, setHealth] = useState<Health | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [modal, setModal] = useState<'pipeline' | 'run' | 'repo' | 'org' | null>(null)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    const update = () => setRoute(location.hash || '#/')
    addEventListener('hashchange', update)
    return () => removeEventListener('hashchange', update)
  }, [])

  useEffect(() => {
    const load = () => {
      api<Org[]>('/api/v1/organizations').then(setOrgs).catch(() => setOrgs([]))
      api<WorkerResult[]>('/api/v1/executions').then(setWorkers).catch(() => setWorkers([]))
      api<Health>('/api/v1/health').then(setHealth).catch(() => setHealth(null))
    }
    load()
    const timer = setInterval(load, 15000)
    return () => clearInterval(timer)
  }, [])

  const loadProject = useCallback(async (id = projectId) => {
    if (!id) { setRepos([]); setPipelines([]); setRuns([]); setArtifacts([]); return }
    setLoading(true); setError('')
    try {
      const [nextRepos, nextPipelines] = await Promise.all([
        api<Repo[]>(`/api/v1/repositories?projectId=${encodeURIComponent(id)}`),
        api<Pipeline[]>(`/api/v1/pipelines?projectId=${encodeURIComponent(id)}`),
      ])
      const nextRuns = (await Promise.all(nextPipelines.map(p => api<Run[]>(`/api/v1/pipelines/${p.id}/runs`)))).flat()
        .sort((a, b) => Date.parse(b.createdAt ?? '') - Date.parse(a.createdAt ?? ''))
      const nextArtifacts = (await Promise.all(nextRuns.slice(0, 8).map(run =>
        api<Artifact[]>(`/api/v1/artifacts?pipelineRunId=${run.id}`).catch(() => [] as Artifact[])))).flat()
      setRepos(nextRepos); setPipelines(nextPipelines); setRuns(nextRuns); setArtifacts(nextArtifacts)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load project data')
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => { void loadProject() }, [loadProject])

  const selectProject = (id: string) => {
    setProjectId(id); setProjectInput(id)
    const url = new URL(location.href)
    if (id) url.searchParams.set('projectId', id); else url.searchParams.delete('projectId')
    history.replaceState(null, '', url)
  }

  const loadProjects = useCallback(async (org: string) => {
    if (!org.trim()) { setProjects([]); return }
    setOrgId(org.trim())
    setLoading(true); setError('')
    try {
      setProjects(await api<Project[]>(`/api/v1/projects?organizationId=${encodeURIComponent(org.trim())}`))
      setError('')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load projects')
    } finally {
      setLoading(false)
    }
  }, [])

  const submitProjectId = (event: FormEvent) => { event.preventDefault(); if (projectInput.trim()) selectProject(projectInput.trim()) }

  const createOrg = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    try {
      const org = await api<Org>('/api/v1/organizations', {
        method: 'POST',
        body: JSON.stringify({ name: data.get('name'), slug: data.get('slug'), description: data.get('description') }),
      })
      setModal(null); setNotice(`Organization ${org.name} created.`); setOrgId(org.id); setProjects([])
      const next = await api<Org[]>('/api/v1/organizations')
      setOrgs(next)
      await loadProjects(org.id)
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to create organization') }
  }

  const createPipeline = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    try {
      await api('/api/v1/pipelines', {
        method: 'POST',
        body: JSON.stringify({ projectId, name: data.get('name'), description: data.get('description') }),
      })
      setModal(null); setNotice('Pipeline created.'); await loadProject()
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to create pipeline') }
  }

  const createRepo = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    try {
      await api('/api/v1/repositories', {
        method: 'POST',
        body: JSON.stringify({
          projectId, provider: data.get('provider'), repositoryUrl: data.get('repositoryUrl'),
          repositoryName: data.get('repositoryName'), defaultBranch: data.get('defaultBranch'),
        }),
      })
      setModal(null); setNotice('Repository connected.'); await loadProject()
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to connect repository') }
  }

  const triggerRun = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    try {
      const result = await api<{ jobId: string; status: string }>('/api/v1/executions/trigger', {
        method: 'POST',
        body: JSON.stringify({
          repositoryUrl: data.get('repositoryUrl'), commitSha: data.get('commitSha'),
          branch: data.get('branch') || 'main', pipelineFile: data.get('pipelineFile') || 'pipeline.yml',
        }),
      })
      setModal(null); setNotice(`Execution ${result.jobId} is ${result.status}.`)
      location.hash = `#/runs/job/${result.jobId}`
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to trigger execution') }
  }

  const filteredRuns = useMemo(() => runs.filter(run =>
    `${run.branch} ${run.commitSha} ${run.triggeredBy} ${pipelineNameOf(pipelines, run.pipelineId)}`.toLowerCase().includes(query.toLowerCase())), [runs, query, pipelines])
  const activePage = route.split('/')[1] || 'overview'
  const runId = route.startsWith('#/runs/') ? route.slice('#/runs/'.length) : ''

  const pageProps = {
    runs: filteredRuns, allRuns: runs, pipelines, repos, workers, artifacts, health, orgs, query,
    onNewPipeline: () => setModal('pipeline'), onTrigger: () => setModal('run'),
    onRepo: () => setModal('repo'), onAddOrg: () => setModal('org'),
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><b>⌘</b><span>CI/CD Platform</span></div>
        <nav>{navItems.map(item => (
          <a className={activePage === item.toLowerCase() ? 'active' : ''} href={routeFor(item)} key={item}>
            <i>{iconFor(item)}</i>{item}
          </a>
        ))}</nav>
        <div className="sidebar-bottom">
          <p><i className="online" />Control plane connected</p>
          <span>v1.0.0</span>
        </div>
      </aside>

      <main className="content">
        <header className="topbar">
          <label className="search"><b>⌕</b><input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search pipelines, runs, repositories..." /></label>
          <div className="profile">
            <button className="icon-button" aria-label="Notifications">♧</button>
            <span className="avatar">A</span>
            <div><strong>Aanya Aggarwal</strong><small>Admin</small></div>
            <span className="chevron">⌄</span>
          </div>
        </header>

        <div className="page">
          <ProjectSelector
            projectId={projectId} input={projectInput} setInput={setProjectInput}
            orgs={orgs} orgId={orgId} projects={projects}
            onSubmit={submitProjectId} onLoadProjects={loadProjects} onSelect={selectProject} onAddOrg={() => setModal('org')}
          />
          {notice && <div className="notice">{notice}<button onClick={() => setNotice('')}>×</button></div>}
          {error && <div className="inline-error">{error}<button onClick={() => { setError(''); void loadProject() }}>Retry</button></div>}
          {loading && <div className="loading">Loading control-plane data…</div>}
          {runId ? <RunDetails runId={runId} runs={runs} pipelines={pipelines} /> : <PageContent page={activePage} {...pageProps} projectId={projectId} orgId={orgId} onBrowseProjects={loadProjects} />}
        </div>
      </main>

      {modal && (
        <Modal title={modal === 'pipeline' ? 'New Pipeline' : modal === 'repo' ? 'Connect Repository' : modal === 'org' ? 'New Organization' : 'Trigger Run'} onClose={() => setModal(null)}>
          {modal === 'pipeline' ? (
            <form onSubmit={createPipeline}>
              <Field name="name" label="Pipeline name" required />
              <Field name="description" label="Description" />
              <Submit disabled={!projectId} label="Create Pipeline" />
            </form>
          ) : modal === 'repo' ? (
            <form onSubmit={createRepo}>
              <Field name="repositoryName" label="Repository name" required />
              <Field name="repositoryUrl" label="Repository URL" type="url" required />
              <label>Provider<select name="provider" defaultValue="GITHUB"><option>GITHUB</option><option>GITLAB</option><option>BITBUCKET</option></select></label>
              <Field name="defaultBranch" label="Default branch" defaultValue="main" required />
              <Submit disabled={!projectId} label="Connect Repository" />
            </form>
          ) : modal === 'org' ? (
            <form onSubmit={createOrg}>
              <Field name="name" label="Organization name" required />
              <Field name="slug" label="Slug" required />
              <Field name="description" label="Description" />
              <Submit label="Create Organization" />
            </form>
          ) : (
            <form onSubmit={triggerRun}>
              <label>Repository
                <select name="repositoryUrl" required defaultValue="">
                  <option value="" disabled>Select a repository</option>
                  {repos.map(repo => <option value={repo.repositoryUrl} key={repo.id}>{repo.repositoryName}</option>)}
                </select>
              </label>
              <Field name="commitSha" label="Commit SHA" required />
              <Field name="branch" label="Branch" defaultValue="main" required />
              <Field name="pipelineFile" label="Pipeline file" defaultValue="pipeline.yml" required />
              <Submit disabled={!repos.length} label="Trigger Run" />
            </form>
          )}
        </Modal>
      )}
    </div>
  )
}

function ProjectSelector({ projectId, input, setInput, orgs, orgId, projects, onSubmit, onLoadProjects, onSelect, onAddOrg }: {
  projectId: string; input: string; setInput: (v: string) => void
  orgs: Org[]; orgId: string; projects: Project[]
  onSubmit: (e: FormEvent) => void; onLoadProjects: (org: string) => void
  onSelect: (id: string) => void; onAddOrg: () => void
}) {
  return (
    <section className="project-selector">
      <form onSubmit={onSubmit}>
        <label>Project ID<input value={input} onChange={e => setInput(e.target.value)} placeholder="Paste a project UUID" /></label>
        <button className="secondary-button" type="submit">Load project</button>
      </form>
      <form onSubmit={e => { e.preventDefault(); onLoadProjects(orgId) }}>
        <label>Organization
          <select value={orgId} onChange={e => onLoadProjects(e.target.value)}>
            <option value="">Select an organization</option>
            {orgs.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
          </select>
        </label>
        <button className="text-button" type="submit">Browse projects</button>
        <button className="text-button" type="button" onClick={onAddOrg}>+ New org</button>
      </form>
      {projects.length > 0 && (
        <label className="project-pick">Project
          <select aria-label="Select project" value={projectId} onChange={e => onSelect(e.target.value)}>
            <option value="">Select a project</option>
            {projects.map(p => <option value={p.id} key={p.id}>{p.name}</option>)}
          </select>
        </label>
      )}
    </section>
  )
}

function PageContent(props: {
  page: string; runs: Run[]; allRuns: Run[]; pipelines: Pipeline[]; repos: Repo[]; workers: WorkerResult[]
  artifacts: Artifact[]; health: Health | null; orgs: Org[]; query: string
  projectId: string; orgId: string
  onNewPipeline: () => void; onTrigger: () => void; onRepo: () => void; onAddOrg: () => void; onBrowseProjects: (org: string) => void
}) {
  const { page, runs, allRuns, pipelines, repos, workers, artifacts, health, orgs, query, projectId, orgId, onNewPipeline, onTrigger, onRepo, onAddOrg, onBrowseProjects } = props
  if (page === 'repositories') return <ReposPage repos={repos} query={query} onRepo={onRepo} />
  if (page === 'workers') return <WorkersPage workers={workers} query={query} />
  if (page === 'runs') return <RunsPanel runs={runs} pipelines={pipelines} full />
  if (page === 'artifacts') return <ArtifactsPage artifacts={artifacts} pipelines={pipelines} runs={allRuns} query={query} />
  if (page === 'infrastructure') return <InfrastructurePage health={health} />
  if (page === 'settings') return <SettingsPage orgs={orgs} orgId={orgId} projectId={projectId} onAddOrg={onAddOrg} onBrowseProjects={onBrowseProjects} />
  if (page === 'pipelines') return <PipelinesPage pipelines={pipelines} runs={allRuns} query={query} onNewPipeline={onNewPipeline} />
  return <OverviewPage runs={runs} allRuns={allRuns} pipelines={pipelines} onTrigger={onTrigger} onRepo={onRepo} />
}

function OverviewPage({ runs, allRuns, pipelines, onTrigger, onRepo }: {
  runs: Run[]; allRuns: Run[]; pipelines: Pipeline[]; onTrigger: () => void; onRepo: () => void
}) {
  const success = allRuns.filter(r => statusClass(r.status) === 'success').length
  const failure = allRuns.filter(r => statusClass(r.status) === 'failed').length
  const running = allRuns.filter(r => statusClass(r.status) === 'running').length
  const total = allRuns.length || 1
  const avg = allRuns.length ? Math.round(allRuns.reduce((n, r) => n + (r.startedAt && r.finishedAt ? (Date.parse(r.finishedAt) - Date.parse(r.startedAt)) / 60000 : 0), 0) / allRuns.length) : null
  const metrics: { label: string; value: string; detail: string; tone: 'success' | 'failed' | 'neutral' | 'blue' }[] = [
    { label: 'Successful runs', value: String(success), detail: 'Completed pipeline runs', tone: 'success' },
    { label: 'Failed runs', value: String(failure), detail: 'Runs needing attention', tone: 'failed' },
    { label: 'Average duration', value: avg != null ? `${avg}m` : '—', detail: 'Across recent runs', tone: 'neutral' },
    { label: 'Total pipelines', value: String(pipelines.length), detail: 'Active in this project', tone: 'blue' },
  ]
  return (
    <>
      <section className="page-heading">
        <div><h1>Overview</h1><p>Build, test and deploy your applications automatically</p></div>
        <button className="primary-button" onClick={onTrigger}><b>+</b> Trigger Run</button>
      </section>
      <section className="metrics">
        {metrics.map(m => (
          <article className="metric-card" key={m.label}>
            <div className={`metric-icon ${m.tone}`}>{m.tone === 'success' ? '✓' : m.tone === 'failed' ? '!' : m.tone === 'blue' ? '▷' : '◷'}</div>
            <div><p>{m.label}</p><h2>{m.value}</h2><small>{m.detail}</small></div>
          </article>
        ))}
      </section>
      <RunsPanel runs={runs} pipelines={pipelines} />
      <section className="bottom-grid">
        <article className="panel status-panel">
          <div className="panel-header"><div><h2>Pipeline Status</h2><p>Distribution of pipeline run results (last 30 days)</p></div></div>
          <div className="distribution">
            <span className="success" style={{ width: `${success / total * 100}%` }} />
            <span className="failed" style={{ width: `${failure / total * 100}%` }} />
            <span className="running" style={{ width: `${running / total * 100}%` }} />
          </div>
          <div className="legend">
            <span><i className="success" />Successful <b>{Math.round(success / total * 100)}%</b></span>
            <span><i className="failed" />Failed <b>{Math.round(failure / total * 100)}%</b></span>
            <span><i className="running" />Running <b>{Math.round(running / total * 100)}%</b></span>
          </div>
        </article>
        <article className="panel actions-panel">
          <div className="panel-header"><div><h2>Quick Actions</h2><p>Common tasks to get started</p></div></div>
          <div className="quick-actions">
            <button onClick={onTrigger}><span>▷</span><div><strong>Trigger Run</strong><small>Run a pipeline now</small></div><b>›</b></button>
            <button onClick={onRepo}><span>▱</span><div><strong>Connect Repo</strong><small>Link a repository</small></div><b>›</b></button>
            <a href="#/workers"><span>♙</span><div><strong>Manage Workers</strong><small>View worker status</small></div><b>›</b></a>
            <a href="#/runs"><span>▤</span><div><strong>View Logs</strong><small>Check run logs</small></div><b>›</b></a>
          </div>
        </article>
      </section>
    </>
  )
}

function PipelinesPage({ pipelines, runs, query, onNewPipeline }: {
  pipelines: Pipeline[]; runs: Run[]; query: string; onNewPipeline: () => void
}) {
  const q = query.trim().toLowerCase()
  const visible = pipelines.filter(p => `${p.name} ${p.description ?? ''}`.toLowerCase().includes(q))
  return (
    <>
      <section className="page-heading">
        <div><h1>Pipelines</h1><p>Build, test and deploy your applications automatically</p></div>
        <button className="primary-button" onClick={onNewPipeline}><b>+</b> New Pipeline</button>
      </section>
      {visible.length ? (
        <section className="pipelines-grid">
          {visible.map(p => {
            const count = runs.filter(r => r.pipelineId === p.id).length
            const latest = runs.find(r => r.pipelineId === p.id)
            return (
              <article className="panel pipeline-card" key={p.id}>
                <h3>{p.name}</h3>
                <p>{p.description || 'No description provided.'}</p>
                <div className="card-row">
                  <span className={`status-badge ${statusClass(p.status)}`}><i />{p.status}</span>
                  <span>{count} run{count === 1 ? '' : 's'} · latest {latest ? relativeTime(latest.createdAt) : '—'}</span>
                </div>
                <div className="card-row">
                  {latest && <span className="latest-run">Last run: {latest.status}</span>}
                  <a href="#/runs">View runs →</a>
                </div>
              </article>
            )
          })}
        </section>
      ) : (
        <Empty text={pipelines.length ? 'No pipelines match your search.' : 'No pipelines yet for this project. Create your first pipeline to get started.'} />
      )}
    </>
  )
}

function RunsPanel({ runs, pipelines, full = false }: { runs: Run[]; pipelines: Pipeline[]; full?: boolean }) {
  return (
    <section className="panel runs-panel">
      <div className="panel-header">
        <div><h2>{full ? 'Pipeline Runs' : 'Recent Pipeline Runs'}</h2><p>{full ? 'Filter with the search field above' : 'Latest runs across all pipelines'}</p></div>
        {!full && <a href="#/runs">View all runs →</a>}
      </div>
      {runs.length ? (
        <div className="table-scroll">
          <table>
            <thead>
              <tr><th>Status</th><th>Pipeline</th><th>Branch</th><th>Commit</th><th>Triggered by</th><th>Duration</th><th>Started at</th><th /></tr>
            </thead>
            <tbody>
              {runs.slice(0, full ? 50 : 6).map(run => (
                <tr key={run.id} className="clickable-row" onClick={() => { location.hash = `#/runs/${run.id}` }}>
                  <td><span className={`status-badge ${statusClass(run.status)}`}><i />{run.status}</span></td>
                  <td><strong>{pipelineNameOf(pipelines, run.pipelineId)}</strong></td>
                  <td><span className="branch">⌘ {run.branch || 'main'}</span></td>
                  <td><code>{shortSha(run.commitSha)}</code></td>
                  <td>{run.triggeredBy || run.triggerType || 'manual'}</td>
                  <td>{duration(run.startedAt, run.finishedAt)}</td>
                  <td>{relativeTime(run.createdAt)}</td>
                  <td>›</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty text="No pipeline runs yet. Select a project or trigger your first run." />
      )}
    </section>
  )
}

function RunDetails({ runId, runs, pipelines }: { runId: string; runs: Run[]; pipelines: Pipeline[] }) {
  const [run, setRun] = useState<Run | null>(runs.find(r => r.id === runId) ?? null)
  const [stages, setStages] = useState<Stage[]>([])
  const [jobs, setJobs] = useState<Job[]>([])
  const [worker, setWorker] = useState<WorkerResult | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!runId.startsWith('job/')) return
    const jobId = runId.slice(4)
    setWorker(null); setError('')
    const poll = async () => {
      try {
        const value = await api<WorkerResult>(`/api/v1/executions/${jobId}`)
        setWorker(value)
        return isDone(value.status)
      } catch { return false }
    }
    const timer = setInterval(() => { void poll().then(done => { if (done) clearInterval(timer) }) }, 6000)
    void poll().then(done => { if (done) clearInterval(timer) })
    return () => clearInterval(timer)
  }, [runId])

  useEffect(() => {
    if (runId.startsWith('job/')) { setRun(null); setStages([]); setJobs([]); return }
    setRun(runs.find(r => r.id === runId) ?? null)
    const load = async () => {
      try {
        const value = await api<Run>(`/api/v1/runs/${runId}`)
        setRun(value)
        const nextStages = await api<Stage[]>(`/api/v1/runs/${runId}/stages`)
        setStages(nextStages)
        const groups = await Promise.all(nextStages.map(stage =>
          api<Job[]>(`/api/v1/runs/${runId}/stages/${stage.id}/jobs`).catch(() => [] as Job[])))
        setJobs(groups.flat())
        setError('')
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Unable to load run')
      }
    }
    void load()
  }, [runId, runs])

  if (worker) {
    return (
      <>
        <a className="back-link" href="#/runs">← All runs</a>
        <SimpleList title={`Execution ${worker.jobId}`} subtitle={`${worker.repositoryUrl} · ${worker.branch || 'main'}`}>
          <div className="detail-summary">
            <span className={`status-badge ${statusClass(worker.status)}`}><i />{worker.status}</span>
            <p>Commit: <code>{shortSha(worker.commitSha)}</code></p>
            <p>Worker: {worker.workerId || 'Waiting for assignment'}</p>
            <p>Duration: {duration(worker.startedAt, worker.completedAt, worker.durationMs)}</p>
            {worker.message && <pre>{worker.message}</pre>}
          </div>
        </SimpleList>
      </>
    )
  }

  return (
    <>
      <a className="back-link" href="#/runs">← All runs</a>
      <SimpleList
        title={run ? pipelineNameOf(pipelines, run.pipelineId) : 'Pipeline run'}
        subtitle={run ? `${run.branch || 'main'} · ${shortSha(run.commitSha)}` : error || 'Loading run details…'}
      >
        {runId.startsWith('job/') && !worker ? (
          <Empty text="No execution result recorded yet. The worker result appears once the execution finishes — the page auto-refreshes." />
        ) : error && !run ? (
          <Empty text={error} />
        ) : (
          <><div className="detail-summary">
            {run && (
              <>
                <span className={`status-badge ${statusClass(run.status)}`}><i />{run.status}</span>
                <p>Triggered by: {run.triggeredBy || run.triggerType || 'manual'}</p>
                <p>Commit: <code>{shortSha(run.commitSha)}</code></p>
                <p>Started: {run.startedAt ? new Date(run.startedAt).toLocaleString() : '—'}</p>
                <p>Duration: {duration(run.startedAt, run.finishedAt)}</p>
              </>
            )}
          </div>
          <div className="timeline">
            {stages.length ? stages.map(stage => (
              <div className="stage" key={stage.id}>
                <span className={`status-badge ${statusClass(stage.status)}`}><i />{stage.status}</span>
                <div>
                  <strong>{stage.name}</strong>
                  <small>{duration(stage.startedAt, stage.finishedAt)}</small>
                  {jobs.filter(job => job.stageId === stage.id).map(job => (
                    <p key={job.id}>{job.name} · {job.status}{job.workerId ? ` · ${job.workerId}` : ''}</p>
                  ))}
                </div>
              </div>
            )) : !error ? <Empty text="No stages recorded for this run." /> : null}
          </div></>
        )}
      </SimpleList>
    </>
  )
}

function ReposPage({ repos, query, onRepo }: { repos: Repo[]; query: string; onRepo: () => void }) {
  const q = query.trim().toLowerCase()
  const visible = repos.filter(r => `${r.repositoryName} ${r.repositoryUrl}`.toLowerCase().includes(q))
  return (
    <SimpleList title="Repositories" subtitle="Connected source repositories" action="Connect Repository" onAction={onRepo}>
      {visible.length ? visible.map(r => (
        <div className="list-row" key={r.id}>
          <strong>{r.repositoryName}</strong>
          <span>{r.provider} · {(r.defaultBranch || 'main')}</span>
          <a href={r.repositoryUrl} target="_blank" rel="noreferrer">Open ↗</a>
        </div>
      )) : <Empty text={repos.length ? 'No repositories match your search.' : 'No repositories connected for this project.'} />}
    </SimpleList>
  )
}

function WorkersPage({ workers, query }: { workers: WorkerResult[]; query: string }) {
  const q = query.trim().toLowerCase()
  const visible = workers.filter(w => `${w.jobId} ${w.workerId} ${w.status}`.toLowerCase().includes(q))
  return (
    <SimpleList title="Workers" subtitle="Worker activity reported by completed executions">
      {visible.length ? visible.map(w => (
        <div className="list-row clickable-row" key={w.jobId} onClick={() => { location.hash = `#/runs/job/${w.jobId}` }}>
          <span className={`status-badge ${statusClass(w.status)}`}><i />{w.status}</span>
          <strong>{w.workerId || 'Unassigned'}</strong>
          <span>{w.jobId} · {relativeTime(w.receivedAt)}</span>
        </div>
      )) : <Empty text={workers.length ? 'No workers match your search.' : 'No worker results recorded yet. Executions appear here once a worker reports back.'} />}
    </SimpleList>
  )
}

function ArtifactsPage({ artifacts, pipelines, runs, query }: { artifacts: Artifact[]; pipelines: Pipeline[]; runs: Run[]; query: string }) {
  const q = query.trim().toLowerCase()
  const runOf = (id: string) => runs.find(r => r.id === id)
  const visible = artifacts.filter(a => `${a.name} ${a.artifactType}`.toLowerCase().includes(q))
  return (
    <SimpleList title="Artifacts" subtitle="Build artifacts produced by recent runs">
      {visible.length ? visible.map(a => {
        const run = runOf(a.pipelineRunId)
        return (
          <div className="list-row" key={a.id}>
            <strong>{a.name}</strong>
            <span>{a.artifactType}{run ? ` · ${pipelineNameOf(pipelines, run.pipelineId)}` : ''}</span>
            {a.locationUrl ? <a href={a.locationUrl} target="_blank" rel="noreferrer">Open ↗</a> : a.imageDigest ? <code>{shortSha(a.imageDigest)}</code> : <span>{a.id.slice(0, 8)}</span>}
          </div>
        )
      }) : <Empty text={artifacts.length ? 'No artifacts match your search.' : 'No artifacts recorded for this project. Artifacts appear after a run publishes them.'} />}
    </SimpleList>
  )
}

function InfrastructurePage({ health }: { health: Health | null }) {
  const components = health?.components ?? {}
  return (
    <>
      <section className="page-heading">
        <div><h1>Infrastructure</h1><p>Health of the services behind the control plane</p></div>
      </section>
      {health ? (
        <section className="health-grid">
          <article className="panel health-card">
            <h3>Control plane</h3>
            <span className={`status-badge ${toneFor(health.status)}`}><i />{health.status}</span>
            <p>{health.service}</p>
            <small>Checked {relativeTime(health.timestamp)}</small>
          </article>
          {Object.entries(components).map(([name, value]) => (
            <article className="panel health-card" key={name}>
              <h3>{name}</h3>
              <span className={`status-badge ${toneFor(value)}`}><i />{value}</span>
              <p>Component of the execution topology</p>
            </article>
          ))}
        </section>
      ) : (
        <Empty text="Backend health endpoint is unreachable. Start the backend (docker compose) and try again." />
      )}
    </>
  )
}

function SettingsPage({ orgs, orgId, projectId, onAddOrg, onBrowseProjects }: {
  orgs: Org[]; orgId: string; projectId: string; onAddOrg: () => void; onBrowseProjects: (org: string) => void
}) {
  return (
    <>
      <section className="page-heading">
        <div><h1>Settings</h1><p>Organization, project and API configuration</p></div>
        <button className="primary-button" onClick={onAddOrg}><b>+</b> New Organization</button>
      </section>
      <section className="panel simple-panel">
        <div className="panel-header"><div><h2>Current context</h2><p>What the dashboard is currently showing</p></div></div>
        <div className="list-row"><strong>Project ID</strong><span>{projectId || 'Not selected'}</span></div>
        <div className="list-row"><strong>Organization ID</strong><span>{orgId || 'Not selected'}</span></div>
        <div className="list-row"><strong>API base path</strong><span>/api/v1</span></div>
        <div className="list-row"><strong>Frontend route</strong><span>hash-based (no server rewrites required)</span></div>
      </section>
      <section className="panel simple-panel" style={{ marginTop: 21 }}>
        <div className="panel-header"><div><h2>Organizations</h2><p>Organizations available on this control plane</p></div></div>
        {orgs.length ? orgs.map(o => (
          <div className="list-row" key={o.id}>
            <strong>{o.name}</strong>
            <span>{o.slug} · {o.status}</span>
            <button className="text-button" onClick={() => onBrowseProjects(o.id)}>Browse projects</button>
          </div>
        )) : <Empty text="No organizations exist yet. Create one to start browsing projects." />}
      </section>
    </>
  )
}

function SimpleList({ title, subtitle, action, onAction, children }: {
  title: string; subtitle: string; action?: string; onAction?: () => void; children: ReactNode
}) {
  return (
    <section className="panel simple-panel">
      <div className="panel-header">
        <div><h2>{title}</h2><p>{subtitle}</p></div>
        {action && <button className="primary-button" onClick={onAction}>{action}</button>}
      </div>
      {children}
    </section>
  )
}

function Empty({ text }: { text: string }) {
  return (
    <div className="empty">
      <span className="empty-icon">⌘</span>
      <h3>Nothing to display</h3>
      <p>{text}</p>
    </div>
  )
}

function Field({ name, label, type = 'text', defaultValue, required }: {
  name: string; label: string; type?: string; defaultValue?: string; required?: boolean
}) {
  return <label>{label}<input name={name} type={type} defaultValue={defaultValue} required={required} /></label>
}

function Submit({ label, disabled }: { label: string; disabled?: boolean }) {
  return <button className="primary-button modal-submit" disabled={disabled}>{label}</button>
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-backdrop" role="presentation">
      <section className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <header><h2>{title}</h2><button onClick={onClose} aria-label="Close">×</button></header>
        {children}
      </section>
    </div>
  )
}

export default App