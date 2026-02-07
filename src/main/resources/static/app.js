// Main application module
const BuildAegis = (() => {
  // DOM Utilities
  const DOM = {
    get: (id) => document.getElementById(id),
    create: (tag, attrs = {}) => {
      const el = document.createElement(tag);
      Object.entries(attrs).forEach(([key, value]) => {
        if (key === 'textContent') {
          el.textContent = value;
        } else if (key === 'innerHTML') {
          el.innerHTML = value;
        } else if (key === 'classList') {
          el.classList.add(...value.split(' '));
        } else if (key === 'dataset') {
          Object.entries(value).forEach(([dataKey, dataValue]) => {
            el.dataset[dataKey] = dataValue;
          });
        } else {
          el.setAttribute(key, value);
        }
      });
      return el;
    },
    show: (el) => { if (el) el.hidden = false; },
    hide: (el) => { if (el) el.hidden = true; },
    toggle: (el, show) => { if (el) el.hidden = !show; },
    setText: (el, text) => { if (el) el.textContent = text; },
    setHTML: (el, html) => { if (el) el.innerHTML = html; },
    on: (el, event, handler) => el?.addEventListener(event, handler),
    off: (el, event, handler) => el?.removeEventListener(event, handler)
  };

  // API Configuration
  const API = {
    BASE_URL: '/buildaegis/api',
    ENDPOINTS: {
      PROJECT_ANALYZE: '/project/analyze',
      CACHED_RESULTS: '/dashboard/cached-results',
      AI_SETTINGS: '/ai/settings',
      AI_TEST: '/ai/test-connection',
      EXPORT_JSON: '/export/json',
      EXPORT_PDF: '/export/pdf',
      VULN_ANALYZE: '/vulnerabilities/analyze',
      VULN_SUPPRESS: '/vulnerabilities/suppress',
      VULN_UNSUPPRESS: '/vulnerabilities/unsuppress',
      VULN_SUPPRESSIONS: '/vulnerabilities/suppressions',
      VULN_AUDIT: '/vulnerabilities/suppression-audit'
    },
    HEADERS: {
      'Content-Type': 'application/json',
      'Accept': 'application/json'
    },
    async request(endpoint, options = {}) {
      const url = `${this.BASE_URL}${endpoint}`;
      const config = {
        headers: { ...this.HEADERS, ...options.headers },
        ...options
      };

      try {
        const response = await fetch(url, config);
        
        if (!response.ok) {
          const error = new Error(this.getFriendlyErrorMessage(response.status));
          error.status = response.status;
          error.response = response;
          throw error;
        }
        
        const data = await response.json();
        
        // Validate response structure
        if (data === null || data === undefined) {
          throw new Error('Server returned empty response');
        }
        
        return data;
      } catch (error) {
        if (error.name === 'TypeError' && error.message.includes('fetch')) {
          console.error('Network error - server may be offline:', error);
          throw new Error('Cannot connect to server. Please ensure the backend is running.');
        }
        console.error('API Request failed:', error);
        throw error;
      }
    },
    
    getFriendlyErrorMessage(status) {
      const messages = {
        400: 'Invalid request. Please check your input and try again.',
        401: 'Authentication required. Please check your API settings.',
        403: 'Access denied. You do not have permission to perform this action.',
        404: 'Endpoint not found. The requested service may be unavailable.',
        500: 'Server error. Please try again later or contact support.',
        502: 'Service temporarily unavailable. Please try again later.',
        503: 'Service overloaded. Please wait a moment and try again.'
      };
      return messages[status] || `Server error (${status}). Please try again.`;
    }
  };

  const Theme = {
    STORAGE_KEY: 'buildaegis.theme',
    getSystemTheme() {
      return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    },
    getStoredTheme() {
      try {
        return localStorage.getItem(this.STORAGE_KEY);
      } catch {
        return null;
      }
    },
    storeTheme(theme) {
      try {
        localStorage.setItem(this.STORAGE_KEY, theme);
      } catch {
        return;
      }
    },
    apply(theme) {
      const root = document.documentElement;
      if (theme === 'light') root.setAttribute('data-theme', 'light');
      else root.removeAttribute('data-theme');
      this.updateToggleText();
    },
    current() {
      return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
    },
    toggle() {
      const next = this.current() === 'dark' ? 'light' : 'dark';
      this.apply(next);
      this.storeTheme(next);
    },
    updateToggleText() {
      const btn = document.getElementById('themeToggle');
      if (!btn) return;
      const theme = this.current();
      btn.textContent = theme === 'dark' ? 'Light' : 'Dark';
      btn.setAttribute('aria-label', theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
    },
    init() {
      const stored = this.getStoredTheme();
      const theme = stored || this.getSystemTheme();
      this.apply(theme);

      const btn = document.getElementById('themeToggle');
      if (btn) btn.addEventListener('click', () => this.toggle());
    }
  };

  // AI Settings
  const AISettings = {
    async load() {
      try {
        const settings = await API.request(API.ENDPOINTS.AI_SETTINGS, { method: 'GET' });
        if (settings) {
          State.settings.aiProvider = settings.provider || 'openai';
          State.settings.aiModel = settings.model || 'gpt-4o-mini';
          State.settings.customEndpoint = settings.customEndpoint || null;
          State.settings.aiApiKey = settings.apiKey || null;
          UI.updateAIFields();
        }
        return settings;
      } catch (error) {
        console.log('No saved AI settings found or backend not ready');
        return null;
      }
    },

    async save() {
      try {
        const provider = DOM.get('aiProvider')?.value;
        const model = DOM.get('aiModel')?.value;
        const apiKey = DOM.get('aiApiKey')?.value;
        const customEndpoint = DOM.get('customEndpoint')?.value;

        if (!provider || !apiKey) {
          UI.showAIStatus('Please provide both provider and API key', 'error');
          return false;
        }

        const request = {
          provider,
          model: model || 'gpt-4o-mini',
          apiKey,
          customEndpoint: provider === 'custom' ? customEndpoint : null
        };

        UI.showAIStatus('Saving settings...', 'info');
        await API.request(API.ENDPOINTS.AI_SETTINGS, {
          method: 'PUT',
          body: JSON.stringify(request)
        });

        State.settings.aiProvider = provider;
        State.settings.aiModel = model;
        State.settings.customEndpoint = provider === 'custom' ? customEndpoint : null;
        UI.showAIStatus('Settings saved successfully! API key is encrypted.', 'success');
        return true;
      } catch (error) {
        UI.showAIStatus(`Failed to save: ${error.message}`, 'error');
        return false;
      }
    },

    async test() {
      try {
        UI.showAIStatus('Testing connection...', 'info');
        await API.request(API.ENDPOINTS.AI_TEST, { method: 'POST' });
        UI.showAIStatus('Connection successful! AI is ready to use.', 'success');
        return true;
      } catch (error) {
        UI.showAIStatus(`Connection failed: ${error.message}`, 'error');
        return false;
      }
    }
  };
  const State = {
    isScanning: false,
    error: null,
    allFindings: [], // Store all findings for filtering
    filteredFindings: [], // Currently displayed findings after filtering
    settings: {
      buildTool: 'maven',
      aiEnabled: false,
      aiProvider: null,
      aiApiKey: null,
      customEndpoint: null
    },
    filters: {
      severity: 'all',
      directness: 'all',
      confidence: 'all',
      search: ''
    },
    setScanning(scanning) {
      this.isScanning = scanning;
      DOM.toggle(DOM.get('analyzeDependencies'), !scanning);
      DOM.toggle(DOM.get('loadingOverlay'), scanning);
    },
    setFindings(findings) {
      this.allFindings = findings || [];
      this.applyFilters();
    },
    setFilter(type, value) {
      this.filters[type] = value;
      this.applyFilters();
    },
    applyFilters() {
      this.filteredFindings = this.allFindings.filter(f => {
        const severityMatch = this.filters.severity === 'all' || 
          String(f.severity).toUpperCase() === this.filters.severity;
        const directnessMatch = this.filters.directness === 'all' || 
          f.directness === this.filters.directness;
        const confidenceMatch = this.filters.confidence === 'all' || 
          String(f.confidence).toUpperCase() === this.filters.confidence;
        const searchMatch = !this.filters.search || 
          String(f.dependency).toLowerCase().includes(this.filters.search.toLowerCase());
        return severityMatch && directnessMatch && confidenceMatch && searchMatch;
      });
      // Update UI with filtered results
      UI.updateResults(this.filteredFindings, this.allFindings.length);
    },
    setError(error) {
      this.error = error;
      if (error) {
        UI.showError(error.message || 'An unknown error occurred');
      }
    },
    updateSettings(updates) {
      this.settings = { ...this.settings, ...updates };
      this.saveSettings();
    },
    saveSettings() {
      try {
        localStorage.setItem('riskScannerSettings', JSON.stringify(this.settings));
      } catch (e) {
        console.error('Failed to save settings:', e);
      }
    },
    loadSettings() {
      try {
        const saved = localStorage.getItem('riskScannerSettings');
        if (saved) {
          this.settings = { ...this.settings, ...JSON.parse(saved) };
        }
      } catch (e) {
        console.error('Failed to load settings:', e);
      }
    }
  };

  // UI Components
  const UI = {
    init() {
      this.bindEvents();
      this.updateUI();
    },
    
    bindEvents() {
      // Build tool selection
      const buildToolEls = document.querySelectorAll('input[name="buildTool"]');
      buildToolEls.forEach((el) => {
        DOM.on(el, 'change', (e) => {
          State.updateSettings({ buildTool: e.target.value });
          this.updateUI();
        });
      });
      
      // AI toggle
      DOM.on(DOM.get('enableAI'), 'change', async (e) => {
        const enabled = !!e.target.checked;
        State.updateSettings({ aiEnabled: enabled });

        // When enabling AI, auto-load saved provider/model/customEndpoint from DB
        // so the user doesn't have to re-enter them.
        if (enabled) {
          await AISettings.load();
        }

        this.updateUI();
      });
      
      // AI Settings buttons
      DOM.on(DOM.get('saveAISettings'), 'click', () => AISettings.save());
      DOM.on(DOM.get('testAIConnection'), 'click', () => AISettings.test());
      
      // AI field changes
      const aiProvider = DOM.get('aiProvider');
      const aiModel = DOM.get('aiModel');
      const aiApiKey = DOM.get('aiApiKey');
      if (aiProvider) DOM.on(aiProvider, 'change', () => {
        this.onProviderChange();
        this.onAIFieldChange();
      });
      if (aiModel) DOM.on(aiModel, 'input', () => this.onAIFieldChange());
      if (aiApiKey) DOM.on(aiApiKey, 'input', () => this.onAIFieldChange());
      
      // Analyze button
      const analyzeBtn = DOM.get('analyzeDependencies');
      DOM.on(analyzeBtn, 'click', () => Scanner.startScan());
      
      // Load Cached button
      const loadCachedBtn = DOM.get('loadCachedResults');
      DOM.on(loadCachedBtn, 'click', () => Scanner.loadCached());
      
      // Filter dropdowns
      DOM.on(DOM.get('filterSeverity'), 'change', (e) => State.setFilter('severity', e.target.value));
      DOM.on(DOM.get('filterDirectness'), 'change', (e) => State.setFilter('directness', e.target.value));
      DOM.on(DOM.get('filterConfidence'), 'change', (e) => State.setFilter('confidence', e.target.value));
      
      // Search input
      const searchInput = DOM.get('searchDependencies');
      if (searchInput) {
        DOM.on(searchInput, 'input', (e) => State.setFilter('search', e.target.value));
      }
      
      // Export buttons
      DOM.on(DOM.get('exportCSV'), 'click', () => this.exportToCSV());
      DOM.on(DOM.get('exportJSON'), 'click', () => Exporter.exportJSON());
      DOM.on(DOM.get('exportPDF'), 'click', () => Exporter.exportPDF());
      
      // Suppression management
      DOM.on(DOM.get('manageSuppressions'), 'click', () => SuppressionUI.showManager());
      
      // Single dependency analysis
      DOM.on(DOM.get('analyzeSingleDep'), 'click', () => SingleDepAnalyzer.analyze());
      
      // Keyboard shortcuts
      DOM.on(document, 'keydown', (e) => this.handleKeyboard(e));
      
      // Build file content changes
      const ta = DOM.get('buildFileContent');
      if (ta) {
        DOM.on(ta, 'input', () => {
          this.updateAnalyzeButton();
        });
      }
      
      // Modal close
      DOM.on(DOM.get('closeDetails'), 'click', () => DOM.hide(DOM.get('detailsModal')));
      DOM.on(DOM.get('detailsModal'), 'click', (e) => {
        if (e.target.classList.contains('modal__backdrop')) {
          DOM.hide(DOM.get('detailsModal'));
        }
      });
      
      // Suppress finding handlers
      this.initSuppressHandlers();
    },
    
    updateUI() {
      const { buildTool, aiEnabled } = State.settings;
      
      // Update build tool UI
      DOM.toggle(DOM.get('gradleWarning'), buildTool === 'gradle');
      DOM.setText(DOM.get('confidenceBadge'), 
        `CONFIDENCE: ${buildTool === 'gradle' ? 'MEDIUM' : 'HIGH'}`);
      
      // Update AI settings
      const aiFieldset = DOM.get('aiFieldset');
      if (aiFieldset) {
        aiFieldset.disabled = !aiEnabled;
      }
      
      // Update build file placeholder
      this.updateBuildFilePlaceholder();
    },
    
    updateAIFields() {
      const { aiProvider, aiModel, aiEnabled, customEndpoint, aiApiKey } = State.settings;
      const providerEl = DOM.get('aiProvider');
      const modelEl = DOM.get('aiModel');
      const apiKeyEl = DOM.get('aiApiKey');
      const customEndpointEl = DOM.get('customEndpoint');
      const customEndpointRow = DOM.get('customEndpointRow');
      
      if (providerEl) providerEl.value = aiProvider || 'openai';
      if (modelEl) modelEl.value = aiModel || 'gpt-4o-mini';
      if (apiKeyEl) apiKeyEl.value = aiApiKey || '';
      if (customEndpointEl) customEndpointEl.value = customEndpoint || '';
      
      // Show/hide custom endpoint field based on provider
      if (customEndpointRow) {
        customEndpointRow.hidden = aiProvider !== 'custom';
      }
      
      const enableCheckbox = DOM.get('enableAI');
      if (enableCheckbox) enableCheckbox.checked = aiEnabled || false;
    },

    onAIFieldChange() {
      // Clear saved status when fields change
      UI.showAIStatus('Unsaved changes - click Save Settings to persist', 'warning');
    },

    onProviderChange() {
      const provider = DOM.get('aiProvider')?.value;
      const customEndpointRow = DOM.get('customEndpointRow');
      
      // Show/hide custom endpoint field based on provider
      if (customEndpointRow) {
        customEndpointRow.hidden = provider !== 'custom';
      }
      
      // Clear custom endpoint if not custom provider
      if (provider !== 'custom') {
        const customEndpointEl = DOM.get('customEndpoint');
        if (customEndpointEl) customEndpointEl.value = '';
      }
    },

    showAIStatus(message, type = 'info') {
      const statusEl = DOM.get('aiStatus');
      if (!statusEl) return;
      
      statusEl.textContent = message;
      statusEl.className = 'helper ai-status ai-status--' + type;
      
      if (type === 'success') {
        setTimeout(() => {
          if (statusEl.textContent === message) {
            statusEl.textContent = '';
          }
        }, 5000);
      }
    },

    updateBuildFilePlaceholder() {
      const ta = DOM.get('buildFileContent');
      if (!ta) return;

      ta.placeholder = "e.g. C:/path/to/project OR C:/path/to/project/pom.xml";
    },
    
    updateAnalyzeButton() {
      const btn = DOM.get('analyzeDependencies');
      const ta = DOM.get('buildFileContent');
      if (!btn || !ta) return;
      
      btn.disabled = !ta.value.trim() || State.isScanning;
    },
    
    showError(message) {
      const banner = DOM.create('div', {
        class: 'alert alert--error',
        innerHTML: `
          <svg class="alert__icon" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="8" x2="12" y2="12"></line>
            <line x1="12" y1="16" x2="12.01" y2="16"></line>
          </svg>
          <div class="alert__content">${message}</div>
          <button class="alert__close" aria-label="Dismiss">&times;</button>
        `
      });
      
      // Add close button handler
      const closeBtn = banner.querySelector('.alert__close');
      if (closeBtn) {
        closeBtn.addEventListener('click', () => {
          banner.remove();
        });
      }
      
      // Insert at the top of the results panel
      const resultsPanel = document.querySelector('.panel--right .panel__body');
      if (resultsPanel?.firstChild) {
        resultsPanel.insertBefore(banner, resultsPanel.firstChild);
        
        // Auto-remove after 10 seconds
        setTimeout(() => banner.remove(), 10000);
      }
    },
    
    showLoading(show, message = 'Analyzing...', progress = 0) {
      State.setScanning(show);
      
      const overlay = DOM.get('loadingOverlay');
      const textEl = DOM.get('loadingText');
      const progressEl = DOM.get('loadingProgress');
      
      if (show) {
        DOM.setText(textEl, message);
        if (progressEl) {
          progressEl.style.width = `${Math.min(100, Math.max(0, progress))}%`;
          progressEl.hidden = progress === 0;
        }
        DOM.show(overlay);
      } else {
        DOM.hide(overlay);
      }
    },
    
    updateResults(findings = [], totalCount = null) {
      const hasFindings = Array.isArray(findings) && findings.length > 0;
      const actualTotal = totalCount !== null ? totalCount : (hasFindings ? findings.length : 0);
      
      // Update summary (from filtered findings for KPI counts)
      const summary = this.computeSummary(findings);
      this.updateSummary(summary, State.allFindings);
      
      // Render findings
      this.renderFindings(findings);

      // Toggle empty vs content
      DOM.toggle(DOM.get('overviewEmpty'), !hasFindings);
      DOM.toggle(DOM.get('overviewContent'), hasFindings);
      
      // Findings count - show filtered vs total when filtering
      const countEl = DOM.get('findingsCount');
      if (countEl) {
        if (totalCount !== null && totalCount !== findings.length) {
          countEl.textContent = `Showing ${findings.length} of ${totalCount} findings`;
        } else {
          countEl.textContent = `${hasFindings ? findings.length : 0} findings`;
        }
      }

      // Update Analysis section
      this.updateAnalysisSection(findings, summary);

      // Update metadata
      this.updateMetadata(findings);
    },

    updateMetadata(findings) {
      // Build tool
      const buildToolEl = DOM.get('metaBuildTool');
      if (buildToolEl) {
        const buildTool = findings[0]?.source?.split(' ')[0] || '--';
        buildToolEl.textContent = buildTool;
      }

      // Timestamp - use the most recent analyzedAt
      const timestampEl = DOM.get('metaTimestamp');
      if (timestampEl) {
        const timestamps = findings
          .map(f => f.analyzedAt)
          .filter(Boolean)
          .sort()
          .reverse();
        
        if (timestamps.length > 0) {
          const date = new Date(timestamps[0]);
          timestampEl.textContent = date.toLocaleString();
        } else {
          timestampEl.textContent = new Date().toLocaleString();
        }
      }

      // Sources - unique list
      const sourcesEl = DOM.get('metaSources');
      if (sourcesEl) {
        const uniqueSources = [...new Set(findings.map(f => f.source).filter(Boolean))];
        sourcesEl.textContent = uniqueSources.length > 0 ? uniqueSources.join(', ') : 'OSV, Maven Central';
      }
    },
    
    clearResults() {
      const tbody = DOM.get('resultsBody');
      if (tbody) tbody.innerHTML = '';
      
      this.updateSummary({ critical: 0, high: 0, medium: 0, low: 0 });

      DOM.toggle(DOM.get('overviewEmpty'), true);
      DOM.toggle(DOM.get('overviewContent'), false);

      const countEl = DOM.get('findingsCount');
      if (countEl) countEl.textContent = '0 findings';

      // Reset Analysis section
      this.updateAnalysisSection([], { critical: 0, high: 0, medium: 0, low: 0 });

      // Reset State
      State.allFindings = [];
      State.filteredFindings = [];
    },

    exportToCSV() {
      const findings = State.filteredFindings;
      if (!findings || findings.length === 0) {
        UI.showError('No results to export. Run an analysis first.');
        return;
      }

      const headers = ['Dependency', 'CVEs', 'Severity', 'ID', 'Confidence', 'Directness', 'Source', 'Description'];
      const rows = findings.map(f => [
        f.dependency,
        f.vulnerabilityCount || 0,
        f.severity,
        f.id,
        f.confidence,
        f.directness,
        f.source,
        f.description
      ]);

      const csv = [headers.join(','), ...rows.map(r => r.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(','))].join('\n');

      const blob = new Blob([csv], { type: 'text/csv' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `risk-scan-${new Date().toISOString().slice(0,10)}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    },

    handleKeyboard(e) {
      // Ctrl+Enter or Cmd+Enter to analyze
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        const btn = DOM.get('analyzeDependencies');
        if (btn && !btn.disabled) {
          Scanner.startScan();
        }
      }
      // Escape to close modal
      if (e.key === 'Escape') {
        const modal = DOM.get('detailsModal');
        if (modal && !modal.hidden) {
          DOM.hide(modal);
        }
      }
    },

    updateAnalysisSection(findings, summary) {
      const total = findings.length || 1; // avoid divide by zero

      // Update severity bars
      const setBar = (severity, count) => {
        const pct = Math.round((count / total) * 100);
        const fill = DOM.get(`bar${severity}`);
        const countEl = DOM.get(`count${severity}`);
        if (fill) fill.style.width = `${pct}%`;
        if (countEl) countEl.textContent = count;
      };
      setBar('Critical', summary.critical || 0);
      setBar('High', summary.high || 0);
      setBar('Medium', summary.medium || 0);
      setBar('Low', summary.low || 0);

      // Update top recommendations
      const recList = DOM.get('topRecommendations');
      if (!recList) return;

      if (!findings || findings.length === 0) {
        recList.innerHTML = '<li class="muted">Run an analysis to see recommendations.</li>';
        return;
      }

      // Collect unique recommendations from findings with HIGH/CRITICAL severity
      const highRiskFindings = findings.filter(f => 
        ['HIGH', 'CRITICAL'].includes(String(f.severity).toUpperCase())
      );

      if (highRiskFindings.length === 0) {
        recList.innerHTML = '<li class="muted">No high-risk dependencies found. Continue routine monitoring.</li>';
        return;
      }

      // Get recommendations from high risk findings
      const allRecs = [];
      highRiskFindings.slice(0, 5).forEach(f => {
        if (f.description && f.description !== '--') {
          allRecs.push(`<strong>${f.dependency}</strong>: ${f.description}`);
        }
      });

      if (allRecs.length === 0) {
        recList.innerHTML = '<li>No specific recommendations available for current findings.</li>';
      } else {
        recList.innerHTML = allRecs.map(r => `<li>${r}</li>`).join('');
      }
    },
    
    updateSummary(summary) {
      const { critical = 0, high = 0, medium = 0, low = 0 } = summary;

      const total = critical + high + medium + low;

      DOM.setText(DOM.get('kpiCritical'), critical);
      DOM.setText(DOM.get('kpiHigh'), high);
      DOM.setText(DOM.get('kpiMedium'), medium);
      DOM.setText(DOM.get('kpiLow'), low);
      DOM.setText(DOM.get('kpiTotalDependencies'), total);
      DOM.setText(DOM.get('kpiVulnsFound'), total);
      
      // Calculate Overall Risk Score from CURRENT VIEW (filtered findings)
      const riskScore = this.calculateRealisticRiskScore(critical, high, medium, low);
      DOM.setText(DOM.get('kpiOverallRisk'), riskScore);
      
      // Update overall status based on current view
      const statusEl = DOM.get('appStatus');
      
      if (total === 0) {
        statusEl.className = 'status-chip status-chip--success';
        statusEl.textContent = 'No Issues';
      } else if (critical > 0) {
        statusEl.className = 'status-chip status-chip--critical';
        statusEl.textContent = 'Critical Issues';
      } else if (high > 0) {
        statusEl.className = 'status-chip status-chip--high';
        statusEl.textContent = 'High Risk';
      } else if (medium > 0) {
        statusEl.className = 'status-chip status-chip--medium';
        statusEl.textContent = 'Medium Risk';
      } else {
        statusEl.className = 'status-chip status-chip--low';
        statusEl.textContent = 'Low Risk';
      }
    },
    
    calculateRealisticRiskScore(critical, high, medium, low) {
      // Weighted impact values (Critical disproportionately higher)
      const weights = { critical: 40, high: 15, medium: 5, low: 1 };
      
      // Calculate raw weighted score
      const rawScore = critical * weights.critical + 
                       high * weights.high + 
                       medium * weights.medium + 
                       low * weights.low;
      
      // "Worst case" reference: 5 Critical, 15 High, 30 Medium, 50 Low
      const maxRawScore = 5 * weights.critical + 15 * weights.high + 30 * weights.medium + 50 * weights.low;
      
      // Normalize to 0-70 base scale (leaving room for critical dominance boost)
      let normalizedScore = (rawScore / maxRawScore) * 70;
      
      // Critical dominance: non-linear boost based on critical count
      // 1 Critical = minimum 40 (high risk floor)
      // 2 Critical = minimum 60
      // 3 Critical = minimum 75
      // 4+ Critical = minimum 90
      const criticalBoost = critical >= 4 ? 35 : 
                            critical === 3 ? 25 : 
                            critical === 2 ? 15 : 
                            critical === 1 ? 10 : 0;
      
      // Calculate final score
      let finalScore = Math.min(100, normalizedScore + criticalBoost);
      
      // Guardrails
      if (critical > 0 && finalScore < 40) {
        finalScore = 40; // Critical presence guarantees at least 40
      }
      if (critical >= 2 && finalScore < 60) {
        finalScore = 60; // Multiple criticals guarantee at least 60
      }
      if (critical === 0 && high === 0 && finalScore > 30) {
        finalScore = 30; // Only medium/low cannot exceed 30
      }
      
      return Math.round(finalScore);
    },
    
    computeSummary(findings) {
      return findings.reduce((acc, finding) => {
        const s = String(finding.severity || '').toUpperCase();
        if (s === 'CRITICAL') acc.critical++;
        else if (s === 'HIGH') acc.high++;
        else if (s === 'MEDIUM') acc.medium++;
        else if (s === 'LOW') acc.low++;
        return acc;
      }, { critical: 0, high: 0, medium: 0, low: 0 });
    },
    
    renderFindings(findings) {
      const tbody = DOM.get('resultsBody');
      if (!tbody) return;
      
      tbody.innerHTML = '';
      
      findings.forEach(finding => {
        const row = this.createFindingRow(finding);
        tbody.appendChild(row);
      });
    },
    
    createFindingRow(finding) {
      const row = document.createElement('tr');
      
      // Add click handler for details
      row.addEventListener('click', () => this.showFindingDetails(finding));
      row.style.cursor = 'pointer';
      
      // Add severity class for row highlighting
      row.className = `finding-row severity-${(finding.severity || '').toLowerCase()}`;
      
      // Create cells
      const cells = [
        this.createCell(finding.dependency || '--', 'dependency'),
        this.createCell(finding.vulnerabilityCount || 0, 'vuln-count mono'),
        this.createSeverityCell(finding.severity),
        this.createCell(finding.id || '--', 'id'),
        this.createConfidenceCell(finding.confidence),
        this.createDirectnessCell(finding.directness),
        this.createActionCell()
      ];
      
      cells.forEach(cell => row.appendChild(cell));
      return row;
    },
    
    createCell(content, className = '') {
      const cell = document.createElement('td');
      if (className) cell.className = className;
      cell.textContent = content;
      return cell;
    },
    
    createSeverityCell(severity) {
      const cell = document.createElement('td');
      const severityText = String(severity || 'UNKNOWN').toUpperCase();
      const severityClass = `severity severity--${severityText.toLowerCase()}`;
      
      const badge = document.createElement('span');
      badge.className = severityClass;
      badge.textContent = severityText;
      
      cell.appendChild(badge);
      return cell;
    },
    
    createConfidenceCell(confidence) {
      const cell = document.createElement('td');
      const confidenceText = String(confidence || 'MEDIUM').toUpperCase();
      const confidenceClass = `confidence confidence--${confidenceText.toLowerCase()}`;
      
      const badge = document.createElement('span');
      badge.className = confidenceClass;
      badge.textContent = confidenceText;
      
      cell.appendChild(badge);
      return cell;
    },
    
    createDirectnessCell(directness) {
      const cell = document.createElement('td');
      const isDirect = directness === 'direct';
      const chipClass = isDirect ? 'chip chip--direct' : 'chip chip--transitive';
      const chipText = isDirect ? 'Direct' : 'Transitive';
      
      const chip = document.createElement('span');
      chip.className = chipClass;
      chip.textContent = chipText;
      
      cell.appendChild(chip);
      return cell;
    },
    
    createActionCell() {
      const cell = document.createElement('td');
      const btn = document.createElement('button');
      
      btn.type = 'button';
      btn.className = 'btn btn--link';
      btn.textContent = 'View';
      
      cell.appendChild(btn);
      return cell;
    },
    
    showFindingDetails(finding) {
      const modal = DOM.get('detailsModal');
      if (!modal) return;
      
      // Update modal content
      DOM.setText(DOM.get('detailsDependency'), finding.dependency || '--');
      DOM.setText(DOM.get('detailsId'), finding.id || '--');
      DOM.setText(DOM.get('detailsRiskScore'), `Risk Score: ${finding.riskScore || 0}`);
      DOM.setText(DOM.get('detailsSeverity'), String(finding.severity || '--').toUpperCase());
      DOM.setText(DOM.get('detailsConfidence'), `Confidence: ${String(finding.confidence || '--').toUpperCase()}`);
      DOM.setText(DOM.get('detailsDirectness'), finding.directness || '--');
      DOM.setText(DOM.get('detailsSource'), `Source: ${finding.source || '--'}`);
      DOM.setText(DOM.get('detailsDescription'), finding.description || '--');
      DOM.setText(DOM.get('detailsAffected'), finding.affectedVersions || '--');
      DOM.setText(DOM.get('detailsPath'), finding.dependencyPath || '--');
      DOM.setText(DOM.get('detailsExplanation'), finding.explanationText || '--');
      
      // Show the modal
      DOM.show(modal);
      
      // Store current finding for suppress action
      State.currentFinding = finding;
      
      // Reset suppress UI
      const suppressBtn = DOM.get('suppressFinding');
      const suppressInputGroup = DOM.get('suppressInputGroup');
      const suppressReason = DOM.get('suppressReason');
      
      if (suppressBtn) suppressBtn.hidden = false;
      if (suppressInputGroup) suppressInputGroup.hidden = true;
      if (suppressReason) suppressReason.value = '';
    },

    initSuppressHandlers() {
      const suppressBtn = DOM.get('suppressFinding');
      const confirmBtn = DOM.get('confirmSuppress');
      const cancelBtn = DOM.get('cancelSuppress');
      const suppressInputGroup = DOM.get('suppressInputGroup');
      const suppressReason = DOM.get('suppressReason');
      
      if (!suppressBtn) return;
      
      // Show reason input when suppress clicked
      DOM.on(suppressBtn, 'click', () => {
        suppressBtn.hidden = true;
        suppressInputGroup.hidden = false;
        suppressReason?.focus();
      });
      
      // Cancel suppress action
      DOM.on(cancelBtn, 'click', () => {
        suppressBtn.hidden = false;
        suppressInputGroup.hidden = true;
        if (suppressReason) suppressReason.value = '';
      });
      
      // Confirm suppress
      DOM.on(confirmBtn, 'click', async () => {
        const reason = suppressReason?.value.trim();
        if (!reason) {
          UI.showError('Please provide a reason for suppression');
          return;
        }
        
        const finding = State.currentFinding;
        if (!finding) {
          UI.showError('No finding selected');
          return;
        }
        
        try {
          await API.request(API.ENDPOINTS.VULN_SUPPRESS, {
            method: 'POST',
            body: JSON.stringify({
              findingId: finding.id,
              dependency: finding.dependency,
              reason,
              suppressedBy: 'user',
              justification: reason
            })
          });
          
          // Hide modal and refresh
          DOM.hide(DOM.get('detailsModal'));
          UI.showError('Finding suppressed successfully');
          
          // Refresh the results to hide suppressed finding
          const projectPath = DOM.get('buildFileContent')?.value.trim();
          if (projectPath) {
            // Reload to get updated results without suppressed items
            await Scanner.startScan();
          }
        } catch (error) {
          UI.showError(`Failed to suppress: ${error.message}`);
        }
      });
    },
  };
  
  // Scan Service
  const Scanner = {
    async startScan() {
      if (State.isScanning) return;
      
      const projectPath = DOM.get('buildFileContent')?.value.trim() || '';
      
      // Validate input
      if (!projectPath) {
        UI.showError('Please provide a project path (folder or build file path) to analyze');
        return;
      }
      
      try {
        // Update UI
        State.setScanning(true);
        UI.showLoading(true, 'Analyzing project...');
        UI.clearResults();

        const response = await API.request(API.ENDPOINTS.PROJECT_ANALYZE, {
          method: 'POST',
          body: JSON.stringify({ projectPath, forceRefresh: true, aiEnabled: State.settings.aiEnabled })
        });

        const findings = this.mapProjectResultsToFindings(response?.results || []);
        State.setFindings(findings);
        
      } catch (error) {
        State.setError(error);
      } finally {
        State.setScanning(false);
        UI.showLoading(false);
      }
    },

    async loadCached() {
      if (State.isScanning) return;
      
      try {
        State.setScanning(true);
        UI.showLoading(true, 'Loading cached results...');
        UI.clearResults();

        const response = await API.request(API.ENDPOINTS.CACHED_RESULTS, {
          method: 'GET'
        });

        if (!response || response.length === 0) {
          UI.showError('No cached results found. Run a new analysis first.');
          return;
        }

        const findings = this.mapProjectResultsToFindings(response);
        State.setFindings(findings);
        
      } catch (error) {
        console.error('Failed to load cached results:', error);
        UI.showError('Failed to load cached results. Please try again.');
      } finally {
        State.setScanning(false);
        UI.showLoading(false);
      }
    },

    mapProjectResultsToFindings(results) {
      return (results || []).map((r, idx) => {
        const dependency = [r.groupId, r.artifactId, r.version].filter(Boolean).join(':');

        const vulnerabilityCount = r.enrichment?.vulnerabilityCount || 0;

        // Calculate severity based on vulnerability count (not just backend riskLevel)
        // Critical: 10+, High: 5-9, Medium: 2-4, Low: 0-1
        let severity;
        if (vulnerabilityCount >= 10) severity = 'CRITICAL';
        else if (vulnerabilityCount >= 5) severity = 'HIGH';
        else if (vulnerabilityCount >= 2) severity = 'MEDIUM';
        else severity = 'LOW';

        // Get actual vulnerability IDs from enrichment, or fallback to dependency coordinate
        const vulnIds = r.enrichment?.vulnerabilityIds;
        const id = Array.isArray(vulnIds) && vulnIds.length > 0
          ? vulnIds[0]
          : `${r.groupId}:${r.artifactId}`;

        // Determine actual data source from enrichment
        const source = r.enrichment?.ecosystem
          ? `${r.enrichment.ecosystem}${r.fromCache ? ' (cached)' : ''}`
          : (r.fromCache ? 'Cache' : 'Analysis');

        // Confidence: HIGH for Maven (reliable), MEDIUM for Gradle (less reliable), or from build tool setting
        const confidence = r.buildTool === 'gradle' ? 'MEDIUM' : 'HIGH';

        // Directness: use actual value from backend dependency resolution
        const directness = r.isDirect ? 'direct' : 'transitive';

        return {
          _id: `dep-${idx}`,
          dependency: dependency || '--',
          severity,
          id,
          confidence,
          directness,
          source,
          description: r.explanation || '--',
          affectedVersions: Array.isArray(r.recommendations) && r.recommendations.length
            ? r.recommendations.join('\n')
            : '--',
          dependencyPath: '--',
          explanationType: 'static',
          explanationText: r.explanation || '--',
          fromCache: r.fromCache,
          analyzedAt: r.analyzedAt,
          vulnerabilityCount: vulnerabilityCount,
          riskScore: r.riskScore || 0,
          buildTool: r.buildTool
        };
      });
    }
  };

  // Exporter Service
  const Exporter = {
    async exportJSON() {
      const projectPath = DOM.get('buildFileContent')?.value.trim();
      if (!projectPath) {
        UI.showError('Please enter a project path first');
        return;
      }

      try {
        UI.showLoading(true, 'Generating JSON report...');
        
        const response = await fetch(`${API.BASE_URL}${API.ENDPOINTS.EXPORT_JSON}`, {
          method: 'POST',
          headers: API.HEADERS,
          body: JSON.stringify({ projectPath, forceRefresh: false })
        });

        if (!response.ok) throw new Error('Export failed');

        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `risk-report-${new Date().toISOString().slice(0,10)}.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        
      } catch (error) {
        UI.showError(`Export failed: ${error.message}`);
      } finally {
        UI.showLoading(false);
      }
    },

    async exportPDF() {
      const projectPath = DOM.get('buildFileContent')?.value.trim();
      if (!projectPath) {
        UI.showError('Please enter a project path first');
        return;
      }

      try {
        UI.showLoading(true, 'Generating PDF report...');
        
        const response = await fetch(`${API.BASE_URL}${API.ENDPOINTS.EXPORT_PDF}`, {
          method: 'POST',
          headers: API.HEADERS,
          body: JSON.stringify({ projectPath, forceRefresh: false })
        });

        if (!response.ok) throw new Error('Export failed');

        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `risk-report-${new Date().toISOString().slice(0,10)}.pdf`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        
      } catch (error) {
        UI.showError(`Export failed: ${error.message}`);
      } finally {
        UI.showLoading(false);
      }
    }
  };

  // Single Dependency Analyzer
  const SingleDepAnalyzer = {
    async analyze() {
      if (State.isScanning) return;

      const groupId = DOM.get('singleGroupId')?.value.trim();
      const artifactId = DOM.get('singleArtifactId')?.value.trim();
      const version = DOM.get('singleVersion')?.value.trim();

      if (!groupId || !artifactId || !version) {
        UI.showError('Please provide Group ID, Artifact ID, and Version');
        return;
      }

      try {
        State.setScanning(true);
        UI.showLoading(true, `Analyzing ${groupId}:${artifactId}:${version}...`);
        UI.clearResults();

        const params = new URLSearchParams({
          groupId,
          artifactId,
          version,
          buildTool: State.settings.buildTool,
          includeExplanations: 'true'
        });

        const response = await API.request(`${API.ENDPOINTS.VULN_ANALYZE}?${params}`, {
          method: 'GET'
        });

        if (!response || !response.findings || response.findings.length === 0) {
          UI.showError('No vulnerabilities found for this dependency.');
          // Show as clean finding
          const cleanFinding = [{
            _id: 'single-dep',
            dependency: `${groupId}:${artifactId}:${version}`,
            severity: 'LOW',
            id: `${groupId}:${artifactId}`,
            confidence: State.settings.buildTool === 'gradle' ? 'MEDIUM' : 'HIGH',
            directness: 'direct',
            source: 'Single Analysis',
            description: 'No known vulnerabilities found for this version.',
            affectedVersions: version,
            dependencyPath: `${groupId}:${artifactId}:${version}`,
            explanationType: 'static',
            explanationText: 'This dependency version appears to have no known vulnerabilities in the scanned databases.',
            fromCache: false,
            analyzedAt: new Date().toISOString(),
            vulnerabilityCount: 0,
            riskScore: 0,
            buildTool: State.settings.buildTool
          }];
          State.setFindings(cleanFinding);
        } else {
          // Convert to findings format
          const findings = response.findings.map((f, idx) => ({
            _id: `single-vuln-${idx}`,
            dependency: `${groupId}:${artifactId}:${version}`,
            severity: f.rawSeverity?.toUpperCase() || 'HIGH',
            id: f.id || f.vulnerability?.id || `${groupId}:${artifactId}`,
            confidence: f.confidenceLevel || 'HIGH',
            directness: 'direct',
            source: f.sources?.join(', ') || 'Analysis',
            description: f.vulnerability?.description || 'Vulnerability found',
            affectedVersions: f.vulnerability?.affectedVersions?.join(', ') || version,
            dependencyPath: `${groupId}:${artifactId}:${version}`,
            explanationType: 'ai',
            explanationText: response.explanations?.[f.id]?.explanation || f.vulnerability?.description || 'See vulnerability details',
            fromCache: false,
            analyzedAt: new Date().toISOString(),
            vulnerabilityCount: 1,
            riskScore: f.riskScore || 50,
            buildTool: State.settings.buildTool,
            _vulnerabilityFinding: f // Keep reference for suppression
          }));
          State.setFindings(findings);
        }
        
      } catch (error) {
        // Show fallback data when AI fails
        const fallbackFinding = [{
          _id: 'single-dep',
          dependency: `${groupId}:${artifactId}:${version}`,
          severity: 'LOW',
          id: `${groupId}:${artifactId}`,
          confidence: State.settings.buildTool === 'gradle' ? 'MEDIUM' : 'HIGH',
          directness: 'direct',
          source: 'Single Analysis',
          description: 'AI analysis failed - showing basic vulnerability data.',
          affectedVersions: version,
          dependencyPath: `${groupId}:${artifactId}:${version}`,
          explanationType: 'static',
          explanationText: 'AI analysis failed. This dependency version appears to have no known vulnerabilities in scanned databases.',
          fromCache: false,
          analyzedAt: new Date().toISOString(),
          vulnerabilityCount: 0,
          riskScore: 0,
          buildTool: State.settings.buildTool
        }];
        State.setFindings(fallbackFinding);
        
        // Show error message about AI failure
        UI.showError(`AI analysis failed: ${error.message}. Showing basic vulnerability data instead.`);
        
        State.setError(error);
      } finally {
        State.setScanning(false);
        UI.showLoading(false);
      }
    }
  };

  // Suppression UI
  const SuppressionUI = {
    async showManager() {
      try {
        const [activeResponse, auditResponse] = await Promise.all([
          API.request(API.ENDPOINTS.VULN_SUPPRESSIONS, { method: 'GET' }).catch(() => []),
          API.request(API.ENDPOINTS.VULN_AUDIT, { method: 'GET' }).catch(() => [])
        ]);

        const activeSuppressions = Array.isArray(activeResponse) ? activeResponse : [];
        const auditLog = Array.isArray(auditResponse) ? auditResponse : [];

        this.renderModal(activeSuppressions, auditLog);
      } catch (error) {
        UI.showError('Failed to load suppression data');
      }
    },

    renderModal(active, audit) {
      const modal = document.createElement('div');
      modal.className = 'modal';
      modal.innerHTML = `
        <div class="modal__backdrop" data-close="true"></div>
        <div class="modal__panel" style="max-width: 800px;">
          <div class="modal__header">
            <div class="modal__title-wrap">
              <div class="modal__title">Suppression Management</div>
              <div class="modal__subtitle">${active.length} active suppressions</div>
            </div>
            <button class="btn btn--ghost" type="button" id="closeSuppressionModal">Close</button>
          </div>
          <div class="modal__body">
            <section class="details-section">
              <h4 class="details-section__title">Active Suppressions</h4>
              ${active.length === 0 ? 
                '<p class="muted">No active suppressions</p>' :
                `<div class="table-wrap">
                  <table class="table">
                    <thead>
                      <tr><th>Finding ID</th><th>Dependency</th><th>Reason</th><th>By</th><th>Action</th></tr>
                    </thead>
                    <tbody>
                      ${active.map(s => `
                        <tr>
                          <td class="mono">${s.findingId || s.id || '--'}</td>
                          <td>${s.dependency || '--'}</td>
                          <td>${s.reason || '--'}</td>
                          <td>${s.suppressedBy || '--'}</td>
                          <td><button class="btn btn--link unsuppress-btn" data-id="${s.findingId || s.id}" data-dep="${s.dependency}">Unsuppress</button></td>
                        </tr>
                      `).join('')}
                    </tbody>
                  </table>
                </div>`
              }
            </section>
            <section class="details-section">
              <h4 class="details-section__title">Recent Audit Log (last 10)</h4>
              ${audit.length === 0 ?
                '<p class="muted">No audit entries</p>' :
                `<ul class="recommendations-list">
                  ${audit.slice(-10).reverse().map(a => `
                    <li><strong>${a.action || 'Unknown'}</strong> - ${a.findingId || '--'} by ${a.by || 'unknown'} at ${new Date(a.timestamp).toLocaleString()}</li>
                  `).join('')}
                </ul>`
              }
            </section>
          </div>
        </div>
      `;

      document.body.appendChild(modal);
      DOM.show(modal);

      // Close handler
      DOM.on(DOM.get('closeSuppressionModal'), 'click', () => {
        modal.remove();
      });

      // Backdrop click
      DOM.on(modal.querySelector('.modal__backdrop'), 'click', () => {
        modal.remove();
      });

      // Unsuppress handlers
      modal.querySelectorAll('.unsuppress-btn').forEach(btn => {
        DOM.on(btn, 'click', async () => {
          const findingId = btn.dataset.id;
          const dependency = btn.dataset.dep;
          if (findingId && confirm('Unsuppress this vulnerability?')) {
            await this.unsuppress(findingId, dependency);
            modal.remove();
            this.showManager();
          }
        });
      });
    },

    async unsuppress(findingId, dependency) {
      try {
        await API.request(`${API.ENDPOINTS.VULN_UNSUPPRESS}?findingId=${encodeURIComponent(findingId)}`, {
          method: 'POST',
          body: JSON.stringify({ dependency, unsuppressedBy: 'user' })
        });
        UI.showError('Vulnerability unsuppressed successfully');
      } catch (error) {
        UI.showError(`Failed to unsuppress: ${error.message}`);
      }
    },

    async suppress(finding) {
      const reason = prompt('Enter reason for suppressing this finding:');
      if (!reason) return;

      try {
        await API.request(API.ENDPOINTS.VULN_SUPPRESS, {
          method: 'POST',
          body: JSON.stringify({
            findingId: finding.id,
            dependency: finding.dependency,
            reason,
            suppressedBy: 'user',
            justification: reason
          })
        });
        UI.showError('Finding suppressed successfully');
      } catch (error) {
        UI.showError(`Failed to suppress: ${error.message}`);
      }
    }
  };
  
  // Initialize the application
  return {
    init() {
      // Load saved settings
      State.loadSettings();

      Theme.init();
      
      // Load AI settings
      AISettings.load().catch(() => null);
      
      // Initialize UI
      UI.init();
      
      // Set initial UI state
      UI.updateUI();
      UI.updateAnalyzeButton();
      
      // Show welcome message
      console.log('BuildAegis initialized');
    }
  };
})();

// Start the application when the DOM is ready
document.addEventListener('DOMContentLoaded', () => {
  BuildAegis.init();
});
