// File Service - Reads files from local file server
// The file server exposes filesystem via HTTP endpoints

import type {
  AppConfig,
  FileSystemState,
  CandidateInfo,
  CandidateStatus,
  ParsedChecklistItem,
  Stage3Artifacts,
} from '@/types/config';
import {
  CANDIDATE_PREFIXES,
  REQUIRED_CHECKLIST_FIELDS,
  createEmptyFileSystemState,
} from '@/types/config';

// Default config - will be loaded from config.json
let appConfig: AppConfig | null = null;

// Load config from public/config.json
export async function loadConfig(): Promise<AppConfig> {
  if (appConfig) return appConfig;
  
  try {
    const response = await fetch('/config.json');
    if (!response.ok) {
      throw new Error(`Failed to load config: ${response.status}`);
    }
    appConfig = await response.json();
    return appConfig!;
  } catch (error) {
    console.error('Failed to load config.json:', error);
    // Return a default config for development
    appConfig = {
      datasetPathFile: '/tmp/ml/dataset_path.txt',
      checklistStatusFile: '/tmp/ml/checklist_status.txt',
      generatedMlRoot: '/tmp/ml/generated_ml/',
      selectedBestFile: '/tmp/ml/generated_ml/selected_best.txt',
      fileServerUrl: 'http://localhost:8080',
    };
    return appConfig;
  }
}

// Read file content from file server
async function readFile(absolutePath: string): Promise<string | null> {
  const config = await loadConfig();
  try {
    const url = `${config.fileServerUrl}/read?path=${encodeURIComponent(absolutePath)}`;
    const response = await fetch(url);
    if (!response.ok) {
      if (response.status === 404) return null;
      throw new Error(`Failed to read file: ${response.status}`);
    }
    return await response.text();
  } catch (error) {
    console.error(`Failed to read file ${absolutePath}:`, error);
    return null;
  }
}

// Check if file exists and has size > 0
async function fileExists(absolutePath: string): Promise<boolean> {
  const config = await loadConfig();
  try {
    const url = `${config.fileServerUrl}/exists?path=${encodeURIComponent(absolutePath)}`;
    const response = await fetch(url);
    if (!response.ok) return false;
    const data = await response.json();
    return data.exists && data.size > 0;
  } catch {
    return false;
  }
}

// List directory contents
async function listDir(absolutePath: string): Promise<string[]> {
  const config = await loadConfig();
  try {
    const url = `${config.fileServerUrl}/list?path=${encodeURIComponent(absolutePath)}`;
    const response = await fetch(url);
    if (!response.ok) return [];
    const data = await response.json();
    return data.entries || [];
  } catch {
    return [];
  }
}

// Parse checklist_status.txt
function parseChecklist(content: string): ParsedChecklistItem[] {
  const lines = content.split('\n');
  const items: ParsedChecklistItem[] = [];
  
  for (const line of lines) {
    // Skip header and empty lines
    if (line.startsWith('===') || !line.trim()) continue;
    
    // Parse line format: "Field name: VALUE (Status)"
    const match = line.match(/^(.+?):\s*(.+?)\s*\(([^)]+)\)$/);
    if (match) {
      const [, field, value, status] = match;
      items.push({
        field: field.trim(),
        value: value.trim() === '' ? null : value.trim(),
        status: status.trim() as ParsedChecklistItem['status'],
      });
    }
  }
  
  return items;
}

// Check if all required fields are confirmed
function isChecklistComplete(items: ParsedChecklistItem[]): boolean {
  for (const requiredField of REQUIRED_CHECKLIST_FIELDS) {
    const item = items.find(i => i.field.toLowerCase() === requiredField.toLowerCase());
    if (!item || item.status !== 'Confirmed') {
      return false;
    }
  }
  return true;
}

// Determine candidate status from folder contents
async function getCandidateStatus(
  folderPath: string,
  entries: string[]
): Promise<{ status: CandidateStatus; metrics?: Record<string, unknown> }> {
  // Check for failure indicators
  if (entries.includes('diff.log')) {
    const diffContent = await readFile(`${folderPath}/diff.log`);
    if (diffContent && (diffContent.includes('Traceback') || diffContent.includes('ERROR') || diffContent.length > 0)) {
      return { status: 'failed' };
    }
  }
  
  // Check for completion
  if (entries.includes('metrics.json')) {
    const metricsContent = await readFile(`${folderPath}/metrics.json`);
    if (metricsContent) {
      try {
        const metrics = JSON.parse(metricsContent);
        return { status: 'done', metrics };
      } catch {
        return { status: 'done' };
      }
    }
  }
  
  // Folder exists but no metrics yet = running
  return { status: 'running' };
}

