import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AppProviders } from './app/providers'
import { App } from './app/App'
import { ErrorBoundary } from './components/feedback/ErrorBoundary'
import { loadRuntimeConfig } from './api/runtimeConfig'
import { installGlobalErrorReporting } from './telemetry/clientEvents'
import './styles/tokens.css'
import './styles/globals.css'

const rootElement = document.getElementById('root')
if (!rootElement) {
  throw new Error('Root element #root not found in index.html')
}

void loadRuntimeConfig()
installGlobalErrorReporting()

ReactDOM.createRoot(rootElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <BrowserRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
        <AppProviders>
          <App />
        </AppProviders>
      </BrowserRouter>
    </ErrorBoundary>
  </React.StrictMode>,
)
