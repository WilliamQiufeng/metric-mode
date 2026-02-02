import { useState, useCallback, useEffect } from 'react';
import type { ChecklistSnapshot } from '@/types/api';
import type { DatasetInfo, ChatMessage } from '@/types/workflow';
import * as api from '@/lib/apiService';

// Helper to check if all required checklist items are confirmed
const isChecklistComplete = (checklist: ChecklistSnapshot): boolean => {
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
};

const SESSION_KEY = 'metricmode_session_id';

// Generate unique IDs
const generateId = () => Math.random().toString(36).substring(2, 15);

// Create initial checklist
const createInitialChecklist = (): ChecklistSnapshot => ({
  inputType: { key: 'inputType', label: 'Input Type', value: null, confirmed: false, required: true },
  outputType: { key: 'outputType', label: 'Output Type', value: null, confirmed: false, required: true },
  trainingType: { key: 'trainingType', label: 'Training Type', value: null, confirmed: false, required: true },
  dataPath: { key: 'dataPath', label: 'Data Path', value: null, confirmed: false, required: true },
  splitStrategy: { key: 'splitStrategy', label: 'Split Strategy', value: null, confirmed: false, required: true },
  metric: { key: 'metric', label: 'Metric', value: null, confirmed: false, required: true },
  predictionDataPath: { key: 'predictionDataPath', label: 'Prediction Data Path', value: null, confirmed: false, required: true },
  modelFamilyCategory: { key: 'modelFamilyCategory', label: 'Model Family', value: null, confirmed: false, required: false },
});

// Initial welcome message
const createInitialMessages = (): ChatMessage[] => [
  {
    id: 'msg-1',
    role: 'assistant',
    content: `Welcome to **Metric Mode**! I'm here to help you build a machine learning pipeline.

To get started:
1. **Upload your training dataset** (CSV format)
2. **Upload your prediction dataset** for inference
3. **Describe your ML goal** — classification or regression?

I'll guide you through configuring the optimal workflow.`,
    timestamp: new Date(),
  },
];

export interface UploadedFile {
  filename: string;
  rowCount: number;
  columnCount: number;
}

export const useSession = (sessionId?: string) => {
  // Stage 1 state
  const [messages, setMessages] = useState<ChatMessage[]>(createInitialMessages());
  const [dataset, setDataset] = useState<DatasetInfo | null>(null);
  const [predictionDataset, setPredictionDataset] = useState<UploadedFile | null>(null);
  const [checklist, setChecklist] = useState<ChecklistSnapshot>(createInitialChecklist());
  const [checklistOk, setChecklistOk] = useState(false);

  // Load session from backend
  useEffect(() => {
    if (sessionId) {
      api.getSession(sessionId)
        .then((response) => {
          setChecklist(response.checklist);
          setChecklistOk(response.checklistOk);
          if (response.dataset) {
            setDataset({
              datasetId: response.dataset.datasetId,
              filename: response.dataset.filename,
              columns: [],
              previewRows: [],
              rowCount: response.dataset.rowCount,
              columnCount: response.dataset.columnCount,
              missingSummary: [],
            });
          }
          if (response.predictionDataset) {
            setPredictionDataset(response.predictionDataset);
          }
        })
        .catch((e) => {
          console.error('Failed to load session from backend:', e);
        });
    }
  }, [sessionId]);

  // Save session to localStorage
  const saveSession = useCallback(() => {
    if (sessionId) {
      localStorage.setItem(
        `session_${sessionId}`,
        JSON.stringify({
          messages,
          dataset,
          checklist,
          checklistOk,
        })
      );
    }
  }, [sessionId, messages, dataset, checklist, checklistOk]);

  // Create new session
  const createSession = useCallback(async () => {
    const response = await api.createSession();
    localStorage.setItem(SESSION_KEY, response.sessionId);
    return response.sessionId;
  }, []);

  // Get existing session ID
  const getStoredSessionId = useCallback(() => {
    return localStorage.getItem(SESSION_KEY);
  }, []);

  // Upload training dataset
  const uploadTrainData = useCallback(async (file: File): Promise<DatasetInfo> => {
    const response = await api.uploadDataset(sessionId || '', file, 'train');
    
    const datasetInfo: DatasetInfo = {
      datasetId: response.datasetId,
      filename: response.filename,
      columns: response.columns,
      previewRows: response.previewRows,
      rowCount: response.rowCount,
      columnCount: response.columnCount,
      missingSummary: response.missingSummary,
    };
    
    setDataset(datasetInfo);
    
    // Sync checklist from mock state (includes the uploaded file)
    const updatedChecklist = api.getMockChecklist();
    setChecklist(updatedChecklist);
    setChecklistOk(isChecklistComplete(updatedChecklist));
    
    // Add assistant message
    const uploadMessage: ChatMessage = {
      id: generateId(),
      role: 'assistant',
      content: `Training dataset **${file.name}** uploaded ✓
📊 **${response.rowCount} rows** × **${response.columnCount} columns**`,
      timestamp: new Date(),
    };
    setMessages((prev) => [...prev, uploadMessage]);
    
    return datasetInfo;
  }, [sessionId]);

  // Upload prediction dataset
  const uploadPredictionData = useCallback(async (file: File): Promise<UploadedFile> => {
    const response = await api.uploadDataset(sessionId || '', file, 'predict');
    
    const predData: UploadedFile = {
      filename: response.filename,
      rowCount: response.rowCount,
      columnCount: response.columnCount,
    };
    
    setPredictionDataset(predData);
    
    // Sync checklist from mock state (includes the uploaded file)
    const updatedChecklist = api.getMockChecklist();
    setChecklist(updatedChecklist);
    setChecklistOk(isChecklistComplete(updatedChecklist));
    
    // Add assistant message
    const uploadMessage: ChatMessage = {
      id: generateId(),
      role: 'assistant',
      content: `Prediction dataset **${file.name}** uploaded ✓ (${predData.rowCount} rows)`,
      timestamp: new Date(),
    };
    setMessages((prev) => [...prev, uploadMessage]);
    
    return predData;
  }, [sessionId]);

  // Send chat message
  const sendMessage = useCallback(async (content: string) => {
    // Add user message
    const userMessage: ChatMessage = {
      id: generateId(),
      role: 'user',
      content,
      timestamp: new Date(),
    };
    setMessages((prev) => [...prev, userMessage]);

    // Get response from API
    const response = await api.sendChatMessage(sessionId || '', content);

    setChecklist(response.checklist);
    setChecklistOk(response.checklistOk);

    // Add assistant message
    const assistantMessage: ChatMessage = {
      id: generateId(),
      role: 'assistant',
      content: response.reply,
      timestamp: new Date(),
    };
    setMessages((prev) => [...prev, assistantMessage]);

    return response;
  }, [sessionId]);

  return {
    // Stage 1 state
    messages,
    dataset,
    predictionDataset,
    checklist,
    checklistOk,
    
    // Actions
    createSession,
    getStoredSessionId,
    uploadTrainData,
    uploadPredictionData,
    sendMessage,
    saveSession,
  };
};
