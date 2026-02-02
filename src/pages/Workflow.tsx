import { useState, useEffect, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useFileSystemState } from '@/hooks/useFileSystemState';
import { useSession } from '@/hooks/useSession';
import WorkflowStepper from '@/components/workflow/WorkflowStepper';
import SessionPanel from '@/components/workflow/SessionPanel';
import Stage1Intake from '@/components/workflow/Stage1Intake';
import Stage2Pipeline from '@/components/workflow/Stage2Pipeline';
import Stage3Results from '@/components/workflow/Stage3Results';
import { Sheet, SheetContent, SheetTrigger } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { PanelRightOpen, Sparkles, AlertCircle } from 'lucide-react';
import { Link } from 'react-router-dom';
import { loadConfig } from '@/lib/fileService';
import type { WorkflowStage } from '@/types/workflow';
import type { ChecklistSnapshot, ChecklistItem } from '@/types/api';

// Convert parsed checklist to ChecklistSnapshot for compatibility
const convertToChecklistSnapshot = (
  parsedItems: { field: string; value: string | null; status: string }[]
): ChecklistSnapshot => {
  const createItem = (field: string, label: string, required: boolean): ChecklistItem => {
    const found = parsedItems.find(
      item => item.field.toLowerCase() === field.toLowerCase()
    );
    return {
      key: field.replace(/\s+/g, ''),
      label,
      value: found?.value || null,
      confirmed: found?.status === 'Confirmed',
      required,
    };
  };

  return {
    inputType: createItem('Input type', 'Input Type', true),
    outputType: createItem('Output type', 'Output Type', true),
    trainingType: createItem('Training type', 'Training Type', true),
    dataPath: createItem('Data path', 'Data Path', true),
    splitStrategy: createItem('Split strategy', 'Split Strategy', true),
    metric: createItem('Metric', 'Metric', true),
    predictionDataPath: createItem('Prediction data path', 'Prediction Data Path', false),
    modelFamilyCategory: createItem('Model family category', 'Model Family', false),
  };
};

const Workflow = () => {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [generatedMlRoot, setGeneratedMlRoot] = useState<string>('');
  
  // File-system based state (polls every 1s)
  const { 
    state: fsState, 
    isLoading: fsLoading, 
    error: fsError,
    currentStage: fsCurrentStage,
  } = useFileSystemState({ pollInterval: 1000 });

  // Session state for Stage 1 chat (HTTP-based, only for chat)
  const {
    messages,
    dataset,
    predictionDataset,
    createSession,
    getStoredSessionId,
    uploadTrainData,
    uploadPredictionData,
    sendMessage,
  } = useSession(sessionId);

  // Load config to get generatedMlRoot
  useEffect(() => {
    loadConfig().then(config => {
      setGeneratedMlRoot(config.generatedMlRoot);
    });
  }, []);

  // Redirect to existing session or create new one (only for Stage 1 chat)
  useEffect(() => {
    if (!sessionId) {
      const existingSession = getStoredSessionId();
      if (existingSession) {
        navigate(`/workflow/${existingSession}`, { replace: true });
      } else {
        createSession().then((newId) => {
          navigate(`/workflow/${newId}`, { replace: true });
        });
      }
    }
  }, [sessionId, navigate, getStoredSessionId, createSession]);

  // Convert filesystem stage to WorkflowStage type
  const currentStage: WorkflowStage = fsCurrentStage as WorkflowStage;

  // Convert checklist for UI compatibility
  const checklist = useMemo(() => 
    convertToChecklistSnapshot(fsState.checklist),
    [fsState.checklist]
  );

  if (!sessionId) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <p className="text-muted-foreground">Initializing session...</p>
      </div>
    );
  }

  if (fsLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <p className="text-muted-foreground">Loading configuration...</p>
      </div>
    );
  }

  const handleStage2Complete = () => {
    // Stage 3 is triggered by filesystem (selected_best.txt)
    // No action needed - polling will detect it
  };

  const renderStageContent = () => {
    switch (currentStage) {
      case 'stage1':
        return (
          <Stage1Intake
            messages={messages}
            dataset={dataset}
            predictionDataset={predictionDataset}
            checklist={checklist}
            checklistOk={fsState.checklistOk}
            onSendMessage={sendMessage}
            onUploadTrainData={uploadTrainData}
            onUploadPredictionData={uploadPredictionData}
            onProceed={() => {}} // Stage 2 is auto-triggered by checklistOk
          />
        );
      case 'stage2':
        return (
          <Stage2Pipeline
            candidates={fsState.candidates}
            selectedBest={fsState.selectedBest}
            generatedMlRoot={generatedMlRoot}
            onComplete={handleStage2Complete}
          />
        );
      case 'stage3':
        return fsState.bestCandidateDir ? (
          <Stage3Results 
            bestCandidateDir={fsState.bestCandidateDir}
            artifacts={fsState.stage3Artifacts}
            done={fsState.stage3Done}
          />
        ) : (
          <div className="flex items-center justify-center h-full">
            <p className="text-muted-foreground">Loading best candidate...</p>
          </div>
        );
      default:
        return null;
    }
  };

  return (
    <div className="flex min-h-screen flex-col bg-background">
      {/* Header */}
      <header className="sticky top-0 z-50 border-b border-border bg-card">
        <div className="flex items-center justify-between px-6 py-3">
          <Link to="/" className="flex items-center gap-3">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary">
              <Sparkles className="h-4 w-4 text-primary-foreground" />
            </div>
            <span className="text-lg font-semibold text-foreground">Metric Mode</span>
          </Link>

          {/* Error indicator */}
          {fsError && (
            <div className="flex items-center gap-2 text-destructive text-sm">
              <AlertCircle className="h-4 w-4" />
              <span>File server error</span>
            </div>
          )}

          {/* Mobile Session Panel Trigger */}
          <Sheet>
            <SheetTrigger asChild>
              <Button variant="outline" size="sm" className="lg:hidden">
                <PanelRightOpen className="h-4 w-4" />
              </Button>
            </SheetTrigger>
            <SheetContent side="right" className="w-[320px] p-0">
              <SessionPanel
                dataset={dataset}
                checklist={checklist}
                currentStage={currentStage}
              />
            </SheetContent>
          </Sheet>
        </div>

        {/* Stepper */}
        <div className="border-t border-border px-6 py-4">
          <WorkflowStepper currentStage={currentStage} />
        </div>
      </header>

      {/* Main Content */}
      <div className="flex flex-1">
        {/* Stage Content */}
        <main className="flex-1 overflow-auto">
          {renderStageContent()}
        </main>

        {/* Desktop Session Panel */}
        <aside className="hidden w-[320px] shrink-0 border-l border-border bg-card lg:block">
          <SessionPanel
            dataset={dataset}
            checklist={checklist}
            currentStage={currentStage}
          />
        </aside>
      </div>
    </div>
  );
};

export default Workflow;
