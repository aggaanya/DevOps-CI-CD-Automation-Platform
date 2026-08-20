import { useEffect, useState } from 'react'

interface HealthData {
  status: string
  service: string
  components: Record<string, string>
  timestamp: string
}

function App() {
  const [health, setHealth] = useState<HealthData | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const fetchHealth = async () => {
      try {
        const res = await fetch('/api/v1/health')
        const data: HealthData = await res.json()
        setHealth(data)
        setError(null)
      } catch {
        setError('Unable to reach backend')
        setHealth(null)
      }
    }

    fetchHealth()
    const interval = setInterval(fetchHealth, 10000)
    return () => clearInterval(interval)
  }, [])

  const backendConnected = health !== null
  const dbUp = health?.components?.database?.startsWith('UP') ?? false
  const mqUp = health?.components?.rabbitmq?.startsWith('UP') ?? false

  return (
    <div className="app">
      <header className="header">
        <h1>CI/CD Automation Platform</h1>
        <p className="subtitle">Enterprise Orchestration Dashboard</p>
      </header>

      <main className="main">
        <div className="card">
          <h2>Backend</h2>
          <div className={`status ${backendConnected ? 'up' : 'down'}`}>
            {backendConnected ? 'CONNECTED' : 'DISCONNECTED'}
          </div>
        </div>

        <div className="card">
          <h2>PostgreSQL</h2>
          <div className={`status ${dbUp ? 'up' : 'down'}`}>
            {dbUp ? 'UP' : 'DOWN'}
          </div>
        </div>

        <div className="card">
          <h2>RabbitMQ</h2>
          <div className={`status ${mqUp ? 'up' : 'down'}`}>
            {mqUp ? 'UP' : 'DOWN'}
          </div>
        </div>

        <div className="card">
          <h2>Environment</h2>
          <div className="status up">LOCAL</div>
        </div>
      </main>

      {error && (
        <div className="error-banner">{error}</div>
      )}

      {health && (
        <footer className="footer">
          <p>Service: {health.service} | Status: {health.status}</p>
          <p>Last checked: {new Date(health.timestamp).toLocaleTimeString()}</p>
        </footer>
      )}
    </div>
  )
}

export default App
