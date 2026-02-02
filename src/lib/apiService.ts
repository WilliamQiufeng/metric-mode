// API Service Layer
// Replace MOCK implementations with real fetch calls when backend is ready

import type {
  CreateSessionResponse,
  GetSessionResponse,
  UploadResponse,
  ChatResponse,
  StartWorkflowResponse,
  ModelInfo,
  Stage3Response,
  ChecklistSnapshot,
} from '@/types/api';

// Base URL - change this when connecting to real backend
const API_BASE = '/api';

// Set to true to use mock data, false to use real backend
const USE_MOCK = true;

// Helper for delays in mock mode
const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

// ============ 1) Create Session ============
export async function createSession(): Promise<CreateSessionResponse> {
  if (USE_MOCK) {
    await delay(200);
    return { sessionId: Math.random().toString(36).substring(2, 15) };
  }
  
  const res = await fetch(`${API_BASE}/sessions`, { method: 'POST' });
  return res.json();
}

// ============ 1b) Get Session State ============
export async function getSession(sessionId: string): Promise<GetSessionResponse> {
  if (USE_MOCK) {
    await delay(200);
    return {
      checklist: createEmptyChecklist(),
      checklistOk: false,
    };
  }
  
  const res = await fetch(`${API_BASE}/sessions/${sessionId}`);
  return res.json();
}

// Helper to create empty checklist for mock
function createEmptyChecklist(): ChecklistSnapshot {
  return {
    inputType: { key: 'inputType', label: 'Input Type', value: null, confirmed: false, required: true },
    outputType: { key: 'outputType', label: 'Output Type', value: null, confirmed: false, required: true },
    trainingType: { key: 'trainingType', label: 'Training Type', value: null, confirmed: false, required: true },
    dataPath: { key: 'dataPath', label: 'Data Path', value: null, confirmed: false, required: true },
    splitStrategy: { key: 'splitStrategy', label: 'Split Strategy', value: null, confirmed: false, required: true },
    metric: { key: 'metric', label: 'Metric', value: null, confirmed: false, required: true },
    predictionDataPath: { key: 'predictionDataPath', label: 'Prediction Data Path', value: null, confirmed: false, required: true },
    modelFamilyCategory: { key: 'modelFamilyCategory', label: 'Model Family', value: null, confirmed: false, required: false },
  };
}

// ============ 2) Upload Dataset ============
export async function uploadDataset(
  sessionId: string,
  file: File,
  type: 'train' | 'predict'
): Promise<UploadResponse> {
  if (USE_MOCK) {
    await delay(800);
    
    // Update mock checklist state based on upload type
    if (type === 'train') {
      mockChecklistState.dataPath = { 
        ...mockChecklistState.dataPath, 
        value: file.name, 
        confirmed: true 
      };
    } else if (type === 'predict') {
      mockChecklistState.predictionDataPath = { 
        ...mockChecklistState.predictionDataPath, 
        value: file.name, 
        confirmed: true 
      };
    }
    
    return {
      datasetId: `dataset-${Date.now()}`,
      filename: file.name,
      rowCount: 150,
      columnCount: 6,
      columns: [
        { name: 'sample_id', type: 'text', missingCount: 0, uniqueCount: 150 },
        { name: 'feature_1', type: 'numeric', missingCount: 3, uniqueCount: 145 },
        { name: 'feature_2', type: 'numeric', missingCount: 0, uniqueCount: 89 },
        { name: 'feature_3', type: 'categorical', missingCount: 5, uniqueCount: 4 },
        { name: 'experiment_date', type: 'datetime', missingCount: 0, uniqueCount: 30 },
        { name: 'target', type: 'numeric', missingCount: 0, uniqueCount: 2 },
      ],
      previewRows: [
        { sample_id: 'S001', feature_1: 1.23, feature_2: 4.56, feature_3: 'A', target: 1 },
        { sample_id: 'S002', feature_1: 2.34, feature_2: 5.67, feature_3: 'B', target: 0 },
      ],
      missingSummary: [
        { column: 'feature_1', count: 3, percentage: 2 },
        { column: 'feature_3', count: 5, percentage: 3.33 },
      ],
    };
  }
  
  const formData = new FormData();
  formData.append('file', file);
  formData.append('type', type);
  const res = await fetch(`${API_BASE}/sessions/${sessionId}/upload`, {
    method: 'POST',
    body: formData,
  });
  return res.json();
}

// Helper to get current mock checklist (for syncing after upload)
export function getMockChecklist(): ChecklistSnapshot {
  return { ...mockChecklistState };
}

