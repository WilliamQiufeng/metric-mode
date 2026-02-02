// Simplified API Contract for Metric Mode
// 6 endpoints for the 3-stage workflow

// ============ Request/Response Types ============

// 1) POST /api/sessions -> Create new session
export interface CreateSessionResponse {
  sessionId: string;
}

// 1b) GET /api/sessions/{sessionId} -> Get session state
export interface GetSessionResponse {
  checklist: ChecklistSnapshot;
  checklistOk: boolean;
  dataset?: {
    datasetId: string;
    filename: string;
    rowCount: number;
    columnCount: number;
  };
  predictionDataset?: {
    filename: string;
    rowCount: number;
    columnCount: number;
  };
}

// 2) POST /api/sessions/{sessionId}/upload (multipart) -> Dataset metadata
export interface UploadResponse {
  datasetId: string;
  filename: string;
  rowCount: number;
  columnCount: number;
  columns: ColumnMeta[];
  previewRows: Record<string, unknown>[];
  missingSummary: { column: string; count: number; percentage: number }[];
}

export interface ColumnMeta {
  name: string;
  type: 'numeric' | 'categorical' | 'datetime' | 'text';
  missingCount: number;
  uniqueCount: number;
}

// 3) POST /api/sessions/{sessionId}/chat -> Chat with AI
export interface ChatRequest {
  message: string;
}

export interface ChatResponse {
  reply: string;
  checklistOk: boolean;
  checklist: ChecklistSnapshot;
}

export interface ChecklistItem {
  key: string;
  label: string;
  value: string | null;
  confirmed: boolean;
  required: boolean;
}

export interface ChecklistSnapshot {
  inputType: ChecklistItem;
  outputType: ChecklistItem;
  trainingType: ChecklistItem;
  dataPath: ChecklistItem;
  splitStrategy: ChecklistItem;
  metric: ChecklistItem;
  predictionDataPath: ChecklistItem;
  modelFamilyCategory: ChecklistItem;
}

// 4) POST /api/sessions/{sessionId}/start -> Start workflow
export interface StartWorkflowResponse {
  runId: string;
}

// 5) GET /api/sessions/{sessionId}/models -> Stage 2 model status
export type ModelStatus = 'pending' | 'running' | 'success' | 'failed';

export interface ModelInfo {
  name: string;
  status: ModelStatus;
  score?: number;
  error?: string;
}

// 6) GET /api/sessions/{sessionId}/stage3 -> Stage 3 file status
export interface Stage3Response {
  done: boolean;
  files: {
    predictions?: string;
    explanation?: string;
    report1?: string;
    report2?: string;
  };
}

// ============ Legacy types for compatibility ============
export type WorkflowStage = 'stage1' | 'stage2' | 'stage3' | 'complete';

export interface LogEvent {
  type: 'log' | 'status' | 'error' | 'complete';
  timestamp: string;
  stage?: WorkflowStage;
  substage?: string;
  progress?: number;
  stream?: 'stdout' | 'stderr';
  line?: string;
  error?: string;
}

export interface ArtifactMap {
  modelPy?: string;
  testPy?: string;
  stage2Done?: boolean;
  predictionsCsv?: string;
  explanationMd?: string;
  report1Pdf?: string;
  report2Pdf?: string;
  stage3Done?: boolean;
}

export interface SelectedModel {
  index: number;
  library: string;
  modelId: string;
  metricValue: number;
}

export interface WorkflowError {
  where: string;
  message: string;
  timestamp?: string;
}
