import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import { Check, Loader2, AlertCircle } from 'lucide-react';
import type { WorkflowStage, WorkflowError, SelectedModel } from '@/types/api';
import { cn } from '@/lib/utils';

interface StatusPanelProps {
  stage: WorkflowStage;
  substage: string;
  progress: number;
  isRunning: boolean;
  errors: WorkflowError[];
  selectedBest?: SelectedModel;
}

const StatusPanel = ({
  stage,
  substage,
  progress,
  isRunning,
  errors,
  selectedBest,
}: StatusPanelProps) => {
  const stages = [
    { id: 'stage1', label: 'Intake', description: 'Configure pipeline' },
    { id: 'stage2', label: 'Pipeline', description: 'Generate & test' },
    { id: 'stage3', label: 'Results', description: 'Train & report' },
  ];

  const getStageStatus = (stageId: string) => {
    const stageOrder = ['stage1', 'stage2', 'stage3', 'complete'];
    const currentIndex = stageOrder.indexOf(stage);
    const stageIndex = stageOrder.indexOf(stageId);

    if (stageIndex < currentIndex) return 'complete';
    if (stageIndex === currentIndex) return isRunning ? 'running' : 'current';
    return 'pending';
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">Workflow Status</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Stage indicators */}
        <div className="flex justify-between">
          {stages.map((s, index) => {
            const status = getStageStatus(s.id);
            return (
              <div key={s.id} className="flex flex-col items-center gap-1">
                <div
                  className={cn(
                    'flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium',
                    status === 'complete' && 'bg-primary text-primary-foreground',
                    status === 'running' && 'border-2 border-primary bg-primary/10 text-primary',
                    status === 'current' && 'border-2 border-primary text-primary',
                    status === 'pending' && 'border border-muted-foreground/30 text-muted-foreground'
                  )}
                >
                  {status === 'complete' ? (
                    <Check className="h-4 w-4" />
                  ) : status === 'running' ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    index + 1
                  )}
                </div>
                <span className={cn(
                  'text-xs',
                  status === 'pending' ? 'text-muted-foreground' : 'text-foreground'
                )}>
                  {s.label}
                </span>
              </div>
            );
          })}
        </div>

        {/* Current substage */}
        <div className="rounded-lg bg-muted/50 p-3">
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">{substage}</span>
            {isRunning && <Badge variant="secondary">{progress}%</Badge>}
          </div>
          {isRunning && (
            <Progress value={progress} className="mt-2 h-1.5" />
          )}
        </div>

        {/* Selected model (if available) */}
        {selectedBest && (
          <div className="rounded-lg border border-primary/20 bg-primary/5 p-3">
            <p className="text-xs text-muted-foreground">Best Model</p>
            <p className="font-medium text-foreground">{selectedBest.modelId}</p>
            <div className="mt-1 flex items-center gap-2">
              <Badge variant="secondary">{selectedBest.library}</Badge>
              <Badge variant="default">Score: {selectedBest.metricValue.toFixed(2)}</Badge>
            </div>
          </div>
        )}

        {/* Errors */}
        {errors.length > 0 && (
          <div className="space-y-2">
            {errors.map((error, i) => (
              <div key={i} className="flex items-start gap-2 rounded-lg bg-destructive/10 p-2 text-sm">
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
                <div>
                  <p className="font-medium text-destructive">{error.where}</p>
                  <p className="text-muted-foreground">{error.message}</p>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
};

export default StatusPanel;