// ============ 3) Chat ============
export async function sendChatMessage(
  sessionId: string,
  message: string
): Promise<ChatResponse> {
  if (USE_MOCK) {
    await delay(500);
    const lowerMessage = message.toLowerCase();
    
    // Update checklist based on message
    const checklist = updateMockChecklist(message);
    const checklistOk = isChecklistComplete(checklist);
    
    // Generate contextual reply
    let reply = "I understand. Let me help you configure your ML pipeline.";
    
    if (lowerMessage.includes('classification')) {
      reply = "Great! I'll set this up as a **classification** problem.";
    } else if (lowerMessage.includes('regression')) {
      reply = "Got it, this is a **regression** problem.";
    } else if (lowerMessage.includes('accuracy') || lowerMessage.includes('f1') || lowerMessage.includes('auc') || 
               lowerMessage.includes('rmse') || lowerMessage.includes('mae') || lowerMessage.includes('r-squared')) {
      reply = "Metric configured! ✓";
    } else if (lowerMessage.includes('split') || lowerMessage.includes('fold')) {
      reply = "Split strategy configured! ✓";
    } else if (lowerMessage.includes('tabular') || lowerMessage.includes('text') || lowerMessage.includes('image')) {
      reply = "Input type configured! ✓";
    } else if (lowerMessage.includes('supervised') || lowerMessage.includes('semi-supervised')) {
      reply = "Training type configured! ✓";
    } else if (lowerMessage.includes('tree') || lowerMessage.includes('linear') || lowerMessage.includes('neural') || lowerMessage.includes('all families')) {
      reply = "Model family configured! ✓";
    }
    
    if (checklistOk) {
      reply += "\n\n🎉 **All required configurations complete!** You can now proceed to Stage 2.";
    }
    
    return { reply, checklist, checklistOk };
  }
  
  const res = await fetch(`${API_BASE}/sessions/${sessionId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  });
  return res.json();
}

// ============ 4) Start Workflow ============
export async function startWorkflow(sessionId: string): Promise<StartWorkflowResponse> {
  if (USE_MOCK) {
    await delay(300);
    return { runId: `run-${Date.now()}` };
  }
  
  const res = await fetch(`${API_BASE}/sessions/${sessionId}/start`, { method: 'POST' });
  return res.json();
}

// ============ 5) Get Models Status (Stage 2) ============
let mockModelIndex = 0;
let mockModelStartTime = 0;

export async function getModelsStatus(sessionId: string): Promise<ModelInfo[]> {
  if (USE_MOCK) {
    await delay(200);
    
    // Initialize on first call
    if (mockModelStartTime === 0) {
      mockModelStartTime = Date.now();
      mockModelIndex = 0;
    }
    
    const elapsed = Date.now() - mockModelStartTime;
    const modelDuration = 3000; // 3 seconds per model
    
    const models: ModelInfo[] = [
      { name: 'RandomForest', status: 'pending' },
      { name: 'XGBoost', status: 'pending' },
      { name: 'LightGBM', status: 'pending' },
      { name: 'CatBoost', status: 'pending' },
    ];
    
    // Update status based on elapsed time
    for (let i = 0; i < models.length; i++) {
      const modelStart = i * modelDuration;
      const modelEnd = (i + 1) * modelDuration;
      
      if (elapsed >= modelEnd) {
        // Model completed
        if (i === 2) {
          // LightGBM fails
          models[i].status = 'failed';
          models[i].error = 'Feature mismatch error';
        } else {
          models[i].status = 'success';
          models[i].score = 0.85 + Math.random() * 0.1;
        }
      } else if (elapsed >= modelStart) {
        // Model running
        models[i].status = 'running';
      }
    }
    
    return models;
  }
  
  const res = await fetch(`${API_BASE}/sessions/${sessionId}/models`);
  return res.json();
}

// Reset mock state (call when starting new workflow)
export function resetMockState() {
  mockModelIndex = 0;
  mockModelStartTime = 0;
}

// ============ 6) Get Stage 3 Status ============
let mockStage3StartTime = 0;

export async function getStage3Status(sessionId: string): Promise<Stage3Response> {
  if (USE_MOCK) {
    await delay(200);
    
    // Initialize on first call
    if (mockStage3StartTime === 0) {
      mockStage3StartTime = Date.now();
    }
    
    const elapsed = Date.now() - mockStage3StartTime;
    const files: Stage3Response['files'] = {};
    
    // Files appear progressively
    if (elapsed > 2000) files.predictions = '/artifacts/predictions.csv';
    if (elapsed > 4000) files.explanation = '/artifacts/explanation.md';
    if (elapsed > 6000) files.report1 = '/artifacts/pipeline_report.pdf';
    if (elapsed > 8000) files.report2 = '/artifacts/scientific_summary.pdf';
    
    return {
      done: elapsed > 8000,
      files,
    };
  }
  
  const res = await fetch(`${API_BASE}/sessions/${sessionId}/stage3`);
  return res.json();
}

// Reset Stage 3 mock state
export function resetStage3MockState() {
  mockStage3StartTime = 0;
}

// ============ 7) Get Artifact URL ============
export function getArtifactUrl(artifactPath: string): string {
  return `${API_BASE}/artifacts${artifactPath}`;
}

// ============ Helper: Mock state to accumulate checklist ============
let mockChecklistState: ChecklistSnapshot = createEmptyChecklist();

export function resetMockChecklist() {
  mockChecklistState = createEmptyChecklist();
}

function updateMockChecklist(message: string): ChecklistSnapshot {
  const lowerMessage = message.toLowerCase();
  
  // Input Type
  if (lowerMessage.includes('tabular')) {
    mockChecklistState.inputType = { ...mockChecklistState.inputType, value: 'tabular', confirmed: true };
  } else if (lowerMessage.includes('text data') || lowerMessage.includes('text input')) {
    mockChecklistState.inputType = { ...mockChecklistState.inputType, value: 'text', confirmed: true };
  } else if (lowerMessage.includes('image')) {
    mockChecklistState.inputType = { ...mockChecklistState.inputType, value: 'image', confirmed: true };
  }
  
  // Output Type (Task)
  if (lowerMessage.includes('classification')) {
    mockChecklistState.outputType = { ...mockChecklistState.outputType, value: 'classification', confirmed: true };
  } else if (lowerMessage.includes('regression')) {
    mockChecklistState.outputType = { ...mockChecklistState.outputType, value: 'regression', confirmed: true };
  }
  
  // Training Type
  if (lowerMessage.includes('supervised')) {
    mockChecklistState.trainingType = { ...mockChecklistState.trainingType, value: 'supervised', confirmed: true };
  } else if (lowerMessage.includes('semi-supervised')) {
    mockChecklistState.trainingType = { ...mockChecklistState.trainingType, value: 'semi-supervised', confirmed: true };
  }
  
  // Split Strategy
  if (lowerMessage.includes('random') && lowerMessage.includes('split')) {
    mockChecklistState.splitStrategy = { ...mockChecklistState.splitStrategy, value: 'random 80/20', confirmed: true };
  } else if (lowerMessage.includes('time-based')) {
    mockChecklistState.splitStrategy = { ...mockChecklistState.splitStrategy, value: 'time-based', confirmed: true };
  } else if (lowerMessage.includes('fold') || lowerMessage.includes('cross validation')) {
    mockChecklistState.splitStrategy = { ...mockChecklistState.splitStrategy, value: '5-fold CV', confirmed: true };
  }
  
  // Metric
  if (lowerMessage.includes('accuracy')) {
    mockChecklistState.metric = { ...mockChecklistState.metric, value: 'accuracy', confirmed: true };
  } else if (lowerMessage.includes('f1')) {
    mockChecklistState.metric = { ...mockChecklistState.metric, value: 'f1', confirmed: true };
  } else if (lowerMessage.includes('auc')) {
    mockChecklistState.metric = { ...mockChecklistState.metric, value: 'auc', confirmed: true };
  } else if (lowerMessage.includes('rmse')) {
    mockChecklistState.metric = { ...mockChecklistState.metric, value: 'rmse', confirmed: true };
  } else if (lowerMessage.includes('mae')) {
    mockChecklistState.metric = { ...mockChecklistState.metric, value: 'mae', confirmed: true };
  } else if (lowerMessage.includes('r-squared') || lowerMessage.includes('r²')) {
    mockChecklistState.metric = { ...mockChecklistState.metric, value: 'r²', confirmed: true };
  }
  
  // Model Family (optional)
  if (lowerMessage.includes('tree-based') || lowerMessage.includes('randomforest') || lowerMessage.includes('xgboost')) {
    mockChecklistState.modelFamilyCategory = { ...mockChecklistState.modelFamilyCategory, value: 'tree-based', confirmed: true };
  } else if (lowerMessage.includes('linear model') || lowerMessage.includes('linear regression') || lowerMessage.includes('logistic')) {
    mockChecklistState.modelFamilyCategory = { ...mockChecklistState.modelFamilyCategory, value: 'linear', confirmed: true };
  } else if (lowerMessage.includes('neural network')) {
    mockChecklistState.modelFamilyCategory = { ...mockChecklistState.modelFamilyCategory, value: 'neural network', confirmed: true };
  } else if (lowerMessage.includes('all families') || lowerMessage.includes('all model')) {
    mockChecklistState.modelFamilyCategory = { ...mockChecklistState.modelFamilyCategory, value: 'all', confirmed: true };
  }
  
  return { ...mockChecklistState };
}

function isChecklistComplete(checklist: ChecklistSnapshot): boolean {
  const required = [
    checklist.inputType,
    checklist.outputType,
    checklist.trainingType,
    checklist.dataPath,
    checklist.splitStrategy,
    checklist.metric,
    checklist.predictionDataPath,
  ];
  return required.every((item) => item.confirmed);
}
