// Workflow Types for Metric Mode

export type WorkflowStage = 'stage1' | 'stage2' | 'stage3';

export type PredictionType = 'classification' | 'regression';

export type SplitStrategy = 'random' | 'time-based' | 'group';

export type ClassificationMetric = 'accuracy' | 'f1' | 'auc';

export type RegressionMetric = 'mae' | 'rmse' | 'r2';

export type EvaluationMetric = ClassificationMetric | RegressionMetric;

export type MissingValueStrategy = 'drop' | 'impute';

export interface ColumnInfo {
  name: string;
  type: 'numeric' | 'categorical' | 'datetime' | 'text';
  missingCount: number;
  uniqueCount: number;
}

export interface DatasetInfo {
  datasetId: string;
  filename: string;
  columns: ColumnInfo[];
  previewRows: Record<string, unknown>[];
  rowCount: number;
  columnCount: number;
  missingSummary: { column: string; count: number; percentage: number }[];
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

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

export interface CandidateModel {
  index: number;
  library: string;
  modelId: string;
  status: 'pending' | 'running' | 'success' | 'failed';
  metricValue?: number;
  stdout?: string;
  stderr?: string;
}

export interface SelectedBest {
  index: number;
  library: string;
  modelId: string;
  metricValue: number;
}

export interface Artifacts {
  modelPyUrl?: string;
  testPyUrl?: string;
  explanationMdUrl?: string;
  reportMdUrl?: string;
  reportPdf1Url?: string;
  reportPdf2Url?: string;
  predictionsCsvUrl?: string;
  predictionsJsonUrl?: string;
}

export interface WorkflowError {
  where: string;
  message: string;
}

export interface SessionStatus {
  sessionId: string;
  stage: WorkflowStage;
  substage: string;
  progress: number;
  checklistOk: boolean;
  checklistSnapshot: ChecklistSnapshot;
  dataset?: DatasetInfo;
  candidates: CandidateModel[];
  selectedBest?: SelectedBest;
  artifacts: Artifacts;
  errors: WorkflowError[];
}

export interface Session {
  sessionId: string;
  createdAt: Date;
  lastUpdated: Date;
  status: SessionStatus;
  messages: ChatMessage[];
}

// API Response Types
export interface CreateSessionResponse {
  sessionId: string;
}

export interface UploadResponse {
  datasetId: string;
  filename: string;
  columns: ColumnInfo[];
  previewRows: Record<string, unknown>[];
  rowCount: number;
  columnCount: number;
  missingSummary: { column: string; count: number; percentage: number }[];
}

export interface ChatResponse {
  assistantMessage: string;
  checklistSnapshot: ChecklistSnapshot;
  checklistOk: boolean;
  stageHint?: string;
}

export interface StartStageResponse {
  started: boolean;
}

export interface Prediction {
  id: number;
  [key: string]: unknown;
}

export interface ResultsSummary {
  selectedModel: SelectedBest;
  evaluationMetric: {
    name: string;
    value: number;
  };
  trainTestSplit: {
    trainSize: number;
    testSize: number;
    strategy: SplitStrategy;
  };
  hyperparameters: Record<string, unknown>;
  confusionMatrix?: number[][];
  residualsData?: { predicted: number; actual: number }[];
}