// Get Stage 3 artifacts status
async function getStage3Artifacts(bestCandidateDir: string): Promise<Stage3Artifacts> {
  const artifacts: Stage3Artifacts = {
    tuneResultJson: await fileExists(`${bestCandidateDir}/tune_result.json`),
    bestParamsJson: await fileExists(`${bestCandidateDir}/best_params.json`),
    trainedModelJson: await fileExists(`${bestCandidateDir}/trained_model.json`),
    finalTrainResultJson: await fileExists(`${bestCandidateDir}/final_train_result.json`),
    predictionsCsv: await fileExists(`${bestCandidateDir}/predictions.csv`),
    predictionResultJson: await fileExists(`${bestCandidateDir}/prediction_result.json`),
    explanationMd: await fileExists(`${bestCandidateDir}/explanation.md`),
    reportPdf: await fileExists(`${bestCandidateDir}/report.pdf`),
    reportMd: await fileExists(`${bestCandidateDir}/report.md`),
  };
  return artifacts;
}

// Check if Stage 3 is complete
function isStage3Complete(artifacts: Stage3Artifacts): boolean {
  return artifacts.predictionsCsv && artifacts.explanationMd && artifacts.reportPdf;
}

// Main function: Poll filesystem and return complete state
export async function pollFileSystemState(): Promise<FileSystemState> {
  const config = await loadConfig();
  const state = createEmptyFileSystemState();
  
  // 1. Check dataset status
  const datasetPathContent = await readFile(config.datasetPathFile);
  if (datasetPathContent && datasetPathContent.trim()) {
    const datasetPath = datasetPathContent.trim();
    const datasetExists = await fileExists(datasetPath);
    state.datasetReady = datasetExists;
    state.datasetPath = datasetExists ? datasetPath : null;
  }
  
  // 2. Parse checklist
  const checklistContent = await readFile(config.checklistStatusFile);
  if (checklistContent) {
    state.checklist = parseChecklist(checklistContent);
    state.checklistOk = isChecklistComplete(state.checklist);
  }
  
  // 3. Scan for candidate folders
  const mlRootEntries = await listDir(config.generatedMlRoot);
  
  for (let i = 0; i < CANDIDATE_PREFIXES.length; i++) {
    const prefix = CANDIDATE_PREFIXES[i];
    const matchingFolder = mlRootEntries.find(entry => entry.startsWith(prefix));
    
    if (matchingFolder) {
      const folderPath = `${config.generatedMlRoot}${matchingFolder}`;
      const folderEntries = await listDir(folderPath);
      const { status, metrics } = await getCandidateStatus(folderPath, folderEntries);
      
      state.candidates[i] = {
        prefix,
        folderName: matchingFolder,
        status,
        metrics,
        artifacts: {
          modelPy: folderEntries.includes('model.py'),
          testPy: folderEntries.includes('test.py'),
          tunePy: folderEntries.includes('tune.py'),
          metricsJson: folderEntries.includes('metrics.json'),
          diffLog: folderEntries.includes('diff.log'),
        },
      };
    }
  }
  
  // 4. Check selected best
  if (config.selectedBestFile) {
    const selectedBestContent = await readFile(config.selectedBestFile);
    if (selectedBestContent && selectedBestContent.trim()) {
      state.selectedBest = selectedBestContent.trim();
      state.bestCandidateDir = `${config.generatedMlRoot}${state.selectedBest}`;
      
      // 5. Get Stage 3 artifacts
      state.stage3Artifacts = await getStage3Artifacts(state.bestCandidateDir);
      state.stage3Done = isStage3Complete(state.stage3Artifacts);
    }
  }
  
  return state;
}

// Get artifact download URL
export function getArtifactDownloadUrl(absolutePath: string): string {
  if (!appConfig) {
    console.warn('Config not loaded yet');
    return '#';
  }
  return `${appConfig.fileServerUrl}/download?path=${encodeURIComponent(absolutePath)}`;
}

// Read file content for display (e.g., explanation.md, report.md)
export async function readFileContent(absolutePath: string): Promise<string | null> {
  return readFile(absolutePath);
}

// Read JSON file
export async function readJsonFile<T>(absolutePath: string): Promise<T | null> {
  const content = await readFile(absolutePath);
  if (!content) return null;
  try {
    return JSON.parse(content);
  } catch {
    return null;
  }
}

// Read CSV file and parse to array of objects
export async function readCsvFile(absolutePath: string): Promise<Record<string, string | number>[] | null> {
  const content = await readFile(absolutePath);
  if (!content) return null;
  
  const lines = content.trim().split('\n');
  if (lines.length < 2) return null;
  
  const headers = lines[0].split(',').map(h => h.trim());
  const rows: Record<string, string | number>[] = [];
  
  for (let i = 1; i < lines.length; i++) {
    const values = lines[i].split(',').map(v => v.trim());
    const row: Record<string, string | number> = {};
    for (let j = 0; j < headers.length; j++) {
      const val = values[j];
      const num = parseFloat(val);
      row[headers[j]] = isNaN(num) ? val : num;
    }
    rows.push(row);
  }
  
  return rows;
}
