import { CheckCircle2, Circle, Database } from 'lucide-react';
import type { DatasetInfo, WorkflowStage } from '@/types/workflow';
import type { ChecklistSnapshot } from '@/types/api';
import { cn } from '@/lib/utils';

interface SessionPanelProps {
  dataset: DatasetInfo | null;
  checklist: ChecklistSnapshot;
  currentStage: WorkflowStage;
}

const SessionPanel = ({ dataset, checklist, currentStage }: SessionPanelProps) => {
  const checklistItems = Object.values(checklist);
  const requiredItems = checklistItems.filter((item) => item.required);
  const confirmedRequiredCount = requiredItems.filter((item) => item.confirmed).length;

  return (
    <div className="flex h-full flex-col overflow-auto">
      {/* Dataset Info */}
      <div className="border-b border-border p-4">
        <h3 className="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground">
          <Database className="h-4 w-4" />
          Dataset
        </h3>
        {dataset ? (
          <div className="space-y-2 text-sm">
            <p className="truncate font-medium text-foreground">{dataset.filename}</p>
            <div className="flex gap-4 text-muted-foreground">
              <span>{dataset.rowCount} rows</span>
              <span>{dataset.columnCount} cols</span>
            </div>
            {dataset.missingSummary.length > 0 && (
              <p className="text-xs text-muted-foreground">
                {dataset.missingSummary.length} column(s) with missing values
              </p>
            )}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">No dataset uploaded</p>
        )}
      </div>

      {/* Checklist */}
      <div className="border-b border-border p-4">
        <h3 className="mb-3 text-sm font-semibold text-foreground">
          Configuration ({confirmedRequiredCount}/{requiredItems.length} required)
        </h3>
        <ul className="space-y-2">
          {checklistItems.map((item) => (
            <li key={item.key} className="flex items-start gap-2">
              {item.confirmed ? (
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
              ) : (
                <Circle className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
              )}
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1">
                  <p
                    className={cn(
                      'text-sm',
                      item.confirmed ? 'text-foreground' : 'text-muted-foreground'
                    )}
                  >
                    {item.label}
                  </p>
                  {!item.required && (
                    <span className="text-xs text-muted-foreground">(optional)</span>
                  )}
                </div>
                {item.value && (
                  <p className="truncate text-xs text-muted-foreground">{item.value}</p>
                )}
              </div>
            </li>
          ))}
        </ul>
      </div>

      {/* Stage Info */}
      <div className="flex-1 p-4">
        <h3 className="mb-3 text-sm font-semibold text-foreground">
          Current Stage
        </h3>
        <p className="text-sm text-muted-foreground capitalize">
          {currentStage === 'stage1' && 'Stage 1: Configuration'}
          {currentStage === 'stage2' && 'Stage 2: Model Training'}
          {currentStage === 'stage3' && 'Stage 3: Results & Reports'}
        </p>
      </div>
    </div>
  );
};

export default SessionPanel;