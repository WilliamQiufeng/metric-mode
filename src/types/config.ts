// App Configuration - All paths are absolute filesystem paths
// The local file server exposes these via HTTP endpoints

export interface AppConfig {
  // Root paths
  datasetPathFile: string;          // e.g., /home/user/ml/dataset_path.txt
  checklistStatusFile: string;      // e.g., /home/user/ml/checklist_status.txt
  generatedMlRoot: string;          // e.g., /home/user/ml/generated_ml/
  selectedBestFile?: string;        // e.g., /home/user/ml/generated_ml/selected_best.txt
  
  // File server base URL
  fileServerUrl: string;            // e.g., http://localhost:8080
}

// Candidate folder prefixes (suffix is dynamic)
export const CANDIDATE_PREFIXES = [
  'candidate_01_',
  'candidate_02_',
  'candidate_03_',
  'candidate_04_',
] as const;

// Checklist field names as they appear in checklist_status.txt
export const CHECKLIST_FIELDS = {
  inputType: 'Input type',
  outputType: 'Output type',
  trainingType: 'Training type',
  dataPath: 'Data path',
  splitStrategy: 'Split strategy',
  metric: 'Metric',
  predictionDataPath: 'Prediction data path',
  modelFamilyCategory: 'Model family category',
} as const;

// Required fields for checklistOk
export const REQUIRED_CHECKLIST_FIELDS = [
  'Input type',
  'Output type',
  'Training type',
  'Data path',
  'Split strategy',
  'Metric',
] as const;

// Status types for candidates
export type CandidateStatus = 'empty' | 'running' | 'done' | 'failed';

// Candidate info from filesystem
export interface CandidateInfo {
  prefix: string;              // e.g., 'candidate_01_'
  folderName: string | null;   // Full folder name if exists, null if empty
  status: CandidateStatus;
  metrics?: {
    score?: number;
    [key: string]: unknown;
  };
  artifacts: {
    modelPy: boolean;
    testPy: boolean;
    tunePy: boolean;
    metricsJson: boolean;
    diffLog: boolean;
  };
}

// Parsed checklist item
export interface ParsedChecklistItem {
  field: string;
  value: string | null;
  status: 'Confirmed' | 'Not confirmed' | 'Not set';
}

// Stage 3 artifact status inside best candidate folder
export interface Stage3Artifacts {
  tuneResultJson: boolean;
  bestParamsJson: boolean;
  trainedModelJson: boolean;
  finalTrainResultJson: boolean;
  predictionsCsv: boolean;
  predictionResultJson: boolean;
  explanationMd: boolean;
  reportPdf: boolean;
  reportMd: boolean;
}

// Complete filesystem state
export interface FileSystemState {
  // Dataset status
  datasetReady: boolean;
  datasetPath: string | null;
  
  // Checklist parsed from file
  checklist: ParsedChecklistItem[];
  checklistOk: boolean;
  
  // Stage 2 candidates
  candidates: CandidateInfo[];
  
  // Selected best (Stage 3)
  selectedBest: string | null;      // Folder name of best candidate
  bestCandidateDir: string | null;  // Full path to best candidate
  
  // Stage 3 artifacts
  stage3Artifacts: Stage3Artifacts;
  stage3Done: boolean;
}

// Default empty state
export const createEmptyFileSystemState = (): FileSystemState => ({
  datasetReady: false,
  datasetPath: null,
  checklist: [],
  checklistOk: false,
  candidates: CANDIDATE_PREFIXES.map(prefix => ({
    prefix,
    folderName: null,
    status: 'empty',
    artifacts: {
      modelPy: false,
      testPy: false,
      tunePy: false,
      metricsJson: false,
      diffLog: false,
    },
  })),
  selectedBest: null,
  bestCandidateDir: null,
  stage3Artifacts: {
    tuneResultJson: false,
    bestParamsJson: false,
    trainedModelJson: false,
    finalTrainResultJson: false,
    predictionsCsv: false,
    predictionResultJson: false,
    explanationMd: false,
    reportPdf: false,
    reportMd: false,
  },
  stage3Done: false,
});
